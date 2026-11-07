package com.autoapply.bot.core;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Job log, your info (facts in categories, with the questions each one answered) and pacing state, kept as JSON
 *  files in the data folder. */
public final class Store {
    public static final String[][] STATUS = {
        {"applied", "Applied", "good"},
        {"ready", "Ready to send", "info"},
        {"approved", "Sending (you said yes)", "info"},
        {"skipped_low_score", "Low match", ""},
        {"no_easy_apply", "No Easy Apply", ""},
        {"already_applied", "Already applied", ""},
        {"closed", "Closed", ""},
        {"blacklisted", "Skipped (filter)", ""},
        {"dry_run_match", "Match (dry run)", "info"},
        {"stuck", "Stuck", "warn"},
        {"needs_answer", "Needs your answer", "warn"},
        {"answered", "Answered, will retry", "info"},
        {"user_skipped", "Skipped by you", ""},
        {"error", "Error", "bad"},
    };

    public static String label(String status) {
        for (String[] s : STATUS) {
            if (s[0].equals(status)) {
                return s[1];
            }
        }
        return status;
    }

    public static String tone(String status) {
        for (String[] s : STATUS) {
            if (s[0].equals(status)) {
                return s[2];
            }
        }
        return "";
    }

    public static String group(String status) {
        if ("applied".equals(status)) {
            return "applied";
        }
        if ("stuck".equals(status) || "needs_answer".equals(status) || "answered".equals(status) || "error".equals(status)) {
            return "problems";
        }
        if ("ready".equals(status) || "approved".equals(status)) {
            return "ready";
        }
        return "skipped";
    }

    private final File jobsFile;
    private final File answersFile;
    private final File factsFile;
    private final File stateFile;
    public final File csvFile;
    private final List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
    /** Your info: {id, category, label, value, asked: [questions it answered], source: you|saved|ai, updated}. */
    private final List<Map<String, Object>> facts = new ArrayList<Map<String, Object>>();
    /** Question (normalized) -> answer, built from the facts. */
    private final Map<String, Object> answers = new LinkedHashMap<String, Object>();
    private final Map<String, Object> state;
    /** Hears every job outcome as it is recorded (the reporter sends them to the server). */
    public interface Listener {
        void onJob(Map<String, Object> job);
    }

    public volatile Listener listener;
    /** Guards your info (the dashboard and the bot both change it). */
    public final Object answersLock = new Object();

    public Store(File dir) {
        jobsFile = new File(dir, "jobs.json");
        answersFile = new File(dir, "answers.json");
        stateFile = new File(dir, "state.json");
        csvFile = new File(new File(dir, "logs"), "applied_jobs.csv");
        for (Object o : Json.arr(Json.tryParse(Util.readText(jobsFile)))) {
            if (o instanceof Map) {
                jobs.add(Json.map(o));
            }
        }
        factsFile = new File(dir, "facts.json");
        Object saved = Json.tryParse(Util.readText(factsFile));
        if (saved instanceof Map && Json.map(saved).get("facts") instanceof List) {
            for (Object o : Json.arr(Json.map(saved).get("facts"))) {
                if (o instanceof Map) {
                    facts.add(Json.map(o));
                }
            }
        } else {
            // First start with facts: the answers saved so far become your info, sorted into categories.
            for (Map.Entry<String, Object> e : Json.map(Json.tryParse(Util.readText(answersFile))).entrySet()) {
                if (e.getValue() != null && !String.valueOf(e.getValue()).trim().isEmpty()) {
                    facts.add(newFact(Facts.categorize(e.getKey()), Facts.labelOf(e.getKey()), e.getValue(), "saved", e.getKey()));
                }
            }
            if (!facts.isEmpty()) {
                Util.writeText(factsFile, Json.pretty(Json.obj("version", 1, "facts", facts)));
            }
        }
        reindex();
        state = Json.map(Json.tryParse(Util.readText(stateFile)));
        if (!state.containsKey("submit_times")) {
            state.put("submit_times", new ArrayList<Object>());
        }
        if (!state.containsKey("next_slot_at")) {
            state.put("next_slot_at", 0);
        }
    }

    // ------------------------------------------------------------------ jobs
    private Map<String, Object> find(String id) {
        for (Map<String, Object> j : jobs) {
            if (id.equals(Json.str(j, "id"))) {
                return j;
            }
        }
        return null;
    }

    /** True if the job was already handled (statuses in `retry` count as not handled). */
    public synchronized boolean seen(String jobId, List<String> retry) {
        Map<String, Object> j = find(jobId);
        return j != null && !retry.contains(Json.str(j, "status"));
    }

    /** One job as it was recorded (without its form's questions), or null. */
    public synchronized Map<String, Object> job(String jobId) {
        Map<String, Object> j = find(jobId);
        if (j == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>(j);
        out.remove("qa");
        return out;
    }

    public synchronized String statusOf(String jobId) {
        Map<String, Object> j = find(jobId);
        return j == null ? null : Json.str(j, "status");
    }

    public synchronized void add(String jobId, String status, String title, String company, Object score, String reason,
                                 List<Object> qa) {
        Map<String, Object> j = find(jobId);
        if (j != null) {
            jobs.remove(j);
        }
        jobs.add(Json.obj("id", jobId, "ts", Util.now(), "status", status, "score", score,
                "title", title == null ? "" : title, "company", company == null ? "" : company,
                "url", "https://www.linkedin.com/jobs/view/" + jobId + "/", "reason", reason == null ? "" : reason,
                "qa", qa == null ? new ArrayList<Object>() : qa));
        while (jobs.size() > 5000) {
            jobs.remove(0);
        }
        saveJobs();
        Listener l = listener;
        if (l != null) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>(jobs.get(jobs.size() - 1));
            copy.remove("qa");
            l.onJob(copy);
        }
    }

    private void saveJobs() {
        Util.writeText(jobsFile, Json.stringify(jobs));
    }

    /** Newest first, with label, tone and group for the dashboard. */
    public synchronized List<Object> rows(int limit) {
        List<Map<String, Object>> sorted = new ArrayList<Map<String, Object>>(jobs);
        Collections.sort(sorted, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(Json.dbl(b, "ts", 0), Json.dbl(a, "ts", 0));
            }
        });
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> j : sorted) {
            if (out.size() >= limit) {
                break;
            }
            Map<String, Object> d = new LinkedHashMap<String, Object>(j);
            String st = Json.str(j, "status");
            d.put("label", label(st));
            d.put("tone", tone(st));
            d.put("group", group(st));
            out.add(d);
        }
        return out;
    }

    /** Jobs with this status recorded since `since` (seconds), newest first, at most `limit`. */
    public synchronized List<Object> recent(String status, double since, int limit) {
        List<Object> out = new ArrayList<Object>();
        for (int k = jobs.size() - 1; k >= 0 && out.size() < limit; k--) {  // newest are at the end
            Map<String, Object> j = jobs.get(k);
            if (status.equals(Json.str(j, "status")) && Json.dbl(j, "ts", 0) >= since) {
                Map<String, Object> d = new LinkedHashMap<String, Object>(j);
                d.remove("qa");
                out.add(d);
            }
        }
        return out;
    }

    public synchronized int countSince(String status, double since) {
        int n = 0;
        for (Map<String, Object> j : jobs) {
            if (status.equals(Json.str(j, "status")) && Json.dbl(j, "ts", 0) >= since) {
                n++;
            }
        }
        return n;
    }

    public synchronized int count(String status) {
        int n = 0;
        for (Map<String, Object> j : jobs) {
            if (status.equals(Json.str(j, "status"))) {
                n++;
            }
        }
        return n;
    }

    public synchronized List<String> idsWithStatus(String status) {
        List<Map<String, Object>> hits = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : jobs) {
            if (status.equals(Json.str(j, "status"))) {
                hits.add(j);
            }
        }
        Collections.sort(hits, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(Json.dbl(a, "ts", 0), Json.dbl(b, "ts", 0));
            }
        });
        List<String> ids = new ArrayList<String>();
        for (Map<String, Object> j : hits) {
            ids.add(Json.str(j, "id"));
        }
        return ids;
    }

    /** Applications filled up to Submit that wait for your OK (ready), or that you said yes to (approved), newest first. */
    public synchronized List<Map<String, Object>> ready() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int k = jobs.size() - 1; k >= 0; k--) {
            Map<String, Object> j = jobs.get(k);
            String st = Json.str(j, "status");
            if ("ready".equals(st) || "approved".equals(st)) {
                Map<String, Object> d = new LinkedHashMap<String, Object>(j);
                d.remove("qa");
                d.put("label", label(st));
                out.add(d);
            }
        }
        return out;
    }

    /** Moves these jobs from status `from` to `to` (others are left alone). Returns how many moved. */
    public synchronized int changeStatus(List<String> ids, String from, String to) {
        int n = 0;
        for (String id : ids) {
            Map<String, Object> j = id == null ? null : find(id);
            if (j != null && from.equals(Json.str(j, "status"))) {
                j.put("status", to);
                if ("user_skipped".equals(to)) {
                    j.put("reason", "You said no to sending it");
                }
                n++;
            }
        }
        if (n > 0) {
            saveJobs();
        }
        return n;
    }

    /** Forget stuck / error / waiting jobs so they are tried again. */
    public synchronized int forgetFailed() {
        int n = 0;
        for (int k = jobs.size() - 1; k >= 0; k--) {
            String st = Json.str(jobs.get(k), "status");
            if (st.equals("stuck") || st.equals("error") || st.equals("needs_answer")) {
                jobs.remove(k);
                n++;
            }
        }
        saveJobs();
        return n;
    }

    /** Jobs that failed in version 1.1 because the Easy Apply window did not open (fixed since 1.2): try them again. */
    public synchronized int forgetOldOpenErrors() {
        int n = 0;
        for (int k = jobs.size() - 1; k >= 0; k--) {
            Map<String, Object> j = jobs.get(k);
            if ("error".equals(Json.str(j, "status"))
                    && Json.str(j, "reason").contains("Easy Apply form did not open (page saved in logs/debug)")) {
                jobs.remove(k);
                n++;
            }
        }
        if (n > 0) {
            saveJobs();
        }
        return n;
    }

    /** Jobs that waited for an answer you have now given: the bot tries them again. */
    /** The stage of a job you applied to: applied, interview, offer or rejected. Null when it is not an application. */
    public synchronized Map<String, Object> setStage(String jobId, String stage) {
        Map<String, Object> j = find(jobId);
        if (j == null || !"applied".equals(Json.str(j, "status"))) {
            return null;
        }
        j.put("stage", stage);
        saveJobs();
        Map<String, Object> out = new LinkedHashMap<String, Object>(j);
        out.remove("qa");
        return out;
    }

    public synchronized int markAnswered(List<String> ids) {
        int n = 0;
        for (String id : ids) {
            Map<String, Object> j = id == null ? null : find(id);
            if (j != null && "needs_answer".equals(Json.str(j, "status"))) {
                j.put("status", "answered");
                j.put("ts", Util.now());
                j.put("reason", "You answered the question it waited for; the bot tries it again");
                n++;
            }
        }
        if (n > 0) {
            saveJobs();
        }
        return n;
    }

    public synchronized void deleteJob(String id) {
        Map<String, Object> j = find(id);
        if (j != null) {
            jobs.remove(j);
            saveJobs();
        }
    }

    // ------------------------------------------------------------------ the same data in your account (Sync)
    /** The job log without each form's questions and answers (those stay on this phone). */
    public synchronized List<Map<String, Object>> jobsForSync() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : jobs) {
            Map<String, Object> c = new LinkedHashMap<String, Object>(j);
            c.remove("qa");
            out.add(c);
        }
        return out;
    }

    /** Jobs from your account (another phone, Google Drive), added or replaced by ID; nothing is reported for them. */
    public synchronized void mergeJobs(Map<String, Map<String, Object>> upserts, Collection<String> deletes) {
        for (String id : deletes) {
            Map<String, Object> j = find(id);
            if (j != null) {
                jobs.remove(j);
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : upserts.entrySet()) {
            Map<String, Object> j = new LinkedHashMap<String, Object>(e.getValue());
            j.put("id", e.getKey());
            Map<String, Object> old = find(e.getKey());
            if (old == null) {
                jobs.add(j);
            } else {
                if (old.get("qa") != null && !j.containsKey("qa")) {
                    j.put("qa", old.get("qa"));
                }
                jobs.set(jobs.indexOf(old), j);
            }
        }
        Collections.sort(jobs, new Comparator<Map<String, Object>>() {   // oldest first, as recorded
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(Json.dbl(a, "ts", 0), Json.dbl(b, "ts", 0));
            }
        });
        while (jobs.size() > 5000) {
            jobs.remove(0);
        }
        saveJobs();
    }

    /** Facts from your account: added, replaced or deleted by ID. One made in Google Drive gets its category and the
     *  question it answers here. */
    public void mergeFacts(Map<String, Map<String, Object>> upserts, Collection<String> deletes) {
        synchronized (answersLock) {
            for (String id : deletes) {
                Map<String, Object> f = factById(id);
                if (f != null) {
                    facts.remove(f);
                }
            }
            for (Map.Entry<String, Map<String, Object>> e : upserts.entrySet()) {
                Map<String, Object> f = new LinkedHashMap<String, Object>(e.getValue());
                f.put("id", e.getKey());
                String label = Json.str(f, "label").trim();
                String cat = Json.str(f, "category").trim();
                f.put("category", cat.isEmpty() ? Facts.categorize(label) : Facts.category(cat));
                if (Json.strings(f.get("asked")).isEmpty() && !Util.normKey(label).isEmpty()) {
                    f.put("asked", Json.list(Util.normKey(label)));
                }
                if (!f.containsKey("source")) {
                    f.put("source", "you");
                }
                Map<String, Object> old = factById(e.getKey());
                if (old == null) {
                    facts.add(f);
                } else {
                    facts.set(facts.indexOf(old), f);
                }
            }
            saveFacts();
        }
    }

    /** Signed in with another account: this phone's facts and job log belonged to the one before. */
    public void clearForAccount() {
        synchronized (answersLock) {
            facts.clear();
            saveFacts();
        }
        synchronized (this) {
            jobs.clear();
            saveJobs();
        }
    }

    // ------------------------------------------------------------------ your info (facts)
    private static Map<String, Object> newFact(String category, String label, Object value, String source, String question) {
        List<Object> asked = new ArrayList<Object>();
        String q = Util.normKey(question);
        if (!q.isEmpty()) {
            asked.add(q);
        }
        return Json.obj("id", "f" + Long.toHexString(Util.RANDOM.nextLong() & 0xffffffffffL), "category", Facts.category(category),
                "label", label, "value", value, "asked", asked, "source", source, "updated", Util.now());
    }

    private void reindex() {
        answers.clear();
        for (Map<String, Object> f : facts) {
            for (String q : Json.strings(f.get("asked"))) {
                answers.put(q, f.get("value"));
            }
        }
    }

    private void saveFacts() {
        reindex();
        Util.writeText(factsFile, Json.pretty(Json.obj("version", 1, "facts", facts)));
        Util.writeText(answersFile, Json.pretty(answers));  // the same answers by question, as before
    }

    private Map<String, Object> factAsked(String key) {
        for (Map<String, Object> f : facts) {
            if (Json.strings(f.get("asked")).contains(key)) {
                return f;
            }
        }
        return null;
    }

    private Map<String, Object> factById(String id) {
        for (Map<String, Object> f : facts) {
            if (Json.str(f, "id").equals(id)) {
                return f;
            }
        }
        return null;
    }

    /** The answer to exactly this question (normalized), or null. */
    public Object answer(String key) {
        synchronized (answersLock) {
            return answers.get(key);
        }
    }

    /** An answer the bot found (the AI, or a number LinkedIn accepts). */
    public void putAnswer(String key, Object value) {
        putAnswer(key, value, null, "ai");
    }

    /** Save an answer to a question: it updates the fact that answers it, or becomes a new fact in its category. */
    public void putAnswer(String key, Object value, String question, String source) {
        synchronized (answersLock) {
            String k = Util.normKey(key);
            Map<String, Object> f = factAsked(k);
            if (f != null) {
                f.put("value", value);
                f.put("updated", Util.now());
                if ("you".equals(source)) {
                    f.put("source", "you");
                }
            } else {
                String label = question == null || question.trim().isEmpty() ? Facts.labelOf(k) : question.trim();
                facts.add(newFact(Facts.categorize(label), label, value, source, k));
            }
            saveFacts();
        }
    }

    /** Stop using this answer for this question (the fact goes when no question uses it any more). */
    public boolean removeAnswer(String key) {
        synchronized (answersLock) {
            Map<String, Object> f = factAsked(Util.normKey(key));
            if (f == null) {
                return false;
            }
            List<String> asked = Json.strings(f.get("asked"));
            asked.remove(Util.normKey(key));
            f.put("asked", new ArrayList<Object>(asked));
            if (asked.isEmpty()) {
                facts.remove(f);
            }
            saveFacts();
            return true;
        }
    }

    /** Question -> answer, for every question your info answered. */
    public Map<String, Object> answersCopy() {
        synchronized (answersLock) {
            return Json.map(Json.copy(answers));
        }
    }

    /** Change saved answers by question (older dashboard calls). Returns how many questions have an answer. */
    public int updateAnswers(Map<String, Object> set, List<String> delete, boolean clear) {
        synchronized (answersLock) {
            if (clear) {
                facts.clear();
            }
            if (delete != null) {
                for (String q : delete) {
                    removeAnswer(q);
                }
            }
            if (set != null) {
                for (Map.Entry<String, Object> e : set.entrySet()) {
                    Object a = e.getValue();
                    if (a instanceof List) {
                        List<Object> items = new ArrayList<Object>();
                        for (String x : Json.strings(a)) {
                            if (!x.trim().isEmpty()) {
                                items.add(x.trim());
                            }
                        }
                        a = items.isEmpty() ? null : items;
                    } else {
                        a = a == null || String.valueOf(a).trim().isEmpty() ? null : String.valueOf(a).trim();
                    }
                    if (!Util.normKey(e.getKey()).isEmpty() && a != null) {
                        putAnswer(e.getKey(), a, e.getKey(), "you");
                    }
                }
            }
            saveFacts();
            return answers.size();
        }
    }

    public List<Map<String, Object>> factsCopy() {
        synchronized (answersLock) {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> f : facts) {
                out.add(Json.map(Json.copy(f)));
            }
            return out;
        }
    }

    public int factCount() {
        synchronized (answersLock) {
            return facts.size();
        }
    }

    /** A fact you add yourself. */
    public Map<String, Object> addFact(String category, String label, Object value) {
        synchronized (answersLock) {
            String l = label == null ? "" : label.trim();
            Map<String, Object> f = factAsked(Util.normKey(l));
            if (f == null) {
                f = newFact(category, l, value, "you", l);
                facts.add(f);
            } else {
                f.put("value", value);
                f.put("category", Facts.category(category));
                f.put("source", "you");
                f.put("updated", Util.now());
            }
            saveFacts();
            return Json.map(Json.copy(f));
        }
    }

    /** Change a fact's answer, wording or category. Returns null when it does not exist. */
    public Map<String, Object> updateFact(String id, Map<String, Object> changes) {
        synchronized (answersLock) {
            Map<String, Object> f = factById(id);
            if (f == null) {
                return null;
            }
            if (changes.containsKey("value")) {
                f.put("value", changes.get("value"));
                f.put("source", "you");
            }
            if (changes.containsKey("label") && !Json.str(changes, "label").trim().isEmpty()) {
                f.put("label", Json.str(changes, "label").trim());
            }
            if (changes.containsKey("category")) {
                f.put("category", Facts.category(Json.str(changes, "category")));
            }
            f.put("updated", Util.now());
            saveFacts();
            return Json.map(Json.copy(f));
        }
    }

    public boolean deleteFact(String id) {
        synchronized (answersLock) {
            Map<String, Object> f = factById(id);
            if (f == null) {
                return false;
            }
            facts.remove(f);
            saveFacts();
            return true;
        }
    }

    /** This fact also answers this question (asked in other words). */
    public void addAsked(String id, String question) {
        synchronized (answersLock) {
            Map<String, Object> f = factById(id);
            String q = Util.normKey(question);
            if (f == null || q.isEmpty() || factAsked(q) != null) {
                return;
            }
            List<Object> asked = new ArrayList<Object>(Json.arr(f.get("asked")));
            asked.add(q);
            f.put("asked", asked);
            saveFacts();
        }
    }

    /** Your info for the AI: "Category / fact" -> answer, your own facts first. */
    public Map<String, Object> knownForAi() {
        synchronized (answersLock) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (int pass = 0; pass < 2; pass++) {
                for (Map<String, Object> f : facts) {
                    boolean mine = "you".equals(Json.str(f, "source"));
                    if (mine == (pass == 0)) {
                        out.put(Json.str(f, "category") + " / " + Json.str(f, "label"), f.get("value"));
                    }
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ pacing state
    public Map<String, Object> state() {
        return state;
    }

    public synchronized Map<String, Object> stateCopy() {
        return Json.map(Json.copy(state));
    }

    public synchronized void saveState() {
        Util.writeText(stateFile, Json.pretty(state));
    }

    // ------------------------------------------------------------------ CSV (opens in Excel / Google Sheets)
    public synchronized File writeCsv() {
        StringBuilder b = new StringBuilder("﻿Date,Title,Company,Match,Link\r\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (Object o : rows(5000)) {
            Map<String, Object> r = Json.map(o);
            if (!"applied".equals(Json.str(r, "status"))) {
                continue;
            }
            b.append(csv(f.format(new Date((long) (Json.dbl(r, "ts", 0) * 1000))))).append(',')
                    .append(csv(Json.str(r, "title"))).append(',').append(csv(Json.str(r, "company"))).append(',')
                    .append(csv(r.get("score") == null ? "" : Json.str(r, "score") + "/10")).append(',')
                    .append(csv(Json.str(r, "url"))).append("\r\n");
        }
        Util.writeText(csvFile, b.toString());
        return csvFile;
    }

    private static String csv(String s) {
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
