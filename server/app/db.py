"""SQLite storage: users, sign-in tokens, reported applications, AI usage, settings, admin sessions, each user's data
(records and the CV file) and their Google Drive link."""
import os
import sqlite3
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

DB_PATH = os.environ.get("DB_PATH", "/data/autoapply.db")
TIMEZONE = ZoneInfo(os.environ.get("TIMEZONE", "Africa/Cairo"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL DEFAULT '',
  pw_hash TEXT NOT NULL,
  created_at REAL NOT NULL,
  pro INTEGER NOT NULL DEFAULT 0,
  pro_changed_at REAL,
  note TEXT NOT NULL DEFAULT '',
  stop_requested_at REAL,
  running INTEGER NOT NULL DEFAULT 0,
  mode TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT '',
  run_started_at REAL,
  last_seen REAL,
  app_version TEXT NOT NULL DEFAULT '',
  device TEXT NOT NULL DEFAULT '',
  app_total INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS tokens (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at REAL NOT NULL,
  last_used REAL NOT NULL,
  device TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS tokens_user ON tokens(user_id);
CREATE TABLE IF NOT EXISTS applications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  job_id TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  company TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL,
  score INTEGER,
  at REAL NOT NULL,
  UNIQUE (user_id, job_id, status)
);
CREATE INDEX IF NOT EXISTS applications_user_at ON applications(user_id, at);
CREATE TABLE IF NOT EXISTS ai_usage (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day TEXT NOT NULL,
  calls INTEGER NOT NULL DEFAULT 0,
  tokens INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  at REAL NOT NULL,
  text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS events_user ON events(user_id, id);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS admin_sessions (token_hash TEXT PRIMARY KEY, expires_at REAL NOT NULL);
-- Each user's data, the same on every phone of theirs and in their Google Drive: setup answers, settings, your info,
-- jobs, the CV's text. One row per item; rev grows with every change (per user), deleted items keep a row (data NULL).
CREATE TABLE IF NOT EXISTS records (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  rid TEXT NOT NULL,
  data TEXT,
  rev INTEGER NOT NULL,
  mtime REAL NOT NULL,
  origin TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (user_id, kind, rid)
);
CREATE INDEX IF NOT EXISTS records_rev ON records(user_id, rev);
-- The CV file itself.
CREATE TABLE IF NOT EXISTS user_files (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  file_name TEXT NOT NULL,
  mime TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  size INTEGER NOT NULL,
  data BLOB NOT NULL,
  at REAL NOT NULL,
  PRIMARY KEY (user_id, name)
);
-- A user's Google Drive: the (encrypted) tokens, the JSTB folder and its files, what was synced last.
CREATE TABLE IF NOT EXISTS drive_links (
  user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  email TEXT NOT NULL DEFAULT '',
  refresh_token TEXT NOT NULL,
  access_token TEXT NOT NULL DEFAULT '',
  access_until REAL NOT NULL DEFAULT 0,
  folder_id TEXT NOT NULL DEFAULT '',
  state TEXT NOT NULL DEFAULT '{}',
  linked_at REAL NOT NULL,
  synced_at REAL,
  checked_at REAL,
  pushed_rev INTEGER NOT NULL DEFAULT -1,
  error TEXT NOT NULL DEFAULT '',
  error_code TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS oauth_states (
  state_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  verifier TEXT NOT NULL,
  expires_at REAL NOT NULL
);
-- Sign in with Google: a sign-in on its way through Google's page (purpose login or delete), before anyone is known.
CREATE TABLE IF NOT EXISTS login_states (
  state_hash TEXT PRIMARY KEY,
  purpose TEXT NOT NULL,
  verifier TEXT NOT NULL,
  app_challenge TEXT NOT NULL DEFAULT '',
  device TEXT NOT NULL DEFAULT '',
  expires_at REAL NOT NULL
);
-- After Google's page: a one-time code the app trades for a session (only with its own PKCE verifier), or the token
-- that confirms deleting an account on the web.
CREATE TABLE IF NOT EXISTS login_codes (
  code_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose TEXT NOT NULL,
  app_challenge TEXT NOT NULL DEFAULT '',
  device TEXT NOT NULL DEFAULT '',
  created INTEGER NOT NULL DEFAULT 0,
  expires_at REAL NOT NULL
);
-- Centralized LinkedIn Job Collection & AI Matching
CREATE TABLE IF NOT EXISTS server_jobs (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  company TEXT NOT NULL,
  location TEXT NOT NULL,
  url TEXT NOT NULL,
  posted_date TEXT NOT NULL DEFAULT '',
  collected_at REAL NOT NULL,
  keywords TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_server_jobs_collected ON server_jobs(collected_at);
CREATE TABLE IF NOT EXISTS user_keywords (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  keyword TEXT NOT NULL,
  location TEXT NOT NULL DEFAULT 'Egypt',
  created_at REAL NOT NULL,
  PRIMARY KEY (user_id, keyword)
);
CREATE INDEX IF NOT EXISTS idx_user_keywords_user ON user_keywords(user_id);
CREATE TABLE IF NOT EXISTS user_job_matches (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  job_id TEXT NOT NULL REFERENCES server_jobs(id) ON DELETE CASCADE,
  score INTEGER NOT NULL,
  why TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'new',
  updated_at REAL NOT NULL,
  PRIMARY KEY (user_id, job_id)
);
CREATE INDEX IF NOT EXISTS idx_matches_user_score ON user_job_matches(user_id, score DESC);
"""

# Settings the admin page changes. Values are stored as text.
DEFAULTS = {
    "openrouter_key": "",
    "model": "openai/gpt-4o-mini",
    "free_ai_per_day": "100",    # accounts without Pro: the setup, filling forms and cover letters
    "pro_ai_per_day": "800",
    "max_per_day": "0",          # applications per user per day; 0 = the app's own setting
    "signups_open": "1",
    "new_users_pro": "0",
    "max_per_hour": "0",         # 0 = the app's own setting
    "announcement": "",          # shown on every app's home screen
    "min_version_play": "0",
    "play_url": "https://play.google.com/store/apps/details?id=com.autoapply.bot",
    "google_client_id": "",      # Sign in with Google and Drive sync: the OAuth client made in Google Cloud (README)
    "google_client_secret": "",
    "password_signups": "0",     # new accounts with an email and a password (once Google sign-in is set up)
}

# Columns added after 2.0, added to existing databases on start.
MIGRATIONS = [
    ("users", "max_per_day", "INTEGER"),
    ("users", "max_per_hour", "INTEGER"),
    ("users", "flavor", "TEXT NOT NULL DEFAULT ''"),
    ("users", "version_code", "INTEGER NOT NULL DEFAULT 0"),
    ("applications", "channel", "TEXT NOT NULL DEFAULT 'auto'"),
    ("applications", "stage", "TEXT NOT NULL DEFAULT ''"),
    ("applications", "reason", "TEXT NOT NULL DEFAULT ''"),
    ("applications", "url", "TEXT NOT NULL DEFAULT ''"),
    ("users", "data_rev", "INTEGER NOT NULL DEFAULT 0"),
    ("users", "data_at", "REAL"),
    ("users", "google_sub", "TEXT NOT NULL DEFAULT ''"),
    ("login_states", "user_id", "INTEGER NOT NULL DEFAULT 0"),
]

_local = threading.local()


def _connect():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    c = sqlite3.connect(DB_PATH, timeout=15, isolation_level=None, check_same_thread=False)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys = ON")
    c.execute("PRAGMA busy_timeout = 15000")
    return c


def conn():
    c = getattr(_local, "conn", None)
    if c is None:
        c = _connect()
        _local.conn = c
    return c


@contextmanager
def tx():
    """One write transaction (commits on success, rolls back on error)."""
    c = conn()
    c.execute("BEGIN IMMEDIATE")
    try:
        yield c
    except BaseException:
        c.execute("ROLLBACK")
        raise
    else:
        c.execute("COMMIT")


def init():
    c = conn()
    c.execute("PRAGMA journal_mode = WAL")
    c.executescript(SCHEMA)
    for table, column, decl in MIGRATIONS:
        have = {r["name"] for r in c.execute(f"PRAGMA table_info({table})").fetchall()}
        if column not in have:
            c.execute(f"ALTER TABLE {table} ADD COLUMN {column} {decl}")
    c.execute("CREATE INDEX IF NOT EXISTS users_google ON users(google_sub)")
    with tx() as t:
        for k, v in DEFAULTS.items():
            t.execute("INSERT OR IGNORE INTO settings(key, value) VALUES (?, ?)", (k, v))
        # First start: take the OpenRouter key and model from the environment, if given.
        for env, key in (("OPENROUTER_KEY", "openrouter_key"), ("OPENROUTER_MODEL", "model")):
            val = os.environ.get(env, "").strip()
            if val:
                row = t.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
                if not row or not row["value"] or row["value"] == DEFAULTS[key]:
                    t.execute("UPDATE settings SET value = ? WHERE key = ?", (val, key))
        t.execute("DELETE FROM admin_sessions WHERE expires_at < ?", (time.time(),))
        t.execute("DELETE FROM oauth_states WHERE expires_at < ?", (time.time(),))
        t.execute("DELETE FROM login_states WHERE expires_at < ?", (time.time(),))
        t.execute("DELETE FROM login_codes WHERE expires_at < ?", (time.time(),))
        for env, key in (("GOOGLE_CLIENT_ID", "google_client_id"), ("GOOGLE_CLIENT_SECRET", "google_client_secret")):
            val = os.environ.get(env, "").strip()      # the Google client from .env, when the admin page has none
            if val:
                row = t.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
                if not row or row["value"] == DEFAULTS[key]:
                    t.execute("UPDATE settings SET value = ? WHERE key = ?", (val, key))


def one(sql, args=()):
    return conn().execute(sql, args).fetchone()


def all_rows(sql, args=()):
    return conn().execute(sql, args).fetchall()


def settings():
    out = dict(DEFAULTS)
    for r in all_rows("SELECT key, value FROM settings"):
        out[r["key"]] = r["value"]
    return out


def setting_int(s, key, low=0, high=10**6):
    try:
        return max(low, min(high, int(float(s.get(key, DEFAULTS.get(key, "0"))))))
    except (TypeError, ValueError):
        return int(DEFAULTS.get(key, "0"))


# ---------------------------------------------------------------- days in the server's time zone
def today(now=None):
    return datetime.fromtimestamp(now or time.time(), TIMEZONE).strftime("%Y-%m-%d")


def start_of_day(now=None, days_back=0):
    d = datetime.fromtimestamp(now or time.time(), TIMEZONE)
    d = d.replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=days_back)
    return d.timestamp()
