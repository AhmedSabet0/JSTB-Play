package com.autoapply.bot.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The AI (OpenRouter, through the JSTB server): job match scoring, form answers, answer suggestions and search
 *  suggestions. The server adds the OpenRouter key and picks the model; the app sends its account token. */
public final class Ai {
    /** Where requests go when `url` is not set (an OpenRouter-compatible chat completions endpoint). */
    public static volatile String apiUrl = "https://openrouter.ai/api/v1/chat/completions";

    /** An AI error that retrying will not fix (signed out, Pro off, daily limit, no key on the server). */
    public static class FatalAIError extends RuntimeException {
        public FatalAIError(String m) {
            super(m);
        }
    }

    public static final String RULE_NUMERIC = "Reply with a whole number only (digits, no words, no currency). For 'years of "
            + "experience with X' use the total years from earliest job in CV if X is in CV, or 0 if X is not in CV. "
            + "For salary reply with digits only (e.g. 20000), never text words or currency symbols. For notice period use the notes; if the "
            + "question does not name a unit, answer in days.";

    public static String rule(String kind) {
        if ("numeric".equals(kind)) {
            return RULE_NUMERIC;
        }
        if ("choice".equals(kind)) {
            return "Reply with EXACTLY one of the options, copied verbatim.";
        }
        if ("multi".equals(kind)) {
            return "Reply with a JSON array of one or more of the options, copied verbatim.";
        }
        if ("textarea".equals(kind)) {
            return "Reply with 2 to 4 professional sentences in the first person.";
        }
        return "Reply with a short value (a few words or one short sentence). Follow the placeholder format if one is given.";
    }

    public static final String ANSWER_SYSTEM = "You fill job application forms for the candidate. Answer truthfully using "
            + "only the CV, the candidate notes and the known answers. Never invent degrees, certifications, employers or "
            + "skills. For voluntary demographic questions (gender, race, ethnicity, veteran, disability, sexual orientation) "
            + "pick the 'prefer not to say' or 'decline' option when one exists. For salary or compensation questions always "
            + "reply with a clean number in digits only (e.g. 20000, 25000), never text words like 'Negotiable'. "
            + "For years of experience with X, reply with total years since first job in CV if X is in CV, or 0 if not. "
            + "Reply with JSON only.";

    public String apiKey;
    public String model;
    public final String cv;
    public final String about;
    /** The endpoint (the server's /v1/ai/chat). */
    public String url = apiUrl;
    /** What the request is for: "setup" (reading your CV), "check" or "apply". The server allows "apply" with Pro only. */
    public String purpose = "apply";
    /** The model that answered last (the server picks it). */
    public volatile String servedBy = "";

    public Ai(String apiKey, String model, String cvText, String aboutMe) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.trim().isEmpty() ? "openai/gpt-4o-mini" : model.trim();
        String c = cvText == null ? "" : cvText;
        this.cv = c.length() > 7000 ? c.substring(0, 7000) : c;
        this.about = aboutMe == null ? "" : aboutMe.trim();
    }

    // ------------------------------------------------------------------ matching options
    /** Map a free-text answer onto one of the options, or return null. */
    public static String matchOption(Object answer, List<String> options) {
        if (answer instanceof List) {
            List<Object> l = Json.arr(answer);
            answer = l.isEmpty() ? null : l.get(0);
        }
        if (answer == null || options == null || options.isEmpty()) {
            return null;
        }
        String a = String.valueOf(answer).trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) {
            return null;
        }
        List<String> norm = new ArrayList<String>();
        for (String o : options) {
            norm.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
        }
        int idx = norm.indexOf(a);
        if (idx >= 0) {
            return options.get(idx);
        }
        for (int i = 0; i < norm.size(); i++) {
            String o = norm.get(i);
            if (!o.isEmpty() && (o.startsWith(a) || a.startsWith(o))) {
                return options.get(i);
            }
        }
        for (int i = 0; i < norm.size(); i++) {
            String o = norm.get(i);
            if (!o.isEmpty() && (o.contains(a) || a.contains(o))) {
                return options.get(i);
            }
        }
        Set<String> at = tokens(a);
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < norm.size(); i++) {
            Set<String> ot = tokens(norm.get(i));
            Set<String> both = new HashSet<String>(at);
            both.retainAll(ot);
            double score = both.size() / (double) Math.max(1, Math.max(at.size(), ot.size()));
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return bestScore >= 0.5 ? options.get(best) : null;
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<String>();
        Matcher m = Pattern.compile("[a-z0-9+#.]+").matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    // ------------------------------------------------------------------ transport
    private String post(String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Title", "JSTB");
            c.setRequestProperty("X-App-Flavor", Account.flavor);  // the server words its replies for this build
            c.setRequestProperty("X-App-Version", String.valueOf(Account.versionCode));
            OutputStream out = c.getOutputStream();
            try {
                out.write(body.getBytes(Util.UTF8));
            } finally {
                out.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(Util.readAll(in), Util.UTF8);
            return code + "\n" + text;
        } finally {
            c.disconnect();
        }
    }

    private static String shortBody(String body) {
        Object data = Json.tryParse(body);
        if (data instanceof Map) {
            Object err = Json.map(data).containsKey("error") ? Json.map(data).get("error") : data;
            if (err instanceof Map && Json.map(err).get("message") != null) {
                return Util.cut(Json.str(Json.map(err), "message"), 200);
            }
            return Util.cut(String.valueOf(err), 200);
        }
        return Util.cut(body, 200);
    }

    public String chat(List<Object> messages, int maxTokens) {
        Map<String, Object> req = Json.obj("messages", messages, "temperature", 0.2, "max_tokens", maxTokens, "purpose", purpose);
        if (model != null && !model.isEmpty()) {
            req.put("model", model);
        }
        String payload = Json.stringify(req);
        String last = "";
        for (int attempt = 0; attempt < 4; attempt++) {
            String raw;
            try {
                raw = post(payload);
            } catch (IOException e) {
                last = Util.shortError(e);
                Util.realSleep(3 * (attempt + 1));
                continue;
            }
            int nl = raw.indexOf('\n');
            int status = Integer.parseInt(raw.substring(0, nl));
            String body = raw.substring(nl + 1);
            if (status == 401) {
                throw new FatalAIError("You were signed out of your JSTB account. Sign in again in Settings.");
            }
            if (status == 402 || status == 403) {
                throw new FatalAIError(shortBody(body));
            }
            if (status == 404 || (status == 400 && body.toLowerCase(Locale.ROOT).contains("model"))) {
                throw new FatalAIError(shortBody(body));
            }
            if (status != 200) {
                last = "HTTP " + status + ": " + shortBody(body);
                Util.realSleep(status == 429 ? 12 * (attempt + 1) : 4 * (attempt + 1));
                continue;
            }
            Object data = Json.tryParse(body);
            if (!(data instanceof Map)) {
                last = "the AI sent an invalid response";
                Util.realSleep(3);
                continue;
            }
            Map<String, Object> d = Json.map(data);
            if (d.get("error") != null) {
                Object err = d.get("error");
                long code = err instanceof Map ? Json.num(Json.map(err), "code", 0) : 0;
                if (code == 401 || code == 402 || code == 403) {
                    throw new FatalAIError(shortBody(body));
                }
                last = shortBody(body);
                Util.realSleep(4 * (attempt + 1));
                continue;
            }
            if (!Json.str(d, "model").isEmpty()) {
                servedBy = Json.str(d, "model");
            }
            List<Object> choices = Json.arr(d.get("choices"));
            Object content = choices.isEmpty() ? null : Json.map(Json.map(choices.get(0)).get("message")).get("content");
            if (content instanceof List) {
                StringBuilder b = new StringBuilder();
                for (Object p : Json.arr(content)) {
                    b.append(Json.str(Json.map(p), "text")).append(' ');
                }
                content = b.toString().trim();
            }
            if (choices.isEmpty()) {
                last = "the AI sent no answer";
                Util.realSleep(3);
                continue;
            }
            return content == null ? "" : String.valueOf(content);
        }
        throw new RuntimeException("AI request failed: " + last);
    }

    private static Map<String, Object> msg(String role, String content) {
        return Json.obj("role", role, "content", content);
    }

    public String ping() {
        return chat(Json.list(msg("user", "Reply with the word OK only.")), 10);
    }

    // ------------------------------------------------------------------ tasks
    /** [keywords..., location] */
    public List<String> suggestSearch(StringBuilder location) {
        String out = chat(Json.list(
                msg("system", "You are a career assistant. Reply with JSON only."),
                msg("user", "CV:\n" + cv + "\n\nCANDIDATE NOTES:\n" + (about.isEmpty() ? "none" : about) + "\n\n"
                        + "Suggest 3 to 5 LinkedIn job-search keyword phrases (job titles) that best match this candidate, "
                        + "and the location (country or city) they should search in, based on the CV and notes. "
                        + "Return ONLY {\"keywords\": [\"...\"], \"location\": \"...\"}")), 250);
        Map<String, Object> data = Json.firstObject(out);
        List<String> keywords = new ArrayList<String>();
        for (String k : Json.strings(data.get("keywords"))) {
            if (!k.trim().isEmpty() && keywords.size() < 6) {
                keywords.add(k.trim());
            }
        }
        location.append(Json.str(data, "location").trim());
        return keywords;
    }

    /** A short cover letter for this job, from your CV and notes. */
    public String coverLetter(String title, String company, String description) {
        String job = title + (company == null || company.isEmpty() ? "" : " at " + company);
        String out = chat(Json.list(
                msg("system", "You write short, specific cover letters for job applications. Plain text only."),
                msg("user", "CV:\n" + cv + "\n\nCANDIDATE NOTES:\n" + (about.isEmpty() ? "none" : about) + "\n\nJOB: " + job + "\n"
                        + (description == null || description.trim().isEmpty() ? "" : "DESCRIPTION:\n" + Util.cut(description, 4000) + "\n")
                        + "\nWrite a cover letter of 120 to 180 words in the first person: why this role, two or three concrete "
                        + "strengths from the CV that fit it, and a short closing. No address block, no date, no placeholders in "
                        + "brackets. Sign with the candidate's name from the CV.")), 500);
        return out.trim();
    }

    /** Which of your facts answers this question, asked in other words: {id, answer} formatted for the field, or null
     *  when none of them states the answer. */
    public Map<String, Object> matchFact(String question, String kind, List<String> options, List<Map<String, Object>> facts) {
        StringBuilder list = new StringBuilder();
        for (Map<String, Object> f : facts) {
            String line = Json.str(f, "id") + " | " + Json.str(f, "category") + " | " + Util.cut(Json.str(f, "label"), 160)
                    + " | " + Util.cut(Facts.answerText(f.get("value")), 200) + "\n";
            if (list.length() + line.length() > 8000) {
                break;
            }
            list.append(line);
        }
        if (list.length() == 0) {
            return null;
        }
        String field = "numeric".equals(kind) ? "a number (digits only)"
                : options != null && !options.isEmpty() ? ("multi".equals(kind) ? "a JSON array of options copied exactly from OPTIONS"
                : "one option copied exactly from OPTIONS") : "short text";
        String out = chat(Json.list(
                msg("system", "You match job application questions to facts the candidate saved about themselves. Reply with "
                        + "JSON only."),
                msg("user", "FACTS (id | category | fact | answer):\n" + list + "\nQUESTION: " + question
                        + (options != null && !options.isEmpty() ? "\nOPTIONS: " + Json.stringify(options) : "")
                        + "\nTHE FIELD TAKES: " + field + "\n\n"
                        + "Pick a fact only when it gives the answer to this question: the same thing asked in other words, "
                        + "or a fact the answer follows from directly (for example 'Python: 4 years' answers 'Do you know "
                        + "Python?' with Yes). A fact about a similar but different thing does not count (weekends are not "
                        + "night shifts, the current salary is not the expected one). If no fact gives the answer, reply "
                        + "{\"id\": null}. Otherwise return ONLY {\"id\": \"<fact id>\", \"answer\": <the answer for this field>}.")),
                200);
        Map<String, Object> d = Json.firstObject(out);
        Object id = d.get("id");
        if (id == null || String.valueOf(id).trim().isEmpty() || "null".equals(String.valueOf(id))) {
            return null;
        }
        return Json.obj("id", String.valueOf(id).trim(), "answer", d.get("answer"));
    }

    /** What the setup screen can fill in from the CV: phone, city, graduation_year, english, titles, location. */
    public Map<String, Object> profileFromCv() {
        String out = chat(Json.list(
                msg("system", "You read CVs and fill in job application details. Reply with JSON only."),
                msg("user", "CV:\n" + cv + "\n\nReturn ONLY {\"phone\": \"\", \"city\": \"\", \"graduation_year\": \"\", \"first_job_year\": \"\", "
                        + "\"english\": \"\", \"titles\": [], \"location\": \"\"} where:\n"
                        + "- phone: the candidate's mobile number exactly as written in the CV, or \"\".\n"
                        + "- city: where the candidate lives, as \"City, Country\", or \"\".\n"
                        + "- graduation_year: the 4-digit year the candidate finished their bachelor's degree (or their "
                        + "highest degree), or \"\".\n"
                        + "- first_job_year: the 4-digit year the candidate started their first real job (not an "
                        + "internship or training), from the experience section, or \"\".\n"
                        + "- english: one of Native, Fluent, Professional, Good, Basic, from what the CV says, or \"\".\n"
                        + "- titles: 3 to 5 LinkedIn job titles (job-search keywords) that fit this candidate best.\n"
                        + "- location: the country (or city) the candidate should search jobs in.")), 350);
        Map<String, Object> d = Json.firstObject(out);
        List<String> titles = new ArrayList<String>();
        for (String t : Json.strings(d.get("titles"))) {
            if (!t.trim().isEmpty() && titles.size() < 6) {
                titles.add(t.trim());
            }
        }
        String year = Json.str(d, "graduation_year").replaceAll("[^0-9]", "");
        String firstJob = Json.str(d, "first_job_year").replaceAll("[^0-9]", "");
        String english = "";
        for (String level : Profile.ENGLISH) {
            if (level.equalsIgnoreCase(Json.str(d, "english").trim())) {
                english = level;
            }
        }
        return Json.obj("phone", Profile.clean("phone", Json.str(d, "phone")), "city", Util.cut(Json.str(d, "city").trim(), 80),
                "graduation_year", year.length() == 4 ? year : "", "first_job_year", firstJob.length() == 4 ? firstJob : "",
                "english", english, "titles", titles,
                "location", Util.cut(Json.str(d, "location").trim(), 80));
    }

    /** {score, reason} */
    public Object[] score(String title, String company, String description) {
        String desc = description == null ? "" : description;
        String out = chat(Json.list(
                msg("system", "You are a strict technical recruiter. Rate how well the candidate fits the job. Reply with JSON only."),
                msg("user", "CANDIDATE CV:\n" + cv + "\n\nCANDIDATE NOTES:\n" + (about.isEmpty() ? "none" : about) + "\n\n"
                        + "JOB: " + title + " at " + company + "\n" + Util.cut(desc, 6000) + "\n\n"
                        + "Return ONLY {\"score\": <integer 0-10>, \"reason\": \"<one short sentence>\"}. "
                        + "Give 7 or more only when the core required skills AND seniority match the CV. "
                        + "Respect any constraints in the candidate notes (location, remote, job type).")), 200);
        Map<String, Object> data = Json.firstObject(out);
        int score;
        Object s = data.get("score");
        if (s instanceof Number) {
            score = (int) ((Number) s).doubleValue();
        } else {
            Matcher m = Pattern.compile("score\"?\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(out);
            if (s instanceof String && ((String) s).trim().matches("\\d+(\\.\\d+)?")) {
                score = (int) Double.parseDouble(((String) s).trim());
            } else if (m.find()) {
                score = Integer.parseInt(m.group(1));
            } else {
                throw new RuntimeException("The AI reply had no score (check the model in Settings): " + Util.cut(out, 120));
            }
        }
        return new Object[]{Math.max(0, Math.min(10, score)), Util.cut(Json.str(data, "reason").trim(), 300)};
    }

    private List<String> parts(String question, String kind, List<String> options, Map<String, Object> known, String job,
                               String placeholder, int maxlen) {
        List<String> p = new ArrayList<String>();
        p.add("CV:\n" + cv);
        p.add("CANDIDATE NOTES:\n" + (about.isEmpty() ? "none" : about));
        p.add("KNOWN ANSWERS:\n" + Util.cut(Json.stringify(known == null ? Json.obj() : known), 3000));
        p.add("JOB: " + (job == null || job.isEmpty() ? "unknown" : job));
        p.add("QUESTION: " + question);
        p.add("FIELD TYPE: " + kind);
        if (options != null && !options.isEmpty()) {
            p.add("OPTIONS: " + Json.stringify(options));
        }
        if (placeholder != null && !placeholder.isEmpty()) {
            p.add("PLACEHOLDER: " + placeholder);
        }
        if (maxlen > 0) {
            p.add("MAX LENGTH: " + maxlen + " characters");
        }
        return p;
    }

    /** Candidate answers for you to pick from (ask-me mode): {suggestions: [...], reason: ""}. */
    public Map<String, Object> suggest(String question, String kind, List<String> options, Map<String, Object> known,
                                       String job, String hint, String placeholder, List<String> rejected, int maxlen) {
        String wanted;
        if ("numeric".equals(kind)) {
            wanted = "Give up to 3 candidate answers, whole numbers as strings, best first.";
        } else if ("choice".equals(kind)) {
            wanted = "Give the best option first and at most one alternative, copied verbatim from OPTIONS.";
        } else if ("multi".equals(kind)) {
            wanted = "Give the options that should be ticked, copied verbatim from OPTIONS, best first.";
        } else if ("textarea".equals(kind)) {
            wanted = "Give up to 2 candidate answers of 2 to 4 sentences, first person, best first.";
        } else {
            wanted = "Give up to 3 short candidate answers, best first. Follow the placeholder format if one is given.";
        }
        List<String> p = parts(question, kind, options, known, job, placeholder, maxlen);
        if (hint != null && !hint.isEmpty()) {
            p.add("LINKEDIN REJECTED THE PREVIOUS ANSWER(S) " + Json.stringify(rejected == null ? Json.list() : rejected)
                    + " WITH THE MESSAGE: \"" + hint + "\". Suggest answers that satisfy this message.");
        }
        p.add("RULE: " + rule(kind) + " " + wanted);
        p.add("Return ONLY {\"suggestions\": [...], \"reason\": \"<one short sentence: what in the CV or notes supports the "
                + "first suggestion>\"}");
        String out = chat(Json.list(
                msg("system", "You suggest answers to job application questions for the candidate, who picks the final "
                        + "answer. Use only the CV, the candidate notes and the known answers; never invent degrees, employers "
                        + "or skills. Reply with JSON only."),
                msg("user", Util.join("\n\n", p))), 400);
        Map<String, Object> data = Json.firstObject(out);
        Object raw = data.get("suggestions");
        List<Object> items = raw instanceof List ? Json.arr(raw) : (raw == null || "".equals(raw) ? Json.list() : Json.list(raw));
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (Object item : items) {
            List<Object> values = item instanceof List && "multi".equals(kind) ? Json.arr(item) : Json.list(item);
            for (Object v : values) {
                if (v == null || "".equals(v)) {
                    continue;
                }
                String s = null;
                if ("numeric".equals(kind)) {
                    Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(String.valueOf(v));
                    s = m.find() ? m.group().replaceAll("\\.0+$", "") : null;
                } else if (options != null && !options.isEmpty()) {
                    s = matchOption(v, options);
                } else {
                    s = String.valueOf(v).trim();
                    if (maxlen > 0 && s.length() > maxlen) {
                        String cutText = s.substring(0, maxlen);
                        s = cutText.contains(" ") ? cutText.substring(0, cutText.lastIndexOf(' ')) : cutText;
                    }
                }
                if (s != null && !s.isEmpty()) {
                    result.add(s);
                }
            }
        }
        List<Object> list = new ArrayList<Object>(result);
        if (list.size() > 4) {
            list = new ArrayList<Object>(list.subList(0, 4));
        }
        return Json.obj("suggestions", list, "reason", Util.cut(Json.str(data, "reason").trim(), 240));
    }

    /** The AI's answer and how sure it is (0 to 100) that the answer is true for you. */
    public static final class Sure {
        public final Object answer;
        public final int sure;
        public final String why;

        Sure(Object answer, int sure, String why) {
            this.answer = answer;
            this.sure = sure;
            this.why = why;
        }
    }

    /** The AI's own answer: a String, a List of options (multi), or null. */
    public Object answer(String question, String kind, List<String> options, Map<String, Object> known, String job,
                         String hint, String placeholder, List<String> rejected, int maxlen) {
        return answer(question, kind, options, known, job, hint, placeholder, rejected, maxlen, false).answer;
    }

    /** The AI's answer, with how sure it is: what the CV, the notes or your answers say scores high; a guess, a
     *  preference or anything only you can know scores low. */
    public Sure sureAnswer(String question, String kind, List<String> options, Map<String, Object> known, String job,
                           String hint, String placeholder, List<String> rejected, int maxlen) {
        return answer(question, kind, options, known, job, hint, placeholder, rejected, maxlen, true);
    }

    static int sureOf(Object raw) {
        double v;
        if (raw instanceof Number) {
            v = ((Number) raw).doubleValue();
        } else {
            Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(raw == null ? "" : String.valueOf(raw));
            if (!m.find()) {
                return 0;
            }
            v = Double.parseDouble(m.group());
        }
        if (v > 0 && v < 1) {
            v *= 100;   // 0.9 meant 90 (a bare 1 stays 1: not sure, so you are asked)
        }
        return (int) Math.max(0, Math.min(100, Math.round(v)));
    }

    private Sure answer(String question, String kind, List<String> options, Map<String, Object> known, String job,
                        String hint, String placeholder, List<String> rejected, int maxlen, boolean rate) {
        List<String> p = parts(question, kind, options, known, job, placeholder, maxlen);
        if (hint != null && !hint.isEmpty() && rejected != null && !rejected.isEmpty()) {
            p.add("LINKEDIN REJECTED THE PREVIOUS ANSWER(S) " + Json.stringify(rejected) + " WITH THE MESSAGE: \"" + hint
                    + "\". Give a different answer that satisfies this message.");
        } else if (hint != null && !hint.isEmpty()) {
            p.add("THE PREVIOUS ANSWER WAS REJECTED WITH: " + hint);
        }
        p.add("RULE: " + rule(kind));
        if (rate) {
            p.add("SURE: how sure you are, from 0 to 100, that the answer is true for this candidate. 90 or more only when the "
                    + "CV, the candidate notes or the known answers say it, or it follows directly from them. 50 or less when "
                    + "you guess, when it is a preference (pay, relocation, work setting, start date) they do not state, or "
                    + "anything only the candidate can know.");
            p.add("Return ONLY {\"answer\": ..., \"sure\": <0-100>, \"why\": \"<a few words>\"}");
        } else {
            p.add("Return ONLY {\"answer\": ...}");
        }
        String out = chat(Json.list(msg("system", ANSWER_SYSTEM), msg("user", Util.join("\n\n", p))), rate ? 360 : 300);
        Map<String, Object> d = Json.firstObject(out);
        int sure = rate ? sureOf(d.get("sure")) : 100;
        String why = Util.cut(Json.str(d, "why").trim(), 160);
        return new Sure(clean(d.get("answer"), kind, options), sure, why);
    }

    /** The answer the AI gave, in the form the field takes; null when it gave none that fits. */
    static Object clean(Object a, String kind, List<String> options) {
        if (a == null || "".equals(a) || (a instanceof List && Json.arr(a).isEmpty())) {
            return null;
        }
        if ("numeric".equals(kind)) {
            String s = String.valueOf(a);
            if (Pattern.compile("\\b(less than|under)\\s*(1|one)\\b", Pattern.CASE_INSENSITIVE).matcher(s).find()) {
                return "0";
            }
            Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(s);
            return m.find() ? m.group().replaceAll("\\.0+$", "") : null;
        }
        if ("multi".equals(kind)) {
            List<Object> items = a instanceof List ? Json.arr(a) : new ArrayList<Object>();
            if (!(a instanceof List)) {
                for (String x : String.valueOf(a).split("\\s*[|,]\\s*")) {
                    items.add(x);
                }
            }
            LinkedHashSet<String> picked = new LinkedHashSet<String>();
            for (Object x : items) {
                String m = matchOption(x, options);
                if (m != null) {
                    picked.add(m);
                }
            }
            return picked.isEmpty() ? null : new ArrayList<Object>(picked);
        }
        if (options != null && !options.isEmpty()) {
            return matchOption(a, options);
        }
        String s = (a instanceof List ? String.valueOf(Json.arr(a).get(0)) : String.valueOf(a)).trim();
        return s.isEmpty() ? null : s;
    }
}
