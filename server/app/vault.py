"""Encrypts what must not sit in the database as plain text (users' Google tokens), with AES-256-GCM.

The key is DATA_KEY from .env when set (any long random string), else a random key made on first start and kept in
data.key next to the database (the Docker volume, readable by the server only). A copied database alone does not
give away the tokens.
"""
import base64
import hashlib
import os
import secrets
import threading

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from . import db

_key = None
_lock = threading.Lock()


def _load_key():
    env = os.environ.get("DATA_KEY", "").strip()
    if env:
        return hashlib.sha256(env.encode()).digest()
    path = os.environ.get("DATA_KEY_FILE") or os.path.join(os.path.dirname(os.path.abspath(db.DB_PATH)), "data.key")
    try:
        with open(path, "rb") as f:
            raw = f.read().strip()
        key = base64.urlsafe_b64decode(raw)
        if len(key) == 32:
            return key
    except (OSError, ValueError):
        pass
    key = secrets.token_bytes(32)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(base64.urlsafe_b64encode(key))
    return key


def key():
    global _key
    with _lock:
        if _key is None:
            _key = _load_key()
        return _key


def seal(text):
    """'v1.' + base64(nonce + ciphertext) of the text, or '' for ''."""
    if not text:
        return ""
    nonce = secrets.token_bytes(12)
    ct = AESGCM(key()).encrypt(nonce, text.encode(), b"autoapply")
    return "v1." + base64.urlsafe_b64encode(nonce + ct).decode()


def unseal(token):
    """The text sealed by seal(), or '' when it cannot be read (another key, damaged)."""
    if not token or not token.startswith("v1."):
        return ""
    try:
        raw = base64.urlsafe_b64decode(token[3:])
        return AESGCM(key()).decrypt(raw[:12], raw[12:], b"autoapply").decode()
    except (ValueError, InvalidTag):
        return ""
