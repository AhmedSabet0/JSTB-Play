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

    /** {answers: {key: value}, filled: n, skipped: [labels]} for the fields fill.js collected. */
    public static Map<String, Object> answers(Engine engine, List<Object> fields, String title, String company) {
        Map<String, String> cfg = engine.config.load();
        Bot bot = new Bot(engine, cfg, new Bot.Options(), engine.newAi(engine.config.cvText(), engine.notesForAi(), "help"),
                null, new ArrayList<String>(), cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION"), new Session("apply"));
        bot.neverAsk = true;
        bot.setJob(title, company);
        Map<String, Object> acc = engine.account.info();
        String name = Json.str(acc, "name").trim();
        String email = Json.str(acc, "email").trim();
        String[] parts = name.split("\\s+", 2);
        Map<String, Object> out = Json.obj();
        List<Object> skipped = new ArrayList<Object>();
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
            if (value == null && !identity && !aiDown) {
                String botKind = "number".equals(kind) ? "numeric" : "textarea".equals(kind) ? "textarea"
                        : ("select".equals(kind) || "radio".equals(kind)) ? "choice" : "multi".equals(kind) ? "multi" : "text";
                try {
                    value = bot.resolve(label, botKind, options.isEmpty() ? null : options, "", Json.str(f, "placeholder"), false,
                            null, (int) Json.num(f, "maxlength", 0), null);
                } catch (Ai.FatalAIError e) {
                    aiDown = true;  // not Pro, AI limit...: fill what your answers cover, leave the rest to you
                    aiProblem = Util.cut(String.valueOf(e.getMessage()), 200);
                    log("  The AI is not available for this form: " + e.getMessage());
                } catch (RuntimeException e) {
                    log("  Could not answer: " + Util.cut(label, 80) + " (" + Util.shortError(e, 80) + ")");
                }
            }
            if (value == null || Facts.answerText(value).trim().isEmpty()) {
                value = fallbackAnswer(label, kind, options, engine);
            }
            if (value == null || Facts.answerText(value).trim().isEmpty()) {
                if (Json.bool(f, "required")) {
                    skipped.add(label);
                }
                continue;
            }
            out.put(key, value);
        }
        log("Filled " + out.size() + " field(s) on " + (company == null || company.isEmpty() ? "a company site" : company)
                + (skipped.isEmpty() ? "" : "; " + skipped.size() + " required left for you"));
        return Json.obj("answers", out, "filled", out.size(), "skipped", skipped, "ai_problem", aiProblem);
    }

    private static Object fallbackAnswer(String label, String kind, List<String> options, Engine engine) {
        String l = label.toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> p = engine.profile.load();

        if ("checkbox".equals(kind)) {
            return Boolean.TRUE;
        }

        if (options != null && !options.isEmpty()) {
            String yesOpt = null;
            String noOpt = null;
            for (String o : options) {
                String ot = o.toLowerCase(java.util.Locale.ROOT).trim();
                if (ot.startsWith("yes") || ot.equals("نعم") || ot.startsWith("نعم")) {
                    yesOpt = o;
                } else if (ot.startsWith("no") || ot.equals("لا") || ot.startsWith("لا")) {
                    noOpt = o;
                }
            }
            if (yesOpt != null && noOpt != null) {
                if (l.contains("sponsor") || l.contains("visa") || l.contains("felon") || l.contains("crime")
                        || l.contains("convict") || l.contains("bankrupt") || l.contains("disability")
                        || l.contains("terminate") || l.contains("fired") || l.contains("إعاقة") || l.contains("جرائم")) {
                    return noOpt;
                }
                return yesOpt;
            }
            if (l.contains("english") || l.contains("language") || l.contains("انجليزي") || l.contains("لغة")) {
                for (String o : options) {
                    if (o.toLowerCase(java.util.Locale.ROOT).matches(".*(fluent|native|proficient|professional|good|ممتاز|جيد جدا|جيد).*")) {
                        return o;
                    }
                }
            }
            if (l.contains("education") || l.contains("degree") || l.contains("مؤهل") || l.contains("تعليم")) {
                for (String o : options) {
                    if (o.toLowerCase(java.util.Locale.ROOT).matches(".*(bachelor|graduate|university|college|بكالوريوس|مؤهل عالي|جامعة).*")) {
                        return o;
                    }
                }
            }
            if (l.contains("notice") || l.contains("start") || l.contains("إخطار") || l.contains("البدء")) {
                for (String o : options) {
                    if (o.toLowerCase(java.util.Locale.ROOT).matches(".*(immediate|1 month|30 days|< 1 month|fresh|فورا|فوراً|شهر).*")) {
                        return o;
                    }
                }
            }
            return options.get(0);
        }

        if ("number".equals(kind) || "numeric".equals(kind)) {
            if (l.contains("salary") || l.contains("compensation") || l.contains("gross") || l.contains("pay") || l.contains("ctc") || l.contains("wage")
                    || l.contains("راتب") || l.contains("مرتب") || l.contains("الأجر")) {
                boolean cur = l.contains("current") || l.contains("present") || l.contains("existing") || l.contains("now") || l.contains("حالي") || l.contains("الحالي");
                String sal = Json.str(p, cur ? "current_salary" : "expected_salary");
                if (sal.isEmpty()) {
                    sal = Json.str(p, cur ? "expected_salary" : "current_salary");
                }
                if (sal.isEmpty()) {
                    sal = cur ? "20000" : "25000";
                }
                return sal.replaceAll("[^0-9]", "");
            }
            if (l.contains("notice") || l.contains("day") || l.contains("إخطار") || l.contains("ايام")) {
                String n = Json.str(p, "notice_days");
                return (n.isEmpty() ? "30" : n).replaceAll("[^0-9]", "");
            }
            if (l.contains("gpa") || l.contains("تقدير")) {
                return "3.5";
            }
            if (l.contains("experience") || l.contains("year") || l.contains("خبرة") || l.contains("سنة") || l.contains("سنوات")) {
                int expYears = Profile.totalExperienceYears(p, engine.config.cvText(), Util.now());
                return String.valueOf(expYears);
            }
            return "2";
        }

        if (l.contains("salary") || l.contains("compensation") || l.contains("gross") || l.contains("pay") || l.contains("ctc") || l.contains("wage")
                || l.contains("راتب") || l.contains("مرتب")) {
            boolean cur = l.contains("current") || l.contains("present") || l.contains("existing") || l.contains("now") || l.contains("حالي");
            String sal = Json.str(p, cur ? "current_salary" : "expected_salary");
            if (sal.isEmpty()) {
                sal = Json.str(p, cur ? "expected_salary" : "current_salary");
            }
            if (sal.isEmpty()) {
                sal = cur ? "20000" : "25000";
            }
            return sal.replaceAll("[^0-9]", "");
        }
        if (l.contains("notice") || l.contains("start") || l.contains("إخطار")) {
            return "Immediately";
        }
        if (l.contains("phone") || l.contains("mobile") || l.contains("cell") || l.contains("contact") || l.contains("هاتف") || l.contains("موبايل")) {
            String ph = Json.str(p, "phone");
            return ph.isEmpty() ? "01000000000" : ph;
        }
        if (l.contains("city") || l.contains("location") || l.contains("address") || l.contains("residence") || l.contains("مدينة") || l.contains("محافظة")) {
            String ct = Json.str(p, "city");
            return ct.isEmpty() ? "Cairo" : ct;
        }
        if (l.contains("linkedin") || l.contains("url") || l.contains("website") || l.contains("link")) {
            return "https://www.linkedin.com";
        }
        if (l.contains("country") || l.contains("بلد") || l.contains("دولة")) {
            return "Egypt";
        }
        if (l.contains("experience") || l.contains("year") || l.contains("خبرة") || l.contains("سنة")) {
            return "2";
        }
        return "Yes";
    }
}
