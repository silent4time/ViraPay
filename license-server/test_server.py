import base64, hashlib, json, os, tempfile, unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import server

APP = "ir.virapay.app"
ADMIN = {"X-Admin-Token": "adm"}


def device(name):  # same shape as the Android app: sha256 hex
    return hashlib.sha256(name.encode()).hexdigest()


def b64u_decode(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def client_verify(token, public_key, my_device, nonce):
    """Mirrors LicenseManager.verify() in License.kt."""
    p, s = token.split(".")
    payload_bytes, sig = b64u_decode(p), b64u_decode(s)
    try:
        public_key.verify(sig, payload_bytes, ec.ECDSA(hashes.SHA256()))
    except Exception:
        return None
    payload = json.loads(payload_bytes)
    if payload["dev"] != my_device or payload["nonce"] != nonce:
        return None
    return payload


class LicenseTests(unittest.TestCase):
    def setUp(self):
        self.priv = ec.generate_private_key(ec.SECP256R1())
        self.pub = self.priv.public_key()
        self.db = tempfile.mktemp(suffix=".db")
        self.c = server.create_app(self.db, self.priv, "adm").test_client()
        self.key = self.c.post("/admin/create", json={"count": 1}, headers=ADMIN).get_json()["keys"][0]

    def tearDown(self):
        if os.path.exists(self.db):
            os.remove(self.db)

    def act(self, key, dev, nonce="a" * 32, app=APP):
        return self.c.post("/activate", json={"key": key, "device": device(dev), "nonce": nonce, "app": app})

    def test_first_activation_binds_device_and_token_verifies(self):
        r = self.act(self.key, "phone-A")
        self.assertEqual(r.status_code, 200)
        payload = client_verify(r.get_json()["token"], self.pub, device("phone-A"), "a" * 32)
        self.assertIsNotNone(payload)

    def test_same_device_can_reactivate(self):
        self.assertEqual(self.act(self.key, "phone-A").status_code, 200)
        self.assertEqual(self.act(self.key, "phone-A").status_code, 200)

    def test_second_device_refused(self):
        self.assertEqual(self.act(self.key, "phone-A").status_code, 200)
        r = self.act(self.key, "phone-B")
        self.assertEqual(r.status_code, 409)
        self.assertEqual(r.get_json()["error"], "already_used")

    def test_token_from_device_A_is_useless_on_device_B(self):
        token = self.act(self.key, "phone-A").get_json()["token"]
        self.assertIsNone(client_verify(token, self.pub, device("phone-B"), "a" * 32))

    def test_replayed_response_with_other_nonce_rejected(self):
        token = self.act(self.key, "phone-A", nonce="b" * 32).get_json()["token"]
        self.assertIsNone(client_verify(token, self.pub, device("phone-A"), "c" * 32))

    def test_token_signed_by_wrong_key_rejected(self):
        token = self.act(self.key, "phone-A").get_json()["token"]
        other = ec.generate_private_key(ec.SECP256R1()).public_key()
        self.assertIsNone(client_verify(token, other, device("phone-A"), "a" * 32))

    def test_tampered_payload_rejected(self):
        token = self.act(self.key, "phone-A").get_json()["token"]
        p, s = token.split(".")
        forged = json.loads(b64u_decode(p)); forged["dev"] = device("phone-B")
        fp = base64.urlsafe_b64encode(json.dumps(forged, separators=(",", ":"), sort_keys=True).encode()).rstrip(b"=").decode()
        self.assertIsNone(client_verify(fp + "." + s, self.pub, device("phone-B"), "a" * 32))

    def test_invalid_key_and_bad_app(self):
        self.assertEqual(self.act("AAAA-BBBB-CCCC-DDDD", "phone-A").status_code, 404)
        self.assertEqual(self.act(self.key, "phone-A", app="evil.app").status_code, 400)

    def test_admin_reset_allows_new_device(self):
        self.act(self.key, "phone-A")
        self.assertEqual(self.act(self.key, "phone-B").status_code, 409)
        self.assertTrue(self.c.post("/admin/reset", json={"key": self.key}, headers=ADMIN).get_json()["ok"])
        self.assertEqual(self.act(self.key, "phone-B").status_code, 200)
        self.assertEqual(self.act(self.key, "phone-A").status_code, 409)

    def test_revoke(self):
        self.act(self.key, "phone-A")
        self.c.post("/admin/revoke", json={"key": self.key}, headers=ADMIN)
        self.assertEqual(self.act(self.key, "phone-A").status_code, 403)

    def test_admin_requires_token(self):
        self.assertEqual(self.c.post("/admin/create", json={}).status_code, 403)
        self.assertEqual(self.c.get("/admin/list", headers={"X-Admin-Token": "nope"}).status_code, 403)

    def test_keys_stored_hashed(self):
        import sqlite3
        rows = sqlite3.connect(self.db).execute("SELECT key_hash FROM licenses").fetchall()
        self.assertTrue(all(self.key not in r[0] and len(r[0]) == 64 for r in rows))

    def test_rate_limit(self):
        codes = [self.act("AAAA-BBBB-CCCC-DDDD", "x").status_code for _ in range(25)]
        self.assertIn(429, codes)

    def test_der_signature_is_what_java_expects(self):
        token = self.act(self.key, "phone-A").get_json()["token"]
        sig = b64u_decode(token.split(".")[1])
        self.assertEqual(sig[0], 0x30)  # ASN.1 SEQUENCE => DER, accepted by SHA256withECDSA

    def test_public_key_export_matches_x509(self):
        der = self.pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        loaded = serialization.load_der_public_key(base64.b64decode(base64.b64encode(der)))
        self.assertEqual(loaded.public_numbers(), self.pub.public_numbers())


if __name__ == "__main__":
    unittest.main()
