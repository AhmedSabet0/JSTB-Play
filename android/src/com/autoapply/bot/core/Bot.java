package com.autoapply.bot.core;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.autoapply.bot.core.Util.log;

/** Question answering and application helpers for the assisted apply companion. */
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
        public boolean sendOnly;

        public Map<String, Object> toMap() {
            return Json.obj("dry_run", dryRun, "no_submit", noSubmit, "once", once, "retry_failed", retryFailed,
                    "send_only", sendOnly);
        }
    }

    public static String searchUrl(String keyword, String location, Map<String, String> cfg, int pageNo) {
        StringBuilder q = new StringBuilder("keywords=" + enc(keyword) + "&f_AL=true&sortBy=DD");
        if (location != null && !location.isEmpty()) {
            q.append("&location=").append(enc(location));
        }
        if (pageNo > 0) {
            q.append("&start=").append(pageNo * 25);
        }
        return "https://www.linkedin.com/jobs/search/?" + q;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

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

    public final Engine engine;
    public final Store store;
    public final Ai ai;
    public final String cvText;
    public boolean neverAsk = false;
    public int perHour = 10;
    public int perDay = 30;
    public int minScore = 6;
    private String jobTitle = "";
    private String jobCompany = "";
    private String askNote = "";

    public Bot(Engine engine, Map<String, String> cfg, Options args, Ai ai, LinkedIn li, List<String> keywords,
               String location, Session session) {
        this.engine = engine;
        this.store = engine.store;
        this.ai = ai;
        this.cvText = engine.config.cvText();
    }

    public void setJob(String title, String company) {
        this.jobTitle = title == null ? "" : title;
        this.jobCompany = company == null ? "" : company;
    }

    String jobLabel() {
        return (jobTitle.isEmpty() ? "" : jobTitle) + (jobCompany.isEmpty() ? "" : " at " + jobCompany);
    }

    @Override
    public Object resolve(String question, String kind, List<String> options, String hint, String placeholder, boolean noCache,
                          List<String> rejected, int maxlen, List<String> extra) {
        String key = Util.normKey(question);
        Object cached = noCache ? null : store.answer(key);
        if ("numeric".equals(kind) && cached != null && !String.valueOf(cached).trim().matches("\\d+(\\.\\d+)?")) {
            cached = null;
        }
        if (cached != null) {
            if (options == null || options.isEmpty()) {
                return cached;
            }
            String hit = Ai.matchOption(cached, options);
            if (hit != null) {
                return hit;
            }
        }
        Profile.Hit hit = engine.profile.answer(question, kind, options, cvText, Util.now());
        if (hit != null && !(rejected != null && rejected.contains(shown(hit.value)))) {
            return hit.value;
        }
        Object fact = fromFacts(question, kind, options, rejected);
        if (fact != null) {
            return fact;
        }
        Object ans = ai.answer(question, kind, options, store.knownForAi(), jobLabel(), hint, placeholder, rejected, maxlen);
        if (ans != null) {
            store.putAnswer(key, ans);
        }
        return ans;
    }

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
                return v;
            }
        }
        return null;
    }

    static String shown(Object v) {
        return v instanceof List ? Util.join(", ", Json.strings(v)) : String.valueOf(v);
    }

    public void run() {
    }

    public boolean reopenPage() {
        return false;
    }

    @Override
    public void remember(String question, Object value) {
        store.putAnswer(Util.normKey(question), value);
    }

    @Override
    public void forget(String question) {
        store.deleteAnswer(Util.normKey(question));
    }

    @Override
    public boolean manual() {
        return false;
    }

    @Override
    public void signInNeeded() {
    }
}
