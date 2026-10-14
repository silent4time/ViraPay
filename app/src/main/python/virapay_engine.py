import os
import re
import json
import time
import asyncio
from telethon import TelegramClient, events
from telethon.errors import SessionPasswordNeededError

_phone_code_hash = None
_used_sms_ids = set()
_used_ref_numbers = set()
_gw_loop = None
_gw_client = None

SMS_MAX_AGE_MS = 30 * 60 * 1000  # only SMS received in the last 30 minutes


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


def check_bank_sms(activity, expected_rial, sender_hint):
    sms_json = activity.getSmsInboxJson()
    sms_list = json.loads(sms_json)
    formatted_amount = f"+{expected_rial:,}"
    now_ms = int(time.time() * 1000)

    for sms in sms_list:
        sms_id = sms.get("id", "")
        sender = sms.get("sender", "")
        body = sms.get("body", "")
        date_ms = sms.get("date", 0)

        if sms_id in _used_sms_ids:
            continue
        if date_ms and now_ms - date_ms > SMS_MAX_AGE_MS:
            continue

        # FIX: sender AND amount must both match (was "or", which accepted any SMS with the amount)
        if sender_hint.lower() in sender.lower() and formatted_amount in body:
            _used_sms_ids.add(sms_id)
            return True, body
    return False, None


def stop_gateway():
    """Called by the Android service on stop: disconnects Telethon so the loop ends."""
    loop, client = _gw_loop, _gw_client
    if loop is None or client is None:
        return

    async def _stop():
        await client.disconnect()

    asyncio.run_coroutine_threadsafe(_stop(), loop)


def start_gateway_loop(activity, work_dir, api_id, api_hash, bank_cards_str, bot_usernames_str):
    global _gw_loop, _gw_client
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    session_path = os.path.join(work_dir, "virapay_session")
    client = TelegramClient(session_path, api_id, api_hash, loop=loop)
    _gw_loop, _gw_client = loop, client

    bots = [b.strip() for b in bot_usernames_str.split(",") if b.strip()]

    cards_map = {}
    for item in bank_cards_str.split(","):
        parts = item.strip().split(":")
        if len(parts) == 2:
            cards_map[parts[0].strip()] = parts[1].strip()

    orders_state = {}

    @client.on(events.NewMessage(chats=bots))
    async def handler(event):
        msg_text = event.raw_text or ""
        chat_id = event.chat_id

        if "درخواست خرید" in msg_text and "تومان" in msg_text:
            m = re.search(r"به مبلغ «([\d,]+) تومان»", msg_text)
            if m:
                toman = int(m.group(1).replace(",", ""))
                orders_state[chat_id] = {"rial": toman * 10, "has_receipt": False}
                activity.appendLog(f"🔔 Order detected / سفارش جدید: {toman:,} Tomans")

        elif event.photo and chat_id in orders_state:
            activity.appendLog("📸 Receipt photo received. / عکس فیش دریافت شد.")
            orders_state[chat_id]["has_receipt"] = True

        elif "آیا سند واریزی فوق مورد تایید است" in msg_text and chat_id in orders_state:
            order = orders_state[chat_id]
            if not order.get("has_receipt"):
                return

            expected_rial = order["rial"]
            activity.appendLog(f"🔍 Checking SMS for {expected_rial:,} Rials... / در حال بررسی پیامک...")

            for attempt in range(6):
                for card_num, sender_hint in cards_map.items():
                    ok, sms_body = check_bank_sms(activity, expected_rial, sender_hint)
                    if ok:
                        activity.appendLog(f"✅ Bank SMS verified ({sender_hint})! Clicking inline button...")
                        if event.buttons:
                            await event.click(0, 0)
                            activity.appendLog("🎉 Payment verified and confirmed successfully! / پرداخت با موفقیت تایید شد!")
                        del orders_state[chat_id]
                        return
                await asyncio.sleep(10)

            activity.appendLog("⏳ SMS timeout. Not confirmed automatically. / پیامک منطبق یافت نشد.")

    async def _main():
        await client.connect()
        if not await client.is_user_authorized():
            activity.appendLog("❌ Telegram session not authorized. Please login first.")
            return
        activity.appendLog(f"🟢 ViraPay Gateway active on bots: {bots} / ربات‌های فعال: {bots}")
        await client.run_until_disconnected()

    try:
        loop.run_until_complete(_main())
    except Exception as e:
        activity.appendLog(f"❌ Gateway Error: {str(e)}")
    finally:
        _gw_loop = None
        _gw_client = None
