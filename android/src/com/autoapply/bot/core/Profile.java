package com.autoapply.bot.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your answers to the questions most Easy Apply forms ask (the setup screen), and the rules that fill forms from them:
 * years of experience with something in your CV = years since you graduated, "how many" = a number, notice period in
 * days, salary in your currency (converted with today's rate when a question names another one), phone, city, military
 * service, relocation, on-site work, work permit, visa sponsorship, English level and driving license.
 */
public final class Profile {
    public static final String[] FIELDS = {"phone", "city", "graduation_year", "current_salary", "expected_salary",
        "currency", "notice_days", "military", "relocate", "onsite", "authorized", "sponsorship", "english", "driving"};
    public static final String[] ENGLISH = {"Native", "Fluent", "Professional", "Good", "Basic"};
    public static final String[] MILITARY = {"Completed", "Exempted", "Postponed", "Not applicable"};
    public static final String[] CURRENCIES = {"EGP", "USD", "EUR", "GBP", "SAR", "AED", "QAR", "KWD", "OMR", "BHD", "JOD"};

    /** Exchange rates: https://www.exchangerate-api.com (free, no key; changed by tests). */
    public static volatile String ratesUrl = "https://open.er-api.com/v6/latest/";
    /** EGP for one unit of each currency, used only when the live rate cannot be fetched (estimates, Nov 2026). */
    static final String[][] EGP_PER_UNIT = {{"EGP", "1"}, {"USD", "51"}, {"EUR", "58"}, {"GBP", "68"}, {"SAR", "13.6"},
        {"AED", "13.9"}, {"QAR", "14"}, {"KWD", "166"}, {"OMR", "132"}, {"BHD", "135"}, {"JOD", "72"}};

    private final File file;
    private final File ratesFile;
    private double ratesFailedAt = 0;
    /** Your info: years you entered for a skill come before the years since you graduated. */
    public volatile Store store;

    public Profile(File dir) {
        file = new File(dir, "profile.json");
        ratesFile = new File(dir, "rates.json");
    }

    // ------------------------------------------------------------------ the saved answers of the setup screen
    public synchronized Map<String, Object> load() {
        Map<String, Object> saved = Json.map(Json.tryParse(Util.readText(file)));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String f : FIELDS) {
            Object v = saved.get(f);
            out.put(f, v == null ? "" : String.valueOf(v).trim());
        }
        if (Json.str(out, "currency").isEmpty()) {
            out.put("currency", "EGP");
        }
        out.put("done", Json.bool(saved, "done"));
        return out;
    }

    public synchronized Map<String, Object> save(Map<String, Object> values) {
        Map<String, Object> cur = load();
        for (String f : FIELDS) {
            if (values.containsKey(f)) {
                cur.put(f, clean(f, values.get(f) == null ? "" : String.valueOf(values.get(f))));
            }
        }
        cur.put("done", true);
        Util.writeText(file, Json.pretty(cur));
        return cur;
    }

    /** Answers from your account (another phone, Google Drive), cleaned the same way; done as it was there. */
    public synchronized void merge(Map<String, Object> values) {
        Map<String, Object> cur = load();
        for (String f : FIELDS) {
            if (values.containsKey(f)) {
                cur.put(f, clean(f, values.get(f) == null ? "" : String.valueOf(values.get(f))));
            }
        }
        if (values.containsKey("done")) {
            Object d = values.get("done");
            cur.put("done", Boolean.TRUE.equals(d) || "1".equals(String.valueOf(d)) || "true".equals(String.valueOf(d)));
        }
        Util.writeText(file, Json.pretty(cur));
    }

    /** Signed in with another account: these answers belonged to the one before. */
    public synchronized void clear() {
        file.delete();
    }

    static String clean(String field, String raw) {
        String v = raw.replace('\n', ' ').trim();
        if (field.equals("current_salary") || field.equals("expected_salary") || field.equals("notice_days")
                || field.equals("graduation_year")) {
            v = v.replace(",", "").replaceAll("\\.\\d*$", "").replaceAll("[^0-9]", "");
        } else if (field.equals("currency")) {
            v = v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
            if (v.length() != 3) {
                v = "EGP";
            }
        } else if (field.equals("phone")) {
            v = v.replaceAll("[^0-9+ ()-]", "").trim();
        }
        return v.length() > 120 ? v.substring(0, 120) : v;
    }

    /** Phone and graduation year found in the CV text without the AI (used when the AI cannot be reached). */
    public static Map<String, Object> fromCvText(String cv) {
        String text = cv == null ? "" : cv;
        String phone = "";
        Matcher m = Pattern.compile("(?:\\+|00)?(?:20[\\s-]?)?\\(?0?1[0125]\\)?[\\s-]?\\d{3,4}[\\s-]?\\d{4}\\b").matcher(text);
        if (m.find()) {
            phone = m.group().trim();
        } else if ((m = Pattern.compile("\\+\\d[\\d\\s()-]{8,16}\\d").matcher(text)).find()) {
            phone = m.group().trim();
        }
        int best = -1;
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        Pattern degree = Pattern.compile("(?i)b\\.?\\s?sc|bachelor|graduat|faculty|university|college|degree|class of");
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!degree.matcher(lines[i]).find()) {
                continue;
            }
            Pattern years = Pattern.compile("\\b(19[89]\\d|20[0-4]\\d)\\b");
            String near = lines[i];
            if (!years.matcher(near).find() && i + 1 < lines.length) {
                near += " " + lines[i + 1];  // "Bachelor of ..." with the dates on the next line
            }
            Matcher y = years.matcher(near);
            while (y.find()) {
                int year = Integer.parseInt(y.group(1));
                if (year <= thisYear + 1 && year > best) {
                    best = year;
                }
            }
        }
        int firstJob = earliestJobYear(text);
        return Json.obj("phone", clean("phone", phone), "city", "", "graduation_year", best > 0 ? String.valueOf(best) : "",
                "earliest_job_year", firstJob > 0 ? String.valueOf(firstJob) : "",
                "english", "", "titles", new ArrayList<Object>(), "location", "");
    }

    /** Finds the earliest job start year from the CV text (looking in experience sections or job date ranges). */
    public static int earliestJobYear(String cvText) {
        if (cvText == null || cvText.trim().isEmpty()) {
            return -1;
        }
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);

        Pattern secPat = Pattern.compile("(?is)(?:work\\s+|professional\\s+|employment\\s+)?experience\\b.*?(?:education|academic|certification|course|language|skill|\\z)");
        Matcher secMat = secPat.matcher(cvText);
        String targetText = secMat.find() ? secMat.group() : cvText;

        int earliest = -1;
        Pattern rangePat = Pattern.compile("(?i)\\b(19[89]\\d|20[0-4]\\d)\\s*(?:[-–—/]|to)\\s*(?:present|current|now|ongoing|\\b(19[89]\\d|20[0-4]\\d)\\b)");
        Matcher m = rangePat.matcher(targetText);
        while (m.find()) {
            int y = Integer.parseInt(m.group(1));
            if (y >= 1990 && y <= thisYear) {
                if (earliest < 0 || y < earliest) {
                    earliest = y;
                }
            }
        }

        Pattern monthYearPat = Pattern.compile("(?i)(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)[a-z]*\\.?\\s+(19[89]\\d|20[0-4]\\d)\\s*(?:[-–—/]|to)");
        Matcher mm = monthYearPat.matcher(targetText);
        while (mm.find()) {
            int y = Integer.parseInt(mm.group(1));
            if (y >= 1990 && y <= thisYear) {
                if (earliest < 0 || y < earliest) {
                    earliest = y;
                }
            }
        }

        return earliest;
    }

    /**
     * Total years of experience calculated from the start of the candidate's first job in the CV until now.
     * Falls back to years since graduation or 2.
     */
    public static int totalExperienceYears(Map<String, Object> p, String cvText, double now) {
        int jobYear = earliestJobYear(cvText);
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        if (jobYear > 1980 && jobYear <= thisYear) {
            return Math.max(1, thisYear - jobYear);
        }
        int gradYears = yearsSince(Json.str(p, "graduation_year"), now);
        if (gradYears >= 0) {
            return Math.max(1, gradYears);
        }
        return 2;
    }

    /** Whole years since graduating (counted from July of that year), or -1 when the year is not set. */
    public static int yearsSince(String gradYear, double now) {
        int y;
        try {
            y = Integer.parseInt(gradYear == null ? "" : gradYear.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        if (y < 1950 || y > 2100) {
            return -1;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis((long) (now * 1000));
        double t = c.get(Calendar.YEAR) + (c.get(Calendar.DAY_OF_YEAR) - 1) / 366.0;
        return (int) Math.max(0, Math.floor(t - (y + 0.5)));
    }

    /** The answers as notes for the AI (for questions the rules do not cover). */
    public String notes(double now) {
        Map<String, Object> p = load();
        List<String> l = new ArrayList<String>();
        String cur = Json.str(p, "currency");
        line(l, "Phone", Json.str(p, "phone"));
        line(l, "Lives in", Json.str(p, "city"));
        int years = yearsSince(Json.str(p, "graduation_year"), now);
        if (years >= 0) {
            l.add("Graduated in " + Json.str(p, "graduation_year") + " (" + years + " years of experience since graduation)");
        }
        if (!Json.str(p, "current_salary").isEmpty()) {
            l.add("Current monthly salary: " + Json.str(p, "current_salary") + " " + cur);
        }
        if (!Json.str(p, "expected_salary").isEmpty()) {
            l.add("Expected monthly salary: " + Json.str(p, "expected_salary") + " " + cur);
        }
        String notice = Json.str(p, "notice_days");
        if (!notice.isEmpty()) {
            l.add("0".equals(notice) ? "Notice period: none, can start immediately" : "Notice period: " + notice + " days");
        }
        line(l, "Military service", Json.str(p, "military"));
        line(l, "Willing to relocate", Json.str(p, "relocate"));
        line(l, "OK with on-site work and commuting", Json.str(p, "onsite"));
        line(l, "Authorized to work in", Json.str(p, "authorized"));
        line(l, "Needs visa sponsorship", Json.str(p, "sponsorship"));
        line(l, "English level", Json.str(p, "english"));
        line(l, "Driving license", Json.str(p, "driving"));
        return Util.join("\n", l);
    }

    private static void line(List<String> l, String label, String value) {
        if (!value.isEmpty()) {
            l.add(label + ": " + value);
        }
    }

    // ------------------------------------------------------------------ answering a form question
    public static final class Hit {
        public final Object value;
        public final String why;

        Hit(Object value, String why) {
            this.value = value;
            this.why = why;
        }
    }

    /** An answer from your setup answers, or null when the rules do not cover this question (then the saved answers,
     *  you or the AI answer it). `kind`: text, numeric, textarea, choice or multi. */
    public Hit answer(String question, String kind, List<String> options, String cvText, double now) {
        if ("textarea".equals(kind) || "multi".equals(kind)) {
            return null;
        }
        String q = norm(question);
        if (q.isEmpty()) {
            return null;
        }
        Map<String, Object> p = load();
        List<String> opts = options == null ? new ArrayList<String>() : options;
        String k = kind == null ? "text" : kind;
        Hit h = sponsorship(p, q, opts);
        if (h == null) {
            h = authorization(p, q, opts);
        }
        if (h == null) {
            h = relocateOrCommute(p, q, k, opts);
        }
        if (h == null) {
            h = notice(p, q, k, opts);
        }
        if (h == null) {
            h = salary(p, q, k, opts);
        }
        if (h == null) {
            h = experience(p, q, k, opts, cvText == null ? "" : cvText, now);
        }
        if (h == null) {
            h = military(p, q, opts);
        }
        if (h == null) {
            h = english(p, q, opts);
        }
        if (h == null) {
            h = driving(p, q, opts);
        }
        if (h == null) {
            h = graduation(p, q, k, opts);
        }
        if (h == null) {
            h = phone(p, q, k, opts);
        }
        if (h == null) {
            h = city(p, q, k, opts);
        }
        if (h == null) {
            h = heardAbout(q, opts);
        }
        return h;
    }

    static String norm(String question) {
        return String.valueOf(question == null ? "" : question).toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("\\s+", " ").replaceAll("[\\s*]+$", "").trim();
    }

    private static boolean has(String q, String regex) {
        return Pattern.compile(regex).matcher(q).find();
    }

    // ---- yes / no
    static boolean isYesNo(List<String> options) {
        if (options.size() < 2 || options.size() > 4) {
            return false;
        }
        return yesNoOption(options, true) != null && yesNoOption(options, false) != null;
    }

    static String yesNoOption(List<String> options, boolean yes) {
        for (String o : options) {
            String t = o.trim().toLowerCase(Locale.ROOT);
            if (yes ? t.matches("^yes\\b.*") : t.matches("^no\\b.*")) {
                return o;
            }
        }
        return null;
    }

    /** "Yes"/"No" for a text box, or the matching option (null if the options are not yes/no). */
    static Hit yesNo(List<String> options, boolean yes, String why) {
        if (options.isEmpty()) {
            return new Hit(yes ? "Yes" : "No", why);
        }
        String o = yesNoOption(options, yes);
        return o == null ? null : new Hit(o, why);
    }

    static Boolean flag(String v) {
        String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        if (t.equals("yes") || t.equals("true") || t.equals("y")) {
            return Boolean.TRUE;
        }
        if (t.equals("no") || t.equals("false") || t.equals("n")) {
            return Boolean.FALSE;
        }
        return null;
    }

    // ---- countries (work permit, sponsorship)
    static final String[][] COUNTRIES = {
        {"Egypt", "egypt"}, {"Saudi Arabia", "saudi|\\bksa\\b"}, {"United Arab Emirates", "\\buae\\b|united arab emirates|emirates|dubai|abu dhabi"},
        {"Qatar", "qatar|doha"}, {"Kuwait", "kuwait"}, {"Oman", "\\boman\\b"}, {"Bahrain", "bahrain"}, {"Jordan", "jordan"},
        {"Lebanon", "lebanon"}, {"Morocco", "morocco"}, {"Tunisia", "tunisia"}, {"Algeria", "algeria"}, {"Iraq", "\\biraq\\b"},
        {"Turkey", "turkey|türkiye|turkiye"}, {"United States", "united states|\\busa\\b|\\bu\\.s\\.|america"},
        {"Canada", "canada"}, {"United Kingdom", "united kingdom|\\buk\\b|britain|england"}, {"Ireland", "ireland"},
        {"Germany", "germany"}, {"France", "france"}, {"Netherlands", "netherlands|holland"}, {"Spain", "spain"},
        {"Italy", "italy"}, {"Poland", "poland"}, {"Sweden", "sweden"}, {"Switzerland", "switzerland"},
        {"European Union", "european union|\\beu\\b"}, {"Australia", "australia"}, {"India", "\\bindia\\b"},
        {"Malaysia", "malaysia"}, {"Singapore", "singapore"},
    };

    static String country(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String[] c : COUNTRIES) {
            if (has(t, c[1])) {
                return c[0];
            }
        }
        return null;
    }

    static boolean authorizedFor(Map<String, Object> p, String country) {
        for (String part : Json.str(p, "authorized").split("[,;/]|\\band\\b")) {
            String c = country(part.trim());
            if (country.equals(c)) {
                return true;
            }
        }
        return false;
    }

    private Hit sponsorship(Map<String, Object> p, String q, List<String> opts) {
        if (!q.contains("sponsor")) {
            return null;
        }
        String named = country(q);
        Boolean needs = named != null && !Json.str(p, "authorized").isEmpty() ? Boolean.valueOf(!authorizedFor(p, named))
                : flag(Json.str(p, "sponsorship"));
        if (needs == null || (!opts.isEmpty() && !isYesNo(opts))) {
            return null;
        }
        return yesNo(opts, needs, "visa sponsorship");
    }

    static final String AUTH = "(authori[sz]ed|eligible|allowed|permitted|legally able|legal right|right) to work|work (permit|authori[sz]ation)"
            + "|legally (work|authori[sz]ed|eligible)";

    private Hit authorization(Map<String, Object> p, String q, List<String> opts) {
        if (!has(q, AUTH)) {
            return null;
        }
        String auth = Json.str(p, "authorized");
        if (auth.isEmpty()) {
            return null;
        }
        if (opts.isEmpty() && has(q, "^(which|what|in which|list|where)\\b")) {
            return new Hit(auth, "work permit");
        }
        if (!opts.isEmpty() && !isYesNo(opts)) {
            return null;
        }
        String named = country(q);
        // No country in the question: the job's own country, which is where you search.
        boolean ok = named == null || authorizedFor(p, named);
        return yesNo(opts, ok, "work permit");
    }

    private Hit relocateOrCommute(Map<String, Object> p, String q, String kind, List<String> opts) {
        boolean rel = q.contains("relocat");
        boolean com = has(q, "commut|on-?site|in the office|from the office|office[- ]based|in-office|work from office|hybrid"
                + "|in person|in-person|reliably (get|travel)|travel to (the|our) office");
        if ((!rel && !com) || "numeric".equals(kind) || (!opts.isEmpty() && !isYesNo(opts))) {
            return null;
        }
        String city = Json.str(p, "city").split(",")[0].trim().toLowerCase(Locale.ROOT);
        if (city.length() >= 3 && q.contains(city)) {
            return yesNo(opts, true, "you live in " + Json.str(p, "city"));
        }
        Boolean r = flag(Json.str(p, "relocate"));
        Boolean c = flag(Json.str(p, "onsite"));
        Boolean ans;
        if (rel && com) {
            ans = Boolean.TRUE.equals(r) || Boolean.TRUE.equals(c) ? Boolean.TRUE : (r == null && c == null ? null : Boolean.FALSE);
        } else {
            ans = rel ? r : c;
        }
        return ans == null ? null : yesNo(opts, ans, rel ? "relocation" : "on-site work");
    }

    // ---- durations and numbers in options ("1-3 years", "Less than 2 weeks", "10,000 - 15,000 EGP")
    static final Pattern NUMK = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(k\\b)?");

    /** [low, high] of an option, in its own units, or null when it holds no number. */
    static double[] range(String option) {
        String t = option.toLowerCase(Locale.ROOT).replace(",", "").replace('–', '-').replace('—', '-');
        List<Double> nums = new ArrayList<Double>();
        Matcher m = NUMK.matcher(t);
        while (m.find()) {
            nums.add(Double.parseDouble(m.group(1)) * (m.group(2) != null ? 1000 : 1));
        }
        if (nums.isEmpty()) {
            return has(t, "\\b(no experience|none|fresh|immediate(ly)?|asap|right away|zero|no notice)\\b") ? new double[]{0, 0} : null;
        }
        double a = nums.get(0);
        if (has(t, "\\b(less than|under|below|fewer than|lower than)\\b") || t.contains("<")) {
            return new double[]{0, a - 1e-6};
        }
        if (has(t, "\\b(up to|at most|maximum|max|within)\\b")) {
            return new double[]{0, a};
        }
        if (has(t, "\\b(more than|over|above|greater than|higher than)\\b") || t.contains(">")) {
            return new double[]{a + 1e-6, Double.MAX_VALUE};
        }
        if (t.contains("+") || has(t, "\\b(or more|and above|and more|at least|minimum|plus)\\b")) {
            return new double[]{a, Double.MAX_VALUE};
        }
        if (nums.size() >= 2) {
            return new double[]{Math.min(a, nums.get(1)), Math.max(a, nums.get(1))};
        }
        return new double[]{a, a};
    }

    /** What one unit of an option is worth in `base` units ("days", "years" or "" for plain numbers). */
    static double factor(String option, String base) {
        String t = option.toLowerCase(Locale.ROOT);
        if (base.equals("days")) {
            return t.contains("month") ? 30 : t.contains("week") ? 7 : t.contains("year") ? 365 : 1;
        }
        if (base.equals("years")) {
            return t.contains("month") ? 1 / 12.0 : 1;
        }
        return 1;
    }

    /** The option whose range holds `value` (or the closest one), null when no option has a number. */
    static String pickByNumber(List<String> options, double value, String base) {
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (String o : options) {
            double[] r = range(o);
            if (r == null) {
                continue;
            }
            double f = factor(o, base);
            double lo = r[0] * f;
            double hi = r[1] == Double.MAX_VALUE ? Double.MAX_VALUE : r[1] * f;
            if (value >= lo - 1e-9 && value <= hi + 1e-9) {
                return o;
            }
            double dist = value < lo ? lo - value : value - hi;
            if (dist < bestDist) {
                bestDist = dist;
                best = o;
            }
        }
        return best;
    }

    static int intOf(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ---- notice period / start date
    static final String NOTICE = "notice|how soon can you (join|start)|when can you (join|start)|earliest (start|joining)"
            + "|availab\\w* to (join|start)|joining (time|date|period)|can you (join|start)|start working|able to (join|start)";

    private Hit notice(Map<String, Object> p, String q, String kind, List<String> opts) {
        if (!has(q, NOTICE) || has(q, "privacy|policy|terms|read the|acknowledg|consent")) {
            return null;
        }
        int days = intOf(Json.str(p, "notice_days"), -1);
        if (days < 0) {
            days = 0; // default to immediate / none if unset
        }
        String why = days == 0 ? "you can start immediately" : "your notice period: " + days + " days";
        boolean question = has(q, "^(can|are|is|do|will|would|could)\\b");
        if (isYesNo(opts) || (opts.isEmpty() && question && !"numeric".equals(kind))) {
            Integer limit = null;
            Matcher m = Pattern.compile("(?:within|in|less than|under|before|up to|max(?:imum)?(?: of)?)\\s+(\\d+)\\s*(day|week|month)").matcher(q);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                limit = n * (m.group(2).startsWith("month") ? 30 : m.group(2).startsWith("week") ? 7 : 1);
            } else if (has(q, "immediate|asap|right away|straight away|right now")) {
                limit = 0;
            }
            return limit == null ? null : yesNo(opts, days <= limit, why);
        }
        if (!opts.isEmpty()) {
            String pick = pickByNumber(opts, days, "days");
            return pick == null ? null : new Hit(pick, why);
        }
        if (!"numeric".equals(kind) && !q.contains("notice")) {  // "When can you start?" in a text box
            return new Hit(days == 0 ? "Immediately" : "In " + days + " days", why);
        }
        long value = q.contains("month") && !q.contains("day") ? Math.round(days / 30.0)
                : q.contains("week") && !q.contains("day") ? (long) Math.ceil(days / 7.0) : days;
        return new Hit(String.valueOf(value), why);
    }

    // ---- salary
    static final String SALARY = "salar|compensation|\\bpackage\\b|\\bctc\\b|\\bpay\\b|remuneration|\\bwage|\\bincome\\b|\\bearn";
    static final String[][] MONEY = {
        {"USD", "\\busd\\b|us\\$|\\$|dollar"}, {"EUR", "\\beur\\b|€|\\beuros?\\b"}, {"GBP", "\\bgbp\\b|£|sterling|british pound"},
        {"QAR", "\\bqar\\b|qatari"}, {"OMR", "\\bomr\\b|omani"}, {"KWD", "\\bkwd\\b|kuwaiti"}, {"BHD", "\\bbhd\\b|bahraini"},
        {"JOD", "\\bjod\\b|jordanian"}, {"AED", "\\baed\\b|dirham|emirati"}, {"SAR", "\\bsar\\b|saudi riyal|\\briyals?\\b"},
        {"EGP", "\\begp\\b|egyptian|\\bl\\.?e\\.?\\b|جنيه|\\bpounds?\\b"},
    };

    static String moneyIn(String q) {
        for (String[] c : MONEY) {
            if (has(q, c[1])) {
                return c[0];
            }
        }
        return null;
    }

    static long roundMoney(double v) {
        if (v >= 10000) {
            return Math.round(v / 100) * 100;
        }
        if (v >= 1000) {
            return Math.round(v / 50) * 50;
        }
        if (v >= 100) {
            return Math.round(v / 10) * 10;
        }
        return Math.round(v);
    }

    private Hit salary(Map<String, Object> p, String q, String kind, List<String> opts) {
        if (!has(q, SALARY) || isYesNo(opts) || has(q, "^(are|is|do|will|would|can) you (ok|okay|fine|comfortable|happy|agree)")) {
            return null;
        }
        boolean expected = has(q, "expect|desired|expectation|asking|requir|looking for|target|minimum|aspir|wish|prefer");
        boolean current = !expected && has(q, "current|present|\\blast\\b|latest|existing|previous|\\bnow\\b|currently|today");
        String field = current ? "current_salary" : "expected_salary";
        int monthly = intOf(Json.str(p, field), -1);
        if (monthly < 0) {
            if (current) {
                int exp = intOf(Json.str(p, "expected_salary"), -1);
                if (exp > 0) {
                    monthly = (int) Math.round(exp * 0.85);
                }
            } else {
                int curSal = intOf(Json.str(p, "current_salary"), -1);
                if (curSal > 0) {
                    monthly = (int) Math.round(curSal * 1.25);
                }
            }
        }
        String base = Json.str(p, "currency");
        if (base.isEmpty()) {
            base = "EGP";
        }
        if (monthly < 0) {
            if ("USD".equalsIgnoreCase(base) || "EUR".equalsIgnoreCase(base) || "GBP".equalsIgnoreCase(base)) {
                monthly = current ? 3000 : 4000;
            } else if ("SAR".equalsIgnoreCase(base) || "AED".equalsIgnoreCase(base) || "QAR".equalsIgnoreCase(base)) {
                monthly = current ? 10000 : 13000;
            } else if ("KWD".equalsIgnoreCase(base) || "BHD".equalsIgnoreCase(base) || "OMR".equalsIgnoreCase(base)) {
                monthly = current ? 1000 : 1300;
            } else {
                monthly = current ? 20000 : 25000;
            }
        }
        String target = moneyIn(q);
        if (target == null) {
            target = base;
        }
        double value = monthly;
        boolean annual = has(q, "annual|yearly|per year|a year|per annum|p\\.a\\.|/ ?year|/ ?yr|\\byear\\b");
        if (annual) {
            value *= 12;
        }
        String why = "your " + (current ? "current" : "expected") + " salary" + (annual ? " x 12" : "");
        boolean rounded = false;
        if (!target.equals(base)) {
            Double rate = rate(base, target);
            if (rate != null) {
                value *= rate;
                rounded = true;
                why += " in " + target + " (1 " + base + " = " + String.format(Locale.US, "%.4f", rate) + " " + target + ")";
            } else {
                why += " in " + base;
            }
        } else {
            why += " in " + base;
        }
        if (has(q, "in thousands|\\(k\\)|\\bin k\\b|'000")) {
            value /= 1000;
            rounded = false;
            why += ", in thousands";
        }
        long out = rounded ? roundMoney(value) : Math.round(value);
        if (out <= 0) {
            out = current ? 20000 : 25000;
        }
        if (!opts.isEmpty()) {
            String pick = pickByNumber(opts, out, "");
            return new Hit(pick != null ? pick : opts.get(0), why);
        }
        return new Hit(String.valueOf(out), why);
    }

    // ---- years of experience
    static final String[] GENERIC = {"work", "working", "professional", "relevant", "hands-on", "hands on", "practical", "industry",
        "total", "overall", "full-time", "full time", "paid", "direct", "prior", "previous", "related", "proven", "real-world",
        "real world", "commercial", "solid", "strong", "corporate", "professionally", "experience"};
    static final String[] TOTAL = {"", "this field", "the field", "field", "this role", "role", "the role", "position",
        "this position", "a similar role", "similar role", "a similar position", "similar position", "this job", "job", "it",
        "the industry", "this industry", "career", "your career", "your field", "this area", "this domain", "domain",
        "the same role", "same role", "the same field", "employment"};
    static final String STOP = "and|or|the|of|with|in|for|a|an|to|on|using|use|like|such|as|etc|other|based|any|your|you|have"
            + "|do|does|been|experience|years|year|tools|tool|technologies|technology";

    /** What a "years of experience with X" question is about ("" = experience in general). Null: not such a question. */
    static String subject(String q) {
        String t = q.replaceAll("[?.!:]+$", "").trim();
        boolean years = has(t, "\\byears?\\b|\\byrs?\\b|how many|how long|\\bmonths?\\b");
        if (!t.contains("experience") && !has(t, "how (many years|long) have you")) {
            return null;
        }
        if (!years && !has(t, "^(do|does|have) you have\\b.*experience")) {
            return null;
        }
        // The skill: after "experience with/in", between "years of" and "experience", after "how long have you
        // worked with", or before "experience" ("Fortinet experience in years").
        String[] patterns = {
            "experience (?:do you have |have you got |have you had |you have |do you possess )?(?:working |hands-on )?"
                + "(?:with|in|using|on|of|as an?|as|doing|across|within|around|related to|for|managing|leading) (.+)$",
            "(?:years?|yrs?|months?) of (.+?) experience",
            "how (?:many years|long) have you (?:been )?(?:worked|working|used|using|done|doing|programmed|programming|developed"
                + "|developing|managed|managing|handled|handling|been) (?:with |in |on |as an? |as )?(.+)$",
            "^(?!how\\b|what\\b|do\\b|does\\b|are\\b|is\\b|have\\b|please\\b|kindly\\b|tell\\b)(?:your |total )?(.+?) experience\\b",
        };
        String s = "";
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat).matcher(t);
            if (m.find()) {
                String c = tidySubject(m.group(1));
                if (!c.isEmpty()) {
                    s = c;
                    break;
                }
            }
        }
        for (String g : TOTAL) {
            if (s.equals(g)) {
                return "";
            }
        }
        return s;
    }

    /** "the network do you have" -> "network"; units like "years" are not a skill. */
    static String tidySubject(String raw) {
        String s = raw == null ? "" : raw.trim();
        String prev;
        do {
            prev = s;
            s = s.replaceAll("\\s+(do you have|have you|in total|overall|so far|in years|in months|currently|professionally"
                    + "|in your career)$", "")
                    .replaceAll("\\s*\\([^)]*\\)\\s*$", "").replaceAll("^(the|a|an|your|our)\\s+", "").trim();
        } while (!s.equals(prev));
        for (String g : GENERIC) {
            s = s.replaceAll("(^|\\s)" + Pattern.quote(g) + "(\\s|$)", " ").trim();
        }
        return s.replaceAll("(^|\\s)(years?|yrs?|months?|in)(?=\\s|$)", " ").replaceAll("\\s+", " ").trim();
    }

    /** True when the CV mentions this subject (all its words, or most of them for long phrases). */
    static boolean inCv(String subject, String cv) {
        String c = cv.toLowerCase(Locale.ROOT);
        if (subject.isEmpty() || c.isEmpty()) {
            return false;
        }
        if (c.contains(subject)) {
            return true;
        }
        List<String> tokens = new ArrayList<String>();
        for (String w : subject.split("[\\s/,&()]+|-(?=\\s)|\\b(?:" + STOP + ")\\b")) {
            String x = w.trim();
            if (x.length() >= 2 && !x.matches(STOP)) {
                tokens.add(x);
            }
        }
        if (tokens.isEmpty()) {
            return false;
        }
        int found = 0;
        for (String tok : tokens) {
            boolean hit;
            if (tok.matches("[a-z0-9]+")) {
                String stem = tok.length() > 4 && tok.endsWith("es") ? tok.substring(0, tok.length() - 2)
                        : tok.length() > 3 && tok.endsWith("s") ? tok.substring(0, tok.length() - 1) : tok;
                hit = has(c, "\\b(" + Pattern.quote(tok) + "|" + Pattern.quote(stem) + ")(s|es)?\\b");
            } else {
                hit = c.contains(tok);
            }
            if (hit) {
                found++;
            }
        }
        return tokens.size() <= 2 ? found == tokens.size() : found * 3 >= tokens.size() * 2;
    }

    private Hit experience(Map<String, Object> p, String q, String kind, List<String> opts, String cv, double now) {
        String s = subject(q);
        if (s == null) {
            if (!has(q, "experience") || !has(q, "years?|yrs?|how many|how long|total")) {
                return null;
            }
            s = "";
        }
        int totalYears = totalExperienceYears(p, cv, now);
        int firstJob = earliestJobYear(cv);
        String jobYearStr = firstJob > 0 ? String.valueOf(firstJob) : Json.str(p, "graduation_year");

        Integer own = store == null ? null : Facts.skillYears(store.factsCopy(), s);
        int years;
        String why;
        boolean inCvFound = s.isEmpty() || inCv(s, cv);

        if (own != null) {
            years = own;
            why = "your info: " + (s.isEmpty() ? "experience in total" : "'" + s + "'") + " = " + own + " years";
        } else if (inCvFound) {
            years = totalYears;
            why = (s.isEmpty() ? "total experience" : "'" + s + "' is in your CV") + ": " + years + " years since your first job (" + (jobYearStr.isEmpty() ? "earliest role" : jobYearStr) + ")";
        } else {
            years = 0;
            why = "'" + s + "' is not mentioned in your CV";
        }

        if (isYesNo(opts) || (opts.isEmpty() && has(q, "^(do|does|have|are|is) you\\b") && !"numeric".equals(kind))) {
            if (!inCvFound) {
                return yesNo(opts, false, why);
            }
            Matcher m = Pattern.compile("(?:at least|minimum(?: of)?|min\\.?|more than|over)\\s+(\\d+)|(\\d+)\\s*\\+|(\\d+) or more").matcher(q);
            if (!m.find()) {
                return yesNo(opts, true, why);
            }
            String n = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            boolean strict = m.group(0).startsWith("more than") || m.group(0).startsWith("over");
            int need = Integer.parseInt(n);
            return yesNo(opts, strict ? years > need : years >= need, why);
        }

        if (!opts.isEmpty()) {
            String pick = pickByNumber(opts, years, "years");
            if (pick == null && years == 0) {
                for (String o : opts) {
                    if (has(o.toLowerCase(Locale.ROOT), "\\b(0|none|no experience|< 1|less than 1)\\b")) {
                        pick = o;
                        break;
                    }
                }
            }
            return new Hit(pick != null ? pick : opts.get(0), why);
        }

        boolean months = q.contains("month") && !q.contains("year");
        return new Hit(String.valueOf(months ? years * 12 : years), why);
    }

    // ---- the rest
    private Hit military(Map<String, Object> p, String q, List<String> opts) {
        String v = Json.str(p, "military");
        if (!q.contains("military") || v.isEmpty()) {
            return null;
        }
        if (opts.isEmpty()) {
            return new Hit(v, "military service");
        }
        String[][] words = {{"Completed", "complet|done|finish|served|fulfil|performed"},
            {"Exempted", "exempt|exemption|not required to serve"}, {"Postponed", "postpon|defer|delay|temporar"},
            {"Not applicable", "not applicable|n/a|\\bfemale\\b|does not apply|not required"}};
        for (String[] w : words) {
            if (w[0].equals(v)) {
                for (String o : opts) {
                    if (has(o.toLowerCase(Locale.ROOT), w[1])) {
                        return new Hit(o, "military service");
                    }
                }
            }
        }
        String m = Ai.matchOption(v, opts);
        return m == null ? null : new Hit(m, "military service");
    }

    static double englishLevel(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (has(t, "native|bilingual|mother")) {
            return 5;
        }
        if (has(t, "full professional|fluent|excellent|expert|proficient|c2|c1")) {
            return 4;
        }
        if (has(t, "professional|advanced|very good|upper|b2")) {
            return 3.5;
        }
        if (has(t, "good|intermediate|conversational|working|moderate|average|b1")) {
            return 2.5;
        }
        if (has(t, "basic|beginner|elementary|limited|fair|a1|a2")) {
            return 1;
        }
        if (has(t, "^(none|no)\\b")) {
            return 0;
        }
        return -1;
    }

    private Hit english(Map<String, Object> p, String q, List<String> opts) {
        String v = Json.str(p, "english");
        if (v.isEmpty() || !q.contains("english") || !has(q, "proficien|level|fluen|rate|speak|communicat|written|spoken|command|skill")) {
            return null;
        }
        double mine = englishLevel(v);
        if (isYesNo(opts) || (opts.isEmpty() && has(q, "^(are|do|can|is) you"))) {
            boolean yes = q.contains("fluen") ? mine >= 3.5 : mine >= 2.5;
            return yesNo(opts, yes, "English: " + v);
        }
        if (opts.isEmpty()) {
            return new Hit(v, "English level");
        }
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (String o : opts) {
            double lvl = englishLevel(o);
            if (lvl < 0) {
                continue;
            }
            double d = Math.abs(lvl - mine) - (lvl > mine ? 0.01 : 0);
            if (d < bestDist) {
                bestDist = d;
                best = o;
            }
        }
        return best == null ? null : new Hit(best, "English: " + v);
    }

    private Hit driving(Map<String, Object> p, String q, List<String> opts) {
        Boolean v = flag(Json.str(p, "driving"));
        if (v == null || !has(q, "driv(er|ing)'?s? licen[cs]e|driving licen[cs]e|valid (driver|driving)|licen[cs]e to drive")) {
            return null;
        }
        return !opts.isEmpty() && !isYesNo(opts) ? null : yesNo(opts, v, "driving license");
    }

    private Hit graduation(Map<String, Object> p, String q, String kind, List<String> opts) {
        String y = Json.str(p, "graduation_year");
        if (y.isEmpty() || !q.contains("graduat") || !has(q, "year|when|date")) {
            return null;
        }
        if (!opts.isEmpty()) {
            String pick = pickByNumber(opts, intOf(y, 0), "");
            return pick == null ? null : new Hit(pick, "graduation year");
        }
        return new Hit(y, "graduation year");
    }

    private Hit phone(Map<String, Object> p, String q, String kind, List<String> opts) {
        if (!has(q, "mobile|phone|cell|contact number|whatsapp")) {
            return null;
        }
        String v = Json.str(p, "phone");
        if (v.isEmpty()) {
            v = "01000000000";
        }
        String digits = v.replaceAll("[^0-9+]", "");
        if (!opts.isEmpty()) {
            if (!q.contains("code") && !q.contains("country")) {
                return null;
            }
            boolean egypt = digits.startsWith("+20") || digits.startsWith("0020") || digits.matches("01[0125]\\d{8}");
            for (String o : opts) {
                String t = o.toLowerCase(Locale.ROOT);
                if (egypt && (t.contains("egypt") || t.contains("+20"))) {
                    return new Hit(o, "your phone number is Egyptian");
                }
            }
            return new Hit(opts.get(0), "country code");
        }
        if (q.contains("code") || q.contains("country")) {
            return null;
        }
        return new Hit("numeric".equals(kind) ? digits.replace("+", "") : v, "your phone number");
    }

    private Hit city(Map<String, Object> p, String q, String kind, List<String> opts) {
        if (!has(q, "city|current location|where are you located|residence|current residence|\\blocation\\b")) {
            return null;
        }
        String v = Json.str(p, "city");
        if (v.isEmpty()) {
            v = "Cairo";
        }
        if (!opts.isEmpty()) {
            String hit = Ai.matchOption(v, opts);
            return new Hit(hit != null ? hit : opts.get(0), "city");
        }
        if ("numeric".equals(kind)) {
            return null;
        }
        return new Hit(v, "your city");
    }

    private Hit heardAbout(String q, List<String> opts) {
        if (!has(q, "how did you (hear|find|learn|come across)|where did you (hear|find|see)|source of (this )?(application|job)")) {
            return null;
        }
        if (opts.isEmpty()) {
            return new Hit("LinkedIn", "found on LinkedIn");
        }
        String m = Ai.matchOption("LinkedIn", opts);
        return m == null ? null : new Hit(m, "found on LinkedIn");
    }

    // ------------------------------------------------------------------ exchange rates
    /** How many `to` one `from` buys: today's rate (cached for a day), or a built-in estimate when offline. */
    public synchronized Double rate(String from, String to) {
        if (from.equals(to)) {
            return 1.0;
        }
        Map<String, Object> cached = Json.map(Json.tryParse(Util.readText(ratesFile)));
        boolean stale = !from.equals(Json.str(cached, "base")) || Util.now() - Json.dbl(cached, "time", 0) > 86400;
        if (stale && Util.now() - ratesFailedAt > 3600) {
            Map<String, Object> fresh = fetchRates(from);
            if (fresh != null) {
                cached = fresh;
                Util.writeText(ratesFile, Json.pretty(fresh));
            } else {
                ratesFailedAt = Util.now();
            }
        }
        Object r = Json.map(cached.get("rates")).get(to);
        if (from.equals(Json.str(cached, "base")) && r instanceof Number && ((Number) r).doubleValue() > 0) {
            return ((Number) r).doubleValue();
        }
        Double f = egpPerUnit(from);
        Double t = egpPerUnit(to);
        return f == null || t == null ? null : f / t;
    }

    static Double egpPerUnit(String code) {
        for (String[] kv : EGP_PER_UNIT) {
            if (kv[0].equals(code)) {
                return Double.parseDouble(kv[1]);
            }
        }
        return null;
    }

    static Map<String, Object> fetchRates(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(ratesUrl + base).openConnection();
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) {
                    return null;
                }
                InputStream in = c.getInputStream();
                Map<String, Object> d = Json.map(Json.tryParse(new String(Util.readAll(in), Util.UTF8)));
                in.close();
                if (!"success".equals(Json.str(d, "result")) || !(d.get("rates") instanceof Map)) {
                    return null;
                }
                return Json.obj("base", base, "time", Util.now(), "rates", d.get("rates"), "source", "exchangerate-api.com");
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
