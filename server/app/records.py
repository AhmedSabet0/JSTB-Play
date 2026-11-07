"""Each user's data, kept as records so every phone of theirs and their Google Drive hold the same.

A record is one item of one kind: profile/<field> and settings/<key> ({"v": value}), facts/<id> (your info),
copilot/<id> (jobs you apply to yourself), jobs/<id> (auto apply's job log), cv/main (the CV's name, hash and text;
the file itself is in user_files). Every change gets the user's next revision number, so a phone asks for what
changed since the revision it has. Deleted records keep a row with data NULL, so other phones learn about it.
"""
import base64
import hashlib
import json
import re
import time

KIND_RE = re.compile(r"^[a-z]{2,20}$")
RID_MAX = 160
DATA_MAX = 100_000           # characters of JSON per record
CV_TEXT_MAX = 200_000
CV_MAX = 5 * 1024 * 1024     # bytes
MAX_RECORDS = 20_000         # per user
CV_TYPES = {".pdf": "application/pdf", ".txt": "text/plain"}


def dumps(data):
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def loads(text):
    try:
        out = json.loads(text) if text else None
    except ValueError:
        return None
    return out if isinstance(out, dict) else None


def write(t, user_id, changes, origin):
    """Saves [(kind, rid, data or None, mtime)] inside the transaction t. Unchanged records are skipped.
    Returns {(kind, rid): rev} of what was saved."""
    rows = []
    for kind, rid, data, mtime in changes:
        cur = t.execute("SELECT data FROM records WHERE user_id = ? AND kind = ? AND rid = ?", (user_id, kind, rid)).fetchone()
        text = None if data is None else dumps(data)
        if (cur is None and text is None) or (cur is not None and cur["data"] == text):
            continue
        rows.append((kind, rid, text, mtime))
    if not rows:
        return {}
    first = t.execute("SELECT data_rev FROM users WHERE id = ?", (user_id,)).fetchone()["data_rev"] + 1
    t.execute("UPDATE users SET data_rev = ?, data_at = ? WHERE id = ?", (first + len(rows) - 1, time.time(), user_id))
    out = {}
    for i, (kind, rid, text, mtime) in enumerate(rows):
        t.execute("INSERT INTO records(user_id, kind, rid, data, rev, mtime, origin) VALUES (?, ?, ?, ?, ?, ?, ?) "
                  "ON CONFLICT(user_id, kind, rid) DO UPDATE SET data = excluded.data, rev = excluded.rev, "
                  "mtime = excluded.mtime, origin = excluded.origin", (user_id, kind, rid, text, first + i, mtime, origin))
        out[(kind, rid)] = first + i
    return out


def current(t, user_id, kind, rid):
    r = t.execute("SELECT data, rev, mtime FROM records WHERE user_id = ? AND kind = ? AND rid = ?", (user_id, kind, rid)).fetchone()
    return None if r is None else {"data": loads(r["data"]), "rev": r["rev"], "mtime": r["mtime"]}


def all_of(c, user_id, kinds):
    """{kind: {rid: data}} of the records that exist (not deleted)."""
    out = {k: {} for k in kinds}
    marks = ",".join("?" * len(kinds))
    for r in c.execute(f"SELECT kind, rid, data FROM records WHERE user_id = ? AND kind IN ({marks}) AND data IS NOT NULL",
                       (user_id, *kinds)).fetchall():
        d = loads(r["data"])
        if d is not None:
            out[r["kind"]][r["rid"]] = d
    return out


def count(c, user_id):
    return c.execute("SELECT COUNT(*) AS n FROM records WHERE user_id = ? AND data IS NOT NULL", (user_id,)).fetchone()["n"]


def summary(c, user_id):
    """How much of each kind the server holds for this user (for the admin page)."""
    out = {}
    for r in c.execute("SELECT kind, COUNT(*) AS n FROM records WHERE user_id = ? AND data IS NOT NULL GROUP BY kind",
                       (user_id,)).fetchall():
        out[r["kind"]] = r["n"]
    return out


# ---------------------------------------------------------------- the CV file
def cv_ext(name):
    n = str(name or "").lower()
    for ext in CV_TYPES:
        if n.endswith(ext):
            return ext
    return ""


def clean_text(text):
    t = re.sub(r"[ \t]+\n", "\n", str(text or "").replace("\r", ""))
    return re.sub(r"\n{3,}", "\n\n", t).strip()[:CV_TEXT_MAX]


def save_cv(t, user_id, name, raw, text, mtime, origin):
    """Keeps the CV file and its record (name, hash, size, text). Returns (rev, sha256)."""
    ext = cv_ext(name)
    sha = hashlib.sha256(raw).hexdigest()
    now = time.time()
    t.execute("INSERT INTO user_files(user_id, name, file_name, mime, sha256, size, data, at) VALUES (?, 'cv', ?, ?, ?, ?, ?, ?) "
              "ON CONFLICT(user_id, name) DO UPDATE SET file_name = excluded.file_name, mime = excluded.mime, "
              "sha256 = excluded.sha256, size = excluded.size, data = excluded.data, at = excluded.at",
              (user_id, name, CV_TYPES.get(ext, "application/octet-stream"), sha, len(raw), raw, now))
    revs = write(t, user_id, [("cv", "main", {"name": name, "sha256": sha, "size": len(raw), "text": clean_text(text),
                                              "at": now}, mtime)], origin)
    rev = revs.get(("cv", "main"))
    if rev is None:
        rev = t.execute("SELECT rev FROM records WHERE user_id = ? AND kind = 'cv' AND rid = 'main'", (user_id,)).fetchone()["rev"]
    return rev, sha


def cv_file(c, user_id):
    r = c.execute("SELECT file_name, mime, sha256, size, data, at FROM user_files WHERE user_id = ? AND name = 'cv'",
                  (user_id,)).fetchone()
    return None if r is None else dict(r)


def b64(raw):
    return base64.b64encode(raw).decode()
