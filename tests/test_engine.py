"""Run:  python3 -m unittest discover -s tests -v   (no Android or Telegram needed)"""
import json, os, sys, tempfile, time, types, unittest

# stub telethon so the engine module can be imported on a normal PC
for name in ("telethon", "telethon.errors"):
    sys.modules.setdefault(name, types.ModuleType(name))
sys.modules["telethon"].TelegramClient = object
sys.modules["telethon"].events = object
sys.modules["telethon.errors"].SessionPasswordNeededError = Exception
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))
import virapay_engine as e  # noqa: E402


class FakeActivity:
    def __init__(self, sms): self.sms = sms
    def getSmsInboxJson(self): return json.dumps(self.sms)


def sms(body, sender="Resalat", sid="1", age_min=1):
    return {"id": sid, "sender": sender, "body": body, "date": int(time.time() * 1000) - age_min * 60000}


class AmountRules(unittest.TestCase):
    def test_plus_style(self):
        rule = e.build_rule("واریز +5,000,000 مانده 12,000,000")
        self.assertTrue(rule["plus"])
        self.assertTrue(e.amount_matches("+5,000,000", 5000000, rule))
        self.assertTrue(e.amount_matches("+5000000", 5000000, rule))
        self.assertTrue(e.amount_matches("واریز +۵٬۰۰۰٬۰۰۰", 5000000, rule))

    def test_prefix_bug(self):
        rule = e.build_rule("+1,000")
        self.assertFalse(e.amount_matches("+5,000,000", 5000, rule))
        self.assertFalse(e.amount_matches("+15,000", 5000, rule))
        self.assertFalse(e.amount_matches("+5,0001", 5000, rule))

    def test_withdrawal_rejected(self):
        rule = e.build_rule("+1,000")
        self.assertFalse(e.amount_matches("برداشت -5,000,000", 5000000, rule))

    def test_word_style_without_plus(self):
        rule = e.build_rule("مبلغ 2,000,000 ریال به حساب شما واریز شد")
        self.assertEqual(rule, {"plus": False, "word": "واریز"})
        self.assertTrue(e.amount_matches("2,500,000 ریال واریز شد", 2500000, rule))
        self.assertFalse(e.amount_matches("2,500,000 ریال برداشت شد", 2500000, rule))
        self.assertFalse(e.amount_matches("-2,500,000 ریال واریز", 2500000, rule))
        self.assertFalse(e.amount_matches("12,500,000 ریال واریز", 2500000, rule))
        self.assertFalse(e.amount_matches("2,500,000 ریال", 2500000, rule))  # no deposit word

    def test_no_signal_sample(self):
        rule = e.build_rule("something")
        self.assertTrue(e.amount_matches("5,000 rial", 5000, rule))
        self.assertFalse(e.amount_matches("-5,000 rial", 5000, rule))


class SmsMatching(unittest.TestCase):
    def setUp(self):
        e._used_sms_ids.clear()
        self.tmp = tempfile.mkdtemp()

    def test_sender_must_match(self):
        act = FakeActivity([sms("+5,000,000", sender="Mellat")])
        self.assertFalse(e.check_bank_sms(act, 5000000, "Resalat")[0])
        self.assertTrue(e.check_bank_sms(act, 5000000, "Mellat")[0])

    def test_empty_sender_never_matches(self):
        act = FakeActivity([sms("+5,000,000")])
        self.assertFalse(e.check_bank_sms(act, 5000000, "")[0])

    def test_old_sms_ignored(self):
        act = FakeActivity([sms("+5,000,000", age_min=45)])
        self.assertFalse(e.check_bank_sms(act, 5000000, "Resalat")[0])

    def test_sms_used_once_and_persisted(self):
        act = FakeActivity([sms("+5,000,000", sid="77")])
        self.assertTrue(e.check_bank_sms(act, 5000000, "Resalat", None, self.tmp)[0])
        self.assertFalse(e.check_bank_sms(act, 5000000, "Resalat", None, self.tmp)[0])
        e._used_sms_ids.clear()          # simulate a service restart
        e._load_used(self.tmp)
        self.assertFalse(e.check_bank_sms(act, 5000000, "Resalat", None, self.tmp)[0])

    def test_two_cards_two_banks(self):
        """A bot with two cards: the SMS decides which card was paid."""
        cards = [
            {"number": "6037000000001111", "sender": "Resalat", "rule": e.build_rule("+1,000")},
            {"number": "6219000000002222", "sender": "Saman", "rule": e.build_rule("واریز 1,000 ریال")},
        ]
        act = FakeActivity([sms("2,500,000 ریال واریز شد", sender="Saman")])
        hit = [c["number"] for c in cards if e.check_bank_sms(act, 2500000, c["sender"], c["rule"])[0]]
        self.assertEqual(hit, ["6219000000002222"])


if __name__ == "__main__":
    unittest.main()
