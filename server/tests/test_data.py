"""Tests of each user's data (the same on every phone) and the Google Drive sync, with a fake Google.
Run: python3 tests/test_data.py"""
import json
import os
import sys
import tempfile
import time

TMP = tempfile.mkdtemp()
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
from fake_google import URLS, FakeGoogle  # noqa: E402

os.environ.update({"DB_PATH": os.path.join(TMP, "t.db"), "ADMIN_PASSWORD": "admin-pass-123", "CONTACT_EMAIL": "help@example.com",
                   "TIMEZONE": "Africa/Cairo", "PUBLIC_URL": "https://aa.example.test", "DRIVE_LOOP": "0", **URLS})

import httpx  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import db, drive, main, vault  # noqa: E402

CLIENT_ID = "1234567890-abcdefg.apps.googleusercontent.com"
SECRET = "GOCSPX-test-secret-123456"
fake = FakeGoogle(CLIENT_ID, SECRET)
drive._client = httpx.AsyncClient(transport=httpx.MockTransport(fake.handle))

results = []


def check(name, cond, extra=None):
    results.append(bool(cond))
    print(("  ok   " if cond else "  FAIL ") + name + ("" if cond or extra is None else f"  -> {extra}"))


def make_pdf(lines):
    """A small real PDF with text pypdf can read."""
    def esc(s):
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    content = "BT /F1 11 Tf 72 760 Td 14 TL " + " ".join(f"({esc(x)}) '" for x in lines) + " ET" if lines else \
        "0.9 g 72 72 300 300 re f"
    objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            f"<< /Length {len(content)} >>\nstream\n{content}\nendstream", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    out, offsets = b"%PDF-1.4\n", []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode() + "".join(f"{x:010d} 00000 n \n" for x in offsets).encode()
    return out + f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()


CV_LINES = ["Sara Ahmed - Network Engineer", "Cairo, Egypt. Phone 01012345678", "Experience: 5 years with Cisco routers, "
            "BGP, OSPF and Aruba switches.", "Skills: Python, Ansible, Docker, Kubernetes, Terraform, AWS and Azure.",
            "Education: B.Sc. Communication and Electronics Engineering, Cairo University, 2019."]
CV_TEXT = "\n".join(CV_LINES)


def main_test():
    import base64
    with TestClient(main.app) as c:
        A = {"X-Requested-With": "admin"}

        def auth(tok):
            return {"Authorization": f"Bearer {tok}"}

        def err(r):
            try:
                return r.json()["error"]["code"]
            except Exception:  # noqa: BLE001
                return r.text

        tok1 = c.post("/v1/auth/signup", json={"email": "sara@example.com", "password": "secret-pass", "name": "Sara"}).json()["token"]
        tok2 = c.post("/v1/auth/login", json={"email": "sara@example.com", "password": "secret-pass", "device": "Tablet"}).json()["token"]
        P1, P2 = auth(tok1), auth(tok2)
        uid = c.get("/v1/me", headers=P1).json()["user"]["id"]

        print("\n== Your data, the same on every phone")
        r = c.get("/v1/me/data", headers=P1)
        check("nothing yet", r.status_code == 200 and r.json()["records"] == [] and r.json()["rev"] == 0, r.text)
        check("needs sign-in", c.get("/v1/me/data").status_code == 401)
        now = time.time()
        fact = {"id": "f1", "category": "Skills", "label": "Years of experience with Ansible", "value": "4", "asked": ["years of experience with ansible"],
                "source": "you", "updated": now}
        item = {"id": "li:4012345678", "url": "https://www.linkedin.com/jobs/view/4012345678/", "title": "Network Engineer",
                "company": "Orange", "channel": "linkedin", "state": "new", "stage": "", "added": now - 100, "note": ""}
        job = {"id": "4099999999", "ts": now - 50, "status": "applied", "title": "DevOps Engineer", "company": "Vodafone",
               "url": "https://www.linkedin.com/jobs/view/4099999999/", "score": 8, "stage": ""}
        changes = [{"k": "profile", "id": "phone", "d": {"v": "01012345678"}, "base": 0, "t": now},
                   {"k": "profile", "id": "expected_salary", "d": {"v": "30000"}, "base": 0, "t": now},
                   {"k": "profile", "id": "english", "d": {"v": "Fluent"}, "base": 0, "t": now},
                   {"k": "settings", "id": "KEYWORDS", "d": {"v": "Network Engineer;DevOps Engineer"}, "base": 0, "t": now},
                   {"k": "settings", "id": "ABOUT_ME", "d": {"v": "# Extra info\nPhone:\nI hold CCNP Enterprise.\n"}, "base": 0, "t": now},
                   {"k": "facts", "id": "f1", "d": fact, "base": 0, "t": now},
                   {"k": "copilot", "id": item["id"], "d": item, "base": 0, "t": now},
                   {"k": "jobs", "id": job["id"], "d": job, "base": 0, "t": now}]
        r = c.post("/v1/me/data", headers=P1, json={"now": now, "changes": changes})
        res = r.json()
        check("a phone saves its data", r.status_code == 200 and all(x["status"] == "saved" for x in res["results"])
              and res["rev"] == 8 and sorted(x["rev"] for x in res["results"]) == list(range(1, 9)), r.text)
        r = c.get("/v1/me/data", headers=P2)
        got = {(x["k"], x["id"]): x for x in r.json()["records"]}
        check("the other phone gets all of it", len(got) == 8 and got[("facts", "f1")]["d"]["value"] == "4"
              and r.json()["more"] is False and r.json()["rev"] == 8, r.json())
        fact2 = dict(fact, value="5", updated=now + 5)
        r = c.post("/v1/me/data", headers=P2, json={"now": now + 5, "changes": [
            {"k": "facts", "id": "f1", "d": fact2, "base": got[("facts", "f1")]["rev"], "t": now + 5}]})
        check("the other phone changes a fact", r.json()["results"][0]["status"] == "saved" and r.json()["rev"] == 9, r.text)
        r = c.get("/v1/me/data?since=8", headers=P1)
        check("the first phone gets only that change", [x["id"] for x in r.json()["records"]] == ["f1"]
              and r.json()["records"][0]["d"]["value"] == "5", r.text)
        r = c.post("/v1/me/data", headers=P1, json={"now": now + 6, "changes": [
            {"k": "facts", "id": "f1", "d": dict(fact, value="3"), "base": 6, "t": now + 2}]})
        x = r.json()["results"][0]
        check("an older change to something changed since: the server's stays, sent back", x["status"] == "conflict"
              and x["d"]["value"] == "5" and x["rev"] == 9, r.text)
        r = c.post("/v1/me/data", headers=P1, json={"now": now + 7, "changes": [
            {"k": "facts", "id": "f1", "d": dict(fact, value="6"), "base": 6, "t": now + 7}]})
        check("a newer change wins", r.json()["results"][0]["status"] == "saved", r.text)
        r = c.post("/v1/me/data", headers=P1, json={"now": now + 8, "changes": [
            {"k": "facts", "id": "f1", "d": dict(fact, value="6"), "base": 10, "t": now + 8}]})
        check("the same data again: nothing new", r.json()["results"][0]["status"] == "same" and r.json()["rev"] == 10, r.text)
        # the phone's clock is an hour behind: times are moved to the server's clock
        r = c.post("/v1/me/data", headers=P1, json={"now": now - 3600, "changes": [
            {"k": "settings", "id": "DAILY_GOAL", "d": {"v": "12"}, "base": 0, "t": now - 3600}]})
        mt = db.one("SELECT mtime FROM records WHERE user_id = ? AND kind = 'settings' AND rid = 'DAILY_GOAL'", (uid,))["mtime"]
        check("a phone clock that is off is corrected", abs(mt - now) < 30, mt - now)
        r = c.post("/v1/me/data", headers=P2, json={"now": now, "changes": [{"k": "jobs", "id": "4099999999", "d": None, "base": 8, "t": now + 9}]})
        check("delete", r.json()["results"][0]["status"] == "saved")
        r = c.get("/v1/me/data?since=10", headers=P1)
        check("other phones learn about the delete", any(x["id"] == "4099999999" and x["d"] is None for x in r.json()["records"]), r.text)
        check("a new phone does not get deleted items", all(x["id"] != "4099999999" for x in c.get("/v1/me/data", headers=P1).json()["records"]))
        c.post("/v1/me/data", headers=P2, json={"now": now, "changes": [{"k": "jobs", "id": "4099999999", "d": job, "base": 0, "t": now + 10}]})
        check("bad kind refused", err(c.post("/v1/me/data", headers=P1, json={"changes": [{"k": "BAD!", "id": "x", "d": {}}]})) == "bad_record")
        check("the CV is not changed through data", err(c.post("/v1/me/data", headers=P1, json={"changes": [{"k": "cv", "id": "main", "d": {}}]})) == "bad_record")
        check("too big refused", err(c.post("/v1/me/data", headers=P1, json={"changes": [
            {"k": "facts", "id": "big", "d": {"v": "x" * 120000}}]})) == "bad_record")
        many = [{"k": "jobs", "id": f"9{i:08d}", "d": {"id": f"9{i:08d}", "status": "skipped_low_score", "ts": now}, "t": now} for i in range(2500)]
        for i in range(0, 2500, 1000):
            c.post("/v1/me/data", headers=P1, json={"now": now, "changes": many[i:i + 1000]})
        r1 = c.get("/v1/me/data", headers=P2).json()
        r2 = c.get(f"/v1/me/data?since={r1['rev']}", headers=P2).json()
        check("a big account comes in pages", r1["more"] and len(r1["records"]) == 2000 and not r2["more"]
              and len(r1["records"]) + len(r2["records"]) == 2509, (len(r1["records"]), len(r2["records"]), r1["rev"]))
        with db.tx() as t:
            t.execute("DELETE FROM records WHERE user_id = ? AND rid LIKE '9%' AND kind = 'jobs' AND rid != '4099999999'", (uid,))

        print("\n== The CV file")
        pdf = make_pdf(CV_LINES)
        r = c.post("/v1/me/cv", headers=P1, json={"name": "Sara CV.pdf", "data": base64.b64encode(pdf).decode(), "text": CV_TEXT,
                                                 "now": time.time(), "t": time.time(), "base": 0})
        check("a phone saves the CV", r.status_code == 200 and r.json()["status"] == "saved" and len(r.json()["sha256"]) == 64, r.text)
        sha = r.json()["sha256"]
        r = c.get("/v1/me/cv", headers=P2)
        check("the other phone downloads it", r.status_code == 200 and base64.b64decode(r.json()["data"]) == pdf
              and r.json()["name"] == "Sara CV.pdf" and "Network Engineer" in r.json()["text"], r.text[:200])
        recs = {(x["k"], x["id"]): x for x in c.get("/v1/me/data", headers=P2).json()["records"]}
        check("the CV's record has its hash and text", recs[("cv", "main")]["d"]["sha256"] == sha
              and "Cisco" in recs[("cv", "main")]["d"]["text"])
        check("not a PDF or text: refused", err(c.post("/v1/me/cv", headers=P1, json={"name": "cv.docx", "data": base64.b64encode(pdf).decode(),
                                                                                       "text": CV_TEXT})) == "bad_cv")
        check("no text: refused", err(c.post("/v1/me/cv", headers=P1, json={"name": "cv.pdf", "data": base64.b64encode(pdf).decode(),
                                                                            "text": "tiny"})) == "bad_cv")
        check("not base64: refused", err(c.post("/v1/me/cv", headers=P1, json={"name": "cv.pdf", "data": "%%%", "text": CV_TEXT})) == "bad_cv")
        r = c.post("/v1/me/cv", headers={**P1, "Content-Type": "application/json"}, content=b'{"x": "' + b"a" * 8_100_000 + b'"}')
        check("a request that is too big is refused", r.status_code == 413 and err(r) == "too_big", r.status_code)

        print("\n== Google Drive: set up by the admin")
        r = c.get("/v1/me/drive", headers=P1).json()["drive"]
        check("not set up yet", r["available"] is False and r["connected"] is False, r)
        check("connect refused until set up", err(c.post("/v1/me/drive/link", headers=P1, json={})) == "drive_off")
        c.post("/admin/api/login", json={"password": "admin-pass-123"})
        check("a bad client ID is refused", err(c.post("/admin/api/settings", headers=A, json={"google_client_id": "abc"})) == "bad_google")
        r = c.post("/admin/api/settings", headers=A, json={"google_client_id": CLIENT_ID, "google_client_secret": SECRET})
        s = r.json()["settings"]
        check("admin adds the Google client; the secret is not shown", s["google_ready"] and s["has_google_secret"]
              and SECRET not in r.text and s["google_redirect"] == "https://aa.example.test/oauth/google/callback", s)

        print("\n== Google Drive: connect")
        r = c.post("/v1/me/drive/link", headers=P1, json={})
        url = r.json()["url"]
        check("the app gets Google's page (drive.file, offline, PKCE)", url.startswith(URLS["GOOGLE_AUTH_URL"])
              and "drive.file" in url and "code_challenge=" in url and "access_type=offline" in url and CLIENT_ID in url, url)
        fake.grant_scope = "openid"
        q = fake.consent(url)
        r = c.get("/oauth/google/callback", params=q)
        check("the Drive box not ticked: explained", r.status_code == 400 and "tick" in r.text, r.text[-300:])
        fake.grant_scope = None
        q = fake.consent(c.post("/v1/me/drive/link", headers=P1, json={}).json()["url"])
        r = c.get("/oauth/google/callback", params=dict(q, state="forged-state"))
        check("a forged state is refused", r.status_code == 400 and "too old" in r.text)
        q = fake.consent(c.post("/v1/me/drive/link", headers=P1, json={}).json()["url"])
        r = c.get("/oauth/google/callback", params=q)
        check("connected: the page says so and links back to the app", r.status_code == 200 and "sara.drive@gmail.com" in r.text
              and "autoapply://drive" in r.text, r.text[-400:])
        check("the same link cannot be used twice", c.get("/oauth/google/callback", params=q).status_code == 400)
        row = db.one("SELECT * FROM drive_links WHERE user_id = ?", (uid,))
        check("the tokens are kept encrypted", row and row["refresh_token"].startswith("v1.") and "refresh-" not in row["refresh_token"]
              and vault.unseal(row["refresh_token"]).startswith("1//refresh-"), dict(row) if row else None)
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("first sync: the folder and its files", st["connected"] and st["ready"] and st["email"] == "sara.drive@gmail.com"
              and st["folder_url"].startswith("https://drive.google.com/drive/folders/") and set(st["files"]) == {"answers", "jobs", "cv"}
              and not st["error"], st)
        folder = fake.find_app("folder")
        ans = fake.find_app("answers")[0]["id"]
        jobs = fake.find_app("jobs")[0]["id"]
        cvf = fake.find_app("cv")[0]["id"]
        check("one Auto Apply folder holding the three files", len(folder) == 1 and folder[0]["name"] == "JSTB" and all(
            folder[0]["id"] in fake.files[x]["parents"] for x in (ans, jobs, cvf)))
        check("the CV in Drive is the user's file", fake.files[cvf]["content"] == pdf and fake.files[cvf]["name"] == "Sara CV.pdf")
        av = fake.user_values(ans)
        check("My answers: heading, main answers, job search, your info", av[0] == ["Section", "Question", "Answer", "By", "ID"]
              and ["Main answers", "Mobile number", "01012345678", "", "p:phone"] in av
              and ["Job search", "Job titles (separate them with ;)", "Network Engineer; DevOps Engineer", "", "s:KEYWORDS"] in av
              and ["For the AI", "Anything else the AI should know", "I hold CCNP Enterprise.", "", "s:ABOUT_ME"] in av
              and ["Skills", "Years of experience with Ansible", "6", "You", "f:f1"] in av, av)
        sh = fake.sheet_of(ans)
        check("the sheet is set up: tab name, frozen heading, ID hidden, dropdowns for picked answers",
              sh["title"] == "Answers" and sh["frozen"] == 1 and 4 in sh["hidden"] and sh["filter"]
              and ["Native", "Fluent", "Professional", "Good", "Basic"] in sh["validations"].values(), sh["validations"])
        jv = fake.user_values(jobs)
        check("My jobs: the job to apply to and the application", jv[0] == ["Status", "Job title", "Company", "Applied on", "Note",
                                                                            "Link", "Via", "ID"]
              and jv[1][:3] == ["To apply", "Network Engineer", "Orange"] and jv[1][6:] == ["LinkedIn", "c:li:4012345678"]
              and jv[2][0] == "Applied" and jv[2][6] == "Auto apply", jv)
        check("My jobs has the Status dropdown", ["To apply", "Applied", "Interview", "Offer", "Rejected", "Skipped"]
              in fake.sheet_of(jobs)["validations"].values())
        n_writes = fake.count("POST", ":batchUpdate")
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("nothing changed: nothing written again", fake.count("POST", ":batchUpdate") == n_writes
              and fake.count("POST", "values:batchGetByDataFilter") >= 2)

        def since(rev):
            return {(x["k"], x["id"]): x["d"] for x in c.get(f"/v1/me/data?since={rev}", headers=P2).json()["records"]}

        def rev_now():
            return c.get("/v1/me/data?since=999999", headers=P2).json()["rev"]

        print("\n== Google Drive: edits in My answers come back")
        r0 = rev_now()
        fake.user_edit(ans, fake.user_find_row(ans, 4, "p:expected_salary"), 2, "35,000 EGP")
        fake.user_edit(ans, fake.user_find_row(ans, 4, "p:english"), 2, "native")
        fake.user_edit(ans, fake.user_find_row(ans, 4, "s:KEYWORDS"), 2, "Network Engineer; Cloud Engineer;  ")
        fake.user_edit(ans, fake.user_find_row(ans, 4, "f:f1"), 2, "7")
        av = fake.user_values(ans)
        av.append(["skills", "Years of experience with Kubernetes", "3"])
        av.append(["", "", "an answer without a question"])
        fake.user_set_rows(ans, av)
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        got = since(r0)
        new_facts = [d for (k, i), d in got.items() if k == "facts" and i != "f1"]
        check("salary edited in the sheet: cleaned as the app does", got.get(("profile", "expected_salary")) == {"v": "35000"}, got)
        check("picked answer: the right case", got.get(("profile", "english")) == {"v": "Native"})
        check("job titles: separated the app's way", got.get(("settings", "KEYWORDS")) == {"v": "Network Engineer;Cloud Engineer"})
        check("a fact edited: yours now", got.get(("facts", "f1"), {}).get("value") == "7" and got[("facts", "f1")]["source"] == "you")
        check("a new row: a new fact in its section", len(new_facts) == 1 and new_facts[0]["label"] == "Years of experience with Kubernetes"
              and new_facts[0]["category"] == "Skills" and new_facts[0]["value"] == "3", new_facts)
        av = fake.user_values(ans)
        nid = "f:" + new_facts[0]["id"]
        check("the sheet now has the new fact's ID; the unreadable row stays at the end", any(r[-1:] == [nid] for r in av)
              and av[-1][:3] == ["", "", "an answer without a question"] and not st["error"], av[-3:])
        r1 = rev_now()
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("synced again: no duplicates", rev_now() == r1 and sum(1 for r in fake.user_values(ans) if "Kubernetes" in "".join(r)) == 1)
        fake.user_edit(ans, fake.user_find_row(ans, 4, nid), 2, "")
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("an answer cleared in the sheet: the fact goes", since(r1).get(("facts", nid[2:]), "x") is None)
        av = [r for r in fake.user_values(ans) if r[-1:] != ["f:f1"]]
        fake.user_set_rows(ans, av)
        r2 = rev_now()
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("a fact's row deleted: the fact goes", since(r2).get(("facts", "f1"), "x") is None)
        av = [r for r in fake.user_values(ans) if r[-1:] != ["p:phone"]]
        fake.user_set_rows(ans, av)
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("a main answer's row deleted: it comes back", fake.user_find_row(ans, 4, "p:phone") > 0)
        fake.user_set_rows(ans, [r for r in fake.user_values(ans)[1:]])      # the heading row deleted
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("the heading row deleted: nothing read, the sheet is written again",
              fake.user_values(ans)[0][:3] == ["Section", "Question", "Answer"] and fake.user_find_row(ans, 4, "p:phone") > 0)

        print("\n== Google Drive: edits in My jobs come back")
        r3 = rev_now()
        jrow = fake.user_find_row(jobs, 7, "c:li:4012345678")
        fake.user_edit(jobs, jrow, 0, "Interview")
        fake.user_edit(jobs, jrow, 4, "HR call on Sunday")
        jv = fake.user_values(jobs)
        jv[0].append("Salary offered")
        jv[jrow].append("40000")
        jv.append(["", "Cloud Engineer", "Valeo", "", "", "https://www.linkedin.com/jobs/view/cloud-engineer-at-valeo-4033333333/?trk=x"])
        jv.append(["Applied", "", "", "", "", "https://www.linkedin.com/jobs/view/4033333333"])
        jv.append(["", "SRE at a startup", "", "", "no link yet"])
        jv.append(["", "Site job", "Acme", "", "", "https://jobs.acme.example/123?utm_source=x&ref=y"])
        fake.user_set_rows(jobs, jv)
        c.post("/v1/me/drive/sync", headers=P1, json={})
        got = since(r3)
        it = got.get(("copilot", "li:4012345678")) or {}
        check("status To apply -> Interview: applied, at the interview stage", it.get("state") == "applied" and it.get("stage") == "interview"
              and it.get("applied_at") and it.get("note") == "HR call on Sunday", it)
        check("a job pasted with its link: added with the app's ID (twice: once)", ("copilot", "li:4033333333") in got
              and got[("copilot", "li:4033333333")]["state"] == "applied" and got[("copilot", "li:4033333333")]["title"] == "Cloud Engineer"
              and got[("copilot", "li:4033333333")]["url"] == "https://www.linkedin.com/jobs/view/4033333333/", got.keys())
        site = [d for (k, i), d in got.items() if k == "copilot" and i.startswith("c:")]
        check("a company site's link: tracking bits removed", len(site) == 1 and site[0]["url"] == "https://jobs.acme.example/123"
              and site[0]["channel"] == "company" and site[0]["state"] == "new", site)
        jv = fake.user_values(jobs)
        srow = fake.user_find_row(jobs, 7, "c:li:4012345678")
        check("the user's own column keeps its value on its row", jv[0][-1] == "Salary offered" and jv[srow][-1] == "40000"
              and jv[srow][0] == "Interview", jv[srow])
        check("a row with no link stays", any("SRE at a startup" in r for r in jv))
        apps = c.get(f"/admin/api/users/{uid}").json()["applications"]
        check("the admin's applications follow the sheet", any(a["job_id"] == "li:4012345678" and a["stage"] == "interview" for a in apps), apps)
        r4 = rev_now()
        jv = [r for r in fake.user_values(jobs) if not (len(r) > 7 and r[7] in ("c:li:4033333333", "a:4099999999"))]
        fake.user_set_rows(jobs, jv)
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("a job of yours deleted in the sheet: gone; auto apply's row comes back",
              since(r4).get(("copilot", "li:4033333333"), "x") is None and fake.user_find_row(jobs, 7, "a:4099999999") > 0)
        # a sort of some columns only: rows lose their place, nothing is read
        jv = fake.user_values(jobs)
        idx = [i for i, r in enumerate(jv) if i and len(r) > 7 and r[7]]
        links = [jv[i][5] for i in idx]
        for n, i in enumerate(idx):
            jv[i][5] = links[(n + 1) % len(links)]
            jv[i][0] = "Rejected"
        fake.user_set_rows(jobs, jv)
        r5 = rev_now()
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("rows out of place: nothing changed, the sheet written again in order", rev_now() == r5
              and fake.user_values(jobs)[fake.user_find_row(jobs, 7, "c:li:4012345678")][0] == "Interview")

        print("\n== Google Drive: changes in the app reach the sheets")
        c.post("/v1/me/data", headers=P2, json={"now": time.time(), "changes": [
            {"k": "facts", "id": "f9", "d": {"id": "f9", "category": "Languages", "label": "German level", "value": "B1", "source": "ai"},
             "base": 0, "t": time.time()},
            {"k": "profile", "id": "city", "d": {"v": "Giza"}, "base": 0, "t": time.time()}]})
        due = db.all_rows("SELECT l.user_id FROM drive_links l JOIN users u ON u.id = l.user_id WHERE u.data_rev > l.pushed_rev")
        check("a change from a phone marks the Drive to sync", [r["user_id"] for r in due] == [uid])
        c.post("/v1/me/drive/sync", headers=P1, json={})
        av = fake.user_values(ans)
        check("the new fact and the city are in My answers", ["Languages", "German level", "B1", "AI guess", "f:f9"] in av
              and any(r[-1:] == ["p:city"] and r[2] == "Giza" for r in av))
        check("synced: not due any more", not db.all_rows("SELECT 1 FROM drive_links l JOIN users u ON u.id = l.user_id "
                                                           "WHERE u.data_rev > l.pushed_rev"))

        print("\n== Google Drive: the CV both ways")
        new_pdf = make_pdf(["Sara Ahmed - Senior Network Engineer"] + CV_LINES[1:] + ["Certifications: CCNP Enterprise, AWS SAA."])
        fake.user_new_version(cvf, new_pdf, name="Sara CV 2026.pdf")
        r6 = rev_now()
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        rec = since(r6).get(("cv", "main")) or {}
        r = c.get("/v1/me/cv", headers=P2).json()
        check("a new version uploaded in Drive becomes the CV, its text read on the server", base64.b64decode(r["data"]) == new_pdf
              and "Senior Network Engineer" in rec.get("text", "") and "CCNP Enterprise" in r["text"] and r["name"] == "Sara CV 2026.pdf"
              and not st["error"], (rec, st))
        scanned = make_pdf([])
        fake.user_new_version(cvf, scanned)
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("a CV without text: not taken, and the user is told", "no text" in st["error"]
              and base64.b64decode(c.get("/v1/me/cv", headers=P2).json()["data"]) == new_pdf, st)
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("the message stays while that version is there", "no text" in st["error"], st)
        third = make_pdf(["Sara Ahmed - Network Automation Engineer"] + CV_LINES[1:])
        c.post("/v1/me/cv", headers=P2, json={"name": "Sara CV.pdf", "data": base64.b64encode(third).decode(),
                                              "text": "Sara Ahmed - Network Automation Engineer\n" + CV_TEXT, "now": time.time(),
                                              "t": time.time(), "base": 99999})
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("a new CV from the app replaces the Drive file and clears the message", fake.files[cvf]["content"] == third
              and fake.files[cvf]["name"] == "Sara CV.pdf" and not st["error"], st)
        other = fake.user_upload(folder[0]["id"], "other.pdf", new_pdf)
        c.post("/v1/me/drive/sync", headers=P1, json={})
        check("a file the user adds is not seen (drive.file)", fake.files[cvf]["content"] == third and fake.count("GET", other) == 0)

        print("\n== Google Drive: when things go wrong")
        fake.user_trash(jobs)
        c.post("/v1/me/drive/sync", headers=P1, json={})
        new_jobs = fake.find_app("jobs")
        check("My jobs put in the trash: made again", len(new_jobs) == 1 and new_jobs[0]["id"] != jobs
              and fake.user_values(new_jobs[0]["id"])[0][0] == "Status")
        fake.expire_access()
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("the access token ran out: a new one, the sync goes on", not st["error"] and fake.count("POST", "oauth2.google.test/token") >= 3, st)
        fake.fail("POST", ":batchUpdate", httpx.Response(429, json={"error": {"code": 429, "message": "Quota exceeded",
                                                                              "status": "RESOURCE_EXHAUSTED"}}))
        c.post("/v1/me/data", headers=P2, json={"now": time.time(), "changes": [{"k": "profile", "id": "city", "d": {"v": "Cairo"}, "t": time.time(), "base": 0}]})
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("Google says slow down: told, tried again later", st["error_code"] == "rate" and "slow down" in st["error"], st)
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("the next sync works", not st["error"] and any(r[-1:] == ["p:city"] and r[2] == "Cairo" for r in fake.user_values(ans)), st)
        fake.fail("GET", "drive/v3/files", httpx.Response(403, json={"error": {"code": 403, "message": "Google Drive API has not been used in project 123 before or it is disabled.",
                                                                               "errors": [{"reason": "accessNotConfigured"}], "status": "PERMISSION_DENIED"}}))
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("the Drive API off in Google Cloud: said so", st["error_code"] == "api_off" and "API" in st["error"], st)
        fake.revoke_all()
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("access removed in the Google account: connect again", st["reconnect"] and st["error_code"] == "reconnect", st)
        calls = len(fake.calls)
        drive_due = db.all_rows("SELECT l.user_id FROM drive_links l JOIN users u ON u.id = l.user_id WHERE l.error_code NOT IN "
                                "('reconnect', 'setup')")
        check("the loop leaves it until then", not drive_due and len(fake.calls) == calls)
        q = fake.consent(c.post("/v1/me/drive/link", headers=P1, json={}).json()["url"])
        c.get("/oauth/google/callback", params=q)
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        check("connected again: the same folder, working", not st["error"] and len(fake.find_app("folder")) == 1
              and st["folder_url"].endswith(folder[0]["id"]), st)

        print("\n== Google Drive: disconnect, connect again")
        n_rt = len(fake.refresh)
        r = c.post("/v1/me/drive/unlink", headers=P1, json={}).json()["drive"]
        check("disconnect: Google forgets the app's access, the files stay", r["connected"] is False and len(fake.refresh) == n_rt - 1
              and fake.find_app("answers"))
        av = fake.user_values(ans)
        av[fake.user_find_row(ans, 4, "p:city")][2] = "Alexandria"          # edited while not connected
        av.append(["Other", "Do you have a car?", "Yes"])
        fake.user_set_rows(ans, av)
        r7 = rev_now()
        q = fake.consent(c.post("/v1/me/drive/link", headers=P1, json={}).json()["url"])
        c.get("/oauth/google/callback", params=q)
        st = c.post("/v1/me/drive/sync", headers=P1, json={}).json()["drive"]
        got = since(r7)
        check("connected again later: the old folder is found, not a second one", len(fake.find_app("folder")) == 1 and st["ready"])
        check("then the server's answers win, new rows still count", ("profile", "city") not in got
              and any(d and d.get("label") == "Do you have a car?" for (k, i), d in got.items() if k == "facts")
              and any(r[-1:] == ["p:city"] and r[2] == "Cairo" for r in fake.user_values(ans)), got)

        print("\n== Admin and account deletion")
        d = c.get(f"/admin/api/users/{uid}").json()["data"]
        check("the admin sees what the user's account holds", d["cv"]["name"] == "Sara CV.pdf" and d["counts"].get("facts", 0) >= 2
              and d["drive"]["email"] == "sara.drive@gmail.com" and "data" not in json.dumps(d["cv"]), d)
        ov = c.get("/admin/api/overview").json()
        check("overview counts Drive users", ov["totals"]["drive"] == 1 and ov["users"][0]["drive"] == 1, ov["totals"])
        check("privacy policy explains Drive and Limited Use", all(x in c.get("/privacy").text for x in ("drive.file", "Limited Use", "My answers")))
        print("\n== Sign in with Google")
        import hashlib
        import re as re2

        def challenge(v):
            return base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).decode().rstrip("=")

        def google_login(email, verifier, name="Nada"):
            fake.email, fake.name = email, name
            start = c.post("/v1/auth/google/start", json={"challenge": challenge(verifier), "device": "Pixel 9"}).json()["url"]
            page = c.get("/oauth/google/callback", params=fake.consent(start))
            m = re2.search(r"autoapply://auth\?code=([A-Za-z0-9_\-]+)", page.text)
            return start, page, (m.group(1) if m else None)

        def mine(key, email):
            return [f for f in fake.find_app(key) if f.get("owner") == email]

        main.auth_limit.hits.clear()     # this test signs in far more often than a person would
        main.google_limit.hits.clear()
        cfg = c.get("/v1/config").json()
        check("the app learns: Google sign-in is on, new password accounts are off", cfg["google_signin"] and not cfg["password_signups"], cfg)
        r = c.post("/v1/auth/signup", json={"email": "new@example.com", "password": "secret-pass"})
        check("a new account with a password: refused, use Google", r.status_code == 403 and err(r) == "use_google", r.text)
        check("starting needs the app's PKCE challenge", err(c.post("/v1/auth/google/start", json={"challenge": "short"})) == "bad_request")
        V = "app-verifier-" + "a" * 48
        url, page, code = google_login("nada@gmail.com", V)
        check("Google's page asks who you are and for your Drive, in one consent", all(x in url for x in (
            "openid", "email", "drive.file", "select_account", "access_type=offline")), url)
        check("back from Google: signed in, with a one-time code for the app", page.status_code == 200 and code
              and "nada@gmail.com" in page.text, page.text[-300:])
        r = c.post("/v1/auth/google/finish", json={"code": code, "verifier": "another-verifier-" + "b" * 40})
        check("the code without the app's own verifier is worth nothing", r.status_code == 400 and err(r) == "bad_code")
        check("and it is used up", err(c.post("/v1/auth/google/finish", json={"code": code, "verifier": V})) == "bad_code")
        r = c.post("/v1/auth/google/claim", json={"verifier": V})
        check("back in the app before Google's page is done: still pending", r.status_code == 200 and r.json().get("pending"), r.text)
        url, page, code = google_login("nada@gmail.com", V)
        r = c.post("/v1/auth/google/claim", json={"verifier": "another-verifier-" + "c" * 40})
        check("another app's verifier finds nothing", r.status_code == 200 and r.json().get("pending"), r.text)
        r = c.post("/v1/auth/google/claim", json={"verifier": V, "device": "Pixel 9"})
        check("the browser did not open the app: back in it, its verifier alone finishes the sign-in",
              r.status_code == 200 and r.json().get("token") and not r.json().get("pending"), r.text)
        check("then the link's code is used up", err(c.post("/v1/auth/google/finish", json={"code": code, "verifier": V})) == "bad_code")
        url, page, code = google_login("nada@gmail.com", V)
        r = c.post("/v1/auth/google/finish", json={"code": code, "verifier": V, "device": "Pixel 9"})
        check("and the link used first: the claim finds nothing more", c.post("/v1/auth/google/claim", json={"verifier": V}).json().get("pending"))
        PN = auth(r.json()["token"])
        me = c.get("/v1/me", headers=PN).json()["user"]
        check("signed in: an account made with Google (at the first try), without a password", r.status_code == 200
              and not r.json()["created"] and me["email"] == "nada@gmail.com" and me["google"] and not me["has_password"], r.text)
        d = c.get("/v1/me/drive", headers=PN).json()["drive"]
        check("its Google Drive is connected by the same consent", d["connected"] and d["email"] == "nada@gmail.com", d)
        check("signing in with a password says to use Google", err(c.post("/v1/auth/login", json={
            "email": "nada@gmail.com", "password": "whatever-1"})) == "use_google")
        t0 = time.time()
        c.post("/v1/me/data", headers=PN, json={"now": t0, "changes": [
            {"k": "profile", "id": "phone", "d": {"v": "01099998888"}, "base": 0, "t": t0},
            {"k": "settings", "id": "KEYWORDS", "d": {"v": "Cloud Engineer"}, "base": 0, "t": t0},
            {"k": "facts", "id": "fn1", "d": {"id": "fn1", "category": "Skills", "label": "Years with Terraform", "value": "3",
                                              "source": "you"}, "base": 0, "t": t0},
            {"k": "copilot", "id": "li:4055555555", "d": {"id": "li:4055555555", "url": "https://www.linkedin.com/jobs/view/4055555555/",
                                                          "title": "Cloud Engineer", "company": "Valeo", "channel": "linkedin",
                                                          "state": "applied", "stage": "offer", "applied_at": t0 - 86400,
                                                          "added": t0 - 90000, "note": "Offer 45k"}, "base": 0, "t": t0},
            {"k": "jobs", "id": "4066666666", "d": {"id": "4066666666", "status": "applied", "title": "DevOps Engineer",
                                                    "company": "Etisalat", "url": "https://www.linkedin.com/jobs/view/4066666666/",
                                                    "ts": t0 - 3600, "stage": "interview"}, "base": 0, "t": t0}]})
        c.post("/v1/me/cv", headers=PN, json={"name": "Nada CV.pdf", "data": base64.b64encode(pdf).decode(), "text": CV_TEXT,
                                              "now": t0, "t": t0, "base": 0})
        c.post("/v1/me/drive/sync", headers=PN, json={})
        check("her Auto Apply folder fills", mine("answers", "nada@gmail.com") and any(
            "Years with Terraform" in "".join(r) for r in fake.user_values(mine("answers", "nada@gmail.com")[0]["id"])))
        r = c.post("/v1/me/delete", headers=PN, json={"confirm": "wrong@example.com"})
        check("deleting an account made with Google: type its email to confirm", r.status_code == 400 and err(r) == "bad_confirm")
        check("deleted", c.post("/v1/me/delete", headers=PN, json={"confirm": "nada@gmail.com"}).status_code == 200)
        url, page, code = google_login("nada@gmail.com", V)
        r = c.post("/v1/auth/google/finish", json={"code": code, "verifier": V})
        PN = auth(r.json()["token"])
        recs = {(x["k"], x["id"]): x["d"] for x in c.get("/v1/me/data", headers=PN).json()["records"]}
        check("signed in again: a new account (as on a new server)", r.json()["created"])
        check("which takes everything back from her Drive, with the setup done", recs.get(("profile", "phone")) == {"v": "01099998888"}
              and recs.get(("settings", "KEYWORDS")) == {"v": "Cloud Engineer"} and recs.get(("facts", "fn1"), {}).get("value") == "3"
              and recs.get(("copilot", "li:4055555555"), {}).get("stage") == "offer"
              and recs.get(("jobs", "4066666666"), {}).get("stage") == "interview"
              and recs.get(("settings", "SETUP_DONE")) == {"v": "1"}, sorted(recs))
        r = c.get("/v1/me/cv", headers=PN)
        check("and her CV", r.status_code == 200 and base64.b64decode(r.json()["data"]) == pdf and "Network Engineer" in r.json()["text"],
              r.text[:200])
        check("one Auto Apply folder in her Drive, not two", len(mine("folder", "nada@gmail.com")) == 1)
        url, page, code = google_login("sara@example.com", V, "Sara")
        r = c.post("/v1/auth/google/finish", json={"code": code, "verifier": V})
        me = c.get("/v1/me", headers=auth(r.json()["token"])).json()["user"]
        check("an account made with a password, signing in with Google for the same email: the same account", me["id"] == uid
              and me["google"] and me["has_password"] and not r.json()["created"], me)
        check("its Drive is now that Google account's", c.get("/v1/me/drive", headers=P1).json()["drive"]["email"] == "sara@example.com")
        c.post("/admin/api/settings", headers=A, json={"password_signups": True})
        r = c.post("/v1/auth/signup", json={"email": "omar@example.com", "password": "omar-pass-123", "name": "Omar"})
        c.post("/admin/api/settings", headers=A, json={"password_signups": False})
        PO = auth(r.json()["token"])
        omar = c.get("/v1/me", headers=PO).json()["user"]
        start = c.post("/v1/me/google/link", headers=PO, json={}).json().get("url", "")
        fake.email, fake.name = "omar.g@gmail.com", "Omar"
        page = c.get("/oauth/google/callback", params=fake.consent(start))
        check("an email account adds Google (any Google address): the page says so", page.status_code == 200
              and "Google is added" in page.text and "drive.file" in start, page.text[-300:])
        check("and its Drive is connected with the same consent", c.get("/v1/me/drive", headers=PO).json()["drive"]["email"]
              == "omar.g@gmail.com")
        url, page, code = google_login("omar.g@gmail.com", V, "Omar")
        r = c.post("/v1/auth/google/finish", json={"code": code, "verifier": V})
        me = c.get("/v1/me", headers=auth(r.json()["token"])).json()["user"]
        check("from then on Sign in with Google opens that same account", me["id"] == omar["id"] and me["google"]
              and me["has_password"] and not r.json()["created"], me)
        start = c.post("/v1/me/google/link", headers=PO, json={}).json()["url"]
        fake.email = "nada@gmail.com"
        page = c.get("/oauth/google/callback", params=fake.consent(start))
        check("a Google account that signs in to another account cannot be added", page.status_code == 400
              and "another JSTB account" in page.text, page.text[-300:])
        c.post("/admin/api/settings", headers=A, json={"signups_open": False})
        url, page, code = google_login("someone@gmail.com", V)
        check("new accounts closed: a new Google account is told", page.status_code == 400 and "closed" in page.text and not code)
        url, page, code = google_login("sara@example.com", V, "Sara")
        check("an account that exists still signs in", code is not None)
        c.post("/admin/api/settings", headers=A, json={"signups_open": True})
        url, page, code = google_login("web@gmail.com", V, "Web")
        c.post("/v1/auth/google/finish", json={"code": code, "verifier": V})
        check("the web page for deleting links to Google", "/delete-account/google" in c.get("/delete-account").text)
        r = c.get("/delete-account/google", follow_redirects=False)
        fake.email = "web@gmail.com"
        page = c.get("/oauth/google/callback", params=fake.consent(r.headers["location"]))
        m = re2.search(r'name="token" value="([^"]+)"', page.text)
        check("deleting on the web with Google: Google says who you are, then you confirm", r.status_code == 302
              and "drive.file" not in r.headers["location"] and m and "web@gmail.com" in page.text, page.text[-400:])
        r = c.post("/delete-account/google", data={"token": m.group(1) if m else ""})
        check("deleted on the web", "were deleted" in r.text and db.one("SELECT 1 FROM users WHERE email = 'web@gmail.com'") is None)
        users = {u["email"]: u for u in c.get("/admin/api/overview").json()["users"]}
        check("the admin sees how each user signs in", users["nada@gmail.com"]["google"] and not users["nada@gmail.com"]["has_password"]
              and users["sara@example.com"]["google"] and users["sara@example.com"]["has_password"])

        n_rt = len(fake.refresh)
        r = c.post("/v1/me/delete", headers=P1, json={"password": "secret-pass"})
        left = [db.one(f"SELECT COUNT(*) AS n FROM {t} WHERE user_id = ?", (uid,))["n"] for t in ("records", "user_files", "drive_links")]
        check("deleting the account deletes its data and disconnects Drive", r.status_code == 200 and left == [0, 0, 0]
              and len(fake.refresh) == n_rt - 1, (left, len(fake.refresh)))


main_test()
ok = sum(results)
print(f"\n{ok}/{len(results)} checks passed")
sys.exit(0 if ok == len(results) else 1)
