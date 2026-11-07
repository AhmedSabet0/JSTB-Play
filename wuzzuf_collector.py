#!/usr/bin/env python3
"""
wuzzuf_collector.py
-------------------
Autonomous Wuzzuf Job Scraper & Ingestion Engine for Linux Servers (Pure Local Storage).
Runs on standard Python 3 with ZERO required external packages (uses urllib / sqlite3).
"""

import argparse
import http.server
import json
import logging
import os
import re
import socketserver
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger("wuzzuf_collector")

BASE = "https://wuzzuf.net"
PAGE_SIZE = 15
PAUSE = 1.5
HEADERS = {
    "Accept": "application/vnd.api+json",
    "Content-Type": "application/vnd.api+json",
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
}

LINKEDIN_EASY_APPLY_TITLES = [
    # DevOps, Cloud & Infrastructure
    "DevOps Engineer", "Cloud Engineer", "Site Reliability Engineer", "SRE",
    "Platform Engineer", "Infrastructure Engineer", "Kubernetes Engineer",
    "Systems Engineer", "Linux Administrator",

    # Software Engineering & Development
    "Backend Developer", "Frontend Developer", "Full Stack Developer", "Software Engineer",
    "Python Developer", "Java Developer", "Node.js Developer", ".NET Developer",
    "Dotnet Developer", "PHP Developer", "Laravel Developer", "Golang Developer",
    "Mobile Developer", "Flutter Developer", "Android Developer", "iOS Developer",
    "React Developer", "Angular Developer",

    # Networking, NOC & Telecom
    "Network Engineer", "NOC Engineer", "Telecom Engineer", "Network Security Engineer",
    "System Administrator", "IT Support Engineer", "Technical Support Engineer",

    # Data, AI & Cybersecurity
    "Data Engineer", "Data Analyst", "Machine Learning Engineer", "Data Scientist",
    "BI Developer", "Cyber Security Engineer", "SOC Analyst",

    # Quality Assurance, Product & Design
    "QA Engineer", "Software Test Engineer", "Automation QA Engineer",
    "UI/UX Designer", "Product Manager"
]


def clean_text(html: Optional[str]) -> str:
    if not html:
        return ""
    t = re.sub(r"<[^>]+>", " ", str(html))
    return " ".join(t.split()).strip()


def extract_code(url: str) -> str:
    m = re.search(r"/jobs/p/([A-Za-z0-9_-]+)", url)
    if m:
        slug = m.group(1)
        return slug.split("-")[0] if "-" in slug else slug
    return ""


def parse_salary(sal_obj: Any) -> str:
    if not isinstance(sal_obj, dict) or sal_obj.get("isConfidential"):
        return "Confidential"
    low = sal_obj.get("min")
    high = sal_obj.get("max")
    curr = sal_obj.get("currency")
    curr_name = curr.get("name") if isinstance(curr, dict) else (curr or "EGP")
    period = sal_obj.get("period")
    period_name = period.get("name") if isinstance(period, dict) else (period or "Per Month")

    if low and high and low != high:
        return f"{low:,} - {high:,} {curr_name} {period_name}"
    elif low:
        return f"{low:,} {curr_name} {period_name}"
    elif high:
        return f"{high:,} {curr_name} {period_name}"
    return sal_obj.get("additionalDetails") or "Not Specified"


def parse_wuzzuf_date(date_str: Optional[str]) -> Optional[str]:
    if not date_str:
        return None
    try:
        parts = date_str.strip().split()
        date_part = parts[0]
        time_part = parts[1] if len(parts) > 1 else "00:00:00"
        m, d, y = map(int, date_part.split("/"))
        hh, mm, ss = map(int, time_part.split(":"))
        return datetime(y, m, d, hh, mm, ss, tzinfo=timezone.utc).isoformat()
    except Exception:
        return date_str


class WuzzufFetcher:
    """Communicates with Wuzzuf REST API using urllib (zero dependencies) or requests."""
    def __init__(self):
        self.use_requests = False
        try:
            import requests
            self.requests_lib = requests
            self.session = requests.Session()
            self.use_requests = True
        except ImportError:
            self.use_requests = False

    def search_jobs(self, query: str, start_index: int = 0, page_size: int = PAGE_SIZE) -> Tuple[List[Dict[str, Any]], int]:
        url = f"{BASE}/api/search/job"
        payload = {"startIndex": start_index, "pageSize": page_size, "query": query, "searchFilters": {}}
        try:
            if self.use_requests:
                res = self.session.post(url, json=payload, headers=HEADERS, timeout=20)
                if res.status_code != 200:
                    logger.warning(f"Search failed HTTP {res.status_code} for query '{query}'")
                    return [], 0
                data = res.json()
            else:
                body = json.dumps(payload).encode("utf-8")
                req = urllib.request.Request(url, data=body, headers=HEADERS, method="POST")
                with urllib.request.urlopen(req, timeout=20) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
            return data.get("data") or [], (data.get("meta") or {}).get("totalResultsCount", 0)
        except Exception as e:
            logger.error(f"Search exception for '{query}': {e}")
            return [], 0

    def fetch_job_details(self, job_ids: List[str], company_name_map: Dict[str, str]) -> List[Dict[str, Any]]:
        if not job_ids:
            return []
        ids_param = ",".join(job_ids)
        url = f"{BASE}/api/job?filter[other][ids]={ids_param}"
        try:
            if self.use_requests:
                res = self.session.get(url, headers=HEADERS, timeout=25)
                if res.status_code != 200:
                    return []
                raw_jobs = res.json().get("data") or []
            else:
                req = urllib.request.Request(url, headers=HEADERS, method="GET")
                with urllib.request.urlopen(req, timeout=25) as resp:
                    raw_jobs = json.loads(resp.read().decode("utf-8")).get("data") or []
            return self._parse_jobs(raw_jobs, company_name_map)
        except Exception as e:
            logger.error(f"Details exception: {e}")
            return []

    def _parse_jobs(self, raw_jobs: List[Dict[str, Any]], company_name_map: Dict[str, str]) -> List[Dict[str, Any]]:
        out = []
        for item in raw_jobs:
            job_uuid = item.get("id")
            a = item.get("attributes") or {}
            uri = a.get("uri") or ""
            if not uri:
                continue
            url = f"{BASE}{uri}" if uri.startswith("/") else uri
            code = extract_code(url) or job_uuid
            title = clean_text(a.get("title"))
            if not title:
                continue

            company_name = company_name_map.get(job_uuid) or ""
            loc = a.get("location") or {}
            city = ((loc.get("city") or {}).get("name") if isinstance(loc.get("city"), dict) else loc.get("city")) or ""
            country = ((loc.get("country") or {}).get("name") if isinstance(loc.get("country"), dict) else loc.get("country")) or "Egypt"
            location_str = f"{city}, {country}".strip(", ")

            workplace = "On-site"
            is_remote = False
            for wt in (a.get("workTypes") or []):
                wt_name = (wt.get("name") if isinstance(wt, dict) else str(wt)).lower()
                if "remote" in wt_name:
                    is_remote = True
                    workplace = "Remote"
                elif "hybrid" in wt_name:
                    workplace = "Hybrid"

            exp = a.get("workExperienceYears") or {}
            skills = [s.get("name") if isinstance(s, dict) else str(s) for s in (a.get("keywords") or a.get("skills") or [])]

            out.append({
                "job_id": code, "title": title, "company": company_name, "location": location_str,
                "city": city, "country": country, "is_remote": is_remote, "workplace": workplace,
                "experience_min": exp.get("min"), "experience_max": exp.get("max"),
                "salary": parse_salary(a.get("salary")), "url": url,
                "description": clean_text(a.get("description")), "requirements": clean_text(a.get("requirements")),
                "skills": ", ".join(skills), "listed_at": parse_wuzzuf_date(a.get("postedAt") or a.get("publishedAt")),
                "expires_at": parse_wuzzuf_date(a.get("expireAt")), "source": "wuzzuf_job", "apply_type": "easy_apply",
                "raw_json": json.dumps(item)
            })
        return out


class LocalDatabaseStorage:
    def __init__(self, db_path="data/jobs.db"):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    def _init_schema(self):
        conn = sqlite3.connect(str(self.db_path))
        with conn:
            conn.execute("PRAGMA journal_mode=WAL;")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS wuzzuf_jobs (
                    job_id TEXT PRIMARY KEY, title TEXT NOT NULL, company TEXT, location TEXT,
                    city TEXT, country TEXT, is_remote INTEGER DEFAULT 0, workplace TEXT,
                    experience_min INTEGER, experience_max INTEGER, salary TEXT, url TEXT NOT NULL,
                    description TEXT, requirements TEXT, skills TEXT, listed_at TEXT, expires_at TEXT,
                    last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, raw_json TEXT
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS opportunities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT DEFAULT 'wuzzuf_job',
                    source_ref TEXT UNIQUE, title TEXT NOT NULL, company TEXT, location TEXT,
                    url TEXT NOT NULL, apply_type TEXT DEFAULT 'easy_apply', is_remote INTEGER DEFAULT 0,
                    salary TEXT, description TEXT, requirements TEXT, skills TEXT, listed_at TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS job_queries (
                    query TEXT PRIMARY KEY, last_run_at TIMESTAMP, total_found INTEGER DEFAULT 0, new_jobs INTEGER DEFAULT 0
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_opp_source_ref ON opportunities(source, source_ref);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_opp_created_at ON opportunities(created_at DESC);")
        conn.close()
        logger.info(f"Local SQLite database ready at {self.db_path.resolve()}")

    def save_jobs(self, jobs: List[Dict[str, Any]]) -> Tuple[int, int]:
        if not jobs:
            return 0, 0
        new_count = 0
        conn = sqlite3.connect(str(self.db_path))
        with conn:
            for j in jobs:
                job_id = j["job_id"]
                cur = conn.execute("UPDATE wuzzuf_jobs SET last_seen_at = CURRENT_TIMESTAMP WHERE job_id = ?", (job_id,))
                if cur.rowcount > 0:
                    conn.execute("UPDATE opportunities SET last_seen_at = CURRENT_TIMESTAMP WHERE source = 'wuzzuf_job' AND source_ref = ?", (job_id,))
                else:
                    conn.execute("""
                        INSERT INTO wuzzuf_jobs (
                            job_id, title, company, location, city, country, is_remote, workplace,
                            experience_min, experience_max, salary, url, description, requirements, skills, listed_at, expires_at, raw_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, (
                        job_id, j["title"], j["company"], j["location"], j["city"], j["country"],
                        1 if j["is_remote"] else 0, j["workplace"], j["experience_min"], j["experience_max"],
                        j["salary"], j["url"], j["description"], j["requirements"], j["skills"],
                        j["listed_at"], j["expires_at"], j.get("raw_json", "")
                    ))
                    conn.execute("""
                        INSERT OR IGNORE INTO opportunities (
                            source, source_ref, title, company, location, url, apply_type, is_remote, salary, description, requirements, skills, listed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, (
                        "wuzzuf_job", job_id, j["title"], j["company"], j["location"], j["url"],
                        "easy_apply", 1 if j["is_remote"] else 0, j["salary"], j["description"], j["requirements"], j["skills"], j["listed_at"]
                    ))
                    new_count += 1
        conn.close()
        self._export_latest_json()
        return len(jobs), new_count

    def update_query_stats(self, query: str, total_found: int, new_jobs: int):
        conn = sqlite3.connect(str(self.db_path))
        with conn:
            conn.execute("""
                INSERT INTO job_queries (query, last_run_at, total_found, new_jobs) VALUES (?, CURRENT_TIMESTAMP, ?, ?)
                ON CONFLICT(query) DO UPDATE SET last_run_at = CURRENT_TIMESTAMP, total_found = excluded.total_found, new_jobs = excluded.new_jobs
            """, (query, total_found, new_jobs))
        conn.close()

    def get_latest_opportunities(self, limit: int = 50) -> List[Dict[str, Any]]:
        conn = sqlite3.connect(str(self.db_path))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()
        cur.execute("SELECT * FROM opportunities ORDER BY created_at DESC LIMIT ?", (limit,))
        rows = [dict(r) for r in cur.fetchall()]
        conn.close()
        return rows

    def _export_latest_json(self):
        try:
            latest = self.get_latest_opportunities(100)
            json_file = self.db_path.parent / "wuzzuf_latest.json"
            json_file.write_text(json.dumps(latest, ensure_ascii=False, indent=2), encoding="utf-8")
        except Exception:
            pass


class SimpleJobServer(http.server.SimpleHTTPRequestHandler):
    storage = None
    def do_GET(self):
        if self.path in ("/api/jobs", "/api/opportunities", "/jobs", "/"):
            items = self.storage.get_latest_opportunities(100) if self.storage else []
            body = json.dumps({"ok": True, "count": len(items), "jobs": items}, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()
    def log_message(self, format, *args):
        pass


def run_collector_cycle(fetcher: WuzzufFetcher, storage: LocalDatabaseStorage, titles: List[str], max_pages: int = 2):
    logger.info(f"=== Starting Wuzzuf Scraping Cycle ({len(titles)} titles) ===")
    total_found_cycle, total_new_cycle = 0, 0
    for idx, title in enumerate(titles, 1):
        query = title.strip()
        if not query:
            continue
        logger.info(f"[{idx}/{len(titles)}] Searching: '{query}'")
        title_found, title_new = 0, 0
        for page in range(max_pages):
            start_index = page * PAGE_SIZE
            items, total_count = fetcher.search_jobs(query, start_index=start_index, page_size=PAGE_SIZE)
            if not items:
                break
            company_map = {item["id"]: (item.get("computedFields") or {}).get("company_name", "") for item in items if "id" in item}
            job_ids = list(company_map.keys())
            time.sleep(PAUSE)
            detailed = fetcher.fetch_job_details(job_ids, company_map)
            seen, new_cnt = storage.save_jobs(detailed)
            title_found += seen
            title_new += new_cnt
            if len(items) < PAGE_SIZE or (start_index + PAGE_SIZE) >= total_count:
                break
            time.sleep(PAUSE)
        storage.update_query_stats(query, title_found, title_new)
        total_found_cycle += title_found
        total_new_cycle += title_new
        logger.info(f"   -> Result: {title_found} found, {title_new} new jobs saved.")
        time.sleep(PAUSE)
    logger.info(f"=== Cycle Complete: {total_found_cycle} found, {total_new_cycle} new opportunities added. ===\n")


def main():
    parser = argparse.ArgumentParser(description="Wuzzuf Autonomous Scraper (Pure Local Storage)")
    parser.add_argument("--titles", type=str)
    parser.add_argument("--pages", type=int, default=2)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--daemon", action="store_true")
    parser.add_argument("--interval", type=int, default=3)
    parser.add_argument("--db", type=str, default="data/jobs.db")
    parser.add_argument("--serve", action="store_true")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()

    titles = [t.strip() for t in args.titles.split(",") if t.strip()] if args.titles else LINKEDIN_EASY_APPLY_TITLES
    storage = LocalDatabaseStorage(db_path=args.db)
    fetcher = WuzzufFetcher()

    if args.serve:
        SimpleJobServer.storage = storage
        server = socketserver.TCPServer(("0.0.0.0", args.port), SimpleJobServer)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        logger.info(f"Opportunities API running at http://0.0.0.0:{args.port}/api/jobs")

    if args.once:
        run_collector_cycle(fetcher, storage, titles, max_pages=args.pages)
        return

    interval_sec = args.interval * 3600
    logger.info(f"Daemon started. Repeating every {args.interval} hours.")
    try:
        while True:
            run_collector_cycle(fetcher, storage, titles, max_pages=args.pages)
            time.sleep(interval_sec)
    except KeyboardInterrupt:
        logger.info("Stopped.")


if __name__ == "__main__":
    main()
