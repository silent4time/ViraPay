"""Run ONCE:  python3 gen_keys.py
Creates private.pem (keep it secret, only on the server) and prints the PUBLIC key
to paste into AppConfig.LICENSE_PUBLIC_KEY in the Android app."""
import base64
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

key = ec.generate_private_key(ec.SECP256R1())
with open("private.pem", "wb") as f:
    f.write(key.private_bytes(serialization.Encoding.PEM,
                              serialization.PrivateFormat.PKCS8,
                              serialization.NoEncryption()))
pub = key.public_key().public_bytes(serialization.Encoding.DER,
                                    serialization.PublicFormat.SubjectPublicKeyInfo)
print("private.pem written. NEVER publish it.\n")
print("AppConfig.LICENSE_PUBLIC_KEY =")
print(base64.b64encode(pub).decode())
