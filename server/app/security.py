"""Passwords (scrypt), sign-in tokens (only their SHA-256 is stored) and a small in-memory rate limiter."""
import base64
import hashlib
import hmac
import re
import secrets
import threading
import time
from collections import defaultdict, deque

SCRYPT_N, SCRYPT_R, SCRYPT_P = 2 ** 14, 8, 1
EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]+\.[^@\s]{2,}$")


def _b64(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _unb64(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def hash_password(password):
    salt = secrets.token_bytes(16)
    dk = hashlib.scrypt(password.encode(), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=32)
    return f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${_b64(salt)}${_b64(dk)}"


_DUMMY = None


def check_password(password, stored):
    """True when the password matches. Takes the same time when there is no such user (stored=None)."""
    global _DUMMY
    if not stored:
        if _DUMMY is None:
            _DUMMY = hash_password(secrets.token_hex(8))
        stored, password = _DUMMY, password + "x"
    try:
        algo, n, r, p, salt, dk = stored.split("$")
        if algo != "scrypt":
            return False
        want = _unb64(dk)
        got = hashlib.scrypt(password.encode(), salt=_unb64(salt), n=int(n), r=int(r), p=int(p), dklen=len(want))
        return hmac.compare_digest(got, want)
    except (ValueError, TypeError):
        return False


def new_token():
    return secrets.token_urlsafe(32)


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def clean_email(email):
    e = str(email or "").strip().lower()
    return e if len(e) <= 254 and EMAIL_RE.match(e) else ""


def password_problem(password):
    p = str(password or "")
    if len(p) < 8:
        return "Use a password of at least 8 characters."
    if len(p) > 128:
        return "That password is too long (128 characters at most)."
    return ""


class RateLimit:
    """At most `limit` hits per `window` seconds for each key (an IP address, an email...)."""

    def __init__(self, limit, window):
        self.limit = limit
        self.window = window
        self.hits = defaultdict(deque)
        self.lock = threading.Lock()

    def allow(self, key):
        now = time.monotonic()
        with self.lock:
            q = self.hits[key]
            while q and now - q[0] > self.window:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(now)
            if len(self.hits) > 50000:  # keep memory bounded
                for k in [k for k, v in self.hits.items() if not v][:25000]:
                    del self.hits[k]
            return True
