"""Google Drive: each user's "JSTB" folder, kept the same as their data on this server, both ways.

The folder has the CV (the PDF or text file), "My answers" (a Google Sheet: the setup answers, the job search, the
notes for the AI and every fact of "Your info") and "My jobs" (a Google Sheet: jobs to apply to and every application,
with its status). What the user changes there comes back to the app: an answer, a fact's wording or section, a job's
status or note, a new job pasted with its link, a new version of the CV file (Manage versions > Upload new version).

How it stays the same: every sync reads a sheet only when Drive says it changed, compares it with what was written
last time (so only the user's own edits count), saves those edits as records (the app picks them up), then writes the
sheet again from the records. Columns the user adds are kept; rows without an ID that cannot be read stay at the end.

Google sign-in: OAuth 2.0 for web servers with PKCE and the drive.file scope (the app sees only the files it made,
not the rest of the Drive). The admin adds the OAuth client from Google Cloud in the admin page. The tokens are kept
encrypted (vault.py).
"""
import asyncio
import base64
import hashlib
import io
import json
import logging
import os
import re
import secrets
import time
from datetime import datetime
from urllib.parse import urlencode

import httpx

from . import db, records, security, vault

log = logging.getLogger("autoapply.drive")

AUTH_URL = os.environ.get("GOOGLE_AUTH_URL", "https://accounts.google.com/o/oauth2/v2/auth")
TOKEN_URL = os.environ.get("GOOGLE_TOKEN_URL", "https://oauth2.googleapis.com/token")
REVOKE_URL = os.environ.get("GOOGLE_REVOKE_URL", "https://oauth2.googleapis.com/revoke")
DRIVE_URL = os.environ.get("GOOGLE_DRIVE_URL", "https://www.googleapis.com/drive/v3")
UPLOAD_URL = os.environ.get("GOOGLE_UPLOAD_URL", "https://www.googleapis.com/upload/drive/v3")
SHEETS_URL = os.environ.get("GOOGLE_SHEETS_URL", "https://sheets.googleapis.com/v4/spreadsheets")
PUBLIC_URL = os.environ.get("PUBLIC_URL", "").rstrip("/")
SCOPE = "https://www.googleapis.com/auth/drive.file"
FOLDER_MIME = "application/vnd.google-apps.folder"
SHEET_MIME = "application/vnd.google-apps.spreadsheet"
CHECK_EVERY = float(os.environ.get("DRIVE_CHECK_EVERY", "300"))   # seconds between looks for edits made in Drive
LOOP = os.environ.get("DRIVE_LOOP", "1") != "0"
KINDS = ("profile", "settings", "facts", "copilot", "jobs", "cv")
MAX_JOB_ROWS = 3000
FILE_FIELDS = "id,name,mimeType,trashed,version,md5Checksum,size,parents,appProperties"
STOP_CODES = ("reconnect", "setup")        # wait for the user (connect again) or the admin (fix the Google client)

_client = None
_locks = {}
_wake = None
_tasks = set()


class DriveError(Exception):
    """code: reconnect (connect Google Drive again), setup (the server's Google client is wrong), api_off (the Drive or
    Sheets API is off in Google Cloud), rate (Google asks to slow down), gone (the file is not there), net, google."""

    def __init__(self, code, message, status=0):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


def client():
    global _client
    if _client is None:
        _client = httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=20.0))
    return _client


async def close():
    global _client
    for task in list(_tasks):
        task.cancel()
    if _client is not None:
        await _client.aclose()
        _client = None


def redirect_uri():
    return PUBLIC_URL + "/oauth/google/callback"


def configured(s=None):
    s = s if s is not None else db.settings()
    return bool(s.get("google_client_id") and s.get("google_client_secret") and PUBLIC_URL)


def _error_of(resp):
    """The DriveError for one of Google's error answers."""
    msg, reasons = "", set()
    try:
        data = resp.json()
    except ValueError:
        data = {}
    err = data.get("error") if isinstance(data, dict) else None
    if isinstance(err, dict):
        msg = str(err.get("message") or "")
        reasons.add(str(err.get("status") or ""))
        for e in err.get("errors") or []:
            if isinstance(e, dict):
                reasons.add(str(e.get("reason") or ""))
        for d in err.get("details") or []:
            if isinstance(d, dict):
                reasons.add(str(d.get("reason") or ""))
    elif isinstance(err, str):          # the OAuth endpoints: {"error": "invalid_grant", "error_description": "..."}
        reasons.add(err)
        msg = str(data.get("error_description") or err)
    status = resp.status_code
    if status == 429 or reasons & {"rateLimitExceeded", "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED", "RESOURCE_EXHAUSTED"}:
        code = "rate"
    elif reasons & {"accessNotConfigured", "SERVICE_DISABLED"} or "has not been used in project" in msg or "is disabled" in msg:
        code = "api_off"
    elif reasons & {"invalid_client", "unauthorized_client", "redirect_uri_mismatch"}:
        code = "setup"
    elif status == 401 or reasons & {"invalid_grant", "insufficientPermissions", "authError", "ACCESS_TOKEN_SCOPE_INSUFFICIENT"}:
        code = "reconnect"
    elif status == 404:
        code = "gone"
    else:
        code = "google"
    return DriveError(code, msg or f"Google answered HTTP {status}.", status)


MESSAGES = {
    "reconnect": "Google Drive needs you to connect it again.",
    "setup": "Google Drive is not set up right on the server (the admin should check the Google client).",
    "api_off": "The Google Drive or Google Sheets API is turned off for the server's Google project.",
    "rate": "Google asked to slow down; it tries again in a few minutes.",
    "net": "Could not reach Google; it tries again in a few minutes.",
    "gone": "A file of your JSTB folder was not found; the next sync makes it again.",
}


async def _form(url, fields):
    try:
        return await client().post(url, data=fields, headers={"Accept": "application/json"})
    except httpx.HTTPError as e:
        raise DriveError("net", f"Could not reach Google ({type(e).__name__}).")


# ---------------------------------------------------------------- one user's Google session
class Session:
    def __init__(self, user_id, row=None, access="", until=0.0, refresh=""):
        self.uid = user_id
        self.refresh = vault.unseal(row["refresh_token"]) if row is not None else refresh
        self.access = vault.unseal(row["access_token"]) if row is not None else access
        self.until = row["access_until"] if row is not None else until

    async def token(self, force=False):
        if self.access and not force and self.until > time.time() + 60:
            return self.access
        if not self.refresh:
            raise DriveError("reconnect", MESSAGES["reconnect"])
        s = db.settings()
        r = await _form(TOKEN_URL, {"grant_type": "refresh_token", "refresh_token": self.refresh,
                                    "client_id": s.get("google_client_id", ""), "client_secret": s.get("google_client_secret", "")})
        if r.status_code != 200:
            e = _error_of(r)
            if e.code == "google" and r.status_code in (400, 401):
                e.code = "reconnect"
            raise e
        data = r.json()
        self.access = str(data.get("access_token") or "")
        self.until = time.time() + float(data.get("expires_in") or 3600)
        with db.tx() as t:
            t.execute("UPDATE drive_links SET access_token = ?, access_until = ? WHERE user_id = ?",
                      (vault.seal(self.access), self.until, self.uid))
        return self.access

    async def call(self, method, url, *, params=None, body=None, content=None, headers=None, raw=False):
        for attempt in (0, 1):
            tok = await self.token(force=attempt == 1)
            h = dict(headers or {})
            h["Authorization"] = f"Bearer {tok}"
            try:
                r = await client().request(method, url, params=params, json=body, content=content, headers=h)
            except httpx.HTTPError as e:
                raise DriveError("net", f"Could not reach Google ({type(e).__name__}).")
            if r.status_code == 401 and attempt == 0:
                continue                       # the access token ran out early: a new one, once
            if r.status_code >= 400:
                raise _error_of(r)
            if raw:
                return r
            try:
                return r.json() if r.content else {}
            except ValueError:
                raise DriveError("google", "Google sent an answer that is not JSON.")
        raise DriveError("reconnect", MESSAGES["reconnect"])

    # ---- Drive
    async def about(self):
        return await self.call("GET", f"{DRIVE_URL}/about", params={"fields": "user(emailAddress,displayName)"})

    async def get(self, fid):
        return await self.call("GET", f"{DRIVE_URL}/files/{fid}", params={"fields": FILE_FIELDS})

    async def find(self, q):
        r = await self.call("GET", f"{DRIVE_URL}/files", params={"q": q, "fields": f"files({FILE_FIELDS})", "pageSize": 100,
                                                                 "spaces": "drive"})
        return [f for f in r.get("files") or [] if isinstance(f, dict)]

    async def create(self, meta):
        return await self.call("POST", f"{DRIVE_URL}/files", params={"fields": FILE_FIELDS}, body=meta)

    async def upload(self, fid, meta, raw, mime):
        boundary = "autoapply" + secrets.token_hex(12)
        body = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{json.dumps(meta)}\r\n"
                f"--{boundary}\r\nContent-Type: {mime}\r\n\r\n").encode() + raw + f"\r\n--{boundary}--\r\n".encode()
        return await self.call("PATCH" if fid else "POST", f"{UPLOAD_URL}/files" + (f"/{fid}" if fid else ""),
                               params={"uploadType": "multipart", "fields": FILE_FIELDS}, content=body,
                               headers={"Content-Type": f"multipart/related; boundary={boundary}"})

    async def download(self, fid):
        return (await self.call("GET", f"{DRIVE_URL}/files/{fid}", params={"alt": "media"}, raw=True)).content

    # ---- Sheets
    async def sheets(self, sid):
        r = await self.call("GET", f"{SHEETS_URL}/{sid}",
                            params={"fields": "sheets.properties(sheetId,title,gridProperties(rowCount,columnCount))"})
        return [s.get("properties") or {} for s in r.get("sheets") or []]

    async def values(self, sid, sheet_id):
        r = await self.call("POST", f"{SHEETS_URL}/{sid}/values:batchGetByDataFilter",
                            body={"dataFilters": [{"gridRange": {"sheetId": sheet_id}}], "majorDimension": "ROWS",
                                  "valueRenderOption": "FORMATTED_VALUE"})
        ranges = r.get("valueRanges") or []
        if not ranges:
            return []
        return (ranges[0].get("valueRange") or {}).get("values") or []

    async def batch(self, sid, requests):
        return await self.call("POST", f"{SHEETS_URL}/{sid}:batchUpdate", body={"requests": requests})


# ---------------------------------------------------------------- what the sheets hold
YES_NO = ["Yes", "No"]
PROFILE = [("phone", "Mobile number", None), ("city", "City you live in", None),
           ("graduation_year", "Year you graduated", None), ("expected_salary", "Expected monthly salary", None),
           ("current_salary", "Current monthly salary", None),
           ("currency", "Salary currency", ["EGP", "USD", "EUR", "GBP", "SAR", "AED", "QAR", "KWD", "OMR", "BHD", "JOD"]),
           ("notice_days", "Notice period (days)", None),
           ("military", "Military service", ["Completed", "Exempted", "Postponed", "Not applicable"]),
           ("english", "English", ["Native", "Fluent", "Professional", "Good", "Basic"]),
           ("onsite", "OK to work from the office?", YES_NO), ("relocate", "Willing to move to another city?", YES_NO),
           ("driving", "Driving license?", YES_NO), ("authorized", "Countries you can work in without a visa", None),
           ("sponsorship", "Need a visa sponsor?", YES_NO)]
SETTING_ROWS = [("KEYWORDS", "Job search", "Job titles (separate them with ;)"),
                ("LOCATION", "Job search", "Where to look for jobs"),
                ("ABOUT_ME", "For the AI", "Anything else the AI should know")]
CATEGORIES = ["Personal", "Contact", "Education", "Experience", "Skills", "Certifications", "Languages",
              "Salary & availability", "Work preferences", "Work permit", "Links", "Other"]
SOURCES = {"you": "You", "ai": "AI guess", "saved": "Saved"}
STATUSES = ["To apply", "Applied", "Interview", "Offer", "Rejected", "Skipped"]
APPLIED = ("applied", "interview", "offer", "rejected")

ANSWERS = {"key": "answers", "title": "My answers", "tab": "Answers", "cols": ["Section", "Question", "Answer", "By", "ID"],
           "need": ["Question", "Answer", "ID"], "edit": ["Section", "Question", "Answer"],
           "widths": [170, 320, 380, 90, 90], "text": ["Question", "Answer"]}
JOBS = {"key": "jobs", "title": "My jobs", "tab": "Jobs",
        "cols": ["Status", "Job title", "Company", "Applied on", "Note", "Link", "Via", "ID"],
        "need": ["Status", "Link", "ID"], "edit": ["Status", "Job title", "Company", "Note"],
        "widths": [110, 290, 190, 105, 280, 320, 110, 90], "text": ["Job title", "Company", "Note", "Link"]}
LAYOUTS = (ANSWERS, JOBS)


def _num_text(v):
    if isinstance(v, bool):
        return "Yes" if v else "No"
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def answer_text(value):
    if value is None:
        return ""
    if isinstance(value, list):
        return "; ".join(_num_text(x) for x in value if x is not None and str(x).strip())
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False)
    return _num_text(value)


def answer_value(text, old):
    """The sheet's text back to an answer: a list when it was a list, a number when it was a number."""
    t = text.strip()
    if isinstance(old, list):
        return [x.strip() for x in t.split(";") if x.strip()]
    if isinstance(old, (int, float)) and not isinstance(old, bool) and re.fullmatch(r"-?\d+(\.\d+)?", t):
        n = float(t)
        return int(n) if n.is_integer() else n
    return t


def split_list(value):
    return [x.strip() for x in str(value or "").replace("\n", ";").split(";") if x.strip()]


def about_text(text):
    """The notes for the AI without the template's comment lines and empty 'Phone:' lines (as the app reads them)."""
    out = []
    for line in str(text or "").split("\n"):
        line = line.strip()
        if not line or line.startswith("#") or re.fullmatch(r"[^:]+:\s*", line):
            continue
        out.append(line)
    return "\n".join(out)


def clean_profile(field, raw):
    """The app's own cleaning of a setup answer (Profile.clean), plus the right case for picked answers."""
    v = str(raw or "").replace("\n", " ").strip()
    if field in ("current_salary", "expected_salary", "notice_days", "graduation_year"):
        v = re.sub(r"[^0-9]", "", re.sub(r"\.\d*$", "", v.replace(",", "")))
    elif field == "currency":
        v = re.sub(r"[^A-Z]", "", v.upper())
        if len(v) != 3:
            v = "EGP"
    elif field == "phone":
        v = re.sub(r"[^0-9+ ()-]", "", v).strip()
    else:
        for f, _label, picks in PROFILE:
            if f == field and picks:
                for p in picks:
                    if p.lower() == v.lower():
                        v = p
    return v[:120]


def category_of(name):
    n = str(name or "").strip().lower()
    for c in CATEGORIES:
        if c.lower() == n:
            return c
    return ""


def day_of(ts):
    try:
        ts = float(ts or 0)
    except (TypeError, ValueError):
        return ""
    return datetime.fromtimestamp(ts, db.TIMEZONE).strftime("%Y-%m-%d") if ts > 0 else ""


def status_of(item):
    """A job of yours as one word of the Status column."""
    state = item.get("state") or "new"
    if state == "skipped":
        return "Skipped"
    if state == "applied":
        return (item.get("stage") or "applied").capitalize()
    return "To apply"


# Copilot.parseLink in the app, the same here: the same job pasted in the sheet gets the same ID as when shared.
LI_JOB = re.compile(r"linkedin\.com/(?:comm/)?jobs/view/(?:[^/?#\s]*?-)?(\d{6,})")
LI_CURRENT = re.compile(r"[?&]currentJobId=(\d{6,})")
URL_RE = re.compile(r"https?://[^\s<>\"']+")


def parse_link(raw):
    url = re.sub(r"[).,;!]+$", "", raw)
    li = LI_JOB.search(url)
    num = li.group(1) if li else None
    if num is None and "linkedin.com" in url:
        cur = LI_CURRENT.search(url)
        num = cur.group(1) if cur else None
    if num:
        return {"url": f"https://www.linkedin.com/jobs/view/{num}/", "id": f"li:{num}", "channel": "linkedin"}
    plain = re.sub(r"#.*$", "", url)
    plain = re.sub(r"([?&])(utm_[a-z]+|gh_src|source|ref|trk|refId|trackingId)=[^&]*", r"\1", plain)
    plain = re.sub(r"[?&]+$", "", re.sub(r"&{2,}", "&", re.sub(r"\?&+", "?", plain)))
    on_li = "linkedin.com" in plain.lower()
    h = hashlib.sha1(plain.encode()).hexdigest()[:12]
    return {"url": plain, "id": ("li:" if on_li else "c:") + h, "channel": "linkedin" if on_li else "company"}


def host_of(url):
    m = re.match(r"https?://(?:www\.)?([^/:?#]+)", url)
    return m.group(1) if m else url


def answers_rows(data):
    rows = []
    prof, sets = data["profile"], data["settings"]
    for f, label, _picks in PROFILE:
        rows.append({"Section": "Main answers", "Question": label, "Answer": answer_text((prof.get(f) or {}).get("v")),
                     "By": "", "ID": f"p:{f}"})
    for key, section, label in SETTING_ROWS:
        v = str((sets.get(key) or {}).get("v") or "")
        v = "; ".join(split_list(v)) if key == "KEYWORDS" else (about_text(v) if key == "ABOUT_ME" else v)
        rows.append({"Section": section, "Question": label, "Answer": v, "By": "", "ID": f"s:{key}"})
    facts = sorted(data["facts"].items(), key=lambda kv: (
        CATEGORIES.index(category_of(kv[1].get("category")) or "Other"), str(kv[1].get("label") or "").lower()))
    for fid, f in facts:
        rows.append({"Section": category_of(f.get("category")) or "Other", "Question": str(f.get("label") or ""),
                     "Answer": answer_text(f.get("value")), "By": SOURCES.get(f.get("source"), ""), "ID": f"f:{fid}"})
    return rows


def jobs_rows(data):
    rows = []
    for cid, it in data["copilot"].items():
        st = status_of(it)
        applied = st not in ("To apply", "Skipped")
        at = float(it.get("applied_at") or 0) if applied else float(it.get("added") or 0)
        rows.append(({"To apply": 0, "Skipped": 2}.get(st, 1), -at, {
            "Status": st, "Job title": str(it.get("title") or ""), "Company": str(it.get("company") or ""),
            "Applied on": day_of(it.get("applied_at")) if applied else "", "Note": str(it.get("note") or ""),
            "Link": str(it.get("url") or ""), "Via": "LinkedIn" if it.get("channel") == "linkedin" else "Company site",
            "ID": f"c:{cid}"}))
    for jid, j in data["jobs"].items():
        if j.get("status") != "applied":
            continue
        at = float(j.get("ts") or 0)
        rows.append((1, -at, {"Status": (j.get("stage") or "applied").capitalize(), "Job title": str(j.get("title") or ""),
                              "Company": str(j.get("company") or ""), "Applied on": day_of(at), "Note": str(j.get("note") or ""),
                              "Link": str(j.get("url") or ""), "Via": "Auto apply", "ID": f"a:{jid}"}))
    rows.sort(key=lambda r: (r[0], r[1]))
    return [r[2] for r in rows[:MAX_JOB_ROWS]]


def parse_sheet(values, layout):
    """The rows of a sheet as {column: text} plus '_x' (columns the user added), or None when the heading row is not
    there any more (then nothing is read from it; the next write puts it back)."""
    if not values:
        return None
    header = [str(h).strip() for h in values[0]]
    idx = {}
    for i, h in enumerate(header):
        if h and h.lower() not in idx:
            idx[h.lower()] = i
    if not all(c.lower() in idx for c in layout["need"]):
        return None
    ours = {c.lower() for c in layout["cols"]}
    extras = [h for h in header if h and h.lower() not in ours]

    def cell(raw, i):
        return str(raw[i]).strip() if i is not None and i < len(raw) and raw[i] is not None else ""

    rows = []
    for raw in values[1:]:
        row = {c: cell(raw, idx.get(c.lower())) for c in layout["cols"]}
        row["_x"] = {h: cell(raw, idx[h.lower()]) for h in extras if cell(raw, idx[h.lower()])}
        if any(row[c] for c in layout["cols"]) or row["_x"]:
            rows.append(row)
    return {"rows": rows, "extras": extras}


def _same(a, b):
    return re.sub(r"\s+", " ", str(a or "")).strip() == re.sub(r"\s+", " ", str(b or "")).strip()


def merge(layout, base, cur, data, now):
    """The user's edits in the sheet (cur) since it was written (base) as record changes [(kind, rid, data, t)], and
    the rows kept as they are: rows without an ID that could not be read. Rows that seem to have lost their place
    (a sort of some columns only) are left out; too many of them and nothing is read this time."""
    base_rows = dict(base.get("rows") or {})
    known_loose = [json.dumps(r, sort_keys=True) for r in base.get("loose") or []]
    derived = {it["ID"]: it for it in (answers_rows(data) if layout is ANSWERS else jobs_rows(data))}
    by_id, fresh, misplaced = {}, [], 0
    for row in cur["rows"]:
        rid = row["ID"]
        if rid and rid not in by_id and (rid in base_rows or rid in derived):
            by_id[rid] = row
            if rid not in base_rows:      # written, but the last write was not remembered: the record is the base
                base_rows[rid] = derived[rid]
        elif rid and (rid[:2] in ("p:", "s:", "a:") or (rid not in by_id and re.match(r"^[fc]:", rid))):
            continue                      # a copy of a fixed row, or the row of an item deleted meanwhile: it goes
        else:
            fresh.append(row)             # new, or a copied row: a new item
    changes, keep = [], []
    edits = {}
    for rid, b in base_rows.items():
        c = by_id.get(rid)
        if c is None:
            if rid.startswith("f:"):
                changes.append(("facts", rid[2:], None, now))
            elif rid.startswith("c:"):
                changes.append(("copilot", rid[2:], None, now))
            continue
        if (layout is JOBS and b.get("Link") and not _same(c["Link"], b["Link"])) or (
                layout is ANSWERS and rid[:2] in ("p:", "s:") and not _same(c["Question"], b["Question"])):
            misplaced += 1
            continue
        e = {col: c[col] for col in layout["edit"] if not _same(c[col], b.get(col))}
        if e:
            edits[rid] = e
    if misplaced and misplaced >= max(3, len(base_rows) // 3):
        return [], [], misplaced
    for rid, e in edits.items():
        ch = (_answer_edit if layout is ANSWERS else _job_edit)(rid, e, data, now)
        if ch:
            changes.append(ch)
    for row in fresh:
        row = dict(row, ID="")
        ch = None if json.dumps(row, sort_keys=True) in known_loose else (_new_answer if layout is ANSWERS else _new_job)(row, data, now)
        if ch:
            changes.append(ch)
            if ch[0] == "copilot":        # the same link twice: one job
                data["copilot"][ch[1]] = ch[2]
        else:
            keep.append(row)
    return changes, keep, misplaced


def _answer_edit(rid, e, data, now):
    kind, key = rid[:1], rid[2:]
    if kind == "p" and "Answer" in e:
        return ("profile", key, {"v": clean_profile(key, e["Answer"])}, now)
    if kind == "s" and "Answer" in e:
        v = e["Answer"]
        v = ";".join(split_list(v)) if key == "KEYWORDS" else (v.replace("\n", " ").strip() if key == "LOCATION" else v)
        return ("settings", key, {"v": v}, now)
    if kind == "f":
        fact = data["facts"].get(key)
        if fact is None:
            return None
        new = dict(fact)
        if "Answer" in e:
            if not e["Answer"].strip():
                return ("facts", key, None, now)
            new["value"] = answer_value(e["Answer"], fact.get("value"))
            new["source"] = "you"
        if "Question" in e and e["Question"].strip():
            new["label"] = e["Question"].strip()
        if "Section" in e and category_of(e["Section"]):
            new["category"] = category_of(e["Section"])
        new["updated"] = now
        return ("facts", key, new, now)
    return None


def _new_answer(row, data, now):
    q, a = row["Question"].strip(), row["Answer"].strip()
    if not q or not a:
        return None
    fid = "f" + secrets.token_hex(5)
    return ("facts", fid, {"id": fid, "category": category_of(row["Section"]), "label": q, "value": a, "asked": [],
                           "source": "you", "updated": now}, now)


def _set_status(item, label, now, copilot=True):
    s = label.strip().lower()
    if copilot and s in ("to apply", "new"):
        item["state"], item["stage"] = "new", ""
        item.pop("applied_at", None)
    elif copilot and s == "skipped":
        item["state"] = "skipped"
    elif s in APPLIED:
        if copilot and item.get("state") != "applied":
            item["applied_at"] = now
            item["state"] = "applied"
        item["stage"] = s


def _job_edit(rid, e, data, now):
    copilot = rid.startswith("c:")
    kind = "copilot" if copilot else "jobs"
    item = data[kind].get(rid[2:])
    if item is None:
        return None
    new = dict(item)
    if "Status" in e:
        _set_status(new, e["Status"], now, copilot)
    for col, k in (("Job title", "title"), ("Company", "company"), ("Note", "note")):
        if col in e and (e[col].strip() or k == "note"):
            new[k] = e[col].strip()
    if copilot:
        new["updated"] = now
    return (kind, rid[2:], new, now) if new != item else None


def _new_job(row, data, now):
    m = URL_RE.search(row["Link"]) or URL_RE.search(row["Job title"])
    if not m:
        return None
    link = parse_link(m.group())
    item = dict(data["copilot"].get(link["id"]) or {})
    if not item:
        title = row["Job title"].strip() if not URL_RE.search(row["Job title"]) else ""
        item = {"id": link["id"], "url": link["url"], "title": title[:160] or (
            "LinkedIn job" if link["channel"] == "linkedin" else host_of(link["url"])), "company": row["Company"].strip()[:100],
            "channel": link["channel"], "source": "sheet", "added": now, "state": "new", "stage": "", "fit": None, "why": ""}
    if row["Note"].strip():
        item["note"] = row["Note"].strip()
    if row["Status"].strip():
        _set_status(item, row["Status"], now)
    item["updated"] = now
    return ("copilot", link["id"], item, now)


def _day_ts(text):
    try:
        d = datetime.strptime(str(text or "").strip()[:10], "%Y-%m-%d")
    except ValueError:
        return 0.0
    return d.replace(hour=12, tzinfo=db.TIMEZONE).timestamp()


SOURCE_OF = {v: k for k, v in SOURCES.items()}


def import_rows(layout, cur, data, now):
    """Everything in a sheet as records: an account that has nothing yet (a new server, an account made again) takes
    its data back from its Drive. Returns (changes, rows kept as they are)."""
    changes, keep = [], []
    fields = {f for f, _l, _p in PROFILE}
    for row in cur["rows"]:
        rid, ch = row["ID"], None
        if layout is ANSWERS:
            if rid[2:] in fields and rid.startswith("p:") and row["Answer"]:
                ch = ("profile", rid[2:], {"v": clean_profile(rid[2:], row["Answer"])}, now)
            elif rid.startswith("s:") and row["Answer"]:
                ch = _answer_edit(rid, {"Answer": row["Answer"]}, data, now)
            elif rid.startswith("f:") and row["Question"] and row["Answer"]:
                ch = ("facts", rid[2:], {"id": rid[2:], "category": category_of(row["Section"]), "label": row["Question"],
                                         "value": row["Answer"], "asked": [], "source": SOURCE_OF.get(row["By"], "you"),
                                         "updated": now}, now)
            elif not rid:
                ch = _new_answer(row, data, now)
        else:
            at = _day_ts(row["Applied on"]) or now
            if rid.startswith("c:") and URL_RE.match(row["Link"]):
                item = {"id": rid[2:], "url": row["Link"], "title": row["Job title"] or host_of(row["Link"]),
                        "company": row["Company"], "channel": "linkedin" if row["Via"] == "LinkedIn" else "company",
                        "source": "drive", "added": at, "state": "new", "stage": "", "fit": None, "why": "", "note": row["Note"]}
                _set_status(item, row["Status"] or "To apply", at)
                ch = ("copilot", rid[2:], item, now)
            elif rid.startswith("a:"):
                st = row["Status"].strip().lower()
                ch = ("jobs", rid[2:], {"id": rid[2:], "status": "applied", "title": row["Job title"], "company": row["Company"],
                                        "url": row["Link"], "stage": st if st in APPLIED else "applied", "ts": at,
                                        "score": None, "reason": "", "note": row["Note"]}, now)
            elif not rid:
                ch = _new_job(row, data, now)
        if ch:
            changes.append(ch)
            if ch[0] == "copilot":
                data["copilot"][ch[1]] = ch[2]
        elif not rid:
            keep.append(dict(row, ID=""))
    return changes, keep


def build(layout, data, extras, loose, cur_rows=None):
    """The sheet's rows (lists) from the records: heading, one row per item, then the kept rows; columns the user
    added keep their values (by the row's ID)."""
    items = answers_rows(data) if layout is ANSWERS else jobs_rows(data)
    xs = {}
    for r in cur_rows or []:
        if r.get("ID") and r.get("_x"):
            xs.setdefault(r["ID"], r["_x"])
    cols = layout["cols"] + list(extras)
    out = [cols]
    for it in items:
        x = xs.get(it["ID"]) or {}
        out.append([it[c] for c in layout["cols"]] + [x.get(h, "") for h in extras])
    for r in loose:
        out.append([r.get(c, "") for c in layout["cols"]] + [(r.get("_x") or {}).get(h, "") for h in extras])
    return out, {it["ID"]: {c: it[c] for c in layout["cols"]} | ({"_x": xs[it["ID"]]} if it["ID"] in xs else {}) for it in items}


# ---------------------------------------------------------------- writing a sheet
def _col(layout, name):
    return layout["cols"].index(name)


def _setup_requests(layout, sheet_id):
    """Once, when the sheet is made: tab name, frozen heading in bold, widths, the ID column hidden, a filter."""
    reqs = [{"updateSheetProperties": {"properties": {"sheetId": sheet_id, "title": layout["tab"],
                                                      "gridProperties": {"frozenRowCount": 1}},
                                       "fields": "title,gridProperties.frozenRowCount"}},
            {"repeatCell": {"range": {"sheetId": sheet_id, "startRowIndex": 0, "endRowIndex": 1},
                            "cell": {"userEnteredFormat": {"textFormat": {"bold": True},
                                                           "backgroundColor": {"red": 0.91, "green": 0.94, "blue": 0.98}}},
                            "fields": "userEnteredFormat(textFormat,backgroundColor)"}}]
    for i, w in enumerate(layout["widths"]):
        reqs.append({"updateDimensionProperties": {"range": {"sheetId": sheet_id, "dimension": "COLUMNS", "startIndex": i,
                                                             "endIndex": i + 1},
                                                   "properties": {"pixelSize": w}, "fields": "pixelSize"}})
    idc = _col(layout, "ID")
    reqs.append({"updateDimensionProperties": {"range": {"sheetId": sheet_id, "dimension": "COLUMNS", "startIndex": idc,
                                                         "endIndex": idc + 1},
                                               "properties": {"hiddenByUser": True}, "fields": "hiddenByUser"}})
    reqs.append({"setBasicFilter": {"filter": {"range": {"sheetId": sheet_id}}}})   # sorting by it moves whole rows
    return reqs


def _list_rule(values):
    return {"condition": {"type": "ONE_OF_LIST", "values": [{"userEnteredValue": v} for v in values]},
            "strict": True, "showCustomUi": True}


def _write_requests(layout, sheet_id, rows):
    n = len(rows)
    width = max(len(r) for r in rows)
    reqs = [{"updateSheetProperties": {"properties": {"sheetId": sheet_id, "gridProperties": {
        "rowCount": max(200, n + 100), "columnCount": max(width, len(layout["cols"]))}},
        "fields": "gridProperties(rowCount,columnCount)"}},
        {"updateCells": {"range": {"sheetId": sheet_id}, "fields": "userEnteredValue"}},
        {"updateCells": {"start": {"sheetId": sheet_id, "rowIndex": 0, "columnIndex": 0},
                         "rows": [{"values": [{"userEnteredValue": {"stringValue": str(v)}} for v in r]} for r in rows],
                         "fields": "userEnteredValue"}}]
    for name in layout["text"]:      # typed text stays text (a phone number keeps its 0)
        c = _col(layout, name)
        reqs.append({"repeatCell": {"range": {"sheetId": sheet_id, "startRowIndex": 1, "endRowIndex": max(200, n + 100),
                                              "startColumnIndex": c, "endColumnIndex": c + 1},
                                    "cell": {"userEnteredFormat": {"numberFormat": {"type": "TEXT"}, "wrapStrategy": "WRAP"}},
                                    "fields": "userEnteredFormat(numberFormat,wrapStrategy)"}})
    if layout is JOBS:
        c = _col(layout, "Status")
        reqs.append({"setDataValidation": {"range": {"sheetId": sheet_id, "startRowIndex": 1, "endRowIndex": max(200, n + 100),
                                                     "startColumnIndex": c, "endColumnIndex": c + 1},
                                           "rule": _list_rule(STATUSES)}})
    else:
        c = _col(layout, "Answer")
        reqs.append({"setDataValidation": {"range": {"sheetId": sheet_id, "startRowIndex": 1, "endRowIndex": max(200, n + 100),
                                                     "startColumnIndex": c, "endColumnIndex": c + 1}}})
        picks = {f"p:{f}": p for f, _l, p in PROFILE if p}
        idc = _col(layout, "ID")
        for i, r in enumerate(rows[1:], start=1):
            if len(r) > idc and r[idc] in picks:
                reqs.append({"setDataValidation": {"range": {"sheetId": sheet_id, "startRowIndex": i, "endRowIndex": i + 1,
                                                             "startColumnIndex": c, "endColumnIndex": c + 1},
                                                   "rule": _list_rule(picks[r[idc]])}})
    return reqs


# ---------------------------------------------------------------- one user's sync
def _load(uid):
    return records.all_of(db.conn(), uid, KINDS)


def _track(t, uid, kind, item):
    """A status changed in My jobs: the admin page's applications count it, as if the app had reported it."""
    if kind == "copilot" and item.get("state") == "applied":
        job_id, channel, at = str(item.get("id") or ""), item.get("channel") or "company", item.get("applied_at")
    elif kind == "jobs" and item.get("status") == "applied":
        job_id, channel, at = str(item.get("id") or ""), "auto", item.get("ts")
    else:
        return
    if not job_id:
        return
    stage = item.get("stage") if item.get("stage") in APPLIED else "applied"
    t.execute("INSERT INTO applications(user_id, job_id, title, company, status, score, at, channel, stage, reason, url) "
              "VALUES (?, ?, ?, ?, 'applied', NULL, ?, ?, ?, '', ?) ON CONFLICT(user_id, job_id, status) DO UPDATE SET "
              "stage = excluded.stage", (uid, job_id[:80], str(item.get("title") or "")[:200], str(item.get("company") or "")[:120],
                                          float(at or time.time()), channel if channel in ("auto", "linkedin", "company") else "company",
                                          stage, str(item.get("url") or "")[:500]))


def _save_changes(uid, changes):
    if not changes:
        return 0
    with db.tx() as t:
        revs = records.write(t, uid, changes, "drive")
        for kind, rid, data, _t in changes:
            if data is not None and (kind, rid) in revs and kind in ("copilot", "jobs"):
                _track(t, uid, kind, data)
    return len(revs)


def extract_text(raw, ext):
    """The CV's text, read here when a new version comes from Drive (the app reads its own with pdf.js)."""
    if ext == ".txt":
        return records.clean_text(raw.decode("utf-8", "replace"))
    try:
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(raw))
        parts = [(page.extract_text() or "") for page in reader.pages[:30]]
    except Exception:  # noqa: BLE001 - a damaged or odd PDF just has no text
        return ""
    return records.clean_text("\n".join(parts))


async def _ensure_folder(s, link, st):
    fid = link["folder_id"]
    if fid:
        try:
            meta = await s.get(fid)
            if not meta.get("trashed"):
                return fid
        except DriveError as e:
            if e.code != "gone":
                raise
    st["files"] = {}
    found = [f for f in await s.find("appProperties has { key='autoapply' and value='folder' } and trashed = false")
             if f.get("mimeType") == FOLDER_MIME]
    if found:
        fid = found[0]["id"]          # connected before: the same folder and its files again
        st["import_all"] = records.count(db.conn(), link["user_id"]) == 0   # an empty account takes them back
        for f in await s.find(f"'{fid}' in parents and trashed = false"):
            key = (f.get("appProperties") or {}).get("autoapply")
            if key in ("cv", "answers", "jobs") and key not in st["files"]:
                st["files"][key] = {"id": f["id"], "reused": True}
    else:
        fid = (await s.create({"name": "JSTB", "mimeType": FOLDER_MIME, "appProperties": {"autoapply": "folder"}}))["id"]
    with db.tx() as t:
        t.execute("UPDATE drive_links SET folder_id = ? WHERE user_id = ?", (fid, link["user_id"]))
    return fid


async def _file(s, info, children):
    """The Drive file of info (from the folder's list, else asked by ID: moved files still count), or None."""
    fid = info.get("id")
    if not fid:
        return None
    meta = children.get(fid)
    if meta is None:
        try:
            meta = await s.get(fid)
        except DriveError as e:
            if e.code == "gone":
                return None
            raise
    return None if meta.get("trashed") else meta


async def _sheet_id(s, fid, want=None):
    """(the tab's sheetId, True when it is another tab than `want`: ours was deleted)."""
    ids = [int(p.get("sheetId") or 0) for p in await s.sheets(fid)]
    if want is not None and want in ids:
        return want, False
    return (ids[0] if ids else 0), True


def _values_of(layout, cur, extras):
    return [layout["cols"] + list(extras)] + [[r[c] for c in layout["cols"]] + [r["_x"].get(h, "") for h in extras]
                                             for r in cur["rows"]]


async def _sync_sheet(s, uid, st, layout, folder, children):
    """One sheet both ways. Returns how many records the user's edits changed."""
    info = st["files"].get(layout["key"]) or {}
    meta = await _file(s, info, children)
    if meta is None:
        meta = await s.create({"name": layout["title"], "mimeType": SHEET_MIME, "parents": [folder],
                               "appProperties": {"autoapply": layout["key"]}})
        sheet_id, _other = await _sheet_id(s, meta["id"])
        await s.batch(meta["id"], _setup_requests(layout, sheet_id))
        info = {"id": meta["id"], "sheet": sheet_id}
    changed = meta.get("version") != info.get("version") or info.get("rows") is None
    if changed or "sheet" not in info:
        sheet_id, other = await _sheet_id(s, meta["id"], info.get("sheet"))
        if other and "sheet" in info:   # the user deleted our tab: the first one is ours now, read as new
            await s.batch(meta["id"], _setup_requests(layout, sheet_id))
            info["rows"] = None
        info["sheet"] = sheet_id
    cur, pulled = None, 0
    if changed:
        cur = parse_sheet(await s.values(meta["id"], info["sheet"]), layout)
    loose = info.get("loose") or []
    extras = info.get("extras") or []
    if cur is not None:
        extras = cur["extras"]
        now = time.time()
        if info.get("rows") is not None:
            changes, loose, misplaced = merge(layout, info, cur, _load(uid), now)
            if misplaced:
                log.info("user %s: %s rows of %s seem out of place", uid, misplaced, layout["title"])
        elif st.get("import_all"):      # an account with nothing yet takes everything back from its Drive
            changes, loose = import_rows(layout, cur, _load(uid), now)
        else:                           # a sheet found again after connecting again: the server's data wins, new rows count
            changes, loose, _m = merge(layout, {}, {"rows": [r for r in cur["rows"] if not r["ID"]]}, _load(uid), now)
        pulled = _save_changes(uid, changes)
    rows, base = build(layout, _load(uid), extras, loose, cur["rows"] if cur else
                       [dict(v, ID=k) for k, v in (info.get("rows") or {}).items()])
    digest = hashlib.sha1(json.dumps(rows).encode()).hexdigest()
    same = (cur is not None and rows == _values_of(layout, cur, extras)) if changed else digest == info.get("digest")
    if not same:
        await s.batch(meta["id"], _write_requests(layout, info["sheet"], rows))
        meta = await s.get(meta["id"])
    info.update(version=meta.get("version"), rows=base, loose=loose, extras=extras, digest=digest)
    info.pop("reused", None)
    st["files"][layout["key"]] = info
    return pulled


async def _take_cv(s, uid, meta, have):
    """A new version of the CV file in Drive becomes the user's CV when the app can read its text.
    Returns (problem, sha256 of the file, saved)."""
    if int(meta.get("size") or 0) > records.CV_MAX:
        return "The CV in your Drive is bigger than 5 MB, so the app did not take it.", "", False
    raw = await s.download(meta["id"])
    sha = hashlib.sha256(raw).hexdigest()
    if have is not None and sha == have["sha256"]:
        return "", sha, False
    ext = ".pdf" if raw[:5] == b"%PDF-" else (".txt" if meta.get("mimeType") == "text/plain" else "")
    if not ext:
        return "The CV in your Drive is not a PDF or a .txt file, so the app did not take it.", "", False
    name = re.sub(r'[\\/:*?"<>|]', "_", str(meta.get("name") or "CV").strip())[:120]
    if records.cv_ext(name) != ext:
        name = re.sub(r"\.[A-Za-z0-9]{1,5}$", "", name) + ext
    text = await asyncio.get_running_loop().run_in_executor(None, extract_text, raw, ext)
    if len(text) < 150:
        return ("The new CV in your Drive has no text the app can read (a scanned image?). Upload a text-based PDF.",
                "", False)
    with db.tx() as t:
        records.save_cv(t, uid, name, raw, text, time.time(), "drive")
    return "", sha, True


async def _sync_cv(s, uid, st, folder, children):
    """The CV both ways: a new version uploaded in Drive (Manage versions) becomes the user's CV; a new CV from the app
    replaces the Drive file's content (Drive keeps the older ones in the file's versions).
    Returns (problem to show, records changed)."""
    info = st["files"].get("cv") or {}
    have = records.cv_file(db.conn(), uid)
    meta = await _file(s, info, children)
    md5 = str((meta or {}).get("md5Checksum") or "")
    saved = 0
    if meta is not None and md5 and info.get("md5") and md5 not in (info["md5"], info.get("rejected")):
        problem, sha, took = await _take_cv(s, uid, meta, have)
        if problem:
            info.update(rejected=md5, problem=problem)
        else:
            info.update(md5=md5, sha=sha)
            info.pop("rejected", None)
            info.pop("problem", None)
            saved = 1 if took else 0
            have = records.cv_file(db.conn(), uid)
    elif meta is not None and md5 and not info.get("md5"):
        info["md5"] = md5               # a CV file found again after connecting again
        if st.get("import_all") and have is None:   # nothing here yet: the CV in Drive is the CV
            problem, sha, took = await _take_cv(s, uid, meta, None)
            if not problem:
                info["sha"] = sha
                saved = 1 if took else 0
                have = records.cv_file(db.conn(), uid)
    if have is not None and have["sha256"] != info.get("sha"):
        meta = await s.upload(meta["id"] if meta else None,
                              {"name": have["file_name"]} if meta else {"name": have["file_name"], "parents": [folder],
                                                                        "appProperties": {"autoapply": "cv"}},
                              have["data"], have["mime"])
        md5 = str(meta.get("md5Checksum") or "")
        info = {"id": meta["id"], "md5": md5, "sha": have["sha256"]}
    st["files"]["cv"] = info
    return (info.get("problem", "") if md5 and md5 == info.get("rejected") else ""), saved


async def sync_user(uid, force=False):
    """One sync of a user's Drive. Returns the user's Drive status."""
    lock = _locks.setdefault(uid, asyncio.Lock())
    async with lock:
        link = db.one("SELECT * FROM drive_links WHERE user_id = ?", (uid,))
        if link is None or not configured() or (link["error_code"] in STOP_CODES and not force):
            return status(uid)
        s = Session(uid, link)
        try:
            st = json.loads(link["state"] or "{}")
        except ValueError:
            st = {}
        st.setdefault("files", {})
        rev = db.one("SELECT data_rev FROM users WHERE id = ?", (uid,))["data_rev"]
        own = 0
        try:
            folder = await _ensure_folder(s, link, st)
            children = {f["id"]: f for f in await s.find(f"'{folder}' in parents and trashed = false")}
            for layout in LAYOUTS:
                own += await _sync_sheet(s, uid, st, layout, folder, children)
            problem, n = await _sync_cv(s, uid, st, folder, children)
            own += n
            if st.pop("import_all", False) and own:   # taken back from Drive: the setup is done on every phone
                with db.tx() as t:
                    own += len(records.write(t, uid, [("settings", "SETUP_DONE", {"v": "1"}, time.time()),
                                                      ("profile", "done", {"v": "1"}, time.time())], "drive"))
                log.info("user %s: data taken back from Google Drive", uid)
            done = db.one("SELECT data_rev FROM users WHERE id = ?", (uid,))["data_rev"]
            with db.tx() as t:          # changes from a phone during this sync are written by the next one
                t.execute("UPDATE drive_links SET state = ?, synced_at = ?, checked_at = ?, error = ?, error_code = ?, "
                          "pushed_rev = ? WHERE user_id = ?", (json.dumps(st), time.time(), time.time(), problem,
                                                              "cv" if problem else "", done if done == rev + own else rev, uid))
        except DriveError as e:
            log.info("user %s: Drive sync stopped: %s (%s)", uid, e.message, e.code)
            with db.tx() as t:
                t.execute("UPDATE drive_links SET state = ?, checked_at = ?, error = ?, error_code = ? WHERE user_id = ?",
                          (json.dumps(st), time.time(), MESSAGES.get(e.code, e.message), e.code, uid))
        return status(uid)


# ---------------------------------------------------------------- connecting and disconnecting
def start_url(uid):
    """The Google page where the user lets the app use their Drive (the app opens it in the browser)."""
    s = db.settings()
    if not configured(s):
        raise DriveError("off", "Google Drive is not set up on the server yet.")
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    now = time.time()
    with db.tx() as t:
        t.execute("DELETE FROM oauth_states WHERE user_id = ? OR expires_at < ?", (uid, now))
        t.execute("INSERT INTO oauth_states(state_hash, user_id, verifier, expires_at) VALUES (?, ?, ?, ?)",
                  (security.token_hash(state), uid, verifier, now + 900))
    return AUTH_URL + "?" + urlencode({
        "client_id": s["google_client_id"], "redirect_uri": redirect_uri(), "response_type": "code", "scope": SCOPE,
        "access_type": "offline", "prompt": "consent", "include_granted_scopes": "true", "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256"})


async def exchange(code, verifier):
    """Google's tokens for the code it sent back (our PKCE verifier proves we started that sign-in)."""
    s = db.settings()
    if not configured(s):
        raise DriveError("off", "Google sign-in is not set up on the server yet.")
    r = await _form(TOKEN_URL, {"grant_type": "authorization_code", "code": code, "client_id": s["google_client_id"],
                                "client_secret": s["google_client_secret"], "redirect_uri": redirect_uri(),
                                "code_verifier": verifier})
    if r.status_code != 200:
        e = _error_of(r)
        raise DriveError(e.code, "Google did not accept the sign-in. Try again from the app." if e.code != "setup" else
                         MESSAGES["setup"])
    return r.json()


def _b64url_json(part):
    try:
        out = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except ValueError:
        return None
    return out if isinstance(out, dict) else None


def id_claims(id_token):
    """Who signed in, from the ID token Google's token endpoint just gave us over TLS (so its signature needs no check,
    OpenID Connect Core 3.1.3.7); its issuer, audience, expiry and the verified email are checked."""
    parts = str(id_token or "").split(".")
    claims = _b64url_json(parts[1]) if len(parts) == 3 else None
    if claims is None:
        raise DriveError("google", "Google did not say who signed in. Try again.")
    if claims.get("iss") not in ("https://accounts.google.com", "accounts.google.com") or \
            claims.get("aud") != db.settings().get("google_client_id") or float(claims.get("exp") or 0) < time.time() - 60:
        raise DriveError("google", "That Google sign-in is not for JSTB, or it ran out. Try again.")
    if not claims.get("sub") or not claims.get("email") or claims.get("email_verified") not in (True, "true"):
        raise DriveError("unverified", "That Google account has no verified email address.")
    return claims


async def link_tokens(uid, data, email=""):
    """Keep the Drive tokens of a sign-in (Connect Google Drive, or Sign in with Google), then sync in the background.
    Returns the Google account's email."""
    if SCOPE not in str(data.get("scope") or "").split():
        raise DriveError("scope", "JSTB needs to see and make the files it uses in your Drive. Try again and tick "
                                  "that box.")
    refresh = str(data.get("refresh_token") or "")
    access = str(data.get("access_token") or "")
    until = time.time() + float(data.get("expires_in") or 3600)
    old = db.one("SELECT refresh_token, email FROM drive_links WHERE user_id = ?", (uid,))
    if not refresh and old is not None:
        refresh = vault.unseal(old["refresh_token"])
    if not refresh:
        raise DriveError("google", "Google did not give a lasting sign-in. Try again from the app.")
    sess = Session(uid, access=access, until=until, refresh=refresh)
    with db.tx() as t:
        t.execute("INSERT INTO drive_links(user_id, refresh_token, access_token, access_until, linked_at) VALUES (?, ?, ?, ?, ?) "
                  "ON CONFLICT(user_id) DO UPDATE SET refresh_token = excluded.refresh_token, access_token = "
                  "excluded.access_token, access_until = excluded.access_until, linked_at = excluded.linked_at, "
                  "error = '', error_code = ''", (uid, vault.seal(refresh), vault.seal(access), until, time.time()))
    if not email:
        email = str(((await sess.about()).get("user") or {}).get("emailAddress") or "")
    other = old is not None and old["email"] and old["email"].lower() != email.lower()
    with db.tx() as t:
        if other:                       # another Google account: its own folder; the old one stays in the old Drive
            t.execute("UPDATE drive_links SET folder_id = '', state = '{}' WHERE user_id = ?", (uid,))
        t.execute("UPDATE drive_links SET email = ?, pushed_rev = -1, checked_at = NULL WHERE user_id = ?", (email, uid))
    if other and vault.unseal(old["refresh_token"]) != refresh:
        try:
            await client().post(REVOKE_URL, data={"token": vault.unseal(old["refresh_token"])})   # the old access ends
        except httpx.HTTPError:
            pass
    later(sync_user(uid, force=True))
    return email


async def finish(code, state):
    """Connect Google Drive: Google sent the user back with a code. Returns the Google account's email."""
    row = db.one("SELECT user_id, verifier, expires_at FROM oauth_states WHERE state_hash = ?", (security.token_hash(state),))
    with db.tx() as t:
        t.execute("DELETE FROM oauth_states WHERE state_hash = ?", (security.token_hash(state),))
    if row is None or row["expires_at"] < time.time():
        raise DriveError("expired", "This link is too old. Go back to the app and tap Connect Google Drive again.")
    return await link_tokens(row["user_id"], await exchange(code, row["verifier"]))


def login_url(purpose, app_challenge="", device="", user_id=0):
    """Google's page for Sign in with Google (purpose login: your account and your Drive in one go), for adding
    Google to an account that exists (purpose link, user_id: it signs in with Google from then on, and its Drive), or
    for deleting an account on the web (purpose delete: only who you are)."""
    s = db.settings()
    if not configured(s):
        raise DriveError("off", "Sign in with Google is not set up on the server yet: the admin adds the Google client in "
                                "the admin page (Settings > Google Drive).")
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    now = time.time()
    with db.tx() as t:
        t.execute("DELETE FROM login_states WHERE expires_at < ?", (now,))
        t.execute("INSERT INTO login_states(state_hash, purpose, verifier, app_challenge, device, expires_at, user_id) "
                  "VALUES (?, ?, ?, ?, ?, ?, ?)", (security.token_hash(state), purpose, verifier, app_challenge, device[:80],
                                                  now + 900, int(user_id or 0)))
    with_drive = purpose in ("login", "link")
    scope = "openid email profile" + (" " + SCOPE if with_drive else "")
    params = {"client_id": s["google_client_id"], "redirect_uri": redirect_uri(), "response_type": "code", "scope": scope,
              "state": state, "code_challenge": challenge, "code_challenge_method": "S256",
              "prompt": "select_account consent" if with_drive else "select_account"}
    if with_drive:
        params.update({"access_type": "offline", "include_granted_scopes": "true"})
    return AUTH_URL + "?" + urlencode(params)


async def unlink(uid):
    """Disconnect: Google forgets the app's access; the files stay in the user's Drive."""
    row = db.one("SELECT refresh_token FROM drive_links WHERE user_id = ?", (uid,))
    if row is None:
        return
    token = vault.unseal(row["refresh_token"])
    if token:
        try:
            await client().post(REVOKE_URL, data={"token": token})
        except httpx.HTTPError:
            pass                        # forgotten here anyway
    with db.tx() as t:
        t.execute("DELETE FROM drive_links WHERE user_id = ?", (uid,))


def status(uid):
    s = db.settings()
    row = db.one("SELECT * FROM drive_links WHERE user_id = ?", (uid,))
    out = {"available": configured(s), "connected": row is not None}
    if row is None:
        return out
    try:
        files = json.loads(row["state"] or "{}").get("files") or {}
    except ValueError:
        files = {}
    links = {}
    if (files.get("answers") or {}).get("id"):
        links["answers"] = f"https://docs.google.com/spreadsheets/d/{files['answers']['id']}/edit"
    if (files.get("jobs") or {}).get("id"):
        links["jobs"] = f"https://docs.google.com/spreadsheets/d/{files['jobs']['id']}/edit"
    if (files.get("cv") or {}).get("id"):
        links["cv"] = f"https://drive.google.com/file/d/{files['cv']['id']}/view"
    out.update({"email": row["email"], "folder_url": f"https://drive.google.com/drive/folders/{row['folder_id']}"
                if row["folder_id"] else "", "files": links, "synced_at": row["synced_at"], "checked_at": row["checked_at"],
                "linked_at": row["linked_at"], "error": row["error"], "error_code": row["error_code"],
                "reconnect": row["error_code"] == "reconnect", "ready": bool(row["synced_at"])})
    return out


# ---------------------------------------------------------------- the background loop
def later(coro):
    task = asyncio.get_running_loop().create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)
    return task


def poke():
    """Something changed: sync soon (the loop waits a few seconds for more changes)."""
    if _wake is not None:
        _wake.set()


async def run():
    """Syncs every user whose data changed, and looks for edits in everyone's Drive every CHECK_EVERY seconds."""
    global _wake
    _wake = asyncio.Event()
    while True:
        try:
            try:
                await asyncio.wait_for(_wake.wait(), timeout=30)
                await asyncio.sleep(4)          # a burst of changes from a phone ends first
            except asyncio.TimeoutError:
                pass
            _wake.clear()
            if not configured():
                continue
            now = time.time()
            due = db.all_rows("SELECT l.user_id FROM drive_links l JOIN users u ON u.id = l.user_id "
                              "WHERE l.error_code NOT IN ('reconnect', 'setup') AND ((l.error_code IN ('', 'cv') AND "
                              "u.data_rev > l.pushed_rev) OR l.checked_at IS NULL OR l.checked_at < ?) "
                              "ORDER BY COALESCE(l.checked_at, 0) LIMIT 25", (now - CHECK_EVERY,))
            for r in due:
                try:
                    await sync_user(r["user_id"])
                except DriveError:
                    pass
                except Exception:  # noqa: BLE001 - one user's problem must not stop the others
                    log.exception("Drive sync of user %s failed", r["user_id"])
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("Drive loop")
            await asyncio.sleep(30)


def start_loop():
    if LOOP:
        later(run())
