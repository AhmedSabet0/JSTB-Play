package com.autoapply.bot.core;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.autoapply.bot.core.Util.log;

/**
 * Keeps your data the same in your account on the server, so every phone you sign in on has it (and your Google
 * Drive, through the server): the setup answers, settings, your info, your jobs, auto apply's job log and the CV.
 *
 * Each item is a record (kind/id). sync.json remembers, for every record, the hash of what was last synced and the
 * server's revision of it. A sync takes what changed on the server since the last one (the server's version of an
 * item wins when this phone did not change it), then sends what changed here (with the revision it was based on:
 * when the item also changed on the server, the newer change wins and the other side takes it).
 *
 * A phone that never synced takes the server's version of what both have, then sends the rest: a new phone gets your
 * data, a phone with data from before 3.1 fills the account. Signing in with another account replaces this phone's
 * data with that account's.
 */
public final class Sync {
    /** Seconds between syncs while the app is in use (tests use less). */
    public static volatile double every = 120;
    /** Seconds a change waits, so changes made together go together. */
    public static volatile double settle = 2.5;
    /** Off in tests that do not want syncs in the background. */
    public static volatile boolean auto = true;

    static final String[] KINDS = {"profile", "settings", "facts", "copilot", "jobs"};
    /** Settings that follow you to every phone (the update settings stay per phone). */
    static final String[] SETTING_KEYS = {"KEYWORDS", "LOCATION", "DATE_POSTED", "WORK_TYPE", "EXPERIENCE",
        "PAGES_PER_KEYWORD", "SEARCH_EVERY_MIN", "PER_HOUR", "PER_DAY", "MIN_SCORE", "SKIP_COMPANIES",
        "SKIP_TITLE_WORDS", "NEW_QUESTIONS", "ASK_TIMEOUT_MIN", "DAILY_GOAL", "SETUP_DONE", "ABOUT_ME", "APPROVE_FIRST"};
    static final int BATCH = 400;

    private final Engine engine;
    private final File file;
    private final Object lock = new Object();
    private final Object cycleLock = new Object();
    private Map<String, Object> snap;
    private Thread thread;
    private boolean wanted;
    private double wantedAt;
    private volatile double touchedAt;
    /** When this phone first changed something that the next sync has not seen yet (its changes get this time). */
    private volatile double firstChange;
    private volatile boolean busy;
    private volatile double lastOk;
    private volatile double lastTry;
    private volatile String error = "";
    private volatile String note = "";
    private volatile int waiting;
    private volatile Map<String, Object> drive;
    private volatile double driveAt;
    private String cvSha = "";
    private long cvLen = -1;
    private long cvTime = -1;

    Sync(Engine engine, File dir) {
        this.engine = engine;
        this.file = new File(dir, "sync.json");
        Object saved = Json.tryParse(Util.readText(file));
        snap = saved instanceof Map ? Json.map(saved) : fresh("");
        lastOk = Json.dbl(snap, "ok_at", 0);
    }

    private static Map<String, Object> fresh(String user) {
        return Json.obj("user", user, "rev", 0, "recs", Json.obj(), "pending", Json.obj());
    }

    // ------------------------------------------------------------------ when
    /** Something changed on this phone: send it soon. */
    public void changed() {
        if (firstChange == 0) {
            firstChange = Util.now();
        }
        kick(settle);
    }

    public void kick(double afterSeconds) {
        if (!auto) {
            return;
        }
        synchronized (lock) {
            double at = Util.now() + afterSeconds;
            if (!wanted || at < wantedAt) {
                wantedAt = at;
            }
            wanted = true;
            lock.notifyAll();
        }
        ensureStarted();
    }

    /** The app is in use (the screens ask for the state every second): sync every so often. */
    public void touch() {
        touchedAt = Util.now();
        if (auto && engine.account.signedIn() && (thread == null || !thread.isAlive())) {
            kick(1);
        }
    }

    private void ensureStarted() {
        synchronized (lock) {
            if (thread != null && thread.isAlive()) {
                return;
            }
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "sync");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void loop() {
        while (true) {
            synchronized (lock) {
                double now = Util.now();
                boolean inUse = engine.running() || now - touchedAt < 300;
                double gap = engine.running() ? Math.min(every, 60) : every;
                double due = wanted ? wantedAt : (inUse ? lastTry + gap : Double.MAX_VALUE);
                if (!error.isEmpty()) {
                    due = Math.max(due, lastTry + Math.min(gap, 30));   // after a failure: not faster than every 30 s
                }
                if (due > now) {
                    try {
                        lock.wait((long) Math.max(50, Math.min((due - now) * 1000, 30000)));
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                wanted = false;
            }
            if (engine.account.signedIn()) {
                cycle();
            } else {
                lastTry = Util.now();       // nothing to sync until you sign in (and no busy loop meanwhile)
            }
        }
    }

    /** Sync now and wait for it (after signing in, Sync now). */
    public Map<String, Object> runNow() {
        cycle();
        return status();
    }

    /** Before signing out (or a Drive sync): send what is still waiting, while the sign-in still works. */
    public void flush() {
        if (engine.account.signedIn() && (firstChange > 0 || waiting > 0 || lastOk == 0 || !error.isEmpty())) {
            cycle();
        }
    }

    /** The account was deleted: this phone's data does not belong to any account now. */
    public void forget() {
        synchronized (cycleLock) {
            snap = fresh("");
            save();
        }
        drive = null;
    }

    public Map<String, Object> status() {
        return Json.obj("busy", busy, "at", lastOk, "tried", lastTry, "error", error, "note", note, "waiting", waiting,
                "drive", drive);
    }

    // ------------------------------------------------------------------ Google Drive (through the server)
    /** Your Google Drive's state on the server (asked at most every 20 seconds unless fresh). */
    public Map<String, Object> drive(boolean fresh) {
        if (!engine.account.signedIn()) {
            return null;
        }
        Map<String, Object> d = drive;
        if (fresh || d == null || Util.now() - driveAt > 20) {
            try {
                d = Json.map(engine.account.call("GET", "/v1/me/drive", null, true).get("drive"));
            } catch (Account.ServerError e) {
                d = Json.obj("available", false, "connected", false, "error", e.status == 404
                        ? "The server does not have Google Drive yet (it updates by itself soon)." : e.getMessage());
            } catch (RuntimeException e) {
                return d != null ? d : Json.obj("available", false, "connected", false, "offline", true,
                        "error", "Could not reach the server.");
            }
            drive = d;
            driveAt = Util.now();
        }
        return d;
    }

    void driveChanged(Map<String, Object> d) {
        drive = d;
        driveAt = Util.now();
    }

    // ------------------------------------------------------------------ one sync
    private void cycle() {
        synchronized (cycleLock) {
            busy = true;
            lastTry = Util.now();
            try {
                note = cycleLocked();
                error = "";
                lastOk = Util.now();
                snap.put("ok_at", lastOk);
            } catch (Account.AuthError e) {
                error = "Sign in again to keep your data in your account.";
            } catch (Account.NetError e) {
                error = "Offline. Your changes are sent when the phone is online.";
            } catch (Account.ServerError e) {
                error = e.status == 404 ? "The server cannot keep your data yet (it updates by itself soon)." : e.getMessage();
            } catch (RuntimeException e) {
                error = Util.shortError(e, 140);
                log("Sync stopped: " + error);
            } finally {
                busy = false;
                save();
            }
        }
    }

    private String cycleLocked() {
        Account acc = engine.account;
        if (!acc.signedIn()) {
            return "";
        }
        String user = acc.userId();
        if (user.isEmpty()) {
            acc.refresh();
            user = acc.userId();
        }
        String owner = Json.str(snap, "user");
        if (!owner.isEmpty() && !owner.equals(user)) {
            log("Signed in with another account: this phone now has that account's data.");
            wipeLocal();
            snap = fresh(user);
        } else if (owner.isEmpty()) {
            snap.put("user", user);
        }
        Map<String, Object> recs = Json.map(snap.get("recs"));
        Map<String, Object> pending = Json.map(snap.get("pending"));
        snap.put("recs", recs);
        snap.put("pending", pending);
        Map<String, Map<String, Object>> local = exportAll();
        double seen = Util.now();
        markPending(local, recs, pending, firstChange > 0 && firstChange <= seen ? firstChange : seen);
        firstChange = 0;

        // 1. What changed in your account since the last sync.
        long since = Json.num(snap, "rev", 0);
        Map<String, Map<String, Map<String, Object>>> up = new LinkedHashMap<String, Map<String, Map<String, Object>>>();
        Map<String, Set<String>> del = new LinkedHashMap<String, Set<String>>();
        Map<String, Long> revs = new LinkedHashMap<String, Long>();
        Map<String, Object> remoteCv = null;
        long remoteCvRev = 0;
        int got = 0;
        while (true) {
            Map<String, Object> r = acc.call("GET", "/v1/me/data?since=" + since, null, true);
            for (Object o : Json.arr(r.get("records"))) {
                Map<String, Object> rec = Json.map(o);
                String kind = Json.str(rec, "k");
                String rid = Json.str(rec, "id");
                long rev = Json.num(rec, "rev", 0);
                String key = kind + "/" + rid;
                Object d = rec.get("d");
                List<Object> s = entry(recs, key);
                if (s != null && revOf(s) == rev) {
                    continue;                       // what this phone sent
                }
                if (kind.equals("cv")) {
                    if (rid.equals("main") && d instanceof Map) {
                        remoteCv = Json.map(d);
                        remoteCvRev = rev;
                    }
                    continue;
                }
                if (!Arrays.asList(KINDS).contains(kind)) {   // from a newer app: kept on the server, not here
                    continue;
                }
                Object mine = local.get(kind).get(rid);
                if (s != null && (mine == null || !hash(mine).equals(hashOf(s)))) {
                    continue;                       // changed here too: it goes up below, the newer one wins
                }
                if (d instanceof Map) {
                    upSlot(up, kind).put(rid, Json.map(d));
                } else {
                    delSlot(del, kind).add(rid);
                }
                revs.put(key, rev);
                got++;
            }
            since = Json.num(r, "rev", since);
            if (!Json.bool(r, "more")) {
                break;
            }
        }
        applyAll(up, del, revs, local, recs, pending);
        snap.put("rev", since);

        // 2. What changed on this phone.
        List<Object> changes = localChanges(local, recs, pending);
        int sent = 0;
        for (int i = 0; i < changes.size(); i += BATCH) {
            List<Object> batch = changes.subList(i, Math.min(changes.size(), i + BATCH));
            Map<String, Object> r = acc.call("POST", "/v1/me/data", Json.obj("now", Util.now(), "changes", new ArrayList<Object>(batch)), true);
            Map<String, Map<String, Object>> sentData = new LinkedHashMap<String, Map<String, Object>>();
            for (Object o : batch) {
                Map<String, Object> ch = Json.map(o);
                sentData.put(Json.str(ch, "k") + "/" + Json.str(ch, "id"), ch);
            }
            up.clear();
            del.clear();
            revs.clear();
            for (Object o : Json.arr(r.get("results"))) {
                Map<String, Object> res = Json.map(o);
                String kind = Json.str(res, "k");
                String rid = Json.str(res, "id");
                String key = kind + "/" + rid;
                long rev = Json.num(res, "rev", 0);
                if ("conflict".equals(Json.str(res, "status"))) {   // the server's is newer: take it
                    if (res.get("d") instanceof Map) {
                        upSlot(up, kind).put(rid, Json.map(res.get("d")));
                    } else {
                        delSlot(del, kind).add(rid);
                    }
                    revs.put(key, rev);
                    continue;
                }
                Map<String, Object> ch = sentData.get(key);
                Object d = ch == null ? null : ch.get("d");
                if (d instanceof Map && rev > 0) {
                    recs.put(key, Json.list(hash(d), rev));
                } else {
                    recs.remove(key);
                }
                pending.remove(key);
                sent++;
            }
            applyAll(up, del, revs, local, recs, pending);
        }

        // 3. The CV file.
        String cvNote = syncCv(remoteCv, remoteCvRev, recs, pending);
        waiting = localChanges(exportAll(), recs, pending).size();
        StringBuilder out = new StringBuilder();
        if (got > 0) {
            out.append("Took ").append(got).append(got == 1 ? " change" : " changes").append(" from your account.");
        }
        if (!cvNote.isEmpty()) {
            out.append(out.length() > 0 ? " " : "").append(cvNote);
        }
        if (got > 0 || sent > 0) {
            log("Synced with your account: " + got + " in, " + sent + " out" + (cvNote.isEmpty() ? "" : "; " + cvNote));
        }
        drive(false);                       // for the Home screen: Google Drive asks to connect again
        return out.toString();
    }

    private static Map<String, Map<String, Object>> upSlot(Map<String, Map<String, Map<String, Object>>> m, String kind) {
        Map<String, Map<String, Object>> v = m.get(kind);
        if (v == null) {
            v = new LinkedHashMap<String, Map<String, Object>>();
            m.put(kind, v);
        }
        return v;
    }

    private static Set<String> delSlot(Map<String, Set<String>> m, String kind) {
        Set<String> v = m.get(kind);
        if (v == null) {
            v = new HashSet<String>();
            m.put(kind, v);
        }
        return v;
    }

    /** Applies what came from the server, kind by kind, and remembers it as synced. */
    private void applyAll(Map<String, Map<String, Map<String, Object>>> up, Map<String, Set<String>> del, Map<String, Long> revs,
                          Map<String, Map<String, Object>> local, Map<String, Object> recs, Map<String, Object> pending) {
        for (String kind : KINDS) {
            Map<String, Map<String, Object>> u = up.get(kind);
            Set<String> d = del.get(kind);
            if ((u == null || u.isEmpty()) && (d == null || d.isEmpty())) {
                continue;
            }
            if (u == null) {
                u = new LinkedHashMap<String, Map<String, Object>>();
            }
            if (d == null) {
                d = new HashSet<String>();
            }
            applyKind(kind, u, d);
            Map<String, Object> now = exportKind(kind);
            local.put(kind, now);
            for (String rid : u.keySet()) {
                String key = kind + "/" + rid;
                Object mine = now.get(rid);
                recs.put(key, Json.list(hash(mine != null ? mine : u.get(rid)), revs.containsKey(key) ? revs.get(key) : 0L));
                pending.remove(key);
            }
            for (String rid : d) {
                recs.remove(kind + "/" + rid);
                pending.remove(kind + "/" + rid);
            }
        }
    }

    private static List<Object> entry(Map<String, Object> recs, String key) {
        Object v = recs.get(key);
        return v instanceof List && ((List<?>) v).size() >= 2 ? Json.arr(v) : null;
    }

    private static String hashOf(List<Object> s) {
        return String.valueOf(s.get(0));
    }

    private static long revOf(List<Object> s) {
        Object v = s.get(1);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    /** New changes here get the time they were first seen (the server compares it with changes made elsewhere). */
    private void markPending(Map<String, Map<String, Object>> local, Map<String, Object> recs, Map<String, Object> pending, double now) {
        for (String kind : KINDS) {
            for (Map.Entry<String, Object> e : local.get(kind).entrySet()) {
                String key = kind + "/" + e.getKey();
                List<Object> s = entry(recs, key);
                if ((s == null || !hash(e.getValue()).equals(hashOf(s))) && !pending.containsKey(key)) {
                    pending.put(key, now);
                }
            }
        }
        for (String key : new ArrayList<String>(recs.keySet())) {
            String kind = key.substring(0, Math.max(0, key.indexOf('/')));
            if (Arrays.asList(KINDS).contains(kind) && !local.get(kind).containsKey(key.substring(kind.length() + 1))
                    && !pending.containsKey(key)) {
                pending.put(key, now);
            }
        }
    }

    private List<Object> localChanges(Map<String, Map<String, Object>> local, Map<String, Object> recs, Map<String, Object> pending) {
        List<Object> out = new ArrayList<Object>();
        for (String kind : KINDS) {
            for (Map.Entry<String, Object> e : local.get(kind).entrySet()) {
                String key = kind + "/" + e.getKey();
                List<Object> s = entry(recs, key);
                if (s == null || !hash(e.getValue()).equals(hashOf(s))) {
                    out.add(Json.obj("k", kind, "id", e.getKey(), "d", e.getValue(), "base", s == null ? 0 : revOf(s),
                            "t", Json.dbl(pending, key, Util.now())));
                }
            }
        }
        for (String key : new ArrayList<String>(recs.keySet())) {
            int slash = key.indexOf('/');
            String kind = key.substring(0, Math.max(0, slash));
            String rid = key.substring(slash + 1);
            if (Arrays.asList(KINDS).contains(kind) && !local.get(kind).containsKey(rid)) {
                out.add(Json.obj("k", kind, "id", rid, "d", null, "base", revOf(entry(recs, key)), "t", Json.dbl(pending, key, Util.now())));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the CV
    private String localCvSha(File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        if (f.length() != cvLen || f.lastModified() != cvTime) {
            try {
                cvSha = sha256(Util.readBytes(f));
            } catch (IOException e) {
                return "";
            }
            cvLen = f.length();
            cvTime = f.lastModified();
        }
        return cvSha;
    }

    private String syncCv(Map<String, Object> remote, long remoteRev, Map<String, Object> recs, Map<String, Object> pending) {
        Map<String, String> cfg = engine.config.load();
        File f = engine.config.cvFile(cfg);
        String sha = localCvSha(f);
        List<Object> s = entry(recs, "cv/main");
        boolean changedHere = !sha.isEmpty() && (s == null ? remote == null : !sha.equals(hashOf(s)));
        if (remote != null && !changedHere) {
            if (!Json.str(remote, "sha256").equals(sha)) {
                return downloadCv(recs, pending);
            }
            recs.put("cv/main", Json.list(sha, remoteRev));
            pending.remove("cv/main");
            return "";
        }
        if (!changedHere) {
            return "";
        }
        if (!pending.containsKey("cv/main")) {
            pending.put("cv/main", Util.now());
        }
        byte[] raw;
        try {
            raw = Util.readBytes(f);
        } catch (IOException e) {
            return "";
        }
        String name = cfg.get("CV_NAME") == null || cfg.get("CV_NAME").isEmpty() ? f.getName() : cfg.get("CV_NAME");
        Map<String, Object> r = engine.account.call("POST", "/v1/me/cv", Json.obj("name", name, "data", Util.base64(raw),
                "text", engine.config.cvText(), "base", s == null ? 0 : revOf(s), "t", Json.dbl(pending, "cv/main", Util.now()),
                "now", Util.now()), true);
        if ("conflict".equals(Json.str(r, "status"))) {
            return downloadCv(recs, pending);
        }
        recs.put("cv/main", Json.list(sha, Json.num(r, "rev", 0)));
        pending.remove("cv/main");
        return "";
    }

    private String downloadCv(Map<String, Object> recs, Map<String, Object> pending) {
        Map<String, Object> r = engine.account.call("GET", "/v1/me/cv", null, true);
        String name = Json.str(r, "name").isEmpty() ? "cv.pdf" : Json.str(r, "name");
        byte[] raw = Util.unbase64(Json.str(r, "data"));
        if (raw.length < 100) {
            return "";
        }
        File dest = engine.config.saveCv(name, raw, Json.str(r, "text"));
        recs.put("cv/main", Json.list(localCvSha(dest), Json.num(r, "rev", 0)));
        pending.remove("cv/main");
        log("Your CV came from your account: " + name);
        return "Your CV came from your account (" + name + ").";
    }

    // ------------------------------------------------------------------ this phone's data as records
    Map<String, Map<String, Object>> exportAll() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        for (String kind : KINDS) {
            out.put(kind, exportKind(kind));
        }
        return out;
    }

    private Map<String, Object> exportKind(String kind) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (kind.equals("profile")) {
            Map<String, Object> prof = engine.profile.load();
            for (String f : Profile.FIELDS) {
                out.put(f, Json.obj("v", Json.str(prof, f)));
            }
            out.put("done", Json.obj("v", Json.bool(prof, "done") ? "1" : ""));
        } else if (kind.equals("settings")) {
            Map<String, String> cfg = engine.config.load();
            for (String k : SETTING_KEYS) {
                if (k.equals("ABOUT_ME")) {
                    String about = Util.readText(engine.config.aboutFile);
                    out.put(k, Json.obj("v", about == null ? "" : about));
                } else {
                    out.put(k, Json.obj("v", cfg.get(k) == null ? "" : cfg.get(k)));
                }
            }
        } else if (kind.equals("facts")) {
            for (Map<String, Object> f : engine.store.factsCopy()) {
                out.put(Json.str(f, "id"), f);
            }
        } else if (kind.equals("copilot")) {
            for (Map<String, Object> it : engine.copilot.list()) {
                out.put(Json.str(it, "id"), it);
            }
        } else if (kind.equals("jobs")) {
            for (Map<String, Object> j : engine.store.jobsForSync()) {
                if (!Json.str(j, "id").isEmpty()) {
                    out.put(Json.str(j, "id"), j);
                }
            }
        }
        return out;
    }

    private void applyKind(String kind, Map<String, Map<String, Object>> up, Set<String> del) {
        if (kind.equals("profile")) {
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Map<String, Object>> e : up.entrySet()) {
                values.put(e.getKey(), Json.str(e.getValue(), "v"));
            }
            engine.profile.merge(values);
        } else if (kind.equals("settings")) {
            for (Map.Entry<String, Map<String, Object>> e : up.entrySet()) {
                String k = e.getKey();
                String v = Json.str(e.getValue(), "v");
                if (k.equals("ABOUT_ME")) {
                    Util.writeText(engine.config.aboutFile, v.replace("\r", ""));
                } else if (Arrays.asList(SETTING_KEYS).contains(k)) {
                    engine.config.set(k, v);
                    if (k.equals("NEW_QUESTIONS")) {
                        Questions.askMode = Questions.askModeOf(v);
                    } else if (k.equals("ASK_TIMEOUT_MIN")) {
                        Map<String, String> one = new java.util.HashMap<String, String>();
                        one.put(k, v);
                        Questions.askTimeoutMin = Config.cfgInt(one, k, 0, 1440);
                    }
                }
            }
        } else if (kind.equals("facts")) {
            engine.store.mergeFacts(up, del);
        } else if (kind.equals("copilot")) {
            engine.copilot.merge(up, del);
        } else if (kind.equals("jobs")) {
            engine.store.mergeJobs(up, del);
        }
    }

    /** Signed in with another account: what this phone had was the other account's (it is in that account). */
    private void wipeLocal() {
        engine.store.clearForAccount();
        engine.copilot.clear();
        engine.profile.clear();
        for (String k : SETTING_KEYS) {
            if (!k.equals("ABOUT_ME")) {
                engine.config.set(k, Config.dflt(k));
            }
        }
        Util.writeText(engine.config.aboutFile, Config.ABOUT_TEMPLATE);
        engine.config.clearCv();
        new File(engine.config.dir, "profile_suggest.json").delete();
        cvLen = -1;
    }

    private void save() {
        Util.writeText(file, Json.stringify(snap));
    }

    // ------------------------------------------------------------------ hashing
    /** The same text for the same data, whatever the order of keys or the kind of number. */
    static String canon(Object v) {
        StringBuilder b = new StringBuilder();
        canon(b, v);
        return b.toString();
    }

    private static void canon(StringBuilder b, Object v) {
        if (v instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(Json.stringify(e.getKey())).append(':');
                canon(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<?>) v) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                canon(b, x);
            }
            b.append(']');
        } else if (v instanceof Number && !(v instanceof Long || v instanceof Integer)) {
            double d = ((Number) v).doubleValue();
            b.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
        } else {
            b.append(Json.stringify(v));
        }
    }

    static String hash(Object v) {
        return sha1(canon(v)).substring(0, 20);
    }

    private static String sha1(String s) {
        return hex("SHA-1", s.getBytes(Util.UTF8));
    }

    static String sha256(byte[] data) {
        return hex("SHA-256", data);
    }

    private static String hex(String algo, byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance(algo).digest(data);
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format(Locale.ROOT, "%02x", x));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
