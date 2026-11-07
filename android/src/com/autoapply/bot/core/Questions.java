package com.autoapply.bot.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Questions for you: the question the bot waits on, checks on your answers, and questions waiting for later. */
public final class Questions {
    private Questions() {
    }

    /** Live settings the dashboard can change while the bot runs. */
    public static volatile String askMode = "ask";
    public static volatile int askTimeoutMin = 10;

    /** "ask": you answer each new question; "ai": the AI answers them; "unsure": the AI answers the ones it is sure
     *  about and asks you the rest. */
    public static String askModeOf(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("unsure") || v.equals("sure") || v.equals("mixed")) {
            return "unsure";
        }
        return v.equals("ai") || v.equals("auto") || v.equals("off") || v.equals("no") || v.equals("false") || v.equals("0")
                ? "ai" : "ask";
    }

    /** A question had no saved answer and you skipped the job, or did not answer in time. */
    public static class NeedsAnswer extends RuntimeException {
        public final String question;
        public final String status;
        public final String reason;

        public NeedsAnswer(String question, String status, String reason) {
            super(reason);
            this.question = question;
            this.status = status;
            this.reason = reason;
        }
    }

    // ------------------------------------------------------------------ the question the bot waits on
    public static final class Desk {
        private Map<String, Object> item;
        private Map<String, Object> reply;
        private int seq = 0;
        int timeouts = 0;
        private double lastTimeoutAt = 0;
        private double seenAt = 0;

        public synchronized String post(Map<String, Object> it) {
            seq++;
            it.put("id", "q" + seq);
            item = it;
            reply = null;
            return "q" + seq;
        }

        public synchronized void update(String qid, Object... kv) {
            if (item != null && qid.equals(item.get("id"))) {
                for (int k = 0; k + 1 < kv.length; k += 2) {
                    item.put(String.valueOf(kv[k]), kv[k + 1]);
                }
            }
        }

        public synchronized Map<String, Object> get() {
            return item == null ? null : Json.map(Json.copy(item));
        }

        /** Your answer (or skip). Returns an error message, or "" when accepted. */
        public synchronized String respond(String qid, String action, Object answer, boolean remember) {
            if (item == null || !String.valueOf(qid).equals(item.get("id"))) {
                return "This question was already answered or skipped.";
            }
            if (reply != null) {
                return "";
            }
            if ("skip".equals(action)) {
                reply = Json.obj("action", "skip");
            } else {
                Object[] c = coerceAnswer(item, answer);
                if (!((String) c[1]).isEmpty()) {
                    return (String) c[1];
                }
                reply = Json.obj("action", "answer", "value", c[0], "remember", remember);
            }
            timeouts = 0;
            seenAt = Util.now();
            return "";
        }

        public synchronized Map<String, Object> take(String qid) {
            if (item != null && qid.equals(item.get("id")) && reply != null) {
                Map<String, Object> r = reply;
                reply = null;
                return r;
            }
            return null;
        }

        public synchronized void clear(String qid) {
            if (item != null && qid.equals(item.get("id"))) {
                item = null;
                reply = null;
            }
        }

        public synchronized void timedOut() {
            timeouts++;
            lastTimeoutAt = Util.now();
        }

        public synchronized void markPresent() {
            seenAt = Util.now();
        }

        /** Two questions in a row got no answer and the dashboard was not used since: you are probably away. */
        public synchronized boolean away() {
            return timeouts >= 2 && seenAt < lastTimeoutAt;
        }
    }

    public static final Desk DESK = new Desk();

    // ------------------------------------------------------------------ checking your answer
    /** Check an answer against the field: {value (String or List), error ("" when fine)}. */
    public static Object[] coerceAnswer(Map<String, Object> item, Object answer) {
        String kind = Json.str(item, "kind");
        List<String> options = Json.strings(item.get("options"));
        if ("multi".equals(kind)) {
            List<String> values = new ArrayList<String>();
            if (answer instanceof List) {
                values.addAll(Json.strings(answer));
            } else {
                for (String v : String.valueOf(answer == null ? "" : answer).split("\\s*\\|\\s*")) {
                    values.add(v);
                }
            }
            LinkedHashSet<String> picked = new LinkedHashSet<String>();
            for (String v : values) {
                if (!v.trim().isEmpty()) {
                    String m = Ai.matchOption(v, options);
                    if (m != null) {
                        picked.add(m);
                    }
                }
            }
            return picked.isEmpty() ? new Object[]{null, "Tick at least one option."}
                    : new Object[]{new ArrayList<Object>(picked), ""};
        }
        if (answer instanceof List) {
            List<Object> l = Json.arr(answer);
            answer = l.isEmpty() ? "" : l.get(0);
        }
        String text = String.valueOf(answer == null ? "" : answer).trim();
        if (text.isEmpty()) {
            return new Object[]{null, "Type an answer or pick a suggestion."};
        }
        String value;
        if (!options.isEmpty()) {
            value = Ai.matchOption(text, options);
            if (value == null) {
                return new Object[]{null, "Pick one of the options."};
            }
        } else if ("numeric".equals(kind)) {
            Matcher m = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?").matcher(text);
            if (!m.find()) {
                return new Object[]{null, "This question needs a number."};
            }
            value = m.group().replace(",", "").replaceAll("\\.0+$", "");
        } else {
            int maxlen = (int) Json.num(item, "maxlen", 0);
            value = maxlen > 0 && text.length() > maxlen ? LinkedIn.shorten(text, maxlen) : text;
        }
        if (Json.strings(item.get("rejected")).contains(value)) {
            return new Object[]{null, "LinkedIn already rejected this answer. Try a different one."};
        }
        return new Object[]{value, ""};
    }

    // ------------------------------------------------------------------ similar saved answers
    public static double similarity(String a, String b) {
        // Ratcliff/Obershelp, the same measure as Python's difflib.SequenceMatcher.ratio()
        int total = a.length() + b.length();
        return total == 0 ? 1.0 : 2.0 * matches(a, b) / total;
    }

    private static int matches(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int bestI = 0;
        int bestJ = 0;
        int best = 0;
        int[] prev = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) {
                        best = cur[j];
                        bestI = i - best;
                        bestJ = j - best;
                    }
                }
            }
            prev = cur;
        }
        if (best == 0) {
            return 0;
        }
        return best + matches(a.substring(0, bestI), b.substring(0, bestJ))
                + matches(a.substring(bestI + best), b.substring(bestJ + best));
    }

    /** Saved answers to questions worded almost the same way. */
    public static List<Object> similarAnswers(Map<String, Object> answers, String key, List<String> options, String kind) {
        List<Object[]> found = new ArrayList<Object[]>();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            String k = e.getKey();
            if (k.equals(key)) {
                continue;
            }
            int la = key.length();
            int lb = k.length();
            if (2.0 * Math.min(la, lb) / Math.max(1, la + lb) < 0.75) {
                continue;  // cannot reach 0.75
            }
            double r = similarity(key, k);
            if (r >= 0.75) {
                found.add(new Object[]{r, k, e.getValue()});
            }
        }
        Collections.sort(found, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                return Double.compare((Double) y[0], (Double) x[0]);
            }
        });
        List<Object> out = new ArrayList<Object>();
        for (Object[] f : found) {
            Object v = f[2];
            if (options != null && !options.isEmpty()) {
                List<String> values = v instanceof List ? Json.strings(v) : new ArrayList<String>();
                if (!(v instanceof List)) {
                    for (String s : String.valueOf(v).split("\\s*\\|\\s*")) {
                        values.add(s);
                    }
                }
                LinkedHashSet<String> mapped = new LinkedHashSet<String>();
                for (String s : values) {
                    String m = Ai.matchOption(s, options);
                    if (m != null) {
                        mapped.add(m);
                    }
                }
                if (mapped.isEmpty()) {
                    continue;
                }
                v = "multi".equals(kind) ? new ArrayList<Object>(mapped) : mapped.iterator().next();
            } else if ("numeric".equals(kind) && !String.valueOf(v).trim().matches("\\d+(\\.\\d+)?")) {
                continue;
            } else if (v instanceof List) {
                v = Util.join(", ", Json.strings(v));
            }
            out.add(Json.obj("question", f[1], "answer", v));
            if (out.size() >= 2) {
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ questions waiting for your answer
    public static final class Waiting {
        private final File file;

        public Waiting(File dir) {
            file = new File(dir, "open_questions.json");
        }

        private List<Map<String, Object>> read() {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Object o : Json.arr(Json.tryParse(Util.readText(file)))) {
                Map<String, Object> m = Json.map(o);
                if (!Json.str(m, "key").isEmpty()) {
                    out.add(m);
                }
            }
            return out;
        }

        private void write(List<Map<String, Object>> items) {
            List<Map<String, Object>> keep = items.size() > 300 ? items.subList(items.size() - 300, items.size()) : items;
            Util.writeText(file, Json.pretty(keep));
        }

        public synchronized List<Map<String, Object>> all() {
            return read();
        }

        public synchronized Map<String, Object> get(String key) {
            for (Map<String, Object> m : read()) {
                if (key.equals(Json.str(m, "key"))) {
                    return m;
                }
            }
            return null;
        }

        public synchronized void add(Map<String, Object> item, Map<String, Object> job) {
            List<Map<String, Object>> items = read();
            Map<String, Object> cur = null;
            for (Map<String, Object> m : items) {
                if (Json.str(item, "key").equals(Json.str(m, "key"))) {
                    cur = m;
                }
            }
            if (cur == null) {
                cur = Json.obj("key", Json.str(item, "key"), "jobs", new ArrayList<Object>(), "asked_at", Util.now());
                items.add(cur);
            }
            for (String k : new String[]{"question", "kind", "options", "maxlen", "placeholder", "hint", "rejected"}) {
                cur.put(k, item.get(k));
            }
            if (!Json.arr(item.get("suggestions")).isEmpty() || !cur.containsKey("suggestions")) {
                cur.put("suggestions", item.get("suggestions") == null ? new ArrayList<Object>() : item.get("suggestions"));
                cur.put("reason", Json.str(item, "reason"));
            }
            List<Object> jobs = Json.arr(cur.get("jobs"));
            cur.put("jobs", jobs);
            String id = Json.str(job, "id");
            boolean known = false;
            for (Object j : jobs) {
                if (id.equals(Json.str(Json.map(j), "id"))) {
                    known = true;
                }
            }
            if (!id.isEmpty() && !known) {
                jobs.add(job);
            }
            cur.put("updated", Util.now());
            write(items);
        }

        public synchronized Map<String, Object> pop(String key) {
            List<Map<String, Object>> items = read();
            Map<String, Object> hit = null;
            for (int k = items.size() - 1; k >= 0; k--) {
                if (key.equals(Json.str(items.get(k), "key"))) {
                    hit = items.remove(k);
                }
            }
            if (hit != null) {
                write(items);
            }
            return hit;
        }

        public synchronized int count() {
            return read().size();
        }
    }
}
