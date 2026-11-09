package com.autoapply.bot.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.autoapply.bot.core.Util.log;

/** New jobs for your job titles, straight from LinkedIn (its public job search) and Wuzzuf (its job search API), newest
 *  first, posted within the last week. The phone asks them itself about once an hour, so the Jobs tab has today's jobs
 *  without waiting for a server. Each job gets a match score against your CV and job titles; weak matches are left out. */
public final class Feed {
    public static final double EVERY = 55 * 60;              // seconds between two rounds by themselves
    static final double KEEP_DAYS = 14;                       // untouched jobs from the feed older than this go
    static final int PER_TITLE = 25;
    static final int MIN_SCORE = 45;                          // %: below that a job is not worth your time
    static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/130.0.0.0 Mobile Safari/537.36";

    private final Engine engine;
    private volatile boolean running;
    private volatile double lastAt;
    private volatile int lastAdded;
    private volatile String lastError = "";

    Feed(Engine engine) {
        this.engine = engine;
        Map<String, Object> saved = Json.map(Json.tryParse(Util.readText(stateFile())));
        lastAt = Json.dbl(saved, "at", 0);
        lastAdded = (int) Json.dbl(saved, "added", 0);
        lastError = Json.str(saved, "error");
    }

    private java.io.File stateFile() {
        return new java.io.File(engine.config.dir, "feed.json");
    }

    /** {running, at, added, error, next_at} for the screens. */
    public Map<String, Object> status() {
        return Json.obj("running", running, "at", lastAt, "added", lastAdded, "error", lastError, "next_at", lastAt + EVERY);
    }

    /** Starts a round in the background when one is due (or `force`). Returns false when one runs already. */
    public boolean maybeRun(boolean force) {
        if (!claim(force)) {
            return false;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                runNow();
            }
        }, "job-feed").start();
        return true;
    }

    /** One round on this thread when one is due (the hourly background job). */
    public void runIfDue() {
        if (claim(false)) {
            runNow();
        }
    }

    /** Takes the right to run a round: none runs, one is due (or forced), and there are job titles to search for. */
    private synchronized boolean claim(boolean force) {
        if (running || (!force && Util.now() - lastAt < EVERY)) {
            return false;
        }
        if (Util.splitList(engine.config.load().get("KEYWORDS")).isEmpty()) {
            return false;   // no job titles yet: nothing to search for
        }
        running = true;
        return true;
    }

    private void runNow() {
        String error = "";
        int added = 0;
        try {
            Map<String, String> cfg = engine.config.load();
            List<String> titles = Util.splitList(cfg.get("KEYWORDS"));
            if (titles.size() > 6) {
                titles = titles.subList(0, 6);
            }
            String location = cfg.get("LOCATION") == null || cfg.get("LOCATION").trim().isEmpty() ? "Egypt" : cfg.get("LOCATION").trim();
            String cv = engine.config.cvText().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> found = new ArrayList<Map<String, Object>>();
            int failures = 0;
            for (String title : titles) {
                try {
                    found.addAll(linkedin(title, location));
                } catch (IOException e) {
                    failures++;
                    error = "LinkedIn: " + Util.shortError(e, 80);
                }
                pause();
                try {
                    found.addAll(wuzzuf(title));
                } catch (IOException e) {
                    failures++;
                    error = "Wuzzuf: " + Util.shortError(e, 80);
                }
                pause();
            }
            List<Map<String, Object>> keep = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> j : found) {
                int score = score(cv, Json.str(j, "title"), titles, Json.str(j, "location"), location);
                if (score >= MIN_SCORE) {
                    j.put("score", score);
                    j.put("fit", Math.max(1, Math.min(10, Math.round(score / 10f))));
                    keep.add(j);
                }
            }
            added = engine.copilot.addFound(keep, Util.now() - KEEP_DAYS * 86400);
            if (failures < titles.size() * 2) {
                error = "";   // one site answered: a hiccup on the other is not worth showing
            }
            log("Job feed: " + found.size() + " jobs found for " + titles.size() + " titles, " + added + " new"
                    + (error.isEmpty() ? "" : " (" + error + ")"));
        } catch (RuntimeException e) {
            error = Util.shortError(e, 100);
        } finally {
            lastAt = Util.now();
            lastAdded = added;
            lastError = error;
            Util.writeText(stateFile(), Json.pretty(Json.obj("at", lastAt, "added", added, "error", error)));
            running = false;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ LinkedIn: public job search, newest first
    static final Pattern LI_CARD_SPLIT = Pattern.compile("<li[\\s>]");
    static final Pattern LI_URN = Pattern.compile("urn:li:jobPosting:(\\d{6,})");
    static final Pattern LI_TITLE = Pattern.compile("base-search-card__title[^>]*>([\\s\\S]*?)</h3>");
    static final Pattern LI_COMPANY = Pattern.compile("base-search-card__subtitle[^>]*>([\\s\\S]*?)</h4>");
    static final Pattern LI_LOCATION = Pattern.compile("job-search-card__location[^>]*>([\\s\\S]*?)</span>");
    static final Pattern LI_TIME = Pattern.compile("<time[^>]*datetime=\"(\\d{4}-\\d{2}-\\d{2})\"[^>]*>([\\s\\S]*?)</time>");

    static List<Map<String, Object>> linkedin(String title, String location) throws IOException {
        String url = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?keywords=" + enc(title)
                + "&location=" + enc(location) + "&f_TPR=r604800&sortBy=DD&start=0";
        return parseLinkedIn(get(url, "text/html"), title, Util.now());
    }

    static List<Map<String, Object>> parseLinkedIn(String html, String query, double now) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String[] cards = LI_CARD_SPLIT.split(html);
        for (String card : cards) {
            Matcher urn = LI_URN.matcher(card);
            Matcher t = LI_TITLE.matcher(card);
            if (!urn.find() || !t.find()) {
                continue;
            }
            String id = urn.group(1);
            String jobTitle = text(t.group(1));
            if (jobTitle.isEmpty()) {
                continue;
            }
            Matcher c = LI_COMPANY.matcher(card);
            Matcher l = LI_LOCATION.matcher(card);
            Matcher tm = LI_TIME.matcher(card);
            double posted = now;
            if (tm.find()) {
                posted = postedAt(tm.group(1), text(tm.group(2)), now);
            }
            out.add(job("li:" + id, "https://www.linkedin.com/jobs/view/" + id + "/", jobTitle, c.find() ? text(c.group(1)) : "",
                    l.find() ? text(l.group(1)) : "", "linkedin", posted, query));
            if (out.size() >= PER_TITLE) {
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Wuzzuf: its search API (JSON:API)
    static List<Map<String, Object>> wuzzuf(String title) throws IOException {
        String body = Json.stringify(Json.obj("startIndex", 0, "pageSize", 15, "longitude", "0", "latitude", "0",
                "query", title, "searchFilters", Json.obj(), "sortBy", "date"));
        Map<String, Object> search = Json.map(Json.tryParse(post("https://wuzzuf.net/api/search/job", body)));
        List<String> ids = new ArrayList<String>();
        Map<String, String> companies = new LinkedHashMap<String, String>();
        for (Object o : Json.arr(search.get("data"))) {
            Map<String, Object> it = Json.map(o);
            String id = Json.str(it, "id");
            if (id.isEmpty()) {
                continue;
            }
            ids.add(id);
            for (Object f : Json.arr(Json.map(it.get("attributes")).get("computedFields"))) {
                Map<String, Object> field = Json.map(f);
                if ("company_name".equals(Json.str(field, "name"))) {
                    Object v = field.get("value");
                    Object first = v instanceof List && !((List<?>) v).isEmpty() ? ((List<?>) v).get(0) : v;
                    if (first != null) {
                        companies.put(id, String.valueOf(first).trim());
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            return new ArrayList<Map<String, Object>>();
        }
        pause();
        String details = get("https://wuzzuf.net/api/job?filter%5Bother%5D%5Bids%5D=" + enc(Util.join(",", ids)),
                "application/vnd.api+json");
        return parseWuzzuf(details, companies, title, Util.now());
    }

    static List<Map<String, Object>> parseWuzzuf(String json, Map<String, String> companies, String query, double now) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object o : Json.arr(Json.map(Json.tryParse(json)).get("data"))) {
            Map<String, Object> it = Json.map(o);
            Map<String, Object> a = Json.map(it.get("attributes"));
            String url = wuzzufUrl(Json.str(a, "uri").isEmpty() ? Json.str(a, "slug") : Json.str(a, "uri"));
            String jobTitle = text(Json.str(a, "title"));
            if (url.isEmpty() || jobTitle.isEmpty()) {
                continue;
            }
            Matcher code = Pattern.compile("/jobs/p/([A-Za-z0-9]+)").matcher(url);
            String id = "wz:" + (code.find() ? code.group(1) : Json.str(it, "id"));
            String company = companies.containsKey(Json.str(it, "id")) ? companies.get(Json.str(it, "id")) : Json.str(a, "companyName");
            Map<String, Object> loc = Json.map(a.get("location"));
            String city = nameOf(loc.get("city"));
            String country = nameOf(loc.get("country"));
            String where = (city + (city.isEmpty() || country.isEmpty() ? "" : ", ") + country).trim();
            double posted = wuzzufDate(Json.str(a, "postedAt").isEmpty() ? Json.str(a, "publishedAt") : Json.str(a, "postedAt"), now);
            out.add(job(id, url, jobTitle, company, where, "wuzzuf", posted, query));
        }
        return out;
    }

    /** The job's page on Wuzzuf. Its API gives "jobs/p/<code>-<title>" (no leading slash): joined carelessly that became
     *  /jobs/p/jobs/p/..., a page that does not exist. */
    public static String wuzzufUrl(String uri) {
        String u = uri == null ? "" : uri.trim();
        if (u.isEmpty()) {
            return "";
        }
        if (!u.startsWith("http")) {
            u = u.replaceFirst("^/+", "");
            u = "https://wuzzuf.net/" + (u.startsWith("jobs/p/") ? u : "jobs/p/" + u);
        }
        return u.replaceAll("(/jobs/p)+/jobs/p/", "/jobs/p/").replaceFirst("^http://", "https://");
    }

    private static String nameOf(Object o) {
        if (o instanceof Map) {
            return Json.str(Json.map(o), "name").trim();
        }
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** Wuzzuf's "MM/DD/YYYY HH:MM:SS" (UTC), or an ISO date. */
    static double wuzzufDate(String raw, double now) {
        String s = raw == null ? "" : raw.trim();
        String[] formats = {"MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String f : formats) {
            try {
                SimpleDateFormat p = new SimpleDateFormat(f, Locale.US);
                p.setTimeZone(TimeZone.getTimeZone("UTC"));
                p.setLenient(false);
                double t = p.parse(s).getTime() / 1000.0;
                return Math.min(t, now);
            } catch (Exception e) {
                // the next format
            }
        }
        return now;
    }

    /** When a LinkedIn job was posted: "5 hours ago" when it says so (today's jobs), else the date it gives. */
    static double postedAt(String isoDate, String label, double now) {
        double rel = relative(label, now);
        if (rel > 0) {
            return rel;
        }
        try {
            SimpleDateFormat p = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar c = Calendar.getInstance();
            c.setTime(p.parse(isoDate));
            c.set(Calendar.HOUR_OF_DAY, 12);   // noon that day: the right day in any time zone near ours
            return Math.min(c.getTimeInMillis() / 1000.0, now);
        } catch (Exception e) {
            return now;
        }
    }

    static final Pattern AGO = Pattern.compile("(\\d+)\\s*(second|minute|hour|day|week|month)s?\\s+ago", Pattern.CASE_INSENSITIVE);

    /** "3 hours ago" -> that moment; 0 when the label says nothing like it. */
    public static double relative(String label, double now) {
        Matcher m = AGO.matcher(label == null ? "" : label);
        if (!m.find()) {
            return 0;
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        long sec = unit.startsWith("second") ? 1 : unit.startsWith("minute") ? 60 : unit.startsWith("hour") ? 3600
                : unit.startsWith("day") ? 86400 : unit.startsWith("week") ? 7 * 86400 : 30 * 86400;
        return now - n * sec;
    }

    private static Map<String, Object> job(String id, String url, String title, String company, String location, String channel,
                                           double posted, String query) {
        return Json.obj("id", id, "url", url, "title", Util.cut(title, 160), "company", Util.cut(company, 100),
                "location", Util.cut(location, 100), "channel", channel, "source", "feed", "posted_at", posted, "query", query);
    }

    // ------------------------------------------------------------------ the match score (same idea as the server's)
    static final List<String> STOP = java.util.Arrays.asList("and", "or", "the", "in", "at", "of", "to", "for", "with", "a",
            "an", "on", "by", "from", "as", "is", "senior", "junior", "mid", "lead", "staff", "principal", "entry", "level",
            "specialist", "officer", "associate", "intern", "trainee", "sr", "jr", "ii", "iii");

    static List<String> words(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("[a-z0-9+#.]+").matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group().replaceAll("^\\.+|\\.+$", "");
            if (w.length() > 1 && !STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** 0-98: your job titles in its title (up to 55), its title's words in your CV (30), skills your CV repeats (10),
     *  the place (5). */
    static int score(String cvLower, String jobTitle, List<String> titles, String jobLocation, String yourLocation) {
        String t = jobTitle.toLowerCase(Locale.ROOT);
        int best = 0;
        for (String kw : titles) {
            String k = kw.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty()) {
                continue;
            }
            if (t.contains(k)) {
                best = 55;
                break;
            }
            List<String> kws = words(k);
            if (!kws.isEmpty()) {
                int hit = 0;
                for (String w : kws) {
                    if (t.contains(w)) {
                        hit++;
                    }
                }
                best = Math.max(best, hit * 45 / kws.size());
            }
        }
        int score = best;
        List<String> core = words(jobTitle);
        if (!cvLower.isEmpty() && !core.isEmpty()) {
            int inCv = 0;
            int repeated = 0;
            for (String w : core) {
                int at = cvLower.indexOf(w);
                if (at >= 0) {
                    inCv++;
                    if (cvLower.indexOf(w, at + w.length()) >= 0) {
                        repeated++;
                    }
                }
            }
            score += inCv * 30 / core.size();
            score += repeated >= 2 ? 10 : repeated == 1 ? 5 : 0;
        } else if (best >= 45) {
            score += 15;
        }
        String jl = jobLocation.toLowerCase(Locale.ROOT);
        String yl = yourLocation.toLowerCase(Locale.ROOT).trim();
        if (jl.isEmpty() || jl.contains("remote") || (!yl.isEmpty() && (jl.contains(yl) || yl.contains("egypt")
                && (jl.contains("cairo") || jl.contains("giza") || jl.contains("alex") || jl.contains("egypt"))))) {
            score += 5;
        }
        return Math.max(0, Math.min(98, score));
    }

    // ------------------------------------------------------------------ http
    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    static String text(String html) {
        String s = html == null ? "" : html.replaceAll("<[^>]+>", " ");
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = ' ';
            }
            m.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(b);
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    static String get(String url, String accept) throws IOException {
        return request(url, "GET", accept, null);
    }

    static String post(String url, String json) throws IOException {
        return request(url, "POST", "application/vnd.api+json", json);
    }

    private static String request(String url, String method, String accept, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.8");
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/vnd.api+json");
            OutputStream out = c.getOutputStream();
            try {
                out.write(json.getBytes(Util.UTF8));
            } finally {
                out.close();
            }
        }
        int code = c.getResponseCode();
        if (code == 429) {
            throw new IOException("busy (429), trying again later");
        }
        if (code != 200) {
            throw new IOException("HTTP " + code);
        }
        InputStream in = c.getInputStream();
        try {
            return new String(Util.readAll(in), Util.UTF8);
        } finally {
            in.close();
        }
    }
}
