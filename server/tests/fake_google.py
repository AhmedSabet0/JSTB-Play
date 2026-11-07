"""A fake Google for the tests: OAuth (with PKCE), the Drive API v3 and the Sheets API v4 calls the server makes, with
drive.file rules (the app sees only the files it made). user_* methods do what a person does in Drive or in the sheet.

Use it as an httpx transport: httpx.AsyncClient(transport=httpx.MockTransport(fake.handle)), with the server's
GOOGLE_*_URL settings pointing at URLS below.
"""
import base64
import hashlib
import json
import re
import secrets
import time
from urllib.parse import parse_qs

import httpx

URLS = {"GOOGLE_AUTH_URL": "https://accounts.google.test/o/oauth2/v2/auth",
        "GOOGLE_TOKEN_URL": "https://oauth2.google.test/token",
        "GOOGLE_REVOKE_URL": "https://oauth2.google.test/revoke",
        "GOOGLE_DRIVE_URL": "https://drive.google.test/drive/v3",
        "GOOGLE_UPLOAD_URL": "https://drive.google.test/upload/drive/v3",
        "GOOGLE_SHEETS_URL": "https://sheets.google.test/v4/spreadsheets"}
SCOPE = "https://www.googleapis.com/auth/drive.file"
KNOWN_REQUESTS = {"updateSheetProperties", "updateCells", "repeatCell", "updateDimensionProperties", "setBasicFilter",
                  "setDataValidation"}


def err(status, message, reason="", api_status=""):
    return httpx.Response(status, json={"error": {"code": status, "message": message, "status": api_status,
                                                  "errors": [{"reason": reason, "message": message}] if reason else []}})


class FakeGoogle:
    def __init__(self, client_id, client_secret):
        self.client_id, self.client_secret = client_id, client_secret
        self.codes, self.refresh, self.access = {}, {}, {}
        self.files = {}
        self.calls = []
        self.failures = []           # [(method, path part, response, times left)]
        self.grant_scope = None      # what the user ticks on the consent screen (None: all that was asked)
        self.email = "sara.drive@gmail.com"   # the Google account that says yes
        self.name = "Sara"

    # ---------------------------------------------------------------- what a person does
    def consent(self, auth_url):
        """The user opens the app's Google link and says yes: returns the callback query (code, state)."""
        q = parse_qs(auth_url.split("?", 1)[1])
        one = {k: v[0] for k, v in q.items()}
        assert one["client_id"] == self.client_id and one["response_type"] == "code" and one["code_challenge_method"] == "S256"
        code = "code-" + secrets.token_hex(8)
        self.codes[code] = {"challenge": one["code_challenge"], "redirect_uri": one["redirect_uri"], "email": self.email,
                            "scope": self.grant_scope if self.grant_scope is not None else one["scope"],
                            "offline": one.get("access_type") == "offline", "name": self.name}
        return {"code": code, "state": one["state"]}

    @staticmethod
    def sub_of(email):
        return "1" + str(int(hashlib.sha256(email.encode()).hexdigest()[:14], 16))

    def id_token(self, email, name, aud=None, exp=None, verified=True):
        def part(d):
            return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")
        now = int(time.time())
        return ".".join([part({"alg": "RS256", "kid": "fake", "typ": "JWT"}),
                         part({"iss": "https://accounts.google.com", "aud": aud or self.client_id, "sub": self.sub_of(email),
                               "email": email, "email_verified": verified, "name": name, "iat": now,
                               "exp": exp if exp is not None else now + 3600}), "fake-signature"])

    def find_app(self, key):
        return [f for f in self.files.values() if (f.get("appProperties") or {}).get("autoapply") == key and not f["trashed"]]

    def sheet_of(self, fid):
        return self.files[fid]["sheets"][0]

    def user_values(self, fid):
        return self._values(self.sheet_of(fid))

    def user_edit(self, fid, row, col, value):
        sh = self.sheet_of(fid)
        while len(sh["cells"]) <= row:
            sh["cells"].append([])
        r = sh["cells"][row]
        while len(r) <= col:
            r.append("")
        r[col] = value
        self._bump(fid)

    def user_set_rows(self, fid, rows):
        self.sheet_of(fid)["cells"] = [list(r) for r in rows]
        self._bump(fid)

    def user_find_row(self, fid, col, value):
        for i, r in enumerate(self.user_values(fid)):
            if len(r) > col and r[col] == value:
                return i
        return -1

    def user_new_version(self, fid, content, name=None, mime=None):
        f = self.files[fid]
        f["content"] = content
        f["md5Checksum"] = hashlib.md5(content).hexdigest()
        f["size"] = str(len(content))
        if name:
            f["name"] = name
        if mime:
            f["mimeType"] = mime
        self._bump(fid)

    def user_trash(self, fid):
        self.files[fid]["trashed"] = True
        self._bump(fid)

    def user_upload(self, parent, name, content):
        """A file the user puts in the folder: drive.file does not let the app see it."""
        fid = self._new_id()
        self.files[fid] = {"id": fid, "name": name, "mimeType": "application/pdf", "parents": [parent], "trashed": False,
                           "version": "1", "content": content, "md5Checksum": hashlib.md5(content).hexdigest(),
                           "size": str(len(content)), "app": False}
        return fid

    def revoke_all(self):
        self.refresh.clear()
        self.access.clear()

    def expire_access(self):
        for tok in self.access:
            self.access[tok] = (self.access[tok][0], 0, self.access[tok][2])

    def fail(self, method, part, response, times=1):
        self.failures.append([method, part, response, times])

    def count(self, method, part):
        return sum(1 for m, p in self.calls if m == method and part in p)

    # ---------------------------------------------------------------- the transport
    def handle(self, request):
        path = request.url.path
        host = request.url.host
        full = host + path
        self.calls.append((request.method, full + ("?" + request.url.query.decode() if request.url.query else "")))
        for f in self.failures:
            if f[3] > 0 and f[0] == request.method and f[1] in full:
                f[3] -= 1
                return f[2]
        if host == "oauth2.google.test":
            form = {k: v[0] for k, v in parse_qs(request.content.decode()).items()}
            return self._token(form) if path == "/token" else self._revoke(form)
        who = self._who(request)
        if who is None:
            return err(401, "Request had invalid authentication credentials.", "authError", "UNAUTHENTICATED")
        if who == "no-drive-scope":
            return err(403, "Request had insufficient authentication scopes.", "insufficientPermissions", "PERMISSION_DENIED")
        if host == "drive.google.test":
            return self._drive(request, path, who)
        if host == "sheets.google.test":
            return self._sheets(request, path, who)
        return httpx.Response(404, text="no such fake")

    # ---- OAuth
    def _token(self, form):
        if form.get("client_id") != self.client_id or form.get("client_secret") != self.client_secret:
            return httpx.Response(401, json={"error": "invalid_client", "error_description": "The OAuth client was not found."})
        if form.get("grant_type") == "authorization_code":
            c = self.codes.pop(form.get("code", ""), None)
            if c is None or c["redirect_uri"] != form.get("redirect_uri"):
                return httpx.Response(400, json={"error": "invalid_grant", "error_description": "Bad Request"})
            want = base64.urlsafe_b64encode(hashlib.sha256(form.get("code_verifier", "").encode()).digest()).decode().rstrip("=")
            if want != c["challenge"]:
                return httpx.Response(400, json={"error": "invalid_grant", "error_description": "Invalid code verifier."})
            out = {"access_token": self._access(c["email"], c["scope"]), "expires_in": 3599, "scope": c["scope"],
                   "token_type": "Bearer"}
            if c["offline"]:
                rt = "1//refresh-" + secrets.token_hex(8)
                self.refresh[rt] = (c["email"], c["scope"])
                out["refresh_token"] = rt
            if "openid" in c["scope"].split():
                out["id_token"] = self.id_token(c["email"], c["name"])
            return httpx.Response(200, json=out)
        if form.get("grant_type") == "refresh_token":
            hit = self.refresh.get(form.get("refresh_token", ""))
            if hit is None:
                return httpx.Response(400, json={"error": "invalid_grant", "error_description": "Token has been expired or revoked."})
            return httpx.Response(200, json={"access_token": self._access(*hit), "expires_in": 3599, "scope": hit[1],
                                             "token_type": "Bearer"})
        return httpx.Response(400, json={"error": "unsupported_grant_type"})

    def _revoke(self, form):
        tok = form.get("token", "")
        hit = self.refresh.pop(tok, None)
        if hit:
            for k in [k for k, v in self.access.items() if v[0] == hit[0]]:
                del self.access[k]
        return httpx.Response(200, json={})

    def _access(self, email, scope=SCOPE):
        at = "ya29.fake-" + secrets.token_hex(10)
        self.access[at] = (email, time.time() + 3600, scope)
        return at

    def _who(self, request):
        h = request.headers.get("authorization", "")
        if not h.startswith("Bearer "):
            return None
        hit = self.access.get(h[7:])
        if hit is None or hit[1] < time.time():
            return None
        return hit[0] if SCOPE in hit[2].split() else "no-drive-scope"

    # ---- Drive
    def _new_id(self):
        return "1" + secrets.token_urlsafe(18).replace("-", "a").replace("_", "b")

    def _bump(self, fid):
        self.files[fid]["version"] = str(int(self.files[fid]["version"]) + 1)

    def _visible(self, f, who):
        return f.get("app") and f.get("owner") == who

    def _meta(self, f):
        out = {k: f[k] for k in ("id", "name", "mimeType", "parents", "trashed", "version") if k in f}
        for k in ("md5Checksum", "size", "appProperties"):
            if k in f:
                out[k] = f[k]
        return out

    def _drive(self, request, path, who):
        q = {k: v[0] for k, v in parse_qs(request.url.query.decode()).items()}
        if path == "/drive/v3/about":
            return httpx.Response(200, json={"user": {"emailAddress": who, "displayName": "Sara"}})
        if path == "/drive/v3/files" and request.method == "GET":
            return httpx.Response(200, json={"files": [self._meta(f) for f in self._query(q.get("q", ""), who)]})
        if path == "/drive/v3/files" and request.method == "POST":
            meta = json.loads(request.content)
            fid = self._new_id()
            f = {"id": fid, "name": meta.get("name", "Untitled"), "mimeType": meta.get("mimeType", "application/octet-stream"),
                 "parents": meta.get("parents") or ["root"], "trashed": False, "version": "1", "app": True, "owner": who,
                 "appProperties": meta.get("appProperties") or {}}
            if f["mimeType"] == "application/vnd.google-apps.spreadsheet":
                f["sheets"] = [{"sheetId": 1234567 + len(self.files), "title": "Sheet1", "rowCount": 1000, "columnCount": 26,
                                "cells": [], "frozen": 0, "hidden": set(), "validations": {}, "filter": False}]
            self.files[fid] = f
            return httpx.Response(200, json=self._meta(f))
        m = re.fullmatch(r"/drive/v3/files/([\w-]+)", path)
        if m and request.method == "GET":
            f = self.files.get(m.group(1))
            if f is None or not self._visible(f, who):
                return err(404, f"File not found: {m.group(1)}.", "notFound")
            if q.get("alt") == "media":
                return httpx.Response(200, content=f.get("content", b""))
            return httpx.Response(200, json=self._meta(f))
        m = re.fullmatch(r"/upload/drive/v3/files(?:/([\w-]+))?", path)
        if m and q.get("uploadType") == "multipart":
            meta, media = self._multipart(request)
            if m.group(1):
                f = self.files.get(m.group(1))
                if f is None or not self._visible(f, who) or request.method != "PATCH":
                    return err(404, "File not found.", "notFound")
                if "parents" in meta:
                    return err(403, "The parents field is not directly writable in update requests.", "fieldNotWritable")
                if meta.get("name"):
                    f["name"] = meta["name"]
                self._bump(f["id"])
            else:
                fid = self._new_id()
                f = {"id": fid, "name": meta.get("name", "Untitled"), "mimeType": "application/octet-stream",
                     "parents": meta.get("parents") or ["root"], "trashed": False, "version": "1", "app": True,
                     "owner": who, "appProperties": meta.get("appProperties") or {}}
                self.files[fid] = f
            f["content"] = media["body"]
            f["mimeType"] = media["type"]
            f["md5Checksum"] = hashlib.md5(media["body"]).hexdigest()
            f["size"] = str(len(media["body"]))
            return httpx.Response(200, json=self._meta(f))
        return httpx.Response(404, text=f"fake Drive has no {request.method} {path}")

    def _query(self, q, who):
        files = [f for f in self.files.values() if self._visible(f, who)]
        if "trashed = false" in q:
            files = [f for f in files if not f["trashed"]]
        m = re.search(r"'([\w-]+)' in parents", q)
        if m:
            files = [f for f in files if m.group(1) in f.get("parents", [])]
        m = re.search(r"appProperties has \{ key='(\w+)' and value='(\w+)' \}", q)
        if m:
            files = [f for f in files if (f.get("appProperties") or {}).get(m.group(1)) == m.group(2)]
        return files

    @staticmethod
    def _multipart(request):
        ctype = request.headers["content-type"]
        boundary = re.search(r"boundary=(\S+)", ctype).group(1).encode()
        parts = [p for p in request.content.split(b"--" + boundary) if p.strip() not in (b"", b"--")]
        out = []
        for p in parts:
            head, _sep, body = p.lstrip(b"\r\n").partition(b"\r\n\r\n")
            if body.endswith(b"\r\n"):
                body = body[:-2]
            t = re.search(rb"Content-Type: ([^\r\n;]+)", head)
            out.append({"type": t.group(1).decode() if t else "", "body": body})
        assert len(out) == 2, f"multipart needs metadata and media, got {len(out)} parts"
        return json.loads(out[0]["body"]), out[1]

    # ---- Sheets
    @staticmethod
    def _values(sh):
        rows = [list(r) for r in sh["cells"]]
        rows = [r[:max([i + 1 for i, v in enumerate(r) if v != ""] or [0])] for r in rows]   # Google leaves out empty ends
        while rows and not rows[-1]:
            rows.pop()
        return rows

    def _sheets(self, request, path, who):
        m = re.fullmatch(r"/v4/spreadsheets/([\w-]+)(:batchUpdate|/values:batchGetByDataFilter)?", path)
        f = self.files.get(m.group(1)) if m else None
        if f is None or not self._visible(f, who) or "sheets" not in f:
            return err(404, "Requested entity was not found.", "notFound", "NOT_FOUND")
        if m.group(2) is None:
            return httpx.Response(200, json={"sheets": [{"properties": {
                "sheetId": s["sheetId"], "title": s["title"],
                "gridProperties": {"rowCount": s["rowCount"], "columnCount": s["columnCount"]}}} for s in f["sheets"]]})
        body = json.loads(request.content)
        if m.group(2) == "/values:batchGetByDataFilter":
            sid = body["dataFilters"][0]["gridRange"]["sheetId"]
            sh = next((s for s in f["sheets"] if s["sheetId"] == sid), None)
            if sh is None:
                return err(400, f"No grid with id: {sid}", "badRequest", "INVALID_ARGUMENT")
            return httpx.Response(200, json={"spreadsheetId": f["id"], "valueRanges": [{"valueRange": {
                "range": f"{sh['title']}!A1:Z{sh['rowCount']}", "majorDimension": "ROWS", "values": self._values(sh)}}]})
        for req in body["requests"]:
            (kind, args), = req.items()
            if kind not in KNOWN_REQUESTS:
                return err(400, f"Unknown request {kind}", "badRequest", "INVALID_ARGUMENT")
            where = args.get("range") or args.get("start") or (args.get("filter") or {}).get("range") or args.get("properties") or {}
            sid = where.get("sheetId")
            sh = next((s for s in f["sheets"] if s["sheetId"] == sid), None)
            if sh is None:
                return err(400, f"No grid with id: {sid}", "badRequest", "INVALID_ARGUMENT")
            problem = self._apply(sh, kind, args)
            if problem:
                return err(400, problem, "badRequest", "INVALID_ARGUMENT")
        self._bump(f["id"])
        return httpx.Response(200, json={"spreadsheetId": f["id"], "replies": [{} for _ in body["requests"]]})

    @staticmethod
    def _apply(sh, kind, a):
        if kind == "updateSheetProperties":
            p = a["properties"]
            if "title" in p:
                sh["title"] = p["title"]
            g = p.get("gridProperties") or {}
            if "rowCount" in g:
                sh["rowCount"] = g["rowCount"]
                sh["cells"] = sh["cells"][:g["rowCount"]]
            if "columnCount" in g:
                sh["columnCount"] = g["columnCount"]
                sh["cells"] = [r[:g["columnCount"]] for r in sh["cells"]]
            if "frozenRowCount" in g:
                sh["frozen"] = g["frozenRowCount"]
        elif kind == "updateCells":
            if "range" in a:                       # clear
                assert a.get("fields") == "userEnteredValue" and "rows" not in a
                sh["cells"] = []
            else:
                r0, c0 = a["start"].get("rowIndex", 0), a["start"].get("columnIndex", 0)
                rows = a["rows"]
                if r0 + len(rows) > sh["rowCount"]:
                    return f"Range exceeds grid limits. Max rows: {sh['rowCount']}"
                for i, row in enumerate(rows):
                    vals = row.get("values") or []
                    if c0 + len(vals) > sh["columnCount"]:
                        return f"Range exceeds grid limits. Max columns: {sh['columnCount']}"
                    while len(sh["cells"]) <= r0 + i:
                        sh["cells"].append([])
                    line = sh["cells"][r0 + i]
                    for j, v in enumerate(vals):
                        while len(line) <= c0 + j:
                            line.append("")
                        uev = v.get("userEnteredValue") or {}
                        if set(uev) - {"stringValue"}:
                            return "only text values are expected"
                        line[c0 + j] = uev.get("stringValue", "")
        elif kind == "updateDimensionProperties":
            if a["properties"].get("hiddenByUser"):
                sh["hidden"].add(a["range"]["startIndex"])
        elif kind == "setBasicFilter":
            sh["filter"] = True
        elif kind == "setDataValidation":
            r = a["range"]
            key = (r.get("startRowIndex", 0), r.get("endRowIndex"), r.get("startColumnIndex", 0))
            if "rule" in a:
                sh["validations"][key] = [v["userEnteredValue"] for v in a["rule"]["condition"]["values"]]
            else:
                sh["validations"] = {k: v for k, v in sh["validations"].items() if k[2] != key[2]}
        return ""
