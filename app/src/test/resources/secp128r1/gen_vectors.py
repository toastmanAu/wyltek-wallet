"""Independent secp128r1 vectors for Secp128r1Test. Regenerate: python3 -I gen_vectors.py > vectors.json"""
import hashlib, json
from ecdsa import SigningKey
from ecdsa.curves import SECP128r1
from ecdsa.util import sigencode_string

sk = SigningKey.from_secret_exponent(0x1234567890ABCDEF1122334455667788 % SECP128r1.order, curve=SECP128r1)
vk = sk.get_verifying_key()
pub = "04" + vk.to_string().hex()
out = {"pub": pub, "cases": []}
for uid_hex in ["04958caa5c5e80", "04ebbb02cd2a81", "040c5f825c1391"]:
    uid = bytes.fromhex(uid_hex)
    sig = sk.sign_digest_deterministic(uid, hashfunc=hashlib.sha256, sigencode=sigencode_string, allow_truncate=True)
    assert vk.verify_digest(sig, uid, allow_truncate=True)
    out["cases"].append({"uid": uid_hex, "sig": sig.hex()})
print(json.dumps(out, indent=1))
