package com.autoapply.bot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your info, sorted into categories: what forms ask about you (personal details, experience, skills...), each fact
 * with the wordings it already answered. A question worded differently still finds its fact: close wordings here,
 * the same thing in other words through the AI (Ai.matchFact).
 */
public final class Facts {
    private Facts() {
    }

    public static final String[] CATEGORIES = {"Personal", "Contact", "Education", "Experience", "Skills", "Certifications",
        "Languages", "Salary & availability", "Work preferences", "Work permit", "Links", "Other"};

    /** Checked in this order: the first category whose words appear in the question. */
    static final String[][] RULES = {
        {"Links", "linkedin|github|portfolio|website|\\burl\\b|\\blink\\b"},
        {"Salary & availability", "salar|compensation|\\bpay\\b|\\bpackage\\b|\\bctc\\b|notice|start date|(start|join)\\b.*\\b(when|soon|date)"
            + "|(when|soon)\\b.*\\b(start|join)|availab"},
        {"Work permit", "authori[sz]|sponsor|\\bvisa\\b|work permit|citizen|right to work|eligible to work|legally"},
        {"Work preferences", "relocat|remote|hybrid|on-?site|office|travel|shift|night|weekend|commut|full[- ]time|part[- ]time"
            + "|contract|freelance|overtime|work from"},
        {"Languages", "english|arabic|french|german|spanish|italian|turkish|chinese|language|fluen|bilingual"},
        {"Personal", "driv(er|ing)|\\bname\\b|birth|\\bage\\b|gender|\\bsex\\b|nationality|marital|military|religio|disab|veteran"
            + "|ethnic|\\brace\\b|pronoun"},
        {"Education", "degree|bachelor|master|ph\\.?d|diploma|universit|college|faculty|\\bgpa\\b|\\bgrade|graduat|\\bmajor\\b"
            + "|school|educat|studied|study"},
        {"Certifications", "certif|ccna|ccnp|ccie|\\bpmp\\b|itil|comptia|cissp|\\bcka\\b|\\brhce\\b|\\brhcsa\\b|mcsa|license"},
        {"Experience", "experience|\\byears?\\b|\\byrs?\\b|how long have you|worked (with|as|in|on)"},
        {"Skills", "proficien|familiar|knowledge|skill|\\btools?\\b|software|know how|\\bknow\\b|comfortable (with|using)|\\buse\\b"
            + "|programming|framework|hands-on"},
        {"Contact", "phone|mobile|whatsapp|e-?mail|address|\\bcity\\b|location|reside|live in|\\bzip\\b|postal"},
    };

    public static String categorize(String text) {
        String t = String.valueOf(text == null ? "" : text).toLowerCase(Locale.ROOT);
        for (String[] r : RULES) {
            if (Pattern.compile(r[1]).matcher(t).find()) {
                return r[0];
            }
        }
        return "Other";
    }

    public static String category(String name) {
        for (String c : CATEGORIES) {
            if (c.equalsIgnoreCase(String.valueOf(name).trim())) {
                return c;
            }
        }
        return "Other";
    }

    /** "how long is your notice period?" -> "How long is your notice period?" */
    public static String labelOf(String key) {
        String k = String.valueOf(key == null ? "" : key).trim();
        return k.isEmpty() ? k : Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    // ------------------------------------------------------------------ close wordings
    /** Words that only shape a question ("how many years of experience do you have with") and say nothing about what
     *  is asked. Two questions with the same remaining words ask the same thing. */
    static final Set<String> TEMPLATE = new HashSet<String>(Arrays.asList(("how many much what which when where who why whats "
            + "is are was were do does did done you your yours have has had having the a an of in on at to for with and or "
            + "please provide enter select choose describe tell us me about any years year yrs experience experienced "
            + "level rate would will can could should be been being it its this that these those if our we as by from "
            + "there their they them my i am so than then number kind type give share list mention state specify "
            + "kindly briefly up into s ve re ll d work working professional relevant practical").split(" ")));

    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("[a-z0-9+#.]+").matcher(String.valueOf(text).toLowerCase(Locale.ROOT).replace('’', '\''));
        while (m.find()) {
            String w = m.group().replaceAll("^[.]+|[.]+$", "");
            if (w.length() >= 2 || w.matches("\\d")) {
                out.add(w);
            }
        }
        return out;
    }

    /** The words that carry the meaning of a question. */
    public static Set<String> distinctive(String text) {
        Set<String> out = new LinkedHashSet<String>();
        for (String w : words(text)) {
            if (!TEMPLATE.contains(w)) {
                out.add(w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w);
            }
        }
        return out;
    }

    /** The fact that answers this question, when it was asked before in nearly the same words (same meaning words). */
    public static Map<String, Object> closeMatch(List<Map<String, Object>> facts, String question) {
        Set<String> q = distinctive(question);
        if (q.isEmpty()) {
            return null;
        }
        String nq = Util.normKey(question);
        Map<String, Object> best = null;
        double bestScore = 0;
        for (Map<String, Object> f : facts) {
            List<String> texts = new ArrayList<String>(Json.strings(f.get("asked")));
            texts.add(Json.str(f, "label"));
            for (String t : texts) {
                if (!distinctive(t).equals(q)) {
                    continue;
                }
                double score = Questions.similarity(nq, Util.normKey(t));
                if (score > bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
        }
        return bestScore >= 0.45 ? best : null;
    }

    // ------------------------------------------------------------------ experience with a skill
    static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /** Years you said you have with this skill or tool ("" = in total), from facts you entered or answered yourself.
     *  Null when you did not say. */
    public static Integer skillYears(List<Map<String, Object>> facts, String subject) {
        Set<String> want = distinctive(subject);
        want.removeAll(Arrays.asList("work", "professional", "total", "overall", "relevant"));
        for (Map<String, Object> f : facts) {
            if (!"Experience".equals(Json.str(f, "category")) || !"you".equals(Json.str(f, "source"))) {
                continue;
            }
            Matcher n = FIRST_NUMBER.matcher(answerText(f.get("value")));
            if (!n.find()) {
                continue;
            }
            List<String> texts = new ArrayList<String>(Json.strings(f.get("asked")));
            texts.add(Json.str(f, "label"));
            for (String t : texts) {
                Set<String> have = distinctive(t);
                have.removeAll(Arrays.asList("work", "professional", "relevant"));
                boolean total = have.isEmpty() || have.equals(new HashSet<String>(Arrays.asList("total")))
                        || have.equals(new HashSet<String>(Arrays.asList("overall")));
                if (want.isEmpty() ? total : (!have.isEmpty() && have.containsAll(want))) {
                    return (int) Math.round(Double.parseDouble(n.group(1)));
                }
            }
        }
        return null;
    }

    static String answerText(Object v) {
        return v instanceof List ? Util.join(", ", Json.strings(v)) : String.valueOf(v == null ? "" : v);
    }

    /** A fact's answer in the form a field takes: one of the options, a number, or text. Null when it does not fit. */
    public static Object fit(Object value, String kind, List<String> options, List<String> rejected) {
        if (value == null) {
            return null;
        }
        Object out;
        if (options != null && !options.isEmpty()) {
            if ("multi".equals(kind)) {
                List<String> parts = value instanceof List ? Json.strings(value)
                        : Arrays.asList(String.valueOf(value).split("\\s*[|,]\\s*"));
                List<Object> picked = new ArrayList<Object>();
                for (String p : parts) {
                    String m = Ai.matchOption(p, options);
                    if (m != null && !picked.contains(m)) {
                        picked.add(m);
                    }
                }
                out = picked.isEmpty() ? null : picked;
            } else {
                out = Ai.matchOption(value, options);
            }
        } else if ("numeric".equals(kind)) {
            // A number, maybe with a unit ("4", "4 years", "30,000 EGP"); not text like "Less than 1 year".
            Matcher m = Pattern.compile("^[~$€£]?\\s*(\\d+(?:\\.\\d+)?)\\s*\\+?\\s*[a-zA-Z$€£%.]*\\s*$")
                    .matcher(answerText(value).replace(",", "").trim());
            out = m.find() ? m.group(1).replaceAll("\\.0+$", "") : null;
        } else {
            String s = answerText(value).trim();
            out = s.isEmpty() ? null : s;
        }
        if (out != null && rejected != null && rejected.contains(answerText(out))) {
            return null;
        }
        return out;
    }
}
