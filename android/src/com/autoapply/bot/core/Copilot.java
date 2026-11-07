package com.autoapply.bot.core;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.autoapply.bot.core.Util.log;

/** Jobs you apply to with the app's help: links you share or receive,
 *  and the stage of each application (applied, interview, offer, rejected). Kept in copilot.json. */
public final class Copilot {
    public static final List<String> STAGES = Arrays.asList("applied", "interview", "offer", "rejected");

    private final Engine engine;
    private final File file;
    private final List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();

    Copilot(Engine engine, File dir) {
        this.engine = engine;
        this.file = new File(dir, "copilot.json");
        for (Object o : Json.arr(Json.map(Json.tryParse(Util.readText(file))).get("items"))) {
            if (o instanceof Map) {
                items.add(Json.map(o));
            }
        }
    }

    private void save() {
        Util.writeText(file, Json.pretty(Json.obj("version", 1, "items", items)));
    }

    // ------------------------------------------------------------------ adding a job
    static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");
    static final Pattern LI_JOB = Pattern.compile("linkedin\\.com/(?:comm/)?jobs/view/(?:[^/?#\\s]*?-)?(\\d{6,})");
    static final Pattern LI_CURRENT = Pattern.compile("[?&]currentJobId=(\\d{6,})");
    static final Pattern CHECK_OUT = Pattern.compile("(?i)check out this job at (.+?):\\s*(.+)");
    static final Pattern TITLE_AT = Pattern.compile("^(.{3,120}?)\\s+(?:at|@|\\||-|–|—)\\s+(.{2,80})$");

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(Util.UTF8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                b.append(String.format(Locale.ROOT, "%02x", d[i]));
            }
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** {url, id, channel} of a job link: LinkedIn job links become their plain /jobs/view/ID/ form. */
    static Map<String, Object> parseLink(String raw) {
        String url = raw.replaceAll("[).,;!]+$", "");
        Matcher li = LI_JOB.matcher(url);
        Matcher cur = LI_CURRENT.matcher(url);
        String num = li.find() ? li.group(1) : (url.contains("linkedin.com") && cur.find() ? cur.group(1) : null);
        if (num != null) {
            return Json.obj("url", "https://www.linkedin.com/jobs/view/" + num + "/", "id", "li:" + num, "channel", "linkedin");
        }
        String plain = url.replaceAll("#.*$", "")
                .replaceAll("([?&])(utm_[a-z]+|gh_src|source|ref|trk|refId|trackingId)=[^&]*", "$1")
                .replaceAll("\\?&+", "?").replaceAll("&{2,}", "&").replaceAll("[?&]+$", "");
        boolean onLinkedIn = plain.toLowerCase(Locale.ROOT).contains("linkedin.com");
        return Json.obj("url", plain, "id", (onLinkedIn ? "li:" : "c:") + hash(plain), "channel", onLinkedIn ? "linkedin" : "company");
    }

    /** A job you shared to the app or pasted: its link, and the title and company when the text has them. */
    public synchronized Map<String, Object> add(String text, String subject) {
        String all = ((subject == null ? "" : subject) + "\n" + (text == null ? "" : text)).trim();
        Matcher m = URL.matcher(all);
        if (!m.find()) {
            throw new IllegalArgumentException("Share or paste a link to a job.");
        }
        Map<String, Object> link = parseLink(m.group());
        String id = Json.str(link, "id");
        for (Map<String, Object> it : items) {
            if (id.equals(Json.str(it, "id"))) {
                Map<String, Object> out = new LinkedHashMap<String, Object>(it);
                out.put("again", true);
                return out;
            }
        }
        String words = URL.matcher(all).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String title = "";
        String company = "";
        Matcher co = CHECK_OUT.matcher(words);
        Matcher at = TITLE_AT.matcher(words);
        if (co.find()) {
            company = co.group(1).trim();
            title = co.group(2).trim();
        } else if (at.find()) {
            title = at.group(1).trim();
            company = at.group(2).trim();
        } else {
            title = Util.cut(words, 120);
        }
        if (title.isEmpty()) {
            title = "linkedin".equals(Json.str(link, "channel")) ? "LinkedIn job" : hostOf(Json.str(link, "url"));
        }
        Map<String, Object> item = Json.obj("id", id, "url", Json.str(link, "url"), "title", Util.cut(title, 160),
                "company", Util.cut(company, 100), "channel", Json.str(link, "channel"), "source", "shared", "added", Util.now(),
                "state", "new", "stage", "", "fit", null, "why", "");
        items.add(0, item);
        save();
        log("Added to your jobs: " + Json.str(item, "title") + (company.isEmpty() ? "" : " at " + company));
        return item;
    }

    static String hostOf(String url) {
        Matcher m = Pattern.compile("https?://(?:www\\.)?([^/:?#]+)").matcher(url);
        return m.find() ? m.group(1) : url;
    }

    // ------------------------------------------------------------------ the list
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> it : items) {
            out.add(new LinkedHashMap<String, Object>(it));
        }
        return out;
    }

    public synchronized Map<String, Object> get(String id) {
        for (Map<String, Object> it : items) {
            if (id.equals(Json.str(it, "id"))) {
                return new LinkedHashMap<String, Object>(it);
            }
        }
        return null;
    }

    /** Change a job: state (new, applied, skipped), stage, title, company, fit, letter. Applying and stages are reported. */
    public Map<String, Object> update(String id, Map<String, Object> changes) {
        Map<String, Object> it;
        Map<String, Object> report = null;
        synchronized (this) {
            it = null;
            for (Map<String, Object> x : items) {
                if (id.equals(Json.str(x, "id"))) {
                    it = x;
                }
            }
            if (it == null) {
                throw new IllegalArgumentException("This job is not in your list any more.");
            }
            String was = Json.str(it, "state");
            String wasStage = Json.str(it, "stage");
            for (String k : new String[]{"title", "company", "fit", "why", "letter", "note"}) {
                if (changes.containsKey(k)) {
                    it.put(k, changes.get(k));
                }
            }
            if (changes.containsKey("state")) {
                String st = Json.str(changes, "state");
                if (!Arrays.asList("new", "applied", "skipped").contains(st)) {
                    throw new IllegalArgumentException("Unknown state: " + st);
                }
                it.put("state", st);
                if ("applied".equals(st) && !"applied".equals(was)) {
                    it.put("applied_at", Util.now());
                    if (Json.str(it, "stage").isEmpty()) {
                        it.put("stage", "applied");
                    }
                }
                if ("new".equals(st)) {
                    it.put("stage", "");
                    it.remove("applied_at");
                }
            }
            if (changes.containsKey("stage")) {
                String stage = Json.str(changes, "stage");
                if (!STAGES.contains(stage)) {
                    throw new IllegalArgumentException("Unknown stage: " + stage);
                }
                it.put("stage", stage);
                if (!"applied".equals(Json.str(it, "state"))) {
                    it.put("state", "applied");
                    it.put("applied_at", Util.now());
                }
            }
            save();
            boolean appliedNow = "applied".equals(Json.str(it, "state")) && !"applied".equals(was);
            boolean stageNow = !Json.str(it, "stage").equals(wasStage) && "applied".equals(Json.str(it, "state"));
            if (appliedNow || stageNow) {
                report = Json.obj("job_id", id, "title", Json.str(it, "title"), "company", Json.str(it, "company"),
                        "status", "applied", "channel", Json.str(it, "channel"), "stage", Json.str(it, "stage"),
                        "url", Json.str(it, "url"), "at", Json.dbl(it, "applied_at", Util.now()));
            }
            if (appliedNow) {
                log("Marked applied: " + Json.str(it, "title") + (Json.str(it, "company").isEmpty() ? "" : " at "
                        + Json.str(it, "company")) + ("linkedin".equals(Json.str(it, "channel")) ? " (LinkedIn)" : " (company site)"));
            } else if (stageNow) {
                log("Stage: " + Json.str(it, "title") + " -> " + Json.str(it, "stage"));
            } else if ("skipped".equals(Json.str(it, "state")) && !"skipped".equals(was)) {
                log("Skipped: " + Json.str(it, "title"));
            }
            it = new LinkedHashMap<String, Object>(it);
        }
        if (report != null) {
            engine.reporter.track(report);
        }
        return it;
    }

    /** Jobs from your account (another phone, Google Drive): added, replaced or deleted by ID; nothing is reported. */
    public synchronized void merge(Map<String, Map<String, Object>> upserts, Collection<String> deletes) {
        for (String id : deletes) {
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(Json.str(items.get(i), "id"))) {
                    items.remove(i);
                    break;
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : upserts.entrySet()) {
            Map<String, Object> it = new LinkedHashMap<String, Object>(e.getValue());
            it.put("id", e.getKey());
            int at = -1;
            for (int i = 0; i < items.size(); i++) {
                if (e.getKey().equals(Json.str(items.get(i), "id"))) {
                    at = i;
                }
            }
            if (at >= 0) {
                items.set(at, it);
            } else {
                items.add(it);
            }
        }
        Collections.sort(items, new Comparator<Map<String, Object>>() {   // newest added first, as add() keeps them
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(Json.dbl(b, "added", 0), Json.dbl(a, "added", 0));
            }
        });
        save();
    }

    /** Signed in with another account: these jobs belonged to the one before. */
    public synchronized void clear() {
        items.clear();
        save();
    }

    public synchronized boolean remove(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(Json.str(items.get(i), "id"))) {
                items.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ searches and counts
    /** One LinkedIn search per job title of yours: Easy Apply only, newest first, posted within your setting. */
    public List<Object> searchLinks(Map<String, String> cfg) {
        List<Object> out = new ArrayList<Object>();
        String location = cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION").trim();
        for (String kw : Util.splitList(cfg.get("KEYWORDS"))) {
            out.add(Json.obj("title", kw, "url", Bot.searchUrl(kw, location, cfg, 0)));
        }
        return out;
    }

    static double midnight(double now, int daysBack) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis((long) (now * 1000));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, -daysBack);
        return c.getTimeInMillis() / 1000.0;
    }

    public synchronized Map<String, Object> stats(double now) {
        int waiting = 0;
        int today = 0;
        int week = 0;
        double d0 = midnight(now, 0);
        double w0 = midnight(now, 6);
        for (Map<String, Object> it : items) {
            if ("new".equals(Json.str(it, "state"))) {
                waiting++;
            }
            double at = Json.dbl(it, "applied_at", 0);
            if ("applied".equals(Json.str(it, "state")) && at >= d0) {
                today++;
            }
            if ("applied".equals(Json.str(it, "state")) && at >= w0) {
                week++;
            }
        }
        return Json.obj("waiting", waiting, "today", today, "week", week);
    }
}
