package com.autoapply.bot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.autoapply.bot.core.Util.log;

/** Fills a job application form on a company's site that you have open in the app (you check it and submit it
 *  yourself): your name and email from your account, everything else the same way auto apply answers (your setup
 *  answers, your info, then the AI). The page part is assets/fill.js. */
public final class Filler {
    private Filler() {
    }

    static final Pattern FIRST = Pattern.compile("(?i)\\b(first|given|fore)\\s*name\\b");
    static final Pattern LAST = Pattern.compile("(?i)\\b(last|family|sur)\\s*name\\b|\\bsurname\\b");
    static final Pattern FULL = Pattern.compile("(?i)^(full\\s+|your\\s+)?name$|\\bfull name\\b|\\bname \\(as");
    static final Pattern EMAIL = Pattern.compile("(?i)e-?mail");
    static final Pattern CONSENT = Pattern.compile("(?i)agree|consent|privacy|terms|confirm|acknowledge|certify|accurate");

    /** How sure the AI must be to fill a field by itself; below that you are asked (and your answer is kept). */
    static final int SURE_ENOUGH = 75;
    /** Questions that want a number: "how many", "how much", years, salary, notice in days. */
    static final Pattern NUMERIC_Q = Pattern.compile("(?i)\\bhow\\s+(many|much|long)\\b|\\bnumber of\\b|\\byears?\\b|\\byrs?\\b"
            + "|\\bsalary\\b|\\bcompensation\\b|\\bnotice\\b.*\\bdays?\\b|\\bgpa\\b|كم|عدد|سنوات|سنين|راتب|مرتب");

    /** {answers: {key: value}, filled: n, ask: [{key, label, kind, options, suggestion}], skipped: [labels]} for the
     *  fields fill.js collected. Answers come from your account, your setup answers and your info, then the AI when it
     *  is sure. What none of them knows is asked of you (`ask`); nothing is made up. */
    public static Map<String, Object> answers(Engine engine, List<Object> fields, String title, String company) {
        Map<String, String> cfg = engine.config.load();
        Bot bot = new Bot(engine, cfg, new Bot.Options(), engine.newAi(engine.config.cvText(), engine.notesForAi(), "help"),
                null, new ArrayList<String>(), cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION"), new Session("apply"));
        bot.neverAsk = true;
        bot.knownOnly = true;
        bot.setJob(title, company);
        Map<String, Object> acc = engine.account.info();
        String name = Json.str(acc, "name").trim();
        String email = Json.str(acc, "email").trim();
        String[] parts = name.split("\\s+", 2);
        Map<String, Object> out = Json.obj();
        List<Object> skipped = new ArrayList<Object>();
        List<Object> ask = new ArrayList<Object>();
        boolean aiDown = false;
        String aiProblem = "";
        for (Object o : fields) {
            Map<String, Object> f = Json.map(o);
            String key = Json.str(f, "key");
            String label = Json.str(f, "label").trim();
            String kind = Json.str(f, "kind");
            List<String> options = Json.strings(f.get("options"));
            if (key.isEmpty() || label.isEmpty() || !Json.str(f, "value").trim().isEmpty()) {
                continue;  // already filled (the site or you), or nothing to go by
            }
            Object value = null;
            Object suggestion = null;
            boolean identity = true;  // your name and email come from your account, never from the AI
            if ("checkbox".equals(kind)) {
                if (CONSENT.matcher(label).find()) {
                    value = Boolean.TRUE;
                }
            } else if ("email".equals(kind) || EMAIL.matcher(label).find()) {
                value = email.isEmpty() ? null : email;
            } else if (FIRST.matcher(label).find()) {
                value = name.isEmpty() ? null : parts[0];
            } else if (LAST.matcher(label).find()) {
                value = parts.length > 1 ? parts[1] : null;
            } else if (FULL.matcher(label).find()) {
                value = name.isEmpty() ? null : name;
            } else {
                identity = false;
            }
            boolean numeric = "number".equals(kind) || (options.isEmpty() && NUMERIC_Q.matcher(label).find());
            String botKind = numeric ? "numeric" : "textarea".equals(kind) ? "textarea"
                    : ("select".equals(kind) || "radio".equals(kind)) ? "choice" : "multi".equals(kind) ? "multi" : "text";
            if (value == null && !identity) {
                try {   // your answers, setup answers, your info
                    value = bot.resolve(label, botKind, options.isEmpty() ? null : options, "", Json.str(f, "placeholder"), false,
                            null, (int) Json.num(f, "maxlength", 0), null);
                } catch (RuntimeException e) {
                    log("  Could not answer: " + Util.cut(label, 80) + " (" + Util.shortError(e, 80) + ")");
                }
            }
            if (value == null && !identity) {
                value = fromProfile(label, kind, engine);   // salary, notice, experience, phone, city: only what you set
            }
            if (value == null && !identity && !"checkbox".equals(kind) && !aiDown) {
                try {   // the AI, from your CV and notes: used when it is sure, otherwise it is only a suggestion
                    Ai.Sure s = bot.ai.sureAnswer(label, botKind, options.isEmpty() ? null : options, engine.store.knownForAi(),
                            bot.jobLabel(), "", Json.str(f, "placeholder"), null, (int) Json.num(f, "maxlength", 0));
                    if (s.answer != null && s.sure >= SURE_ENOUGH) {
                        value = s.answer;
                        engine.store.putAnswer(Util.normKey(label), value);
                    } else {
                        suggestion = s.answer;
                    }
                } catch (Ai.FatalAIError e) {
                    aiDown = true;  // not Pro, AI limit...: fill what your answers cover, ask you the rest
                    aiProblem = Util.cut(String.valueOf(e.getMessage()), 200);
                    log("  The AI is not available for this form: " + e.getMessage());
                } catch (RuntimeException e) {
                    log("  The AI could not answer: " + Util.cut(label, 80) + " (" + Util.shortError(e, 80) + ")");
                }
            }
            if (value != null && numeric && !(value instanceof Boolean) && !(value instanceof List)) {
                String n = number(Facts.answerText(value));
                value = n.isEmpty() ? null : n;   // "how many / how much": a number only
            }
            if (value == null || Facts.answerText(value).trim().isEmpty()) {
                if (identity || "checkbox".equals(kind) || "file".equals(kind)) {
                    if (Json.bool(f, "required")) {
                        skipped.add(label);
                    }
                    continue;
                }
                if (Json.bool(f, "required") || label.contains("?") || NUMERIC_Q.matcher(label).find() || !options.isEmpty()) {
                    if (suggestion != null && numeric) {
                        String n = number(Facts.answerText(suggestion));
                        suggestion = n.isEmpty() ? null : n;
                    }
                    ask.add(Json.obj("key", key, "label", label, "kind", numeric ? "number" : kind, "options", options,
                            "suggestion", suggestion == null ? "" : Facts.answerText(suggestion), "required", Json.bool(f, "required")));
                    skipped.add(label);
                }
                continue;
            }
            out.put(key, value);
        }
        log("Filled " + out.size() + " field(s) on " + (company == null || company.isEmpty() ? "a company site" : company)
                + (ask.isEmpty() ? "" : "; " + ask.size() + " to ask you"));
        return Json.obj("answers", out, "filled", out.size(), "ask", ask, "skipped", skipped, "ai_problem", aiProblem);
    }

    /** The first number in an answer ("3 years" -> "3", "25,000 EGP" -> "25000"); "" when there is none. */
    static String number(String s) {
        java.util.regex.Matcher m = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?").matcher(s == null ? "" : s);
        return m.find() ? m.group().replace(",", "") : "";
    }

    /** Your answer to a question a form asked: kept in your info, so no form asks you again. Returns the value to fill. */
    public static Object learn(Engine engine, String label, Object value, String kind) {
        Object v = value instanceof String ? ((String) value).trim() : value;
        if (("number".equals(kind) || NUMERIC_Q.matcher(label).find()) && v instanceof String) {
            String n = number((String) v);
            if (!n.isEmpty()) {
                v = n;
            }
        }
        if (v == null || Facts.answerText(v).trim().isEmpty()) {
            throw new IllegalArgumentException("Type an answer first.");
        }
        engine.store.addFact(Facts.categorize(label), label, v);
        engine.store.putAnswer(Util.normKey(label), v);
        log("Saved your answer for next time: " + Util.cut(label, 80));
        return v;
    }

    /** Answers from your setup answers only (never invented): salary, notice period, years of experience, phone, city. */
    private static Object fromProfile(String label, String kind, Engine engine) {
        String l = label.toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> p = engine.profile.load();
        if (l.contains("salary") || l.contains("compensation") || l.contains("gross") || l.contains("ctc") || l.contains("wage")
                || l.contains("راتب") || l.contains("مرتب") || l.contains("الأجر")) {
            boolean cur = l.contains("current") || l.contains("present") || l.contains("existing") || l.contains("last")
                    || l.contains("حالي") || l.contains("الحالي");
            String sal = Json.str(p, cur ? "current_salary" : "expected_salary").replaceAll("[^0-9]", "");
            return sal.isEmpty() ? null : sal;
        }
        if (l.contains("notice") || l.contains("إخطار") || l.contains("join") && l.contains("days")) {
            String n = Json.str(p, "notice_days").replaceAll("[^0-9]", "");
            if (n.isEmpty()) {
                return null;
            }
            return "number".equals(kind) || l.contains("day") || l.contains("ايام") ? n : ("0".equals(n) ? "Immediately" : n + " days");
        }
        if ((l.contains("experience") || l.contains("خبرة")) && (l.contains("year") || l.contains("how") || l.contains("سن")
                || "number".equals(kind))) {
            int y = Profile.totalExperienceYears(p, engine.config.cvText(), Util.now());
            return y >= 0 ? String.valueOf(y) : null;
        }
        if (l.contains("phone") || l.contains("mobile") || l.contains("whatsapp") || l.contains("هاتف") || l.contains("موبايل")
                || l.contains("تليفون")) {
            String ph = Json.str(p, "phone");
            return ph.isEmpty() ? null : ph;
        }
        if (l.contains("city") || l.contains("where do you live") || l.contains("residence") || l.contains("مدينة")
                || l.contains("محل الإقامة")) {
            String ct = Json.str(p, "city");
            return ct.isEmpty() ? null : ct;
        }
        return null;
    }
}
