"""
ViraPay license server (reference implementation).

  pip install flask cryptography
  python3 gen_keys.py                       # once
  export ADMIN_TOKEN='long-random-secret'
  python3 server.py                         # put it behind HTTPS (nginx/caddy)

Public endpoint
  POST /activate   {key, device, nonce, app}  -> {ok, token} | {ok:false, error}

Admin endpoints (header  X-Admin-Token: $ADMIN_TOKEN)
  POST /admin/create  {count, note}   -> {keys:[...]}   (plain keys are shown only once)
  POST /admin/reset   {key}           -> frees the key so it can be activated on a NEW device
  POST /admin/revoke  {key}
  GET  /admin/list

Rules
  * A key is bound to the FIRST device that activates it (device = SHA-256 hash from the app).
  * Same key + same device again  -> OK (re-install / re-activation).
  * Same key + another device     -> refused ("already_used").
  * Keys are stored only as SHA-256 hashes.
  * The token is signed with ECDSA P-256; the app verifies it offline with the public key.
"""
import base64, hashlib, hmac, json, os, secrets, sqlite3, time
from collections import defaultdict, deque

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from flask import Flask, jsonify, request

DB_PATH = os.environ.get("LICENSE_DB", "licenses.db")
KEY_PATH = os.environ.get("LICENSE_PRIVATE_KEY", "private.pem")
APP_ID = os.environ.get("LICENSE_APP_ID", "ir.virapay.app")
RATE_LIMIT = int(os.environ.get("RATE_LIMIT_PER_MIN", "20"))


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def key_hash(key: str) -> str:
    return hashlib.sha256(key.strip().upper().encode()).hexdigest()


def create_app(db_path=DB_PATH, private_key=None, admin_token=None):
    app = Flask(__name__)
    admin_token = admin_token or os.environ.get("ADMIN_TOKEN", "")
    if private_key is None:
        with open(KEY_PATH, "rb") as f:
            private_key = serialization.load_pem_private_key(f.read(), password=None)
    hits = defaultdict(deque)

    def db():
        con = sqlite3.connect(db_path)
        con.row_factory = sqlite3.Row
        con.execute("""CREATE TABLE IF NOT EXISTS licenses(
            key_hash TEXT PRIMARY KEY, device TEXT, activated_at INTEGER,
            revoked INTEGER DEFAULT 0, note TEXT, created_at INTEGER)""")
        return con

    def limited():
        ip = request.headers.get("X-Forwarded-For", request.remote_addr or "?").split(",")[0].strip()
        q, now = hits[ip], time.time()
        while q and now - q[0] > 60:
            q.popleft()
        if len(q) >= RATE_LIMIT:
            return True
        q.append(now)
        return False

    def admin_only():
        sent = request.headers.get("X-Admin-Token", "")
        return bool(admin_token) and hmac.compare_digest(sent, admin_token)

    def sign(payload: dict) -> str:
        raw = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
        sig = private_key.sign(raw, ec.ECDSA(hashes.SHA256()))  # DER, same format Java expects
        return b64u(raw) + "." + b64u(sig)

    @app.post("/activate")
    def activate():
        if limited():
            return jsonify(ok=False, error="rate_limited"), 429
        d = request.get_json(silent=True) or {}
        key, device, nonce = (d.get("key") or ""), (d.get("device") or ""), (d.get("nonce") or "")
        if not key or len(device) != 64 or len(nonce) < 16 or d.get("app") != APP_ID:
            return jsonify(ok=False, error="bad_request"), 400
        con = db()
        row = con.execute("SELECT * FROM licenses WHERE key_hash=?", (key_hash(key),)).fetchone()
        if row is None:
            return jsonify(ok=False, error="invalid_key"), 404
        if row["revoked"]:
            return jsonify(ok=False, error="revoked"), 403
        if row["device"] is None:
            con.execute("UPDATE licenses SET device=?, activated_at=? WHERE key_hash=? AND device IS NULL",
                        (device, int(time.time()), row["key_hash"]))
            con.commit()
            row = con.execute("SELECT * FROM licenses WHERE key_hash=?", (row["key_hash"],)).fetchone()
        if not hmac.compare_digest(row["device"], device):
            return jsonify(ok=False, error="already_used"), 409
        token = sign({"key": row["key_hash"][:16], "dev": device, "nonce": nonce,
                      "iat": int(time.time()), "v": 1})
        return jsonify(ok=True, token=token)

    @app.post("/admin/create")
    def create():
        if not admin_only():
            return jsonify(ok=False, error="forbidden"), 403
        d = request.get_json(silent=True) or {}
        count = max(1, min(int(d.get("count", 1)), 500))
        con, keys = db(), []
        for _ in range(count):
            k = "-".join(secrets.token_hex(2).upper() for _ in range(4))
            con.execute("INSERT INTO licenses(key_hash,note,created_at) VALUES(?,?,?)",
                        (key_hash(k), d.get("note", ""), int(time.time())))
            keys.append(k)
        con.commit()
        return jsonify(ok=True, keys=keys)

    @app.post("/admin/reset")
    def reset():
        if not admin_only():
            return jsonify(ok=False, error="forbidden"), 403
        key = (request.get_json(silent=True) or {}).get("key", "")
        con = db()
        n = con.execute("UPDATE licenses SET device=NULL, activated_at=NULL WHERE key_hash=?",
                        (key_hash(key),)).rowcount
        con.commit()
        return jsonify(ok=n == 1)

    @app.post("/admin/revoke")
    def revoke():
        if not admin_only():
            return jsonify(ok=False, error="forbidden"), 403
        key = (request.get_json(silent=True) or {}).get("key", "")
        con = db()
        n = con.execute("UPDATE licenses SET revoked=1 WHERE key_hash=?", (key_hash(key),)).rowcount
        con.commit()
        return jsonify(ok=n == 1)

    @app.get("/admin/list")
    def list_all():
        if not admin_only():
            return jsonify(ok=False, error="forbidden"), 403
        rows = db().execute("SELECT key_hash,device,activated_at,revoked,note FROM licenses").fetchall()
        return jsonify(ok=True, licenses=[{**dict(r), "key_hash": r["key_hash"][:12]} for r in rows])

    return app


if __name__ == "__main__":
    create_app().run(host="127.0.0.1", port=int(os.environ.get("PORT", "8080")))
