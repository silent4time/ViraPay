# ViraPay license server (reference)

```bash
pip install -r requirements.txt
python3 gen_keys.py                 # creates private.pem, prints the public key for the app
export ADMIN_TOKEN='a-long-random-secret'
python3 server.py                   # listens on 127.0.0.1:8080 -> put nginx/caddy HTTPS in front
python3 -m unittest test_server -v  # 15 tests
```

Create keys: `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"count":5,"note":"batch1"}' http://127.0.0.1:8080/admin/create`

Free a key for a new device: `POST /admin/reset {"key":"XXXX-XXXX-XXXX-XXXX"}`.
Keep `private.pem` and `licenses.db` OUT of git. Always serve over HTTPS.
