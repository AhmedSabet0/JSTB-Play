package com.autoapply.bot.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The dashboard's API: the same endpoints as the PC version's app.py, answered in-process. */
public final class Api {
    public static final String[] SETTINGS_KEYS = {"KEYWORDS", "LOCATION", "DATE_POSTED", "WORK_TYPE", "EXPERIENCE",
        "PAGES_PER_KEYWORD", "SEARCH_EVERY_MIN", "PER_HOUR", "PER_DAY", "MIN_SCORE", "SKIP_COMPANIES", "SKIP_TITLE_WORDS",
        "NEW_QUESTIONS", "ASK_TIMEOUT_MIN", "UPDATE_SOURCE", "DAILY_GOAL", "AUTO_UPDATE", "APPROVE_FIRST", "SEARCH_EVERY_MIN"};

    /** Requests that change your data (it then goes to your account). */
    static final java.util.Set<String> CHANGES = new java.util.HashSet<String>(java.util.Arrays.asList("/api/settings",
            "/api/cv", "/api/profile", "/api/facts", "/api/setup", "/api/answers", "/api/answer", "/api/open-questions",
            "/api/ask-mode", "/api/tracker/stage", "/api/fill/learn"));

    private final Engine engine;
    private final java.io.File updateFile;
    private final java.io.File suggestFile;
    private volatile Map<String, Object> lastCheck;
    private volatile boolean checking = false;

    public Api(Engine engine) {
        this.engine = engine;
        this.updateFile = new java.io.File(engine.config.dir, "update_check.json");
        this.suggestFile = new java.io.File(engine.config.dir, "profile_suggest.json");
        Object saved = Json.tryParse(Util.readText(updateFile));
        this.lastCheck = saved instanceof Map ? Json.map(saved) : null;
    }

    /** Handle one request. `body` is null for GET. Returns JSON text; errors come back as {"ok": false, "error": ...}. */
    public String handle(String path, String body) {
        try {
            String p = path;
            String query = "";
            int qi = path.indexOf('?');
            if (qi >= 0) {
                p = path.substring(0, qi);
                query = path.substring(qi + 1);
            }
            Map<String, Object> b = body == null ? null : Json.map(Json.tryParse(body));
            if (!query.contains("bg=1")) {
                engine.sync.touch();        // the app is open: sync every so often
            }
            Object out = b == null ? get(p, query) : post(p, b);
            if (out == null) {
                return Json.stringify(Json.obj("ok", false, "error", "Unknown request " + p));
            }
            if (b != null && (CHANGES.contains(p) || (p.startsWith("/api/copilot/") && !p.endsWith("/open")))) {
                engine.sync.changed();      // your account gets it in a moment
            }
            return Json.stringify(out);
        } catch (IllegalArgumentException e) {
            return Json.stringify(Json.obj("ok", false, "error", e.getMessage()));
        } catch (RuntimeException e) {
            return Json.stringify(Json.obj("ok", false, "error", Util.shortError(e)));
        }
    }

    // ------------------------------------------------------------------ GET
    private Object get(String p, String query) {
        if (p.equals("/api/state")) {
            return state();
        }
        if (p.equals("/api/log")) {
            long since = 0;
            for (String kv : query.split("&")) {
                if (kv.startsWith("since=")) {
                    try {
                        since = Long.parseLong(kv.substring(6));
                    } catch (NumberFormatException e) {
                        since = 0;
                    }
                }
            }
            return Util.logSince(since);
        }
        if (p.equals("/api/jobs")) {
            return Json.obj("jobs", engine.store.rows(1500));
        }
        if (p.equals("/api/settings")) {
            return settings();
        }
        if (p.equals("/api/answers")) {
            return Json.obj("answers", engine.store.answersCopy());
        }
        if (p.equals("/api/cv")) {
            String text = engine.config.cvText();
            Map<String, String> cfg = engine.config.load();
            java.io.File cv = engine.config.cvFile(cfg);
            String name = cfg.get("CV_NAME");
            return Json.obj("ok", true, "has_cv", cv != null && cv.isFile() && cv.length() > 0,
                    "name", cv == null ? "" : (name == null || name.isEmpty() ? cv.getName() : name),
                    "chars", text.length(),
                    "preview", Util.cut(text, 1000),
                    "text", text);
        }
        if (p.equals("/api/update")) {
            return updateInfo();
        }
        if (p.equals("/api/profile")) {
            return profileInfo();
        }
        if (p.equals("/api/facts")) {
            return factsInfo();
        }
        if (p.equals("/api/copilot")) {
            Map<String, String> cfg = engine.config.load();
            engine.feed.maybeRun(false);   // about every hour: new jobs from LinkedIn and Wuzzuf
            return Json.obj("items", engine.copilot.list(), "feed", engine.feed.status(), "search_links", engine.copilot.searchLinks(cfg),
                    "stats", engine.copilot.stats(Util.now()), "location", cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION"));
        }
        if (p.equals("/api/tracker")) {
            return tracker();
        }
        if (p.equals("/api/ready")) {
            return Json.obj("ok", true, "items", engine.ready());
        }
        if (p.equals("/api/sync")) {
            return Json.obj("ok", true, "sync", engine.sync.status(), "drive", engine.sync.drive(query.contains("fresh=1")));
        }
        if (p.equals("/api/open-questions")) {
            List<Map<String, Object>> items = engine.waiting.all();
            Collections.sort(items, new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> a, Map<String, Object> b) {
                    return Double.compare(Json.dbl(b, "updated", 0), Json.dbl(a, "updated", 0));
                }
            });
            return Json.obj("items", items);
        }
        return null;
    }

    public Map<String, Object> state() {
        Map<String, String> cfg = engine.config.load();
        boolean running = engine.running();
        int[] counts = Bot.appliedCounts(engine.store.stateCopy(), Util.now());
        Session s = engine.session;
        Bot.Options opts = engine.options();
        Map<String, Object> setup = setupStatus(cfg);
        int perDay = Config.cfgInt(cfg, "PER_DAY", 1, 300);
        int cap = engine.account.maxPerDay();
        return Json.obj(
                "running", running,
                "options", opts == null ? null : opts.toMap(),
                "status", Util.getStatus(),
                "now", Util.now(),
                "stats", Json.obj("today", counts[1], "hour", counts[0], "total", engine.store.count("applied"),
                        "week", engine.store.countSince("applied", Util.now() - 7 * 86400),
                        "per_hour", Config.cfgInt(cfg, "PER_HOUR", 1, 30), "per_day", cap > 0 ? Math.min(cap, perDay) : perDay,
                        "admin_cap", cap, "goal", Config.cfgInt(cfg, "DAILY_GOAL", 1, 200),
                        "min_score", Config.cfgInt(cfg, "MIN_SCORE", 0, 10)),
                "setup", setup,
                "session", s == null ? null : s.summary(),
                "last_session", Json.tryParse(Util.readText(engine.summaryFile)),
                "debug_zip", engine.debugZip.isFile(),
                "question", running ? Questions.DESK.get() : null,
                "open_questions", engine.waiting.count(),
                "facts", engine.store.factCount(),
                "ask_mode", running ? Questions.askModeOf(Questions.askMode) : Questions.askModeOf(cfg.get("NEW_QUESTIONS")),
                "ask_timeout_min", running ? Questions.askTimeoutMin : Config.cfgInt(cfg, "ASK_TIMEOUT_MIN", 0, 1440),
                "permissions", engine.platform.permissions(),
                "update", updateInfo(),
                "recent", engine.store.recent("applied", 0, 6),
                "account", engine.account.info(),
                "build", engine.platform.buildInfo(),
                "auto_apply", autoApply(),
                "copilot", engine.copilot.stats(Util.now()),
                "feed", engine.feed.status(),
                "ready", engine.store.idsWithStatus("ready").size(),
                "approved", engine.store.idsWithStatus("approved").size(),
                "sync", engine.sync.status(),
                "platform", "android");
    }

    // ------------------------------------------------------------------ app updates
    /** The development build updates itself from GitHub; the Google Play build is updated by Google Play. */
    boolean selfUpdates() {
        return Boolean.TRUE.equals(engine.platform.buildInfo().get("self_update"));
    }

    /** Settings' update source, or the repo the build was made for. */
    private String updateSource() {
        String fromBuild = Json.str(engine.platform.buildInfo(), "update_repo");
        return Updater.repoOf(engine.config.load().get("UPDATE_SOURCE"), fromBuild);
    }

    boolean autoApply() {
        return false;
    }

    public Map<String, Object> updateInfo() {
        Map<String, Object> current = engine.platform.appVersion();
        if (!selfUpdates()) {  // the Google Play build: Play updates it
            return Json.obj("current", current, "disabled", true);
        }
        Map<String, Object> last = lastCheck;
        if (last != null && Json.bool(last, "available")
                && Updater.compare(Json.str(last, "version"), Json.str(current, "name")) <= 0) {
            last = null;  // already installed
        }
        return Json.obj("current", current, "checking", checking, "last", last, "download", engine.platform.updateState(),
                "source", updateSource());
    }

    /** Check in the background (checks by themselves run at most every 5 minutes: GitHub allows 60 an hour). */
    void startUpdateCheck(final boolean force) {
        Map<String, Object> last = lastCheck;
        if (!selfUpdates() || checking) {
            return;
        }
        if (!force && last != null && Util.now() - Json.dbl(last, "checked_at", 0) < 5 * 60) {
            autoInstall(last);  // checked lately: install what it found, if that was not tried yet
            return;
        }
        checking = true;
        final String source = updateSource();
        final String current = Json.str(engine.platform.appVersion(), "name");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> r = Updater.check(source, current);
                    lastCheck = r;
                    Util.writeText(updateFile, Json.pretty(r));
                    if (!force) {
                        autoInstall(r);
                    }
                } finally {
                    checking = false;
                }
            }
        }, "update-check").start();
    }

    private volatile String autoTried = "";

    boolean autoUpdates() {
        return !"0".equals(engine.config.load().get("AUTO_UPDATE"));
    }

    private void autoInstall(Map<String, Object> r) {
        String v = Json.str(r, "version");
        if (!Json.bool(r, "available") || v.isEmpty() || v.equals(autoTried) || !autoUpdates()
                || Updater.compare(v, Json.str(engine.platform.appVersion(), "name")) <= 0) {
            return;
        }
        autoTried = v;
        Util.log("Version " + v + " is out: downloading it (it installs when you open the app)");
        engine.platform.installUpdate(Json.str(r, "url"), v, true);
    }

    private Map<String, Object> settings() {
        Map<String, String> cfg = engine.config.load();
        Map<String, Object> data = Json.obj();
        for (String k : SETTINGS_KEYS) {
            data.put(k, cfg.get(k) == null ? "" : cfg.get(k));
        }
        String about = Util.readText(engine.config.aboutFile);
        data.put("ABOUT_ME", about == null ? "" : about);
        data.put("setup", setupStatus(cfg));
        return data;
    }

    /** What is set up: your account, CV, LinkedIn sign-in, setup answers, job titles, and whether you finished the setup. */
    private Map<String, Object> setupStatus(Map<String, String> cfg) {
        Map<String, Object> setup = engine.config.status(cfg);
        setup.put("account", engine.account.signedIn());
        setup.put("pro", engine.account.pro());
        setup.put("signed_in", engine.platform.signedIn());
        setup.put("profile_done", Json.bool(engine.profile.load(), "done"));
        setup.put("has_search", !Util.splitList(cfg.get("KEYWORDS")).isEmpty());
        setup.put("location", cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION"));
        setup.put("setup_done", "1".equals(cfg.get("SETUP_DONE")));
        return setup;
    }

    // ------------------------------------------------------------------ setup answers
    private Map<String, Object> profileInfo() {
        Map<String, Object> prof = engine.profile.load();
        Map<String, Object> cached = Json.map(Json.tryParse(Util.readText(suggestFile)));
        boolean fresh = Json.num(cached, "cv_chars", -1) == engine.config.cvText().length();
        return Json.obj("profile", prof, "years", Profile.yearsSince(Json.str(prof, "graduation_year"), Util.now()),
                "suggest", fresh ? cached.get("data") : null, "notes", engine.notesForAi(),
                "currencies", java.util.Arrays.asList(Profile.CURRENCIES), "english", java.util.Arrays.asList(Profile.ENGLISH),
                "military", java.util.Arrays.asList(Profile.MILITARY));
    }

    /** Older answers (and the AI's) that your main answers now cover are dropped, so the main answers are used for
     *  them. Facts you entered or answered yourself stay. */
    private int dropCoveredAnswers() {
        String cv = engine.config.cvText();
        int dropped = 0;
        for (Map<String, Object> f : engine.store.factsCopy()) {
            if ("you".equals(Json.str(f, "source"))) {
                continue;
            }
            List<String> texts = Json.strings(f.get("asked"));
            texts.add(Json.str(f, "label"));
            for (String t : texts) {
                if (engine.profile.answer(t, "text", null, cv, Util.now()) != null) {
                    if (engine.store.deleteFact(Json.str(f, "id"))) {
                        dropped++;
                    }
                    break;
                }
            }
        }
        return dropped;
    }

    // ------------------------------------------------------------------ your info
    private Map<String, Object> factsInfo() {
        List<Map<String, Object>> facts = engine.store.factsCopy();
        final List<String> order = java.util.Arrays.asList(Facts.CATEGORIES);
        Collections.sort(facts, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int c = order.indexOf(Json.str(a, "category")) - order.indexOf(Json.str(b, "category"));
                return c != 0 ? c : Json.str(a, "label").compareToIgnoreCase(Json.str(b, "label"));
            }
        });
        return Json.obj("categories", order, "facts", facts, "count", facts.size());
    }

    private Object changeFacts(Map<String, Object> b) {
        String op = Json.str(b, "op");
        if (op.equals("add")) {
            String label = Json.str(b, "label").trim();
            Object value = b.get("value");
            if (label.isEmpty() || value == null || Facts.answerText(value).trim().isEmpty()) {
                throw new IllegalArgumentException("Type the question (or the skill) and the answer.");
            }
            String category = Json.str(b, "category").isEmpty() ? Facts.categorize(label) : Json.str(b, "category");
            Map<String, Object> f = engine.store.addFact(category, label, value instanceof String ? ((String) value).trim() : value);
            Map<String, Object> out = factsInfo();
            out.put("ok", true);
            out.put("fact", f);
            return out;
        }
        if (op.equals("update")) {
            Map<String, Object> changes = Json.obj();
            for (String k : new String[]{"value", "label", "category"}) {
                if (b.containsKey(k)) {
                    changes.put(k, b.get(k) instanceof String ? ((String) b.get(k)).trim() : b.get(k));
                }
            }
            if (changes.containsKey("value") && Facts.answerText(changes.get("value")).trim().isEmpty()) {
                throw new IllegalArgumentException("An answer cannot be empty. Delete the fact instead.");
            }
            if (engine.store.updateFact(Json.str(b, "id"), changes) == null) {
                throw new IllegalArgumentException("This fact is not there any more.");
            }
        } else if (op.equals("delete")) {
            engine.store.deleteFact(Json.str(b, "id"));
        } else {
            throw new IllegalArgumentException("Unknown change: " + op);
        }
        Map<String, Object> out = factsInfo();
        out.put("ok", true);
        return out;
    }

    /** Read phone, city, graduation year, English level and job titles from the CV (AI, with a simple fallback). */
    private Object suggestProfile(boolean force) {
        String cv = engine.config.cvText();
        if (cv.length() < 150) {
            throw new IllegalArgumentException("Add your CV first.");
        }
        Map<String, Object> cached = Json.map(Json.tryParse(Util.readText(suggestFile)));
        if (!force && Json.num(cached, "cv_chars", -1) == cv.length() && cached.get("data") instanceof Map) {
            return Json.obj("ok", true, "suggest", cached.get("data"));
        }
        Map<String, Object> data;
        String problem = "";
        if (!engine.account.signedIn()) {
            data = Profile.fromCvText(cv);
            problem = "Sign in to your account so the AI can read more from your CV.";
        } else {
            try {
                data = engine.newAi(cv, "", "setup").profileFromCv();
            } catch (Ai.FatalAIError e) {
                data = Profile.fromCvText(cv);
                problem = e.getMessage();
            } catch (RuntimeException e) {
                data = Profile.fromCvText(cv);
                problem = "Could not reach the AI (" + Util.shortError(e, 80) + ").";
            }
            Map<String, Object> simple = Profile.fromCvText(cv);
            for (String k : new String[]{"phone", "graduation_year"}) {
                if (Json.str(data, k).isEmpty()) {
                    data.put(k, simple.get(k));
                }
            }
        }
        if (problem.isEmpty()) {
            Util.writeText(suggestFile, Json.pretty(Json.obj("cv_chars", cv.length(), "at", Util.now(), "data", data)));
        }
        return Json.obj("ok", true, "suggest", data, "problem", problem);
    }

    // ------------------------------------------------------------------ POST
    private Object post(String p, Map<String, Object> b) {
        if (p.equals("/api/start")) {
            if (!autoApply()) {
                throw new IllegalArgumentException("This version applies with you: open Jobs and apply with the app's help.");
            }
            if (engine.account.updateRequired()) {
                throw new IllegalArgumentException("Update the app first: this version is too old.");
            }
            Bot.Options o = new Bot.Options();
            String mode = Json.str(b, "mode");
            o.dryRun = "dry".equals(mode);
            o.noSubmit = "test".equals(mode);
            o.once = Json.bool(b, "once");
            o.retryFailed = Json.bool(b, "retry");
            String err = engine.start(o);
            return Json.obj("ok", err.isEmpty(), "error", err);
        }
        if (p.equals("/api/stop")) {
            engine.stop();
            return Json.obj("ok", true);
        }
        if (p.equals("/api/settings")) {
            saveSettings(b);
            return Json.obj("ok", true, "running", engine.running());
        }
        if (p.startsWith("/api/account/")) {
            return account(p.substring("/api/account/".length()), b);
        }
        if (p.startsWith("/api/copilot/")) {
            return copilot(p.substring("/api/copilot/".length()), b);
        }
        if (p.equals("/api/ready")) {   // Apply / Skip / Apply all for the applications filled up to Submit
            List<String> ids = Json.bool(b, "all") ? null : Json.strings(b.get("ids"));
            int n = "skip".equals(Json.str(b, "op")) ? engine.skipReady(ids) : engine.approve(ids);
            return Json.obj("ok", true, "count", n, "items", engine.ready(), "running", engine.running());
        }
        if (p.equals("/api/feed")) {   // Refresh on the Jobs tab
            boolean started = engine.feed.maybeRun(true);
            return Json.obj("ok", true, "started", started, "feed", engine.feed.status());
        }
        if (p.equals("/api/sync/now")) {
            if (!engine.account.signedIn()) {
                throw new IllegalArgumentException("Sign in to your account first.");
            }
            Map<String, Object> st = engine.sync.runNow();
            if (!Json.str(st, "error").isEmpty()) {
                throw new IllegalArgumentException(Json.str(st, "error"));
            }
            return Json.obj("ok", true, "sync", st, "setup", setupStatus(engine.config.load()));
        }
        if (p.startsWith("/api/drive/")) {
            return drive(p.substring("/api/drive/".length()), b);
        }
        if (p.equals("/api/fill")) {
            Map<String, Object> item = Json.str(b, "id").isEmpty() ? null : engine.copilot.get(Json.str(b, "id"));
            String title = item != null ? Json.str(item, "title") : Json.str(b, "title");
            String company = item != null ? Json.str(item, "company") : Json.str(b, "company");
            Map<String, Object> out = Filler.answers(engine, Json.arr(b.get("fields")), title, company);
            out.put("ok", true);
            return out;
        }
        if (p.equals("/api/fill/learn")) {   // your answer to a form's question: kept in your info for every form
            Object v = Filler.learn(engine, Json.str(b, "label"), b.get("value"), Json.str(b, "kind"));
            return Json.obj("ok", true, "value", v);
        }
        if (p.equals("/api/tracker/stage")) {
            String id = Json.str(b, "id");
            String stage = Json.str(b, "stage");
            if (!Copilot.STAGES.contains(stage)) {
                throw new IllegalArgumentException("Unknown stage: " + stage);
            }
            if (id.startsWith("copilot:")) {
                engine.copilot.update(id.substring(8), Json.obj("stage", stage));
            } else {
                String jobId = id.startsWith("auto:") ? id.substring(5) : id;
                Map<String, Object> j = engine.store.setStage(jobId, stage);
                if (j == null) {
                    throw new IllegalArgumentException("This application is not there any more.");
                }
                engine.reporter.track(Json.obj("job_id", jobId, "title", Json.str(j, "title"), "company", Json.str(j, "company"),
                        "status", "applied", "channel", "auto", "stage", stage, "url", Json.str(j, "url"), "at", Json.dbl(j, "ts", Util.now())));
                Util.log("Stage: " + Json.str(j, "title") + " -> " + stage);
            }
            Map<String, Object> out = tracker();
            out.put("ok", true);
            return out;
        }
        if (p.equals("/api/open-link")) {
            String url = Json.str(b, "url");
            if (!url.matches("(?i)^https://.+")) {
                throw new IllegalArgumentException("Only https links open from here.");
            }
            engine.platform.openLink(url, Json.str(b, "how").isEmpty() ? "browser" : Json.str(b, "how"), "", "");
            return Json.obj("ok", true);
        }
        if (p.equals("/api/update/store")) {
            String url = Json.str(Json.map(engine.account.info().get("update")), "play_url");
            engine.platform.openLink(url.isEmpty() ? "https://play.google.com/store/apps/details?id=com.autoapply.bot" : url,
                    "store", "", "");
            return Json.obj("ok", true);
        }
        if (p.equals("/api/cv")) {
            return saveCv(b);
        }
        if (p.equals("/api/profile")) {
            engine.profile.save(Json.map(b.get("profile")));
            int replaced = dropCoveredAnswers();
            Map<String, Object> out = profileInfo();
            out.put("ok", true);
            out.put("replaced", replaced);
            return out;
        }
        if (p.equals("/api/facts")) {
            return changeFacts(b);
        }
        if (p.equals("/api/profile/suggest")) {
            return suggestProfile(Json.bool(b, "force"));
        }
        if (p.equals("/api/setup")) {
            engine.config.set("SETUP_DONE", Json.bool(b, "done") ? "1" : "");
            return Json.obj("ok", true, "setup", setupStatus(engine.config.load()));
        }
        if (p.equals("/api/test-ai")) {
            return testAi(b);
        }
        if (p.equals("/api/answers")) {
            int n;
            if (b.get("answers") instanceof Map) {
                n = engine.store.updateAnswers(Json.map(b.get("answers")), null, true);
            } else {
                n = engine.store.updateAnswers(Json.map(b.get("set")), Json.strings(b.get("delete")), Json.bool(b, "clear"));
            }
            return Json.obj("ok", true, "count", n);
        }
        if (p.equals("/api/answer")) {  // your answer to the question the bot is waiting on
            String err = Questions.DESK.respond(Json.str(b, "id"), "answer", b.get("answer"),
                    !b.containsKey("remember") || Json.bool(b, "remember"));
            if (!err.isEmpty()) {
                throw new IllegalArgumentException(err);
            }
            return Json.obj("ok", true);
        }
        if (p.equals("/api/skip-question")) {
            String err = Questions.DESK.respond(Json.str(b, "id"), "skip", null, false);
            if (!err.isEmpty()) {
                throw new IllegalArgumentException(err);
            }
            return Json.obj("ok", true);
        }
        if (p.equals("/api/open-questions")) {
            String key = Util.normKey(Json.str(b, "key"));
            if (Json.bool(b, "dismiss")) {
                return Json.obj("ok", engine.waiting.pop(key) != null);
            }
            return answerWaiting(key, b.get("answer"));
        }
        if (p.equals("/api/ask-mode")) {
            return Json.obj("ok", true, "ask_mode", setAskMode(Json.str(b, "mode")));
        }
        if (p.equals("/api/here")) {  // the dashboard is in use: you are around to answer questions
            Questions.DESK.markPresent();
            return Json.obj("ok", true);
        }
        // ---- phone-only actions
        if (p.equals("/api/linkedin")) {
            engine.platform.openSignIn();
            return Json.obj("ok", true);
        }
        if (p.equals("/api/signed-in")) {
            engine.signInDone();
            return Json.obj("ok", true, "signed_in", engine.platform.signedIn());
        }
        if (p.equals("/api/share")) {
            return share(Json.str(b, "what"));
        }
        if (p.equals("/api/permission")) {
            engine.platform.requestPermission(Json.str(b, "which"));
            return Json.obj("ok", true);
        }
        if (p.equals("/api/open-logs")) {
            return share("log");
        }
        if (p.equals("/api/update/check")) {
            if (!selfUpdates()) {
                return Json.obj("ok", true, "update", updateInfo());
            }
            startUpdateCheck(!Json.bool(b, "auto"));
            return Json.obj("ok", true, "update", updateInfo());
        }
        if (p.equals("/api/update/install")) {
            if (!selfUpdates()) {
                throw new IllegalArgumentException("Updates come from Google Play.");
            }
            if (engine.running()) {
                throw new IllegalArgumentException("Stop the bot first: installing the update closes the app for a moment.");
            }
            Map<String, Object> last = lastCheck;
            if (last == null || !Json.bool(last, "available")) {
                throw new IllegalArgumentException("No update to install. Tap Check for updates.");
            }
            engine.platform.installUpdate(Json.str(last, "url"), Json.str(last, "version"), false);
            return Json.obj("ok", true, "update", updateInfo());
        }
        return null;
    }

    private String setAskMode(String value) {
        String mode = Questions.askModeOf(value);
        engine.config.set("NEW_QUESTIONS", mode);
        Questions.askMode = mode;
        return mode;
    }

    private void saveSettings(Map<String, Object> b) {
        String titlesBefore = engine.config.load().get("KEYWORDS");
        try {
            saveSettingsNow(b);
        } finally {
            String after = engine.config.load().get("KEYWORDS");
            if (after != null && !after.equals(titlesBefore)) {
                engine.feed.maybeRun(true);   // new job titles: their jobs now, not in an hour
            }
        }
    }

    private void saveSettingsNow(Map<String, Object> b) {
        for (String key : SETTINGS_KEYS) {
            if (!b.containsKey(key)) {
                continue;
            }
            String raw = b.get(key) == null ? "" : String.valueOf(b.get(key)).replace("\r", "");
            String value;
            if (key.equals("KEYWORDS") || key.equals("SKIP_COMPANIES") || key.equals("SKIP_TITLE_WORDS")) {
                value = Util.join(";", Util.splitList(raw));
            } else {
                value = raw.replace("\n", " ").trim();
            }
            engine.config.set(key, value);
        }
        if (b.containsKey("ABOUT_ME")) {
            Util.writeText(engine.config.aboutFile, String.valueOf(b.get("ABOUT_ME") == null ? "" : b.get("ABOUT_ME")).replace("\r", ""));
        }
        // These two also change a running bot right away.
        if (b.containsKey("NEW_QUESTIONS")) {
            setAskMode(Json.str(b, "NEW_QUESTIONS"));
        }
        if (b.containsKey("ASK_TIMEOUT_MIN")) {
            Map<String, String> one = new java.util.HashMap<String, String>();
            one.put("ASK_TIMEOUT_MIN", Json.str(b, "ASK_TIMEOUT_MIN"));
            Questions.askTimeoutMin = Config.cfgInt(one, "ASK_TIMEOUT_MIN", 0, 1440);
        }
    }

    private Object saveCv(Map<String, Object> b) {
        String name = Json.str(b, "name").isEmpty() ? "cv.pdf" : Json.str(b, "name");
        String lower = name.toLowerCase(Locale.ROOT);
        String ext = lower.endsWith(".txt") ? ".txt" : (lower.endsWith(".pdf") ? ".pdf" : "");
        if (ext.isEmpty()) {
            throw new IllegalArgumentException("Please choose a PDF (or a .txt) file.");
        }
        String data = Json.str(b, "data");
        byte[] raw = Util.unbase64(data.contains(",") ? data.substring(data.lastIndexOf(',') + 1) : data);
        if (raw.length < 100) {
            throw new IllegalArgumentException("That file looks empty.");
        }
        String text = ext.equals(".txt") ? new String(raw, Util.UTF8) : Json.str(b, "text");
        text = text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() < 150) {
            throw new IllegalArgumentException("Could not read text from " + name + " (it may be a scanned image). Export your CV "
                    + "as a text-based PDF, or upload it as a .txt file.");
        }
        engine.config.saveCv(name, raw, text);
        List<String> titles = Profile.extractTitles(text);
        if (!titles.isEmpty()) {
            engine.config.set("KEYWORDS", String.join("\n", titles));
        }
        Map<String, Object> p = engine.profile.load();
        if (p == null) p = new java.util.HashMap<String, Object>();
        Map<String, Object> autoProfile = Profile.fromCvText(text);
        for (String k : new String[]{"phone", "graduation_year", "earliest_job_year"}) {
            if (Json.str(p, k).isEmpty() && !Json.str(autoProfile, k).isEmpty()) {
                p.put(k, autoProfile.get(k));
            }
        }
        if (!titles.isEmpty()) {
            p.put("titles", titles);
        }
        engine.profile.save(p);
        return Json.obj("ok", true, "name", name, "chars", text.length(), "preview", Util.cut(text, 600));
    }

    /** Check the account and the AI through the server. */
    private Object testAi(Map<String, Object> b) {
        if (!engine.account.signedIn()) {
            throw new IllegalArgumentException("Sign in to your account first.");
        }
        Ai ai = engine.newAi("", "", "check");
        try {
            engine.account.refresh();
            ai.ping();
        } catch (Account.AuthError e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Account.ServerError e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Ai.FatalAIError e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Could not reach the JSTB server: " + Util.shortError(e, 100));
        }
        return Json.obj("ok", true, "message", "Connected. The AI works" + (ai.servedBy.isEmpty() ? "" : " (" + ai.servedBy + ")") + ".",
                "account", engine.account.info());
    }

    // ------------------------------------------------------------------ your JSTB account
    private Object account(String what, Map<String, Object> b) {
        Account acc = engine.account;
        try {
            if (what.equals("server")) {
                acc.setServer(Json.str(b, "server"));
                return Json.obj("ok", true, "account", acc.info());
            }
            if (what.equals("info")) {  // the contact address and whether new accounts are open
                if (!Json.str(b, "server").isEmpty() && !Account.serverBuiltIn()) {
                    acc.setServer(Json.str(b, "server"));
                }
                Map<String, Object> r = acc.serverInfo();
                return Json.obj("ok", true, "account", acc.info(), "server", r);
            }
            if (what.equals("signup") || what.equals("login")) {
                if (!Account.serverBuiltIn() && b.containsKey("server")) {
                    acc.setServer(Json.str(b, "server"));
                }
                String email = Json.str(b, "email").trim();
                String password = Json.str(b, "password");
                if (email.isEmpty() || password.isEmpty()) {
                    throw new IllegalArgumentException("Type your email and a password.");
                }
                Map<String, Object> info = what.equals("signup") ? acc.signUp(email, password, Json.str(b, "name").trim())
                        : acc.signIn(email, password);
                Util.log(what.equals("signup") ? "Created your account (" + email + ")." : "Signed in as " + email + ".");
                engine.sync.kick(2);
                return Json.obj("ok", true, "account", info);
            }
            if (what.equals("refresh")) {
                acc.refresh();
                return Json.obj("ok", true, "account", acc.info());
            }
            if (what.equals("google")) {   // Sign in with Google: Google's page in the browser
                if (!Account.serverBuiltIn() && b.containsKey("server")) {
                    acc.setServer(Json.str(b, "server"));
                }
                engine.platform.openLink(acc.googleStart(), "google-auth", "", "");
                return Json.obj("ok", true);
            }
            if (what.equals("google-link")) {     // an email account adds Google (and its Drive)
                engine.platform.openLink(acc.googleLink(), "google-auth", "", "");
                return Json.obj("ok", true);
            }
            if (what.equals("google-claim")) {    // back in the app without the link from the page after Google
                Map<String, Object> info = acc.googleClaim();
                if (info == null) {
                    return Json.obj("ok", true, "pending", true);
                }
                Util.log("Signed in with Google as " + Json.str(info, "email") + ".");
                engine.sync.kick(2);
                return Json.obj("ok", true, "account", info);
            }
            if (what.equals("google-native")) {   // the Google account on the phone, without the browser
                Map<String, Object> info = acc.googleNative(Json.str(b, "code"), Json.str(b, "id_token"));
                Util.log("Signed in with Google as " + Json.str(info, "email") + ".");
                engine.sync.kick(2);
                return Json.obj("ok", true, "account", info);
            }
            if (what.equals("google-finish")) {   // back from Google's page
                Map<String, Object> info = acc.googleFinish(Json.str(b, "code"));
                Util.log("Signed in with Google as " + Json.str(info, "email") + ".");
                engine.sync.kick(2);
                return Json.obj("ok", true, "account", info);
            }
            if (what.equals("logout")) {
                if (engine.running()) {
                    throw new IllegalArgumentException("Stop auto apply first.");
                }
                engine.sync.flush();        // what is still waiting goes to your account first
                acc.signOut();
                return Json.obj("ok", true, "account", acc.info());
            }
            if (what.equals("delete")) {
                if (engine.running()) {
                    throw new IllegalArgumentException("Stop auto apply first.");
                }
                acc.delete(Json.str(b, "password"), Json.str(b, "confirm"));
                engine.sync.forget();
                Util.log("Your account was deleted from the server.");
                return Json.obj("ok", true, "account", acc.info());
            }
        } catch (Account.AuthError e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Account.ServerError e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Account.NetError e) {
            throw new IllegalArgumentException("Could not reach the server. Check the internet connection and try again.");
        }
        throw new IllegalArgumentException("Unknown account request: " + what);
    }

    // ------------------------------------------------------------------ Google Drive (through the server)
    private Object drive(String what, Map<String, Object> b) {
        Account acc = engine.account;
        if (!acc.signedIn()) {
            throw new IllegalArgumentException("Sign in to your account first.");
        }
        try {
            if (what.equals("connect")) {
                Map<String, Object> r = acc.call("POST", "/v1/me/drive/link", Json.obj(), true);
                engine.platform.openLink(Json.str(r, "url"), "browser", "", "");
                Util.log("Opened Google to connect your Drive");
                return Json.obj("ok", true);
            }
            if (what.equals("sync")) {   // yours to the server, the server with your Drive, then back to this phone
                engine.sync.flush();
                Map<String, Object> d = Json.map(acc.call("POST", "/v1/me/drive/sync", Json.obj(), true).get("drive"));
                engine.sync.driveChanged(d);
                Map<String, Object> st = engine.sync.runNow();
                return Json.obj("ok", true, "drive", d, "sync", st);
            }
            if (what.equals("disconnect")) {
                Map<String, Object> d = Json.map(acc.call("POST", "/v1/me/drive/unlink", Json.obj(), true).get("drive"));
                engine.sync.driveChanged(d);
                Util.log("Disconnected Google Drive (the files stay in your Drive)");
                return Json.obj("ok", true, "drive", d);
            }
            if (what.equals("open")) {
                Map<String, Object> d = engine.sync.drive(false);
                String which = Json.str(b, "which");
                String url = d == null ? "" : which.equals("folder") ? Json.str(d, "folder_url") : Json.str(Json.map(d.get("files")), which);
                if (!url.startsWith("https://")) {
                    throw new IllegalArgumentException("Your Drive folder is not ready yet. Tap Sync now.");
                }
                engine.platform.openLink(url, "browser", "", "");
                return Json.obj("ok", true);
            }
        } catch (Account.AuthError e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Account.ServerError e) {
            throw new IllegalArgumentException(e.status == 404 ? "The server does not have Google Drive yet (it updates by itself soon)."
                    : e.getMessage());
        } catch (Account.NetError e) {
            throw new IllegalArgumentException("Could not reach the server. Check the internet connection and try again.");
        }
        throw new IllegalArgumentException("Unknown request: " + what);
    }

    /** You answered a waiting question: save it and let the jobs that waited for it run again. */
    private Object answerWaiting(String key, Object answer) {
        Map<String, Object> item = engine.waiting.get(key);
        if (item == null) {
            throw new IllegalArgumentException("This question is not waiting any more.");
        }
        Map<String, Object> check = Json.map(Json.copy(item));
        check.put("rejected", new ArrayList<Object>());
        Object[] c = Questions.coerceAnswer(check, answer);
        if (!((String) c[1]).isEmpty()) {
            throw new IllegalArgumentException((String) c[1]);
        }
        Object value = c[0];
        engine.store.putAnswer(key, value, Json.str(item, "question"), "you");  // becomes part of your info
        Map<String, Object> popped = engine.waiting.pop(key);
        if (popped != null) {
            item = popped;
        }
        List<String> ids = new ArrayList<String>();
        for (Object j : Json.arr(item.get("jobs"))) {
            ids.add(Json.str(Json.map(j), "id"));
        }
        int retried = engine.store.markAnswered(ids);
        Questions.DESK.markPresent();
        Map<String, Object> cur = Questions.DESK.get();
        if (cur != null && key.equals(Json.str(cur, "key"))) {  // the bot is asking the same question right now
            Questions.DESK.respond(Json.str(cur, "id"), "answer", value, true);
        }
        Util.log("Saved your answer: " + Util.cut(Json.str(item, "question"), 80) + " -> " + Bot.shown(value)
                + (retried > 0 ? " (" + retried + " job(s) will be tried again)" : ""));
        return Json.obj("ok", true, "value", value, "retried", retried);
    }

    private Object share(String what) {
        File f;
        String mime;
        String title;
        if ("csv".equals(what)) {
            f = engine.store.writeCsv();
            mime = "text/csv";
            title = "Applied jobs";
        } else if ("debug".equals(what)) {
            f = engine.debugZip;
            mime = "application/zip";
            title = "JSTB debug.zip";
        } else {
            f = Util.logFile;
            mime = "text/plain";
            title = "JSTB log";
        }
        if (f == null || !f.isFile()) {
            throw new IllegalArgumentException("Nothing to share yet.");
        }
        engine.platform.share(f, mime, title);
        return Json.obj("ok", true);
    }

    // ------------------------------------------------------------------ jobs you apply to yourself
    private Object copilot(String what, Map<String, Object> b) {
        Copilot c = engine.copilot;
        String id = Json.str(b, "id");
        if (what.equals("add")) {
            Map<String, Object> item = c.add(Json.str(b, "text"), Json.str(b, "subject"));
            return Json.obj("ok", true, "item", item, "items", c.list(), "stats", c.stats(Util.now()));
        }
        Map<String, Object> item = c.get(id);
        if (item == null) {
            throw new IllegalArgumentException("This job is not in your list any more.");
        }
        if (what.equals("update")) {
            Map<String, Object> changes = Json.obj();
            for (String k : new String[]{"state", "stage", "title", "company", "note"}) {
                if (b.containsKey(k)) {
                    changes.put(k, b.get(k) instanceof String ? ((String) b.get(k)).trim() : b.get(k));
                }
            }
            Map<String, Object> it = c.update(id, changes);
            return Json.obj("ok", true, "item", it, "items", c.list(), "stats", c.stats(Util.now()));
        }
        if (what.equals("remove")) {
            c.remove(id);
            return Json.obj("ok", true, "items", c.list(), "stats", c.stats(Util.now()));
        }
        if (what.equals("open")) {
            boolean linkedin = "linkedin".equals(Json.str(item, "channel"));
            boolean external = Json.bool(b, "external");
            boolean fill = !b.containsKey("fill") || Json.bool(b, "fill");
            String how = external ? (linkedin ? "linkedin" : "browser") : (fill ? "inapp-fill" : "inapp");
            Util.log("Opened (" + how + ") " + (linkedin ? "on LinkedIn" : "on the company site") + ": " + Json.str(item, "title"));
            engine.platform.openLink(Json.str(item, "url"), how, id, Json.str(item, "title"));
            return Json.obj("ok", true);
        }
        if (what.equals("letter")) {
            requireAi();
            String letter;
            try {
                letter = engine.newAi(engine.config.cvText(), engine.notesForAi(), "help")
                        .coverLetter(Json.str(item, "title"), Json.str(item, "company"), Json.str(b, "text"));
            } catch (Ai.FatalAIError e) {
                throw new IllegalArgumentException(e.getMessage());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Could not reach the AI: " + Util.shortError(e, 100));
            }
            Map<String, Object> it = c.update(id, Json.obj("letter", letter));
            return Json.obj("ok", true, "item", it, "letter", letter);
        }
        if (what.equals("fit")) {  // the in-app browser read the job page
            String heading = Json.str(b, "heading").replaceAll("\\s+", " ").trim();
            String t = Json.str(item, "title");
            if (heading.length() >= 3 && heading.length() <= 160 && (t.isEmpty() || t.equals(Copilot.hostOf(Json.str(item, "url")))
                    || t.equals("LinkedIn job") || t.startsWith("http"))) {
                item = c.update(id, Json.obj("title", heading));  // a better name than the site's address
            }
            String text = Json.str(b, "text");
            if (text.trim().length() < 200 || !engine.account.signedIn() || item.get("fit") instanceof Number) {
                return Json.obj("ok", true, "item", item);
            }
            try {
                Object[] r = engine.newAi(engine.config.cvText(), engine.notesForAi(), "help")
                        .score(Json.str(item, "title"), Json.str(item, "company"), Util.cut(text, 6000));
                Map<String, Object> it = c.update(id, Json.obj("fit", r[0], "why", r.length > 1 ? String.valueOf(r[1]) : ""));
                Util.log("Fit " + r[0] + "/10: " + Json.str(item, "title") + (r.length > 1 ? " (" + Util.cut(String.valueOf(r[1]), 120) + ")" : ""));
                return Json.obj("ok", true, "item", it);
            } catch (RuntimeException e) {
                return Json.obj("ok", true, "item", item, "problem", Util.shortError(e, 100));
            }
        }
        throw new IllegalArgumentException("Unknown request: " + what);
    }

    private void requireAi() {
        if (!engine.account.signedIn()) {
            throw new IllegalArgumentException("Sign in to your account first.");
        }
        // Forms, new questions and cover letters work on every account; only auto apply needs Pro.
    }

    /** Every job you applied to: by auto apply, and by yourself with the app's help. Newest first, with its stage. */
    private Map<String, Object> tracker() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object o : engine.store.rows(5000)) {
            Map<String, Object> j = Json.map(o);
            if (!"applied".equals(Json.str(j, "status"))) {
                continue;
            }
            out.add(Json.obj("id", "auto:" + Json.str(j, "id"), "title", Json.str(j, "title"), "company", Json.str(j, "company"),
                    "url", Json.str(j, "url"), "channel", "auto", "at", Json.dbl(j, "ts", 0),
                    "stage", Json.str(j, "stage").isEmpty() ? "applied" : Json.str(j, "stage"), "score", j.get("score")));
        }
        for (Map<String, Object> it : engine.copilot.list()) {
            if (!"applied".equals(Json.str(it, "state"))) {
                continue;
            }
            out.add(Json.obj("id", "copilot:" + Json.str(it, "id"), "title", Json.str(it, "title"), "company", Json.str(it, "company"),
                    "url", Json.str(it, "url"), "channel", Json.str(it, "channel"), "at", Json.dbl(it, "applied_at", 0),
                    "stage", Json.str(it, "stage").isEmpty() ? "applied" : Json.str(it, "stage"), "score", it.get("fit")));
        }
        Collections.sort(out, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(Json.dbl(b, "at", 0), Json.dbl(a, "at", 0));
            }
        });
        Map<String, Object> stages = Json.obj();
        for (String s : Copilot.STAGES) {
            stages.put(s, 0);
        }
        double d0 = Copilot.midnight(Util.now(), 0);
        double w0 = Copilot.midnight(Util.now(), 6);
        int today = 0;
        int week = 0;
        for (Map<String, Object> x : out) {
            stages.put(Json.str(x, "stage"), Json.num(stages, Json.str(x, "stage"), 0) + 1);
            if (Json.dbl(x, "at", 0) >= d0) {
                today++;
            }
            if (Json.dbl(x, "at", 0) >= w0) {
                week++;
            }
        }
        return Json.obj("items", out, "stages", stages, "today", today, "week", week, "total", out.size());
    }
}
