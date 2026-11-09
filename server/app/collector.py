"""Centralized LinkedIn Job Collector & Matcher for Auto Apply.

Scrapes public LinkedIn guest job listings (zero cookies/login required), extracts optimal search
keywords from candidate CVs using AI (max 4 per user), maintains a central jobs database updated
hourly, and scores job matches per user (filtering at >= 60% match). Matched jobs are synced
automatically to user records and Google Drive.
"""
import asyncio
import json
import logging
import random
import re
import time
from typing import Any, Dict, List, Optional, Tuple

import httpx
from bs4 import BeautifulSoup

from app import ai, db, drive, records

logger = logging.getLogger("autoapply.collector")

LINKEDIN_GUEST_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
]

DEFAULT_KEYWORDS = ["Network Engineer", "IT Support Specialist", "System Administrator", "Software Engineer"]


class LinkedInBusy(Exception):
    """LinkedIn answered 429: the rest of this round waits for the next one."""


def clean_job_url(raw_url: str) -> str:
    """Strips query tracking parameters, preserving clean job view URL."""
    if not raw_url:
        return ""
    clean = raw_url.split("?")[0].strip()
    return clean


def extract_job_id(card: Any, url: str) -> str:
    """Extracts unique numeric or entity job ID from card attributes or URL."""
    base_card = card.find("div", class_="base-card")
    if base_card:
        urn = base_card.get("data-entity-urn", "")
        if ":" in urn:
            return urn.split(":")[-1]
    # Check data-id on the li
    card_id = card.get("data-id") or card.get("data-entity-urn")
    if card_id:
        return str(card_id).split(":")[-1]
    # Check URL
    m = re.search(r"view/(?:[^/?#\s]*-)?(\d{6,})", url)
    if m:
        return m.group(1)
    # Fallback to hash of clean url
    return str(abs(hash(clean_job_url(url))))[:10]


async def fetch_linkedin_guest_jobs(
    keywords: str,
    location: str = "Egypt",
    total_jobs: int = 25,
    max_pages: int = 2,
) -> List[Dict[str, Any]]:
    """Fetches public LinkedIn job postings without authentication or cookies.

    Uses LinkedIn's guest search endpoint with randomized delays to respect rate limits.
    """
    jobs: List[Dict[str, Any]] = []
    seen_ids = set()
    page_size = 25
    start = 0

    headers = {
        "User-Agent": random.choice(USER_AGENTS),
        "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
        "Accept": "*/*",
    }

    async with httpx.AsyncClient(headers=headers, timeout=httpx.Timeout(25.0, connect=10.0)) as client:
        for _ in range(max_pages):
            params = {
                "keywords": keywords,
                "location": location,
                "f_TPR": "r604800",   # posted within the last week
                "sortBy": "DD",       # newest first (without it LinkedIn returns its "relevant" jobs, often weeks old)
                "start": start,
            }
            try:
                resp = await client.get(LINKEDIN_GUEST_URL, params=params)
            except Exception as e:
                logger.warning(f"Error fetching jobs for {keywords} in {location}: {e}")
                break

            if resp.status_code == 429:
                logger.warning(f"Rate limited (429) on LinkedIn guest API for {keywords}. Pausing until the next round.")
                raise LinkedInBusy()
            if resp.status_code != 200:
                logger.warning(f"LinkedIn guest API returned status {resp.status_code} for {keywords}.")
                break

            soup = BeautifulSoup(resp.text, "html.parser")
            job_cards = soup.find_all("li")
            if not job_cards:
                break

            for card in job_cards:
                title_tag = card.find("h3", class_="base-search-card__title")
                company_tag = card.find("h4", class_="base-search-card__subtitle")
                location_tag = card.find("span", class_="job-search-card__location")
                link_tag = card.find("a", class_="base-card__full-link")
                time_tag = card.find("time")

                raw_url = link_tag["href"] if link_tag and "href" in link_tag.attrs else ""
                url = clean_job_url(raw_url)
                if not url:
                    continue

                job_id = extract_job_id(card, url)
                if job_id in seen_ids:
                    continue
                seen_ids.add(job_id)

                title = title_tag.get_text(strip=True) if title_tag else ""
                company = company_tag.get_text(strip=True) if company_tag else ""
                job_loc = location_tag.get_text(strip=True) if location_tag else location
                posted_date = time_tag.get_text(strip=True) if time_tag else ""
                posted_at = time.time()
                if time_tag is not None:   # "3 hours ago" when it says so, else the date it gives (at noon UTC)
                    m_ago = re.search(r"(\d+)\s*(minute|hour|day|week|month)s?\s+ago", posted_date, re.I)
                    if m_ago:
                        unit = {"minute": 60, "hour": 3600, "day": 86400, "week": 604800, "month": 2592000}[m_ago.group(2).lower()]
                        posted_at = time.time() - int(m_ago.group(1)) * unit
                    elif re.match(r"\d{4}-\d{2}-\d{2}$", time_tag.get("datetime", "")):
                        posted_at = min(posted_at, time.mktime(time.strptime(time_tag["datetime"] + " 12", "%Y-%m-%d %H")))

                if not title:
                    continue

                jobs.append({
                    "id": job_id,
                    "title": title,
                    "company": company,
                    "location": job_loc,
                    "url": url,
                    "posted_date": posted_date,
                    "posted_at": posted_at,
                    "keywords": keywords,
                    "collected_at": time.time(),
                })

                if len(jobs) >= total_jobs:
                    break

            if len(jobs) >= total_jobs or len(job_cards) < 5:
                break

            start += page_size
            await asyncio.sleep(random.uniform(2.0, 3.5))

    return jobs


async def extract_keywords_from_cv(cv_text: str) -> List[str]:
    """Uses AI to extract up to 4 high-value job search keywords/titles from candidate's CV."""
    if not cv_text or len(cv_text.strip()) < 100:
        return []

    settings = {r["key"]: r["value"] for r in db.conn().execute("SELECT key, value FROM settings").fetchall()}
    key = settings.get("openrouter_key", "").strip()
    model = settings.get("model", "openai/gpt-4o-mini").strip()

    prompt = (
        "You are an expert technical recruiter and career counselor.\n"
        "Analyze this candidate's CV text and extract 2 to 4 of the most accurate, high-demand job titles "
        "to search for on LinkedIn (e.g. ['Network Engineer', 'IT Support Specialist', 'System Administrator']).\n\n"
        "Rules:\n"
        "1. Return AT MOST 4 job titles.\n"
        "2. Keep each title concise (1-3 words, standard job market naming).\n"
        "3. Output MUST be valid JSON array of strings only, like: [\"Title 1\", \"Title 2\"].\n"
        "4. No markdown formatting, no code fences, no extra text.\n\n"
        f"Candidate CV:\n{cv_text[:4000]}"
    )

    if key:
        try:
            res = await ai.chat(
                key=key,
                model=model,
                messages=[{"role": "user", "content": prompt}],
                max_tokens=200,
                temperature=0.2,
            )
            content = res["choices"][0]["message"]["content"].strip()
            # Clean up potential code block markers
            if "```" in content:
                content = re.sub(r"^```[a-z]*\s*", "", content)
                content = re.sub(r"\s*```$", "", content)
            parsed = json.loads(content)
            if isinstance(parsed, list):
                clean_titles = []
                for item in parsed:
                    if isinstance(item, str) and item.strip():
                        clean_titles.append(item.strip()[:60])
                if clean_titles:
                    return clean_titles[:4]
        except Exception as e:
            logger.warning(f"AI keyword extraction failed: {e}. Falling back to heuristic.")

    # Heuristic fallback if AI unavailable or fails
    fallback_titles = []
    text_lower = cv_text.lower()
    common_roles = [
        "network engineer", "systems administrator", "system administrator",
        "it support", "it specialist", "devops engineer", "soc analyst",
        "frontend developer", "backend developer", "full stack developer",
        "software engineer", "python developer", "data analyst",
        "quality assurance", "cybersecurity specialist"
    ]
    for role in common_roles:
        if role in text_lower and len(fallback_titles) < 4:
            fallback_titles.append(role.title())

    return fallback_titles if fallback_titles else ["Software Engineer"]


STOPWORDS = {
    "and", "or", "the", "in", "at", "of", "to", "for", "with", "a", "an", "on", "by",
    "from", "as", "is", "senior", "junior", "mid", "lead", "staff", "principal",
    "entry", "level", "specialist", "officer", "associate", "intern", "trainee"
}


def score_job_for_user(
    cv_text: str,
    job: Dict[str, Any],
    user_keywords: Optional[List[str]] = None,
    user_location: str = "Egypt",
) -> Tuple[int, str]:
    """Calculates deterministic algorithmic match score (0-100%) purely in code (no AI).

    Measures keyword match between job title/tags and candidate CV + target keywords.
    Threshold for a valid match is >= 60%.
    """
    title = str(job.get("title") or "").strip()
    company = str(job.get("company") or "").strip()
    job_loc = str(job.get("location") or "").strip().lower()

    if not title:
        return 0, "No job title available for evaluation."

    if not cv_text and not user_keywords:
        return 0, "No CV text or target keywords available for evaluation."

    cv_lower = (cv_text or "").lower()
    title_lower = title.lower()
    title_words = [w for w in re.findall(r"[a-z0-9+#.]+", title_lower) if len(w) > 2]
    core_title_words = [w for w in title_words if w not in STOPWORDS]

    score = 0
    match_reasons = []

    # 1. User Target Keyword Match (up to 55 points)
    best_kw_score = 0
    matched_kw_name = ""
    if user_keywords:
        for kw in user_keywords:
            kw_clean = kw.strip().lower()
            if not kw_clean:
                continue
            # Full phrase match (e.g. "network engineer" in "senior network engineer")
            if kw_clean in title_lower:
                best_kw_score = 55
                matched_kw_name = kw
                break
            # Word-by-word match
            kw_words = [w for w in re.findall(r"[a-z0-9+#.]+", kw_clean) if len(w) > 2 and w not in STOPWORDS]
            if kw_words:
                matched_count = sum(1 for w in kw_words if w in title_lower)
                kw_ratio = matched_count / len(kw_words)
                current_score = int(kw_ratio * 45)
                if current_score > best_kw_score:
                    best_kw_score = current_score
                    matched_kw_name = kw

    score += best_kw_score
    if matched_kw_name and best_kw_score >= 35:
        match_reasons.append(f"Direct match for your role '{matched_kw_name}'")

    # 2. Core Job Title Words in CV Text (up to 30 points)
    if cv_lower and core_title_words:
        cv_matches = sum(1 for w in core_title_words if w in cv_lower)
        cv_ratio = cv_matches / len(core_title_words)
        title_points = int(cv_ratio * 30)
        score += title_points
        if cv_matches > 0:
            match_reasons.append(f"{cv_matches}/{len(core_title_words)} title keywords verified in your CV")
    else:
        if best_kw_score >= 45:
            score += 15

    # 3. Technical Terms & Skills Overlap in CV (up to 10 points)
    if cv_lower:
        repeated_matches = 0
        for w in core_title_words:
            if cv_lower.count(w) >= 2:
                repeated_matches += 1
        if repeated_matches >= 2:
            score += 10
            match_reasons.append("High skill occurrence in your experience")
        elif repeated_matches == 1:
            score += 5

    # 4. Location Match (up to 5 points)
    user_loc_clean = user_location.lower().strip()
    if user_loc_clean and (
        user_loc_clean in job_loc
        or "remote" in job_loc
        or ("egypt" in user_loc_clean and ("cairo" in job_loc or "giza" in job_loc or "alexandria" in job_loc))
    ):
        score += 5

    score = max(0, min(98, score))

    if not match_reasons:
        why = f"Algorithmic keyword score {score}% based on CV text overlap."
    else:
        why = " • ".join(match_reasons)

    return score, why


def get_user_cv_text(user_id: int) -> str:
    """Retrieves the latest CV text stored for this user."""
    with db.tx() as t:
        rec = records.current(t, user_id, "cv", "main")
        if rec and rec.get("data") and isinstance(rec["data"], dict):
            return str(rec["data"].get("text", ""))
    return ""


def split_titles(value: str) -> List[str]:
    """Job titles as the app keeps them: one per line, or separated by ";" (also "," from older versions)."""
    out: List[str] = []
    for part in re.split(r"[\r\n,;]+", str(value or "")):
        t = part.strip()
        if t and t.lower() not in [x.lower() for x in out]:
            out.append(t)
    return out


def get_user_keywords(user_id: int) -> List[str]:
    """The job titles to search for this user: the ones set in the app (settings KEYWORDS, the source of truth), else
    the ones kept before, else roles found in the CV. user_keywords is kept the same as them, one title per row.

    (The app keeps titles separated by ";": they used to be read as one long title, a search LinkedIn refused.)"""
    found: List[str] = []
    with db.tx() as t:
        cfg = records.current(t, user_id, "settings", "KEYWORDS")
        if cfg and cfg.get("data") and cfg["data"].get("v"):
            found = split_titles(cfg["data"]["v"])
    if not found:
        rows = db.conn().execute("SELECT keyword FROM user_keywords WHERE user_id = ?", (user_id,)).fetchall()
        for r in rows:
            for kw in split_titles(r["keyword"]):
                if kw.lower() not in [x.lower() for x in found]:
                    found.append(kw)
    if not found:
        cv = get_user_cv_text(user_id)
        if cv:
            common = ["network engineer", "it support", "devops engineer", "system administrator", "software engineer", "cloud infrastructure"]
            for role in common:
                if role in cv.lower():
                    found.append(role.title())
    found = found[:6]
    loc = get_user_location(user_id)
    now = time.time()
    with db.tx() as t:
        have = sorted(r["keyword"] for r in t.execute("SELECT keyword FROM user_keywords WHERE user_id = ?", (user_id,)).fetchall())
        if have != sorted(found):
            t.execute("DELETE FROM user_keywords WHERE user_id = ?", (user_id,))
            for kw in found:
                t.execute("INSERT OR IGNORE INTO user_keywords(user_id, keyword, location, created_at) VALUES (?, ?, ?, ?)",
                          (user_id, kw, loc, now))
    return found


def get_user_location(user_id: int) -> str:
    """Retrieves preferred location for this user."""
    with db.tx() as t:
        cfg = records.current(t, user_id, "settings", "LOCATION")
        if cfg and cfg.get("data") and cfg["data"].get("v"):
            return str(cfg["data"]["v"])
    return "Egypt"


async def process_user_cv(user_id: int, cv_text: Optional[str] = None) -> List[str]:
    """Called when user uploads or updates CV. Extracts keywords, updates user_keywords,

    and performs immediate search & matching for the user.
    """
    if cv_text is None:
        cv_text = get_user_cv_text(user_id)
    if not cv_text:
        return []

    # Get location from settings or default to Egypt
    loc = "Egypt"
    with db.tx() as t:
        cfg = records.current(t, user_id, "settings", "LOCATION")
        if cfg and cfg.get("data") and cfg["data"].get("v"):
            loc = cfg["data"]["v"]

    keywords = await extract_keywords_from_cv(cv_text)
    if not keywords:
        return []

    now = time.time()
    with db.tx() as t:
        # Save up to 4 keywords for this user
        t.execute("DELETE FROM user_keywords WHERE user_id = ?", (user_id,))
        for kw in keywords[:4]:
            t.execute(
                "INSERT INTO user_keywords(user_id, keyword, location, created_at) VALUES (?, ?, ?, ?)",
                (user_id, kw, loc, now),
            )

    # Immediately fetch jobs for these keywords and match
    for kw in keywords[:4]:
        try:
            jobs = await fetch_linkedin_guest_jobs(kw, loc, total_jobs=20)
            await save_and_match_jobs(jobs, kw, loc)
        except Exception as e:
            logger.warning(f"Error fetching initial jobs for user {user_id} kw {kw}: {e}")

    # Evaluate any existing jobs in server_jobs against this user's profile
    try:
        existing_jobs = db.conn().execute(
            "SELECT id, title, company, location, url, posted_date FROM server_jobs ORDER BY collected_at DESC LIMIT 150"
        ).fetchall()
        copilot_changes = []
        for ej in existing_jobs:
            job_id = ej["id"]
            already = db.conn().execute(
                "SELECT 1 FROM user_job_matches WHERE user_id = ? AND job_id = ?",
                (user_id, job_id),
            ).fetchone()
            if already:
                continue
            j_dict = dict(ej)
            score, why = score_job_for_user(cv_text, j_dict, user_keywords=keywords[:4], user_location=loc)
            if score >= 60:
                with db.tx() as t:
                    t.execute(
                        """
                        INSERT INTO user_job_matches(user_id, job_id, score, why, status, updated_at)
                        VALUES (?, ?, ?, ?, 'new', ?)
                        ON CONFLICT(user_id, job_id) DO UPDATE SET score=excluded.score, why=excluded.why, updated_at=excluded.updated_at
                        """,
                        (user_id, job_id, score, why, now),
                    )
                copilot_record_data = {
                    "id": f"li:{job_id}",
                    "url": j_dict["url"],
                    "title": j_dict["title"],
                    "company": j_dict["company"],
                    "channel": "linkedin",
                    "source": "server_match",
                    "added": now,
                    "state": "new",
                    "stage": "",
                    "fit": max(1, min(10, round(score / 10))),
                    "score": score,
                    "why": why,
                    "location": j_dict.get("location", ""),
                    "posted_date": j_dict.get("posted_date", ""),
                }
                copilot_changes.append(("copilot", f"li:{job_id}", copilot_record_data, now))

        if copilot_changes:
            with db.tx() as t:
                records.write(t, user_id, copilot_changes, "server")
    except Exception as e:
        logger.warning(f"Error matching existing jobs for user {user_id}: {e}")

    return keywords


async def match_jobs_for_all_users(jobs: List[Dict[str, Any]]) -> int:
    """Evaluates a batch of jobs against all registered users and writes matches to records."""
    if not jobs:
        return 0
    now = time.time()
    matches_created = 0
    with db.tx() as t:
        users = t.execute("SELECT id, email FROM users").fetchall()

    for u in users:
        uid = u["id"]
        cv_text = get_user_cv_text(uid)
        user_kws = get_user_keywords(uid)
        user_loc = get_user_location(uid)

        if not cv_text and not user_kws:
            continue

        copilot_changes = []
        for j in jobs:
            job_id = j["id"]
            existing = db.conn().execute(
                "SELECT score, status FROM user_job_matches WHERE user_id = ? AND job_id = ?",
                (uid, job_id),
            ).fetchone()

            if existing is not None:
                continue

            score, why = score_job_for_user(cv_text, j, user_keywords=user_kws, user_location=user_loc)
            if score >= 60:
                with db.tx() as t:
                    t.execute(
                        """
                        INSERT INTO user_job_matches(user_id, job_id, score, why, status, updated_at)
                        VALUES (?, ?, ?, ?, 'new', ?)
                        ON CONFLICT(user_id, job_id) DO UPDATE SET
                          score = excluded.score,
                          why = excluded.why,
                          updated_at = excluded.updated_at
                        """,
                        (uid, job_id, score, why, now),
                    )

                copilot_record_data = {
                    "id": f"li:{job_id}",
                    "url": j["url"],
                    "title": j["title"],
                    "company": j["company"],
                    "channel": "linkedin",
                    "source": "server_match",
                    "added": now,
                    "state": "new",
                    "stage": "",
                    "fit": max(1, min(10, round(score / 10))),
                    "score": score,
                    "why": why,
                    "location": j.get("location", ""),
                    "posted_date": j.get("posted_date", ""),
                }
                if j.get("posted_at"):
                    copilot_record_data["posted_at"] = j["posted_at"]
                copilot_changes.append(("copilot", f"li:{job_id}", copilot_record_data, now))
                matches_created += 1

        if copilot_changes:
            with db.tx() as t:
                records.write(t, uid, copilot_changes, "server")
            try:
                drive.poke()
            except Exception as e:
                logger.debug(f"drive.poke() notice: {e}")

    return matches_created


async def save_and_match_jobs(jobs: List[Dict[str, Any]], keyword: str, location: str) -> int:
    """Saves collected jobs into server_jobs and evaluates matches against all users.

    Jobs matching with score >= 60% are recorded in user_job_matches AND added as copilot records
    so they seamlessly sync to the Android app and Google Drive!
    """
    if not jobs:
        return 0

    now = time.time()
    new_jobs_count = 0

    # 1. Insert jobs into server_jobs
    with db.tx() as t:
        for j in jobs:
            t.execute(
                """
                INSERT INTO server_jobs(id, title, company, location, url, posted_date, collected_at, keywords)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  collected_at = excluded.collected_at,
                  posted_date = excluded.posted_date
                """,
                (j["id"], j["title"], j["company"], j["location"], j["url"], j["posted_date"], now, keyword),
            )
            new_jobs_count += 1

    # 2. Evaluate against all users
    await match_jobs_for_all_users(jobs)

    return new_jobs_count


async def run_collection_cycle() -> int:
    """Runs the periodic collection cycle for all distinct keywords in the database.

    Evaluates newly collected jobs as well as all existing jobs in server_jobs against all users.
    """
    logger.info("Starting LinkedIn guest job collection cycle...")

    # 1. Backfill user_keywords from settings for all users
    try:
        with db.tx() as t:
            all_users = t.execute("SELECT id FROM users").fetchall()
        for u in all_users:
            get_user_keywords(u["id"])
    except Exception as e:
        logger.warning(f"Error backfilling user keywords: {e}")

    c = db.conn()
    queries = c.execute(
        """
        SELECT DISTINCT keyword, location
        FROM user_keywords
        WHERE keyword NOT LIKE '%;%'
        LIMIT 30
        """
    ).fetchall()

    if not queries:
        # Default seed queries if no users have set keywords yet
        queries = [{"keyword": kw, "location": "Egypt"} for kw in DEFAULT_KEYWORDS]

    total_collected = 0
    for q in queries:
        kw = q["keyword"]
        loc = q["location"] or "Egypt"
        try:
            logger.info(f"Collecting jobs for [{kw}] in [{loc}]...")
            jobs = await fetch_linkedin_guest_jobs(kw, loc, total_jobs=25)
            count = await save_and_match_jobs(jobs, kw, loc)
            total_collected += count
            # Polite pause between keyword searches: LinkedIn answers 429 to quick ones
            await asyncio.sleep(random.uniform(10.0, 18.0))
        except LinkedInBusy:
            break
        except Exception as e:
            logger.error(f"Error during job collection cycle for '{kw}': {e}", exc_info=True)

    # 2. Evaluate existing stored jobs against all users to match immediately
    try:
        existing_jobs = db.conn().execute(
            "SELECT id, title, company, location, url, posted_date FROM server_jobs ORDER BY collected_at DESC LIMIT 200"
        ).fetchall()
        jobs_list = [dict(r) for r in existing_jobs]
        matched_count = await match_jobs_for_all_users(jobs_list)
        logger.info(f"Evaluated existing stored jobs. Matched {matched_count} new job items.")
    except Exception as e:
        logger.error(f"Error matching existing jobs: {e}", exc_info=True)

    logger.info(f"Job collection cycle finished. Processed {total_collected} jobs.")
    return total_collected


async def periodic_job_collector_task():
    """Background standing worker that runs the job collection and scoring cycle every hour."""
    # Brief initial startup delay to let the app fully initialize
    await asyncio.sleep(10.0)
    while True:
        try:
            await run_collection_cycle()
        except Exception as e:
            logger.error(f"Unexpected error in periodic job collector: {e}", exc_info=True)
        # Sleep for 1 hour (3600 seconds)
        await asyncio.sleep(3600)
