import os
import re
import json
import time
import asyncio
from telethon import TelegramClient, events
from telethon.errors import SessionPasswordNeededError

_phone_code_hash = None
_used_sms_ids = set()
_gw_loop = None
_gw_client = None

SMS_MAX_AGE_MS = 30 * 60 * 1000  # only SMS received in the last 30 minutes
_USED_FILE = "used_sms.json"

# Persian / Arabic-Indic digits -> ASCII, so "+۵٬۰۰۰" is read as "+5,000"
_DIGIT_MAP = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")

_DEPOSIT_WORDS = ["واریز", "ایداع", "deposit", "variz"]
_WITHDRAW_WORDS = ["برداشت", "withdraw", "bardasht", "انتقال از", "خرید"]


def _normalize(text):
    return (text or "").translate(_DIGIT_MAP).replace("٬", ",").replace("،", ",")


# ---------------------------------------------------------------- used SMS ids

def _load_used(work_dir):
    """Already-consumed SMS ids survive a service restart (prevents re-using one deposit twice)."""
    global _used_sms_ids
    try:
        with open(os.path.join(work_dir, _USED_FILE), encoding="utf-8") as f:
            _used_sms_ids = set(str(x) for x in json.load(f))
    except Exception:
        _used_sms_ids = set()


def _save_used(work_dir):
    try:
        ids = sorted(_used_sms_ids, key=lambda v: int(v) if v.isdigit() else 0)[-500:]
        with open(os.path.join(work_dir, _USED_FILE), "w", encoding="utf-8") as f:
            json.dump(ids, f)
    except Exception:
        pass


# ------------------------------------------------------------ SMS format rules

def build_rule(sample):
    """
    Learn how this bank writes a DEPOSIT from a sample SMS:
      - "plus": the amount is written like +5,000,000
      - "word": otherwise, a deposit keyword (واریز, deposit...) found in the sample
    """
    s = _normalize(sample).lower()
    if re.search(r"\+\s?\d[\d,]*", s):
        return {"plus": True, "word": None}
    for w in _DEPOSIT_WORDS:
        if w in s:
            return {"plus": False, "word": w}
    return {"plus": False, "word": None}


def amount_matches(body, rial, rule=None):
    """True only for a DEPOSIT of exactly `rial` rials ("+5,000" must not match "+5,000,000")."""
    rule = rule or {"plus": True, "word": None}
    body = _normalize(body)
    low = body.lower()
    num = r"(?:" + re.escape(f"{rial:,}") + "|" + re.escape(str(rial)) + r")"
    tail = r"(?!\d|,\d)"

    if rule.get("plus"):
        return re.search(r"\+\s?" + num + tail, body) is not None

    m = re.search(r"(?<![\d,.])([-−–]?)\s?" + num + tail, body)
    if not m or m.group(1):          # amount missing, or written with a minus sign
        return False
    if any(w in low for w in _WITHDRAW_WORDS):
        return False
    word = rule.get("word")
    return (word in low) if word else True


def check_bank_sms(activity, expected_rial, sender_hint, rule=None, work_dir=None):
    sms_list = json.loads(activity.getSmsInboxJson())
    now_ms = int(time.time() * 1000)

    for sms in sms_list:
        sms_id = str(sms.get("id", ""))
        sender = sms.get("sender", "")
        body = sms.get("body", "")
        date_ms = sms.get("date", 0)

        if sms_id in _used_sms_ids:
            continue
        if date_ms and now_ms - date_ms > SMS_MAX_AGE_MS:
            continue

        # sender AND amount must both match
        if sender_hint and sender_hint.lower() in sender.lower() and amount_matches(body, expected_rial, rule):
            _used_sms_ids.add(sms_id)
            if work_dir:
                _save_used(work_dir)
            return True, body
    return False, None


# ------------------------------------------------------------- Telegram login

def send_login_code(work_dir, api_id, api_hash, phone):
    global _phone_code_hash
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    session_path = os.path.join(work_dir, "virapay_session")
    client = TelegramClient(session_path, api_id, api_hash, loop=loop)

    async def _send():
        global _phone_code_hash
        await client.connect()
        if await client.is_user_authorized():
            await client.disconnect()
            return "✅ Telegram session already active. / اکانت تلگرام از قبل متصل است."
        sent = await client.send_code_request(phone)
        _phone_code_hash = sent.phone_code_hash
        await client.disconnect()
        return "📩 OTP code sent to Telegram. / کد ورود به تلگرام ارسال شد."

    try:
        return loop.run_until_complete(_send())
    except Exception as e:
        return f"❌ Error: {str(e)}"


def verify_login_code(work_dir, api_id, api_hash, phone, code_input):
    global _phone_code_hash
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    session_path = os.path.join(work_dir, "virapay_session")
    client = TelegramClient(session_path, api_id, api_hash, loop=loop)

    parts = code_input.strip().split()
    code = parts[0]
    password = parts[1] if len(parts) > 1 else None

    async def _verify():
        await client.connect()
        try:
            await client.sign_in(phone=phone, code=code, phone_code_hash=_phone_code_hash)
        except SessionPasswordNeededError:
            if not password:
                await client.disconnect()
                return "🔐 2FA Password required! Format: CODE PASSWORD / رمز دوم نیاز است."
            await client.sign_in(password=password)
        await client.disconnect()
        return "✅ Telegram login successful! / ورود موفقیت‌آمیز به تلگرام."

    try:
        return loop.run_until_complete(_verify())
    except Exception as e:
        return f"❌ Verify Error: {str(e)}"


# -------------------------------------------------------------------- gateway

def stop_gateway():
    """Called by the Android service on stop: disconnects Telethon so the loop ends."""
    loop, client = _gw_loop, _gw_client
    if loop is None or client is None:
        return

    async def _stop():
        await client.disconnect()

    asyncio.run_coroutine_threadsafe(_stop(), loop)


def start_gateway_loop(activity, work_dir, api_id, api_hash, config_json):
    """
    config_json = {"bots": [{"id","bot_id","username","admin",
                             "cards": [{"number","holder","sample","sender"}]}]}
    Every card belongs to exactly one bot; a bot may have several cards.
    """
    global _gw_loop, _gw_client
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    session_path = os.path.join(work_dir, "virapay_session")
    client = TelegramClient(session_path, api_id, api_hash, loop=loop)
    _gw_loop, _gw_client = loop, client

    _load_used(work_dir)

    bots = {}
    for b in json.loads(config_json).get("bots", []):
        uname = (b.get("username") or "").strip().lstrip("@").lower()
        cards = []
        for c in b.get("cards", []):
            number = (c.get("number") or "").strip()
            sender = (c.get("sender") or "").strip()
            if number and sender:  # an empty sender hint would match every SMS
                cards.append({
                    "number": number,
                    "sender": sender,
                    "rule": build_rule(c.get("sample", "")),
                })
        if uname and cards:
            bots[uname] = {"id": b.get("id"), "bot_id": str(b.get("bot_id") or ""),
                           "username": uname, "admin": (b.get("admin") or "").lstrip("@"),
                           "cards": cards}

    orders_state = {}

    @client.on(events.NewMessage(chats=list(bots.keys())))
    async def handler(event):
        chat = await event.get_chat()
        uname = (getattr(chat, "username", None) or "").lower()
        bot = bots.get(uname)
        if bot is None:
            return
        if bot["bot_id"] and event.sender_id and str(event.sender_id) != bot["bot_id"]:
            return  # not really from this bot

        key = bot["id"]
        msg_text = event.raw_text or ""

        if "درخواست خرید" in msg_text and "تومان" in msg_text:
            m = re.search(r"به مبلغ «([\d,]+) تومان»", _normalize(msg_text))
            if m:
                toman = int(m.group(1).replace(",", ""))
                orders_state[key] = {"rial": toman * 10, "has_receipt": False}
                activity.appendLog(f"🔔 @{uname}: سفارش جدید / new order {toman:,} Tomans")

        elif event.photo and key in orders_state:
            activity.appendLog(f"📸 @{uname}: عکس فیش دریافت شد / receipt photo received")
            orders_state[key]["has_receipt"] = True

        elif "آیا سند واریزی فوق مورد تایید است" in msg_text and key in orders_state:
            order = orders_state[key]
            if not order.get("has_receipt"):
                return

            rial = order["rial"]
            activity.appendLog(f"🔍 @{uname}: بررسی پیامک {rial:,} ریال / checking SMS")

            for _ in range(6):
                for card in bot["cards"]:
                    ok, _body = check_bank_sms(activity, rial, card["sender"], card["rule"], work_dir)
                    if ok:
                        last4 = card["number"][-4:]
                        activity.appendLog(f"✅ @{uname}: پیامک کارت ••••{last4} تایید شد / SMS verified")
                        if event.buttons:
                            await event.click(0, 0)
                            activity.appendLog(f"🎉 @{uname}: پرداخت تایید شد / payment confirmed")
                        activity.recordTransaction(uname, card["number"], int(rial), True)
                        del orders_state[key]
                        return
                await asyncio.sleep(10)

            activity.appendLog(f"⏳ @{uname}: پیامک منطبق یافت نشد / SMS timeout")
            activity.recordTransaction(uname, "", int(rial), False)

    async def _main():
        activity.setConnection("connecting")
        await client.connect()
        if not await client.is_user_authorized():
            activity.setConnection("error")
            activity.appendLog("❌ Telegram session not authorized. Please login first. / ابتدا وارد تلگرام شوید.")
            return
        me = await client.get_me()
        my_name = (getattr(me, "username", None) or "").lower()
        for b in bots.values():
            if b["admin"] and my_name and b["admin"].lower() != my_name:
                activity.appendLog(f"⚠️ @{b['username']}: ادمین @{b['admin']} با اکانت واردشده (@{my_name}) یکی نیست")
        activity.setConnection("connected")
        activity.appendLog(f"🟢 ViraPay active: {', '.join('@' + u for u in bots)}")
        await client.run_until_disconnected()

    try:
        loop.run_until_complete(_main())
    except Exception as e:
        activity.setConnection("error")
        activity.appendLog(f"❌ Gateway Error: {str(e)}")
    finally:
        _gw_loop = None
        _gw_client = None
