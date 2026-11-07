package com.autoapply.bot.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What happened during one run: the brief shown at the end and on the dashboard. */
public final class Session {
    public final String mode;
    public final double started;
    public volatile double ended = 0;
    private final List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();

    public Session(String mode) {
        this.mode = mode;
        this.started = Util.now();
    }

    public synchronized void add(String jobId, String status, String title, String company, Object score, String reason) {
        for (int k = jobs.size() - 1; k >= 0; k--) {
            if (jobId.equals(Json.str(jobs.get(k), "id"))) {
                jobs.remove(k);
            }
        }
        jobs.add(Json.obj("id", jobId, "status", status, "title", title == null || title.isEmpty() ? "Job " + jobId : title,
                "company", company == null ? "" : company, "score", score, "reason", reason == null ? "" : reason,
                "url", String.format(LinkedIn.JOB_URL, jobId), "ts", Util.now()));
    }

    /** The question(s) a stuck form failed on, from "Form error: question: message / ...". */
    static List<String> blockers(String reason) {
        List<String> out = new ArrayList<String>();
        String r = reason == null ? "" : reason;
        if (!r.contains("Form error:")) {
            if (!r.isEmpty()) {
                String[] parts = r.split(" \\| ");
                out.add(parts[parts.length - 1]);
            }
            return out;
        }
        for (String part : r.split("Form error:", 2)[1].split(" / ")) {
            String q = part.contains(": ") ? part.substring(0, part.lastIndexOf(": ")).trim() : part.trim();
            if (!q.isEmpty()) {
                out.add(q);
            }
        }
        return out;
    }

    public synchronized Map<String, Object> summary() {
        List<Object> applied = new ArrayList<Object>();
        List<Object> ready = new ArrayList<Object>();
        List<Object> filled = new ArrayList<Object>();
        List<Object> stuck = new ArrayList<Object>();
        List<Object> errors = new ArrayList<Object>();
        List<Object> questions = new ArrayList<Object>();
        final Map<String, Integer> skipped = new LinkedHashMap<String, Integer>();
        final Map<String, Integer> blockers = new LinkedHashMap<String, Integer>();
        int skippedTotal = 0;
        for (Map<String, Object> j : jobs) {
            String st = Json.str(j, "status");
            Map<String, Object> copy = new LinkedHashMap<String, Object>(j);
            if (st.equals("applied")) {
                applied.add(copy);
            } else if (st.equals("ready") || st.equals("approved")) {
                ready.add(copy);
            } else if (st.equals("dry_run_match")) {
                filled.add(copy);
            } else if (st.equals("stuck")) {
                stuck.add(copy);
                for (String q : blockers(Json.str(j, "reason"))) {
                    blockers.put(q, (blockers.containsKey(q) ? blockers.get(q) : 0) + 1);
                }
            } else if (st.equals("error")) {
                errors.add(copy);
            } else if (st.equals("needs_answer")) {
                questions.add(copy);
            } else {
                String label = Store.label(st).toLowerCase();
                skipped.put(label, (skipped.containsKey(label) ? skipped.get(label) : 0) + 1);
                skippedTotal++;
            }
        }
        List<String> skipKeys = new ArrayList<String>(skipped.keySet());
        Collections.sort(skipKeys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return skipped.get(b) - skipped.get(a);
            }
        });
        Map<String, Object> skippedOut = new LinkedHashMap<String, Object>();
        for (String k : skipKeys) {
            skippedOut.put(k, skipped.get(k));
        }
        List<String> blockKeys = new ArrayList<String>(blockers.keySet());
        Collections.sort(blockKeys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return blockers.get(b) - blockers.get(a);
            }
        });
        List<Object> blockOut = new ArrayList<Object>();
        for (String k : blockKeys) {
            if (blockOut.size() < 8) {
                blockOut.add(Json.list(k, blockers.get(k)));
            }
        }
        return Json.obj("mode", mode, "started", started, "ended", ended == 0 ? null : ended, "applied", applied,
                "ready", ready, "filled", filled, "stuck", stuck, "errors", errors, "questions", questions, "skipped", skippedOut,
                "skipped_total", skippedTotal, "blockers", blockOut, "total", jobs.size());
    }

    public List<String> lines() {
        Map<String, Object> s = summary();
        double end = ended == 0 ? Util.now() : ended;
        String bar = "======================================================================";
        List<String> out = new ArrayList<String>();
        out.add(bar);
        out.add("SESSION BRIEF  " + Util.fmtWhen(started) + " -> " + Util.fmtWhen(end) + " (" + Util.fmtDuration(end - started) + ")");
        List<Object> applied = Json.arr(s.get("applied"));
        out.add(applied.isEmpty() ? "Applied to no jobs." : "Applied to " + applied.size() + " job(s):");
        for (Object o : applied) {
            Map<String, Object> j = Json.map(o);
            out.add("  + " + Json.str(j, "title") + " @ " + Json.str(j, "company") + "   " + Json.str(j, "url"));
        }
        List<Object> ready = Json.arr(s.get("ready"));
        if (!ready.isEmpty()) {
            out.add("Ready to send, waiting for your yes: " + ready.size());
            for (Object o : ready) {
                Map<String, Object> j = Json.map(o);
                out.add("  > " + Json.str(j, "title") + " @ " + Json.str(j, "company") + "   " + Json.str(j, "url"));
            }
        }
        List<Object> filled = Json.arr(s.get("filled"));
        if (!filled.isEmpty()) {
            out.add(("dry".equals(mode) ? "Matched (dry run)" : "Filled, stopped before Submit (test run)") + ": " + filled.size());
            for (Object o : filled) {
                Map<String, Object> j = Json.map(o);
                out.add("  ~ " + Json.str(j, "title") + " @ " + Json.str(j, "company") + "   " + Json.str(j, "url"));
            }
        }
        List<Object> stuck = Json.arr(s.get("stuck"));
        if (!stuck.isEmpty()) {
            out.add("Stuck on " + stuck.size() + " job(s) (form closed without submitting):");
            for (Object o : stuck) {
                Map<String, Object> j = Json.map(o);
                String r = Json.str(j, "reason");
                String why = r.contains("Form error") ? r.substring(r.lastIndexOf("Form error: ") + 12) : r.substring(r.lastIndexOf(" | ") + (r.contains(" | ") ? 3 : 0));
                out.add("  ! " + Json.str(j, "title") + " @ " + Json.str(j, "company") + ": " + Util.cut(why, 150));
            }
        }
        List<Object> errors = Json.arr(s.get("errors"));
        if (!errors.isEmpty()) {
            out.add("Errors on " + errors.size() + " job(s):");
            for (Object o : errors) {
                Map<String, Object> j = Json.map(o);
                out.add("  x " + Json.str(j, "title") + " @ " + Json.str(j, "company") + ": " + Util.cut(Json.str(j, "reason"), 150));
            }
        }
        List<Object> questions = Json.arr(s.get("questions"));
        if (!questions.isEmpty()) {
            out.add("Waiting for your answer on " + questions.size() + " job(s) (answer in Saved answers, the bot then tries them again):");
            for (Object o : questions) {
                Map<String, Object> j = Json.map(o);
                String r = Json.str(j, "reason");
                String q = r.contains("Waiting for your answer: ") ? r.substring(r.lastIndexOf("Waiting for your answer: ") + 25) : r;
                out.add("  ? " + Json.str(j, "title") + " @ " + Json.str(j, "company") + ": " + Util.cut(q, 150));
            }
        }
        long skippedTotal = Json.num(s, "skipped_total", 0);
        if (skippedTotal > 0) {
            List<String> parts = new ArrayList<String>();
            for (Map.Entry<String, Object> e : Json.map(s.get("skipped")).entrySet()) {
                parts.add(e.getKey() + " " + e.getValue());
            }
            out.add("Skipped " + skippedTotal + ": " + Util.join(", ", parts));
        }
        List<Object> blockers = Json.arr(s.get("blockers"));
        if (!blockers.isEmpty()) {
            List<String> parts = new ArrayList<String>();
            for (int k = 0; k < Math.min(3, blockers.size()); k++) {
                List<Object> b = Json.arr(blockers.get(k));
                parts.add("\"" + b.get(0) + "\" (" + b.get(1) + ")");
            }
            out.add("What blocked forms most: " + Util.join("; ", parts));
        }
        out.add(bar);
        return out;
    }

    public Map<String, Object> save(File summaryFile, File textFile) {
        Map<String, Object> data = summary();
        Util.writeText(summaryFile, Json.pretty(data));
        Util.writeText(textFile, Util.join("\n", lines()) + "\n");
        return data;
    }
}
