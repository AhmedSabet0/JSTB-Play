package com.autoapply.bot.core;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.autoapply.bot.core.Util.log;
import static com.autoapply.bot.core.Util.pause;
import static com.autoapply.bot.core.Util.sleep;

/** The bot: search, score, apply at a safe pace, ask you new questions, record everything. */
public final class Bot implements LinkedIn.Resolver, LinkedIn.Hooks {
    public static class TooManyErrors extends RuntimeException {
        public TooManyErrors() {
            super("Too many errors in a row");
        }
    }

    /** Run options, as the dashboard passes them. */
    public static final class Options {
        public boolean dryRun;
        public boolean noSubmit;
        public boolean once;
        public boolean retryFailed;
        /** Only send the applications you said yes to (Apply in the notification while the bot was not running). */
        public boolean sendOnly;

        public Map<String, Object> toMap() {
            return Json.obj("dry_run", dryRun, "no_submit", noSubmit, "once", once, "retry_failed", retryFailed,
                    "send_only", sendOnly);
        }
    }

    // ------------------------------------------------------------------ search URL
    static String code(String value, String[][] table) {
        List<String> out = new ArrayList<String>();
        for (String part : String.valueOf(value == null ? "" : value).toLowerCase(Locale.ROOT).split("[,;\\s]+")) {
            for (String[] kv : table) {
                if (kv[0].equals(part.trim()) && !out.contains(kv[1])) {
                    out.add(kv[1]);
                }
            }
        }
        return Util.join(",", out);
    }

    static final String[][] DATE_CODES = {{"day", "r86400"}, {"24h", "r86400"}, {"week", "r604800"}, {"month", "r2592000"},
        {"any", ""}};
    static final String[][] WORK_CODES = {{"onsite", "1"}, {"on-site", "1"}, {"remote", "2"}, {"hybrid", "3"}};
    static final String[][] EXP_CODES = {{"internship", "1"}, {"entry", "2"}, {"associate", "3"}, {"mid-senior", "4"},
        {"mid", "4"}, {"senior", "4"}, {"director", "5"}, {"executive", "6"}};

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static String searchUrl(String keyword, String location, Map<String, String> cfg, int pageNo) {
        StringBuilder q = new StringBuilder("keywords=" + enc(keyword) + "&f_AL=true&sortBy=DD");
        if (location != null && !location.isEmpty()) {
            q.append("&location=").append(enc(location));
        }
        String dp = cfg.get("DATE_POSTED") == null || cfg.get("DATE_POSTED").trim().isEmpty() ? "week"
                : cfg.get("DATE_POSTED").trim().toLowerCase(Locale.ROOT);
        String date = "r604800";
        for (String[] kv : DATE_CODES) {
            if (kv[0].equals(dp)) {
                date = kv[1];
            }
        }
        if (!date.isEmpty()) {
            q.append("&f_TPR=").append(date);
        }
        String work = code(cfg.get("WORK_TYPE"), WORK_CODES);
        if (!work.isEmpty()) {
            q.append("&f_WT=").append(enc(work));
        }
        String exp = code(cfg.get("EXPERIENCE"), EXP_CODES);
        if (!exp.isEmpty()) {
            q.append("&f_E=").append(enc(exp));
        }
        if (pageNo > 0) {
            q.append("&start=").append(pageNo * 25);
        }
        return "https://www.linkedin.com/jobs/search/?" + q;
    }

    // ------------------------------------------------------------------ pacing
    /** {last hour, today} */
    public static int[] appliedCounts(Map<String, Object> state, double now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis((long) (now * 1000));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        double midnight = c.getTimeInMillis() / 1000.0;
        int hour = 0;
        int today = 0;
        for (Object o : Json.arr(state.get("submit_times"))) {
            double t = ((Number) o).doubleValue();
            if (now - t < 3600) {
                hour++;
            }
            if (t >= midnight) {
                today++;
            }
        }
        return new int[]{hour, today};
    }

    public static double tomorrowMorning(double now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis((long) (now * 1000));
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, Util.RANDOM.nextInt(45));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000.0;
    }

    /** Seconds to wait before the next application, and why ("slot" or "day"). */
    public static Object[] slotWait(Map<String, Object> state, int perHour, int perDay, double now) {
        int[] counts = appliedCounts(state, now);
        if (counts[1] >= perDay) {
            return new Object[]{tomorrowMorning(now) - now, "day"};
        }
        double until = Json.dbl(state, "next_slot_at", 0);
        if (counts[0] >= perHour) {
            double first = Double.MAX_VALUE;
            for (Object o : Json.arr(state.get("submit_times"))) {
                double t = ((Number) o).doubleValue();
                if (now - t < 3600) {
                    first = Math.min(first, t);
                }
            }
            until = Math.max(until, first + 3600 + Util.uniform(5, 60));
        }
        return new Object[]{Math.max(0.0, until - now), "slot"};
    }

    public static void recordSubmit(Map<String, Object> state, int perHour, double now) {
        List<Object> times = new ArrayList<Object>();
        for (Object o : Json.arr(state.get("submit_times"))) {
            if (now - ((Number) o).doubleValue() < 48 * 3600) {
                times.add(o);
            }
        }
        times.add(now);
        state.put("submit_times", times);
        state.put("next_slot_at", now + 3600.0 / Math.max(1, perHour) * Util.uniform(0.85, 1.15));
    }

    // ------------------------------------------------------------------ the bot
    final Engine engine;
    final Map<String, String> cfg;
    final Options args;
    final Store store;
    final Ai ai;
    public final LinkedIn li;
    final List<String> keywords;
    final String location;
    final int perHour;
    final int perDay;
    final int minScore;
    final int pages;
    final int everyMin;
    final List<String> skipCompanies = new ArrayList<String>();
    final List<String> skipWords = new ArrayList<String>();
    int errorStreak = 0;
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    int roundApplied = 0;
    final Session session;
    final boolean noSubmit;
    final String cvText;
    final List<String> retryStatuses = new ArrayList<String>();

    public Bot(Engine engine, Map<String, String> cfg, Options args, Ai ai, LinkedIn li, List<String> keywords, String location,
               Session session) {
        this.engine = engine;
        this.cfg = cfg;
        this.args = args;
        this.store = engine.store;
        this.ai = ai;
        this.li = li;
        this.keywords = keywords;
        this.location = location;
        this.perHour = Config.cfgInt(cfg, "PER_HOUR", 1, 30);
        this.perDay = Config.cfgInt(cfg, "PER_DAY", 1, 300);
        this.minScore = Config.cfgInt(cfg, "MIN_SCORE", 0, 10);
        this.pages = Config.cfgInt(cfg, "PAGES_PER_KEYWORD", 1, 10);
        this.everyMin = Config.cfgInt(cfg, "SEARCH_EVERY_MIN", 5, 720);
        for (String c : Util.splitList(cfg.get("SKIP_COMPANIES"))) {
            skipCompanies.add(c.toLowerCase(Locale.ROOT));
        }
        for (String w : Util.splitList(cfg.get("SKIP_TITLE_WORDS"))) {
            skipWords.add(w.toLowerCase(Locale.ROOT));
        }
        this.session = session;
        this.noSubmit = args.noSubmit;
        this.cvText = engine.config.cvText();
        if (li != null) {
            li.hooks = this;
        }
        // Matches from a dry run / test run are tried again in a normal run, and jobs that waited for your answer
        // are tried again once you answered.
        if (!args.dryRun) {
            retryStatuses.add("answered");
            if (!noSubmit) {
                retryStatuses.add("dry_run_match");
            }
        }
    }

    /** Ask before sending: each form is filled up to Submit, then waits for your yes (Apply) while the bot goes on.
     *  Read for each job, so flipping it in Settings counts right away. */
    boolean approveFirst() {
        return !args.dryRun && !noSubmit && !"0".equals(engine.config.load().get("APPROVE_FIRST"));
    }

    boolean seen(String jobId) {
        return store.seen(jobId, retryStatuses);
    }

    /** The fewest forms that may wait for your yes before the bot waits for you (else an hour's worth, at your pace). */
    public static int readyMin = 3;

    int readyCap() {
        return Math.max(readyMin, hourCap());
    }

    /** Before the next job: when as many forms wait for your yes as your pace sends in an hour, the bot waits for you
     *  (and sends what you say yes to meanwhile). False in a one-round run, which ends its round there. */
    boolean roomToPrepare() {
        if (!approveFirst() || store.idsWithStatus("ready").size() < readyCap()) {
            return true;
        }
        if (args.once) {
            log(waitingLine() + " No more are prepared in this run.");
            return false;
        }
        log(waitingLine() + " Preparing more after you answer some.");
        while (approveFirst() && store.idsWithStatus("ready").size() >= readyCap()) {
            int n = store.idsWithStatus("ready").size();
            Util.setStatus("state", "waiting", "message", "Waiting for your yes: " + n + (n == 1 ? " application is" : " applications are")
                    + " ready to send", "job", null, "next_slot_at", 0);
            sleep(10);
            sendApproved();
        }
        return true;
    }

    String waitingLine() {
        int n = store.idsWithStatus("ready").size();
        return n + (n == 1 ? " application waits" : " applications wait") + " for your yes.";
    }

    // ------------------------------------------------------------------ loop
    public void run() {
        if (args.sendOnly) {
            sendApproved();
            return;
        }
        while (true) {
            roundApplied = 0;
            cycle();
            sendApproved();
            if (args.once) {
                return;
            }
            double wait = everyMin * 60 * Util.uniform(0.9, 1.2);
            double at = Util.now() + wait;
            log("Round finished (" + roundApplied + " applied). Searching again at " + Util.fmtWhen(at) + ".");
            Util.setStatus("state", "waiting", "message", "Round finished. Searching again at " + Util.fmtWhen(at), "job", null,
                    "next_slot_at", at);
            double left = wait;
            while (left > 0) {                // a yes you give meanwhile is sent right away
                double chunk = Math.min(10, left);
                engine.betweenRounds = true;
                try {
                    sleep(chunk);
                } finally {
                    engine.betweenRounds = false;
                }
                left -= chunk;
                if (!store.idsWithStatus("approved").isEmpty()) {
                    sendApproved();
                    Util.setStatus("state", "waiting", "message", "Round finished. Searching again at " + Util.fmtWhen(at),
                            "job", null, "next_slot_at", at);
                }
            }
            Util.setStatus("next_slot_at", 0);
        }
    }

    /** The applications you said yes to (in the notification or on Home): sent now, at your pace. */
    void sendApproved() {
        if (args.dryRun || noSubmit) {
            return;
        }
        for (String jobId : store.idsWithStatus("approved")) {
            log("You said yes to this application. Sending it: " + String.format(LinkedIn.JOB_URL, jobId));
            handle(jobId);
            engine.readyChanged();
            pause(3, 6);
        }
    }

    void cycle() {
        retryAnswered();
        sendApproved();
        for (String keyword : keywords) {
            for (int pageNo = 0; pageNo < pages; pageNo++) {
                String where = location.isEmpty() ? "" : " in " + location;
                log("Searching \"" + keyword + "\"" + where + " (page " + (pageNo + 1) + ")");
                Util.setStatus("state", "searching", "message", "Searching \"" + keyword + "\"" + where + ", page " + (pageNo + 1),
                        "job", null);
                List<String> ids = li.search(searchUrl(keyword, location, cfg, pageNo));
                List<String> fresh = new ArrayList<String>();
                for (String id : ids) {
                    if (!seen(id)) {
                        fresh.add(id);
                    }
                }
                log("  " + ids.size() + " jobs found, " + fresh.size() + " new");
                if (ids.isEmpty()) {
                    break;
                }
                for (String jobId : fresh) {
                    if (seen(jobId)) {
                        continue;
                    }
                    if (!roomToPrepare()) {
                        return;                 // the rest are looked at in the next run
                    }
                    handle(jobId);
                    pause(5, 12);
                    retryAnswered();
                    sendApproved();
                }
                pause(4, 9);
            }
        }
    }

    /** Jobs that waited for your answer to a question: once you answered it, try them again. */
    void retryAnswered() {
        if (args.dryRun) {
            return;
        }
        for (String jobId : store.idsWithStatus("answered")) {
            log("You answered the question this job waited for. Trying it again: " + String.format(LinkedIn.JOB_URL, jobId));
            handle(jobId);
            pause(5, 12);
        }
    }

    void handle(String jobId) {
        job = Json.obj("id", jobId);
        try {
            process(jobId);
        } catch (Util.BrowserClosed e) {
            throw e;
        } catch (Util.StopRequested e) {
            throw e;
        } catch (Ai.FatalAIError e) {
            throw e;
        } catch (TooManyErrors e) {
            throw e;
        } catch (RuntimeException e) {
            String msg = Util.shortError(e);
            log("  Error: " + msg);
            li.dump("error-" + jobId, "error", 5);
            try {
                li.discard();
            } catch (Util.BrowserClosed x) {
                throw x;
            } catch (RuntimeException x) {
                // the form may already be gone
            }
            if (li.page.isClosed()) {
                reopenPage();
            }
            record(jobId, "error", job.get("score"), msg, null, true);
        }
        if (errorStreak >= 10) {
            throw new TooManyErrors();
        }
        if (errorStreak > 0 && errorStreak % 5 == 0) {
            log("  5 errors in a row. Pausing 10 minutes before trying again...");
            sleep(600);
        }
    }

    /** Open a fresh page if the bot's page was closed. Returns false if that is not possible. */
    public boolean reopenPage() {
        try {
            engine.platform.closePage(li.page);
        } catch (RuntimeException e) {
            // already gone
        }
        try {
            li.page = engine.platform.newPage();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ one job
    void process(String jobId) {
        String url = String.format(LinkedIn.JOB_URL, jobId);
        Util.setStatus("state", "working", "message", "Opening the job", "job", Json.obj("id", jobId, "url", url));
        Map<String, Object> info = li.openJob(jobId);
        job.putAll(info);
        String title = Json.str(info, "title");
        String company = Json.str(info, "company");
        String state = info.get("state") == null ? null : Json.str(info, "state");
        log((title.isEmpty() ? "Job " + jobId : title) + " @ " + (company.isEmpty() ? "?" : company) + "   " + url);
        Util.setStatus("message", "Reading the job post", "job", Json.obj("id", jobId, "title", title, "company", company, "url", url));
        if (title.isEmpty() && state == null) {
            record(jobId, "error", null, "Could not read the job page", null, false);
            return;
        }
        String lc = company.toLowerCase(Locale.ROOT);
        String lt = title.toLowerCase(Locale.ROOT);
        for (String c : skipCompanies) {
            if (lc.contains(c)) {
                record(jobId, "blacklisted", null, "Matches a skip filter in Settings", null, false);
                return;
            }
        }
        for (String w : skipWords) {
            if (lt.contains(w)) {
                record(jobId, "blacklisted", null, "Matches a skip filter in Settings", null, false);
                return;
            }
        }
        if (!"easy".equals(state)) {
            record(jobId, state == null ? "no_easy_apply" : state, null, "", null, false);
            return;
        }

        Map<String, Object> was = store.job(jobId);
        boolean approved = was != null && "approved".equals(Json.str(was, "status"));
        int score;
        String reason;
        if (approved) {                 // you said yes to it: no second scoring
            score = (int) Json.num(was, "score", minScore);
            reason = Json.str(was, "reason");
            job.put("score", score);
            log("  Sending what you said yes to (match " + score + "/10)");
        } else {
            pause(2, 5);  // read the post like a person would
            Util.setStatus("message", "AI is checking how well the job matches your CV");
            Object[] sc = ai.score(title, company, Json.str(info, "description"));
            score = (Integer) sc[0];
            reason = (String) sc[1];
            job.put("score", score);
            log("  Match " + score + "/10: " + reason);
            if (score < minScore) {
                record(jobId, "skipped_low_score", score, reason, null, false);
                return;
            }
            if (args.dryRun) {
                record(jobId, "dry_run_match", score, reason, null, false);
                return;
            }
        }

        boolean prepare = !approved && approveFirst();   // fill it up to Submit, then ask you
        if (!noSubmit && !prepare && waitForSlot()) {
            Map<String, Object> again = li.openJob(jobId);
            if (!"easy".equals(again.get("state"))) {
                record(jobId, again.get("state") == null ? "no_easy_apply" : Json.str(again, "state"), score, reason, null, false);
                return;
            }
        }

        Util.setStatus("state", "working", "message", (prepare ? "Preparing" : "Filling") + " the application (match " + score + "/10)");
        LinkedIn.Outcome out;
        try {
            out = li.apply(jobId, this, noSubmit || prepare);
            for (int k = 0; k < 2 && "daily_limit".equals(out.status); k++) {
                double at = tomorrowMorning(Util.now());
                log("  LinkedIn says the daily Easy Apply limit is reached. Waiting until " + Util.fmtWhen(at) + ".");
                Util.setStatus("state", "waiting", "message", "LinkedIn's daily limit. Continuing " + Util.fmtWhen(at),
                        "next_slot_at", at);
                sleep(at - Util.now());
                Map<String, Object> again = li.openJob(jobId);
                if (!"easy".equals(again.get("state"))) {
                    record(jobId, again.get("state") == null ? "no_easy_apply" : Json.str(again, "state"), score, reason, null, false);
                    return;
                }
                out = li.apply(jobId, this, noSubmit || prepare);
            }
        } catch (Questions.NeedsAnswer e) {
            li.discard();  // nothing is sent; the job is tried again after you answer
            if ("user_skipped".equals(e.status)) {
                log("  You skipped this job.");
            } else {
                log("  Skipped for now: no answer to \"" + Util.cut(e.question, 80) + "\". Answer it in Saved answers and "
                        + "the bot tries this job again.");
            }
            record(jobId, e.status, score, reason.isEmpty() ? e.reason : reason + " | " + e.reason, null, true);
            return;
        }
        String status = out.status;
        String note = out.note;
        if (prepare && "dry_run_match".equals(status)) {
            status = "ready";
            note = "";
        }
        if ("daily_limit".equals(status)) {
            status = "error";
            note = "LinkedIn daily limit";
        }
        if ("applied".equals(status)) {
            recordSubmit(store.state(), hourCap(), Util.now());
            store.saveState();
            roundApplied++;
            int[] counts = appliedCounts(store.stateCopy(), Util.now());
            log("  APPLIED  (" + counts[1] + " today, " + counts[0] + " in the last hour)");
        } else if ("ready".equals(status)) {
            log("  READY: filled up to Submit (" + out.qa.size() + " answers). It waits for your yes: Apply in the "
                    + "notification or on Home. Going on with the next jobs.");
        } else if ("dry_run_match".equals(status)) {
            log("  Form filled; stopped before Submit (test run). " + out.qa.size() + " answers given.");
        } else if ("stuck".equals(status)) {
            log("  Stuck: " + note + " (page saved for troubleshooting)");
            if (note.startsWith("Form error")) {
                log("  Tip: fix the answer in Saved answers (or add the fact in Settings > About me), then start again "
                        + "with 'Retry jobs that got stuck before'.");
            }
        } else if ("error".equals(status)) {
            log("  Error: " + note);
        }
        String full = reason;
        if (!note.isEmpty()) {
            full = full.isEmpty() ? note : full + " | " + note;
        }
        record(jobId, status, score, full, out.qa, true);
        if ("ready".equals(status)) {
            engine.readyChanged();      // the notification with Apply
        }
    }

    /** Your daily limit, or the admin's cap when that is lower. */
    int dayCap() {
        int cap = engine.account.maxPerDay();
        return cap > 0 ? Math.min(perDay, cap) : perDay;
    }

    /** Your hourly limit, or the admin's cap when that is lower. */
    int hourCap() {
        int cap = engine.account.maxPerHour();
        return cap > 0 ? Math.min(perHour, cap) : perHour;
    }

    /** Respect the hourly and daily limits. True if it waited long enough to reload the job. */
    boolean waitForSlot() {
        double now = Util.now();
        int perDay = dayCap();
        int perHour = hourCap();
        Object[] w = slotWait(store.stateCopy(), perHour, perDay, now);
        double wait = (Double) w[0];
        if (wait <= 0) {
            return false;
        }
        String message;
        if ("day".equals(w[1])) {
            int[] counts = appliedCounts(store.stateCopy(), now);
            log("  Daily limit reached (" + counts[1] + "/" + perDay + "). Waiting until " + Util.fmtWhen(now + wait) + ".");
            message = "Daily limit reached. Continuing " + Util.fmtWhen(now + wait);
        } else {
            log("  Next application slot at " + Util.fmtTime(now + wait) + " (in " + Util.fmtDuration(wait) + "; max " + perHour
                    + " per hour)");
            message = "Good match. Waiting for the next application slot (" + perHour + "/hour)";
        }
        Util.setStatus("state", "waiting", "message", message, "next_slot_at", now + wait);
        sleep(wait);
        Util.setStatus("state", "working", "next_slot_at", 0);
        return wait > 90;
    }

    void record(String jobId, String status, Object score, String reason, List<Object> qa, boolean quiet) {
        errorStreak = "error".equals(status) ? errorStreak + 1 : 0;
        if (!quiet) {
            if ("error".equals(status)) {
                log("  Error: " + reason);
            } else if (!"applied".equals(status) && !"stuck".equals(status)) {
                log("  Skipped: " + Store.label(status).toLowerCase(Locale.ROOT));
            }
        }
        store.add(jobId, status, Json.str(job, "title"), Json.str(job, "company"), score, reason, qa);
        session.add(jobId, status, Json.str(job, "title"), Json.str(job, "company"), score, reason);
    }

    // ------------------------------------------------------------------ form answers
    @Override
    public void remember(String question, Object value) {
        store.putAnswer(Util.normKey(question), value);
    }

    @Override
    public void forget(String question) {
        store.removeAnswer(Util.normKey(question));
    }

    /** Filling a form you have open yourself (a company's site): never wait for a question card. */
    public boolean neverAsk;

    /** New questions may wait for you: "Ask me", or "the AI answers what it is sure about". */
    @Override
    public boolean manual() {
        return !neverAsk && !"ai".equals(Questions.askModeOf(Questions.askMode));
    }

    /** "ask", "ai" or "unsure" (the AI answers when it is sure, else asks you). */
    String askMode() {
        return neverAsk ? "ai" : Questions.askModeOf(Questions.askMode);
    }

    /** How sure the AI must be (0-100) to answer by itself in the "unsure" mode. */
    public static int sureAt = 75;

    /** A line for the question card: why you are asked. */
    private String askNote = "";

    /** The job a form belongs to (used in the AI's answers). */
    public void setJob(String title, String company) {
        job = new LinkedHashMap<String, Object>();
        job.put("title", title == null ? "" : title);
        job.put("company", company == null ? "" : company);
    }

    @Override
    public void signInNeeded() {
        engine.platform.onSignInNeeded();
    }

    String jobLabel() {
        return (Json.str(job, "title") + " at " + Json.str(job, "company")).trim();
    }

    private static final Object AI_ANSWERS = new Object();

    @Override
    public Object resolve(String question, String kind, List<String> options, String hint, String placeholder, boolean noCache,
                          List<String> rejected, int maxlen, List<String> extra) {
        String key = Util.normKey(question);
        Object cached = noCache ? null : store.answer(key);
        if ("numeric".equals(kind) && cached != null && !String.valueOf(cached).trim().matches("\\d+(\\.\\d+)?")) {
            cached = null;  // e.g. "Less than 1 year" saved for a number question: ask again
        }
        if (cached != null) {
            if (options == null || options.isEmpty()) {
                return cached;
            }
            if ("multi".equals(kind)) {
                List<String> values = new ArrayList<String>();
                if (cached instanceof List) {
                    values.addAll(Json.strings(cached));
                } else {
                    for (String v : String.valueOf(cached).split("\\s*\\|\\s*")) {
                        values.add(v);
                    }
                }
                List<Object> picked = new ArrayList<Object>();
                for (String v : values) {
                    String m = Ai.matchOption(v, options);
                    if (m != null && !picked.contains(m)) {
                        picked.add(m);
                    }
                }
                if (!picked.isEmpty()) {
                    return picked;
                }
            } else {
                String hit = Ai.matchOption(cached, options);
                if (hit != null) {
                    return hit;
                }
            }
        }
        // Your setup answers (salary, notice period, years since graduating for skills in your CV...)
        Profile.Hit hit = engine.profile.answer(question, kind, options, cvText, Util.now());
        if (hit != null && !(rejected != null && rejected.contains(shown(hit.value)))) {
            log("    From your setup: " + Util.cut(question, 70) + " -> " + shown(hit.value)
                    + (hit.why.isEmpty() ? "" : "  (" + hit.why + ")"));
            return hit.value;
        }
        // Your info: a fact that answers this question, asked before in other words
        Object fact = fromFacts(question, kind, options, rejected);
        if (fact != null) {
            return fact;
        }
        String mode = askMode();
        Ai.Sure guess = null;
        if ("unsure".equals(mode)) {        // the AI answers what it is sure about; you the rest
            guess = ai.sureAnswer(question, kind, options, store.knownForAi(), jobLabel(), hint, placeholder, rejected, maxlen);
            if (guess.answer != null && guess.sure >= sureAt) {
                store.putAnswer(key, guess.answer);
                log("    AI answer (sure " + guess.sure + "%): " + Util.cut(question, 70) + " -> " + shown(guess.answer));
                return guess.answer;
            }
            log("    The AI is not sure (" + guess.sure + "%" + (guess.why.isEmpty() ? "" : ": " + Util.cut(guess.why, 60))
                    + "), so it asks you.");
            if (guess.answer instanceof String && !String.valueOf(guess.answer).isEmpty()) {
                List<String> more = extra == null ? new ArrayList<String>() : new ArrayList<String>(extra);
                if (!more.contains(guess.answer)) {
                    more.add((String) guess.answer);   // its guess is one of the suggestions
                }
                extra = more;
            }
            askNote = "The AI was not sure about this one" + (guess.why.isEmpty() ? "" : " (" + guess.why + ")")
                    + ". You answer once; the app remembers it.";
        }
        if ("ask".equals(mode) || "unsure".equals(mode)) {
            Object got;
            try {
                got = askUser(question, kind, options, hint, placeholder, rejected, maxlen, extra);
            } finally {
                askNote = "";
            }
            if (got != AI_ANSWERS) {
                return got;
            }
            if (guess != null && guess.answer != null) {   // you switched to AI answers meanwhile: its answer goes in
                store.putAnswer(key, guess.answer);
                log("    AI answer: " + Util.cut(question, 70) + " -> " + shown(guess.answer));
                return guess.answer;
            }
        }
        Object ans = ai.answer(question, kind, options, store.knownForAi(), jobLabel(), hint, placeholder, rejected, maxlen);
        if (ans == null) {
            log("    No answer for: " + Util.cut(question, 80));
            return null;
        }
        store.putAnswer(key, ans);
        log("    AI answer: " + Util.cut(question, 70) + " -> " + shown(ans));
        return ans;
    }

    /** Your info: the fact that answers this question when it was asked before in nearly the same words, or (through
     *  the AI) in other words. Null when none does. */
    Object fromFacts(String question, String kind, List<String> options, List<String> rejected) {
        List<Map<String, Object>> facts = store.factsCopy();
        if (facts.isEmpty()) {
            return null;
        }
        Map<String, Object> close = Facts.closeMatch(facts, question);
        if (close != null) {
            Object v = Facts.fit(close.get("value"), kind, options, rejected);
            if (v != null) {
                store.addAsked(Json.str(close, "id"), question);
                log("    From your info (" + Json.str(close, "category") + "): " + Util.cut(question, 70) + " -> " + shown(v));
                return v;
            }
        }
        Map<String, Object> m;
        try {
            m = ai.matchFact(question, kind, options, facts);
        } catch (Ai.FatalAIError e) {
            throw e;
        } catch (Util.StopRequested e) {
            throw e;
        } catch (RuntimeException e) {
            return null;  // the AI could not check: you or the AI answer as usual
        }
        if (m == null) {
            return null;
        }
        Map<String, Object> fact = null;
        for (Map<String, Object> f : facts) {
            if (Json.str(f, "id").equals(Json.str(m, "id"))) {
                fact = f;
            }
        }
        if (fact == null) {
            return null;
        }
        Object v = Facts.fit(m.get("answer") != null ? m.get("answer") : fact.get("value"), kind, options, rejected);
        if (v == null) {
            return null;
        }
        Object plain = Facts.fit(fact.get("value"), kind, options, null);
        if (plain != null && Facts.answerText(plain).equalsIgnoreCase(Facts.answerText(v))) {
            store.addAsked(Json.str(fact, "id"), question);  // the same answer: next time without the AI
        }
        log("    From your info (" + Json.str(fact, "category") + ", \"" + Util.cut(Json.str(fact, "label"), 50) + "\"): "
                + Util.cut(question, 60) + " -> " + shown(v));
        return v;
    }

    static String shown(Object v) {
        return v instanceof List ? Util.join(", ", Json.strings(v)) : String.valueOf(v);
    }

    /** Ask you (dashboard or notification) with AI suggestions. Returns the answer, AI_ANSWERS if you switched to AI
     *  answers meanwhile, or throws NeedsAnswer (skipped / no answer in time). */
    Object askUser(String question, String kind, List<String> options, String hint, String placeholder, List<String> rejectedIn,
                   int maxlen, List<String> extra) {
        String key = Util.normKey(question);
        List<String> rejected = new ArrayList<String>();
        if (rejectedIn != null) {
            for (String r : rejectedIn) {
                if (r != null && !r.isEmpty()) {
                    rejected.add(r);
                }
            }
        }
        String jobId = Json.str(job, "id");
        Map<String, Object> jobInfo = Json.obj("id", jobId, "title", Json.str(job, "title"), "company", Json.str(job, "company"),
                "url", jobId.isEmpty() ? "" : String.format(LinkedIn.JOB_URL, jobId));
        List<Object> sugg = new ArrayList<Object>();
        if (extra != null) {
            for (String x : extra) {
                if (x != null && !x.isEmpty() && !rejected.contains(x)) {
                    sugg.add(x);
                }
            }
        }
        Map<String, Object> item = Json.obj("key", key, "question", question, "kind", kind,
                "options", options == null ? new ArrayList<Object>() : new ArrayList<Object>(options), "hint", hint == null ? "" : hint,
                "rejected", new ArrayList<Object>(rejected), "maxlen", maxlen, "placeholder", placeholder == null ? "" : placeholder,
                "job", jobInfo, "suggestions", sugg, "similar", Questions.similarAnswers(store.answersCopy(), key, options, kind),
                "reason", "", "loading", true, "asked_at", Util.now(), "timeout_at", 0, "remember", !"textarea".equals(kind),
                "note", askNote);

        int minutes = Questions.askTimeoutMin;  // 0 = wait until you answer
        if (minutes > 0 && (engine.waiting.get(key) != null || Questions.DESK.away())) {
            minutes = 1;  // asked before without an answer, or you seem to be away: do not hold the job long
        }
        if (minutes > 0) {
            item.put("timeout_at", Util.now() + minutes * 60 * Util.speed);
        }
        String qid = Questions.DESK.post(item);
        Util.setStatus("state", "question", "message", "Answer the question above. The application form stays open until you do.");
        log("    Question for you: " + Util.cut(question, 150) + (hint == null || hint.isEmpty() ? "" : "  (LinkedIn said: " + Util.cut(hint, 80) + ")"));
        engine.platform.onQuestion(Questions.DESK.get());
        Map<String, Object> reply;
        Map<String, Object> last = item;
        try {
            Map<String, Object> sug;
            try {
                sug = ai.suggest(question, kind, options, store.knownForAi(), jobLabel(), hint, placeholder, rejected, maxlen);
            } catch (Ai.FatalAIError e) {
                throw e;
            } catch (Util.StopRequested e) {
                throw e;
            } catch (RuntimeException e) {
                sug = Json.obj("suggestions", new ArrayList<Object>(), "reason", "No AI suggestions this time (" + Util.shortError(e, 80) + ").");
            }
            List<Object> merged = new ArrayList<Object>();
            List<Object> all = new ArrayList<Object>(Json.arr(sug.get("suggestions")));
            all.addAll(sugg);
            for (Object v : all) {
                if (!merged.contains(v) && !rejected.contains(String.valueOf(v)) && merged.size() < 5) {
                    merged.add(v);
                }
            }
            Questions.DESK.update(qid, "suggestions", merged, "reason", Json.str(sug, "reason"), "loading", false);
            Map<String, Object> now = Questions.DESK.get();
            if (now != null) {
                engine.platform.onQuestion(now);
            }
            while (true) {
                reply = Questions.DESK.take(qid);
                if (reply != null) {
                    break;
                }
                if (!manual()) {
                    log("    New questions are answered by the AI now.");
                    return AI_ANSWERS;
                }
                double timeoutAt = Json.dbl(item, "timeout_at", 0);
                if (timeoutAt > 0 && Util.now() >= timeoutAt) {
                    reply = Json.obj("action", "timeout");
                    break;
                }
                Util.realSleep(0.5);
            }
            Map<String, Object> fin = Questions.DESK.get();
            if (fin != null) {
                last = fin;
            }
        } finally {
            Questions.DESK.clear(qid);
            engine.platform.onQuestionDone();
            Util.setStatus("state", "working", "message", "Filling the application");
        }

        String action = Json.str(reply, "action");
        if ("skip".equals(action)) {
            throw new Questions.NeedsAnswer(question, "user_skipped", "You skipped it at: " + question);
        }
        if ("timeout".equals(action)) {
            Questions.DESK.timedOut();
            engine.waiting.add(last, jobInfo);
            throw new Questions.NeedsAnswer(question, "needs_answer", "Waiting for your answer: " + question);
        }
        Object value = reply.get("value");
        boolean remember = Json.bool(reply, "remember");
        if (remember) {
            store.putAnswer(key, value, question, "you");  // becomes part of your info
            Map<String, Object> waitingItem = engine.waiting.pop(key);
            List<String> others = new ArrayList<String>();
            for (Object j : Json.arr(waitingItem == null ? null : waitingItem.get("jobs"))) {
                String id = Json.str(Json.map(j), "id");
                if (!id.equals(jobId)) {
                    others.add(id);
                }
            }
            if (store.markAnswered(others) > 0) {
                log("    " + others.size() + " earlier job(s) waited for this answer; they are tried again soon.");
            }
        }
        log("    Your answer: " + Util.cut(question, 70) + " -> " + shown(value) + (remember ? "" : " (not saved)"));
        return value;
    }
}
