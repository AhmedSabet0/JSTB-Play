import com.autoapply.bot.core.Ai;
import com.autoapply.bot.core.Api;
import com.autoapply.bot.core.Engine;
import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Page;
import com.autoapply.bot.core.Platform;
import com.autoapply.bot.core.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the Android engine on the JVM against the LinkedIn mocks in headless Chrome. */
public class EngineTest {
    static final File SCRATCH = new File(System.getProperty("scratch"));
    static final File ASSETS = new File(System.getProperty("assets"));
    static int mockPort;
    static Process mock;
    static final List<Boolean> results = new ArrayList<>();

    static void check(String name, boolean cond, Object extra) {
        results.add(cond);
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name + (extra == null ? "" : "  -> " + extra));
    }

    static void check(String name, boolean cond) {
        check(name, cond, null);
    }

    // ------------------------------------------------------------------ test platform
    static class TestPlatform implements Platform {
        final String scenario;
        final List<CdpPage> pages = new ArrayList<>();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> lastSummary;

        TestPlatform(String scenario) {
            this.scenario = scenario;
        }

        List<String> console() {
            List<String> out = new ArrayList<>();
            for (CdpPage p : pages) {
                out.addAll(p.console);
            }
            return out;
        }

        @Override
        public Page newPage() {
            CdpPage p = new CdpPage(scenario, mockPort);
            pages.add(p);
            return p;
        }

        @Override
        public void closePage(Page page) {
            ((CdpPage) page).close();
        }

        @Override
        public String pageScripts() {
            try {
                return new String(Files.readAllBytes(new File(ASSETS, "li.js").toPath()), "UTF-8");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String browserName() {
            return "headless Chrome (test)";
        }

        @Override
        public void onRunning(boolean running) {
            events.add("running=" + running);
        }

        @Override
        public void onQuestion(Map<String, Object> item) {
            events.add("question:" + Json.str(item, "question") + (Json.bool(item, "loading") ? " (loading)" : ""));
        }

        @Override
        public void onQuestionDone() {
            events.add("question-done");
        }

        @Override
        public void onSignInNeeded() {
            events.add("signin");
        }

        @Override
        public void onSessionEnd(Map<String, Object> summary, List<String> lines) {
            lastSummary = summary;
            events.add("session-end");
        }

        volatile List<Map<String, Object>> ready = new ArrayList<>();

        @Override
        public void onReady(List<Map<String, Object>> items) {
            ready = items;
            int waiting = 0;
            for (Map<String, Object> j : items) {
                if ("ready".equals(Json.str(j, "status"))) {
                    waiting++;
                }
            }
            events.add("ready:" + waiting);
        }

        @Override
        public void openSignIn() {
            events.add("open-signin");
        }

        @Override
        public boolean signedIn() {
            return true;
        }

        @Override
        public void share(File file, String mime, String title) {
            events.add("share:" + file.getName());
        }

        @Override
        public Map<String, Object> permissions() {
            return Json.obj("notifications", true, "battery", true, "overlay", true);
        }

        @Override
        public void requestPermission(String which) {
            events.add("permission:" + which);
        }

        @Override
        public void openLink(String url, String how, String jobId, String title) {
            events.add("open:" + how + ":" + url + (jobId == null || jobId.isEmpty() ? "" : " job=" + jobId));
        }

        volatile String updState = "idle";
        volatile boolean selfUpdate = true;

        public Map<String, Object> buildInfo() {
            return Json.obj("flavor", selfUpdate ? "github" : "play", "self_update", selfUpdate);
        }

        public Map<String, Object> appVersion() {
            return Json.obj("name", "1.0", "code", 1);
        }

        public void installUpdate(String url, String version, boolean auto) {
            events.add((auto ? "install-auto:" : "install:") + url + ":" + version);
            updState = "downloading";
        }

        public Map<String, Object> updateState() {
            return Json.obj("state", updState, "progress", 40, "message", "downloading".equals(updState) ? "Downloading version 1.2... 40%" : "");
        }
    }

    // ------------------------------------------------------------------ helpers
    static final String EMAIL = "tester@example.com";
    static final String PASSWORD = "tester-pass-1";
    static String token = "";

    /** Signed in to the test server as EMAIL (Pro). */
    static void signIn(File d) {
        write(d, "account.json", Json.pretty(Json.obj("token", token, "email", EMAIL, "user", Json.obj("pro", true))));
    }

    static File fresh(String name) throws Exception {
        return fresh(name, true);
    }

    static File fresh(String name, boolean signedIn) throws Exception {
        File d = new File(SCRATCH, "jrun_" + name);
        deleteTree(d);
        d.mkdirs();
        if (signedIn) {
            signIn(d);
        }
        Files.copy(new File(SCRATCH, "cv_test.pdf").toPath(), new File(d, "cv.pdf").toPath());
        Files.copy(new File(SCRATCH, "pyrun/data/cv_text.txt").toPath(), new File(d, "cv_text.txt").toPath());
        return d;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    static Map<String, Object> config(Object... extra) {
        Map<String, Object> cfg = Json.obj("KEYWORDS", "DevOps Engineer", "LOCATION", "Egypt",
                "PAGES_PER_KEYWORD", "1", "NEW_QUESTIONS", "ai", "ASK_TIMEOUT_MIN", "0", "CV_FILE", "cv.pdf", "CV_NAME", "cv.pdf",
                "APPROVE_FIRST", "0");   // these scenarios send by themselves; approve() tests asking first
        for (int k = 0; k + 1 < extra.length; k += 2) {
            cfg.put(String.valueOf(extra[k]), extra[k + 1]);
        }
        return cfg;
    }

    static void write(File dir, String name, String text) {
        Util.writeText(new File(dir, name), text);
    }

    static Map<String, Map<String, Object>> jobs(File dir) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Object o : Json.arr(Json.tryParse(Util.readText(new File(dir, "jobs.json"))))) {
            Map<String, Object> j = Json.map(o);
            out.put(Json.str(j, "id"), j);
        }
        return out;
    }

    static Map<String, String> statuses(File dir) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> e : jobs(dir).entrySet()) {
            out.put(e.getKey(), Json.str(e.getValue(), "status"));
        }
        return out;
    }

    static Map<String, String> qa(Map<String, Object> job) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Object o : Json.arr(job.get("qa"))) {
            out.put(Json.str(Json.map(o), "q"), Json.str(Json.map(o), "a"));
        }
        return out;
    }

    static Map<String, Object> answers(File dir) {
        return Json.map(Json.tryParse(Util.readText(new File(dir, "answers.json"))));
    }

    static String log(File dir) {
        String t = Util.readText(new File(dir, "logs/bot.log"));
        return t == null ? "" : t;
    }

    static int count(List<String> list, String s) {
        int n = 0;
        for (String x : list) {
            if (x.equals(s)) {
                n++;
            }
        }
        return n;
    }

    static int count(String text, String s) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(s, i)) >= 0) {
            n++;
            i += s.length();
        }
        return n;
    }

    static com.autoapply.bot.core.Bot.Options opts(boolean noSubmit) {
        com.autoapply.bot.core.Bot.Options o = new com.autoapply.bot.core.Bot.Options();
        o.once = true;
        o.noSubmit = noSubmit;
        return o;
    }

    static Map<String, Object> calls() throws Exception {
        java.net.http.HttpResponse<String> r = CdpPage.HTTP.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + mockPort + "/__calls")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        return Json.map(Json.parse(r.body()));
    }

    // ------------------------------------------------------------------ scenarios
    static void classic() throws Exception {
        System.out.println("\n== Classic layout (AI answers, apply)");
        File d = fresh("classic");
        write(d, "config.json", Json.pretty(config()));
        write(d, "about_me.txt", "Phone: 01012345678\n");
        TestPlatform p = new TestPlatform("classic");
        Engine e = new Engine(p, d);
        long t0 = System.currentTimeMillis();
        e.run(opts(false));
        System.out.println("  (" + (System.currentTimeMillis() - t0) / 1000 + "s)");
        Map<String, String> st = statuses(d);
        Map<String, String> qa1 = qa(jobs(d).get("111"));
        List<String> con = p.console();
        String lg = log(d);
        Map<String, Object> ans = answers(d);
        check("statuses", st.toString().equals("{111=applied, 222=applied, 444=stuck, 555=no_easy_apply, 666=skipped_low_score}"), st);
        check("phone typed", "01012345678".equals(qa1.get("Mobile phone number")), qa1.get("Mobile phone number"));
        check("city picked from the typeahead", "Cairo".equals(qa1.get("Location (city)")), qa1.get("Location (city)"));
        check("years fixed after the validation error", "3".equals(qa1.get("How many years of work experience do you have with Terraform?")));
        check("radio", "Yes".equals(qa1.get("Are you comfortable working in a hybrid setting?")));
        check("select mapped to the closest option", "Professional".equals(qa1.get("What is your level of proficiency in English?")));
        check("multi checkbox", "AWS, Azure".equals(qa1.get("Which cloud platforms have you used in production?")),
                qa1.get("Which cloud platforms have you used in production?"));
        check("consent checkbox", "Checked".equals(qa1.get("Privacy policy")));
        check("CV uploaded for both applications (file input, no dialog)", count(con, "AA_UPLOADED=cv.pdf") == 2,
                count(con, "AA_UPLOADED=cv.pdf"));
        check("follow company unticked before submit", count(con, "AA_FOLLOW_AT_SUBMIT=false") == 2
                && !con.contains("AA_FOLLOW_AT_SUBMIT=true"));
        check("success dialog closed", count(con, "AA_CLOSED_DIALOG") == 2);
        check("stuck form discarded with confirm", con.contains("AA_DISCARDED"));
        check("stuck reason recorded", Json.str(jobs(d).get("444"), "reason").contains("ZZ-0000"), Json.str(jobs(d).get("444"), "reason"));
        File[] dumps = new File(d, "logs/debug").listFiles((x, n) -> n.endsWith("stuck-444.html"));
        check("page of the stuck form saved + debug.zip", dumps != null && dumps.length == 1 && new File(d, "logs/debug.zip").isFile());
        check("answers saved", "3".equals(ans.get("how many years of work experience do you have with terraform?"))
                && Json.list("AWS", "Azure").equals(ans.get("which cloud platforms have you used in production?")), ans);
        Map<String, Object> state = Json.map(Json.parse(Util.readText(new File(d, "state.json"))));
        check("2 submit times saved", Json.arr(state.get("submit_times")).size() == 2);
        check("pacing wait logged", count(lg, "Next application slot at") == 2, count(lg, "Next application slot at"));
        check("session brief", lg.contains("Applied to 2 job(s):") && lg.contains("Stuck on 1 job(s)"));
        check("no stray errors in the log", !lg.contains("Error:"), errorLines(lg));
        check("session end reported", p.events.contains("session-end") && p.lastSummary != null);
    }

    static String errorLines(String lg) {
        StringBuilder b = new StringBuilder();
        for (String l : lg.split("\n")) {
            if (l.contains("Error:")) {
                b.append(l).append(" | ");
            }
        }
        return b.toString();
    }

    static void sdui() throws Exception {
        System.out.println("\n== Newer layout (AI answers): test run, then apply");
        File d = fresh("sdui");
        write(d, "config.json", Json.pretty(config()));
        write(d, "about_me.txt", "Phone: 01012345678\nYears of experience with Fortinet: 2\n");
        write(d, "answers.json", Json.stringify(Json.obj("how many years of work experience do you have with fortinet?", "Less than 1 year")));
        TestPlatform p = new TestPlatform("sdui");
        Engine e = new Engine(p, d);
        e.run(opts(true));
        Map<String, String> st1 = statuses(d);
        List<String> con1 = p.console();
        check("statuses after the test run", st1.toString().equals("{4480000001=dry_run_match, 4480000002=dry_run_match, 4480000003=no_easy_apply}"), st1);
        check("forms filled and discarded, nothing submitted", count(con1, "SDUI_DISCARDED=Acme") == 1
                && count(con1, "SDUI_DISCARDED=Globex") == 1 && con1.stream().noneMatch(c -> c.startsWith("SDUI_SUBMITTED")), con1);
        String lg1 = log(d);
        check("3 job ids found (2 from links, 1 from page data)", lg1.contains("3 jobs found, 3 new"));
        e.run(opts(false));
        Map<String, String> st2 = statuses(d);
        List<String> con = p.console();
        Map<String, String> qa1 = qa(jobs(d).get("4480000001"));
        check("matches applied in the real run", st2.toString().equals("{4480000001=applied, 4480000002=applied, 4480000003=no_easy_apply}"), st2);
        check("both submitted once", count(con, "SDUI_SUBMITTED=Acme") == 1 && count(con, "SDUI_SUBMITTED=Globex") == 1);
        check("Fortinet 2 (from the notes, the saved text answer ignored)",
                "2".equals(qa1.get("How many years of work experience do you have with Fortinet?")), qa1);
        check("stubborn AI '0' repaired to a number in range (Juniper -> 1)",
                "1".equals(qa1.get("How many years of work experience do you have with Juniper?")));
        check("'Enter a whole number' -> salary 25000", "25000".equals(qa1.get("What is your expected monthly salary in EGP?")));
        check("'decimal larger than 0.0' -> GPA 1.0", "1.0".equals(qa1.get("What is your GPA?")), qa1.get("What is your GPA?"));
        check("20-character limit at a word boundary", "Experienced DevOps".equals(qa1.get("Professional headline")),
                qa1.get("Professional headline"));
        check("other answers", "3".equals(qa1.get("How many years of experience do you have with Kubernetes?"))
                && "Yes".equals(qa1.get("Are you willing to relocate?")) && "01012345678".equals(qa1.get("Mobile phone number")), qa1);
        check("no errors", !log(d).contains("Error:"), errorLines(log(d)));
    }

    static void real() throws Exception {
        System.out.println("\n== LinkedIn's current markup (AI answers, apply)");
        File d = fresh("real");
        write(d, "config.json", Json.pretty(config()));
        write(d, "about_me.txt", "Phone: 01012345678\n");
        write(d, "answers.json", Json.stringify(Json.obj(
                "how many years of work experience do you have with fortinet?", "Less than 1 year",
                "mention your salary expectations.", "Negotiable",
                "how long is your notice period?", "Immediate availability",
                "what is your current monthly salary?", "Negotiable")));
        TestPlatform p = new TestPlatform("real");
        Engine e = new Engine(p, d);
        e.run(opts(false));
        Map<String, String> st = statuses(d);
        Map<String, String> qa1 = qa(jobs(d).get("4481850707"));
        String lg = log(d);
        check("both jobs applied", st.toString().equals("{4481850707=applied, 4481896241=applied}"), st);
        check("submitted twice", p.console().stream().filter(c -> c.startsWith("REAL_SUBMITTED")).count() == 2);
        check("radio question from aria-label, option text from the sibling",
                "Yes".equals(qa1.get("Have you completed the following level of education: Bachelor's Degree?")), qa1);
        boolean digits = true;
        for (String q : new String[]{"How many years of work experience do you have with Fortinet?",
            "Mention your salary expectations.", "How long is your notice period?", "What is your current monthly Salary?"}) {
            digits = digits && qa1.getOrDefault(q, "").matches("\\d+");
        }
        check("years / salary / notice answered with numbers", digits, qa1);
        check("'Invalid input' under the field detected and repaired",
                lg.contains("LinkedIn rejected: How many years of experience do you have in the network?: Invalid input")
                        && "3".equals(qa1.get("How many years of experience do you have in the network?")), qa1);
        check("answers fit the 20-character limit", qa1.values().stream().allMatch(v -> v.length() <= 20));
        check("brief in the log", lg.contains("SESSION BRIEF") && lg.contains("Applied to 2 job(s):"));
        check("no errors", !lg.contains("Error:"), errorLines(lg));
    }

    static void trusted() throws Exception {
        System.out.println("\n== Easy Apply button that ignores script clicks (only a real tap opens it)");
        File d = fresh("trusted");
        write(d, "config.json", Json.pretty(config()));
        write(d, "about_me.txt", "Phone: 01012345678\n");
        write(d, "jobs.json", Json.stringify(Json.list(Json.obj("id", "4481850707", "ts", Util.now() - 3600, "status", "error",
                "score", 8, "title", "Information Technology Network Specialist", "company", "Al-Mansour Automotive",
                "reason", "Good fit | The Easy Apply form did not open (page saved in logs/debug)", "qa", Json.list()))));
        TestPlatform p = new TestPlatform("trusted");
        Engine e = new Engine(p, d);
        e.run(opts(false));
        Map<String, String> st = statuses(d);
        String lg = log(d);
        List<String> con = p.console();
        long tapsOpened = con.stream().filter(c -> c.startsWith("TRUSTED_OPENED=") && c.endsWith(" tap")).count();
        check("both jobs applied", st.toString().equals("{4481850707=applied, 4481896241=applied}"), st);
        check("script click ignored, then the window opened with a tap", count(con, "TRUSTED_IGNORED=Easy Apply") == 2
                && tapsOpened == 2 && count(lg, "Tapped Easy Apply") == 2, con);
        check("the first Next and a stubborn Submit went through with a tap", count(con, "TRUSTED_IGNORED=Next") == 2
                && count(con, "TRUSTED_IGNORED=Submit application") == 1 && count(lg, "Tapped Submit") == 1
                && count(lg, "Tapped the button") == 2, lg.lines().filter(l -> l.contains("Tapped")).toList());
        check("submitted once per job", count(con, "REAL_SUBMITTED=Al-Mansour Automotive") == 1
                && count(con, "REAL_SUBMITTED=Konecta") == 1);
        check("no errors", !lg.contains("Error:"), errorLines(lg));
        check("a job that failed in 1.1 (window did not open) was tried again", lg.contains("1 job(s) where the Easy Apply window "
                + "did not open before this version will be tried again."));
    }

    // ------------------------------------------------------------------ setup answers and their rules
    static Map<String, Object> testProfile() {
        return Json.obj("phone", "01012345678", "city", "Cairo, Egypt", "graduation_year", "2019", "current_salary", "20,000",
                "expected_salary", "30000", "currency", "EGP", "notice_days", "30", "military", "Completed", "relocate", "No",
                "onsite", "Yes", "authorized", "Egypt", "sponsorship", "No", "english", "Professional", "driving", "Yes");
    }

    static void profile() throws Exception {
        System.out.println("\n== Setup answers: rules for the questions forms ask most");
        File d = fresh("profile");
        write(d, "config.json", Json.pretty(config()));
        write(d, "answers.json", Json.stringify(Json.obj("what is your expected salary?", "Negotiable",
                "how many years of experience do you have with kubernetes?", "0",
                "how many years of experience do you have with juniper?", "1")));
        com.autoapply.bot.core.Profile.ratesUrl = "http://127.0.0.1:" + mockPort + "/rates/";
        TestPlatform p = new TestPlatform("real");
        Engine e = new Engine(p, d);
        Api a = new Api(e);
        check("not done before the setup", !Json.bool(Json.map(api(a, "/api/state", null).get("setup")), "profile_done"));
        Map<String, Object> r = api(a, "/api/profile", Json.obj("profile", testProfile()));
        Map<String, Object> saved = Json.map(r.get("profile"));
        check("saved (numbers cleaned: 20,000 -> 20000)", Json.bool(r, "ok") && "20000".equals(Json.str(saved, "current_salary"))
                && Json.bool(Json.map(api(a, "/api/state", null).get("setup")), "profile_done"), saved);
        check("saved answers the setup now covers are replaced, the rest kept", Json.num(r, "replaced", -1) == 2
                && answers(d).containsKey("how many years of experience do you have with juniper?") && answers(d).size() == 1,
                answers(d));
        int y = com.autoapply.bot.core.Profile.yearsSince("2019", Util.now());
        check("years since graduating in 2019", y >= 7 && y <= 8 && Json.num(r, "years", -1) == y, y);
        check("notes for the AI", Json.str(r, "notes").contains("Expected monthly salary: 30000 EGP")
                && Json.str(r, "notes").contains("Notice period: 30 days"), r.get("notes"));
        String Y = String.valueOf(y);
        String cv = e.config.cvText();
        String[][] cases = {
            {"How many years of experience do you have with Kubernetes?", "numeric", "", Y},
            {"How many years of work experience do you have with Cisco Routers?", "numeric", "", Y},
            {"How many years of Python experience do you have?", "numeric", "", Y},
            {"How many years of experience do you have in the network?", "numeric", "", Y},
            {"How many years of experience do you have?", "numeric", "", Y},
            {"How many years of experience do you have with Juniper?", "numeric", "", null},
            {"How many months of experience do you have with Docker?", "numeric", "", String.valueOf(y * 12)},
            {"Do you have at least 5 years of experience with AWS?", "choice", "Yes|No", "Yes"},
            {"Do you have at least 10 years of experience with AWS?", "choice", "Yes|No", "No"},
            {"Years of experience with Terraform", "choice", "Less than 1 year|1-3 years|3-5 years|5+ years", "5+ years"},
            {"What is your notice period?", "numeric", "", "30"},
            {"What is your notice period in weeks?", "numeric", "", "5"},
            {"Notice period (months)", "numeric", "", "1"},
            {"Can you start within 2 weeks?", "choice", "Yes|No", "No"},
            {"Are you able to join within 1 month?", "choice", "Yes|No", "Yes"},
            {"How soon can you join?", "choice", "Immediately|Within 2 weeks|Within 1 month|More than 1 month", "Within 1 month"},
            {"What is your expected salary?", "numeric", "", "30000"},
            {"What is your current monthly salary?", "numeric", "", "20000"},
            {"Expected salary in USD", "numeric", "", "600"},
            {"What is your expected annual salary ($)?", "numeric", "", "7200"},
            {"Expected monthly salary (SAR)", "numeric", "", "2250"},
            {"Current salary in EGP (in thousands)", "numeric", "", "20"},
            {"Expected salary range", "choice", "10,000 - 20,000 EGP|20,000 - 30,000 EGP|More than 30,000 EGP", "20,000 - 30,000 EGP"},
            {"Are you comfortable commuting to this job's location?", "choice", "Yes|No", "Yes"},
            {"Are you willing to relocate?", "choice", "Yes|No", "No"},
            {"Are you willing to relocate to Cairo?", "choice", "Yes|No", "Yes"},
            {"Are you legally authorized to work in Egypt?", "choice", "Yes|No", "Yes"},
            {"Are you legally authorized to work in the United States?", "choice", "Yes|No", "No"},
            {"Will you now or in the future require sponsorship for employment visa status?", "choice", "Yes|No", "No"},
            {"Will you require visa sponsorship to work in the UAE?", "choice", "Yes|No", "Yes"},
            {"What is your military service status?", "choice", "Completed|Exempted|Postponed", "Completed"},
            {"Military status", "text", "", "Completed"},
            {"What is your level of proficiency in English?", "choice", "None|Conversational|Professional|Native or bilingual", "Professional"},
            {"Are you fluent in English?", "choice", "Yes|No", "Yes"},
            {"Do you have a valid driver's license?", "choice", "Yes|No", "Yes"},
            {"Mobile phone number", "text", "", "01012345678"},
            {"Phone country code", "choice", "United States (+1)|Egypt (+20)|Saudi Arabia (+966)", "Egypt (+20)"},
            {"Location (city)", "text", "", "Cairo, Egypt"},
            {"How did you hear about this job?", "choice", "Company website|LinkedIn|A friend", "LinkedIn"},
            {"What year did you graduate?", "numeric", "", "2019"},
            {"Why do you want this job?", "textarea", "", null},
            {"How many people have you managed?", "numeric", "", null},
            {"Have you read the privacy notice?", "choice", "Yes|No", null},
        };
        int good = 0;
        List<String> bad = new ArrayList<>();
        for (String[] c : cases) {
            List<String> opts = c[2].isEmpty() ? null : java.util.Arrays.asList(c[2].split("\\|"));
            com.autoapply.bot.core.Profile.Hit h = e.profile.answer(c[0], c[1], opts, cv, Util.now());
            String got = h == null ? null : String.valueOf(h.value);
            if (java.util.Objects.equals(got, c[3])) {
                good++;
            } else {
                bad.add(c[0] + " -> " + got + " (expected " + c[3] + ")");
            }
        }
        check(good + "/" + cases.length + " questions answered by the rules as expected", bad.isEmpty(), bad);
        check("today's rates kept for a day", Json.str(Json.map(Json.tryParse(Util.readText(new File(d, "rates.json")))), "base")
                .equals("EGP"));
        com.autoapply.bot.core.Profile.ratesUrl = "http://127.0.0.1:9/";
        Double offline = e.profile.rate("USD", "SAR");
        com.autoapply.bot.core.Profile.ratesUrl = "http://127.0.0.1:" + mockPort + "/rates/";
        check("offline: built-in estimate (1 USD = 3.75 SAR)", offline != null && Math.abs(offline - 3.75) < 0.01, offline);

        Map<String, Object> s = api(a, "/api/profile/suggest", Json.obj());
        Map<String, Object> sug = Json.map(s.get("suggest"));
        check("CV read for the setup screen (AI)", "2019".equals(Json.str(sug, "graduation_year"))
                && "Professional".equals(Json.str(sug, "english")) && Json.strings(sug.get("titles")).size() == 3
                && "Cairo, Egypt".equals(Json.str(sug, "city")), sug);
        check("suggestions kept for the screen", Json.map(api(a, "/api/profile", null).get("suggest")).containsKey("titles"));
        Map<String, Object> simple = com.autoapply.bot.core.Profile.fromCvText(
                "Ahmed\n+20 101 234 5678\nB.Sc. Communication Engineering, Cairo University, 2015 - 2019\nCCNA 2021");
        check("without the AI: phone and graduation year from the text", "2019".equals(Json.str(simple, "graduation_year"))
                && Json.str(simple, "phone").startsWith("+20"), simple);
        r = api(a, "/api/setup", Json.obj("done", true));
        check("setup marked done", Json.bool(Json.map(r.get("setup")), "setup_done"));

        System.out.println("\n== A real form filled from the setup answers");
        e.run(opts(false));
        Map<String, String> qa1 = qa(jobs(d).get("4481850707"));
        String lg = log(d);
        check("both jobs applied", statuses(d).toString().equals("{4481850707=applied, 4481896241=applied}"), statuses(d));
        check("salary, notice, current salary, phone and years from the setup",
                "30000".equals(qa1.get("Mention your salary expectations.")) && "30".equals(qa1.get("How long is your notice period?"))
                        && "20000".equals(qa1.get("What is your current monthly Salary?"))
                        && "01012345678".equals(qa1.get("Mobile phone number"))
                        && Y.equals(qa1.get("How many years of experience do you have in the network?")), qa1);
        check("Fortinet is not in the CV: not from the setup (the AI answered)",
                !lg.contains("From your setup: How many years of work experience do you have with Fortinet"), qa1);
        check("setup answers logged, the AI got them as notes", count(lg, "From your setup:") >= 8
                && Json.str(api(a, "/api/profile", null), "notes").contains("Military service: Completed"));
        check("no errors", !lg.contains("Error:"), errorLines(lg));
    }

    // ------------------------------------------------------------------ your info
    static Map<String, Object> factBy(Map<String, Object> info, String labelPart) {
        for (Object o : Json.arr(info.get("facts"))) {
            Map<String, Object> f = Json.map(o);
            if (Json.str(f, "label").toLowerCase().contains(labelPart.toLowerCase())) {
                return f;
            }
        }
        return Json.obj();
    }

    static void facts() throws Exception {
        System.out.println("\n== Your info: categories, other wordings, the AI matcher");
        File d = fresh("facts");
        write(d, "config.json", Json.pretty(config()));
        write(d, "answers.json", Json.stringify(Json.obj(
                "what is your linkedin profile url?", "https://www.linkedin.com/in/ahmed-sabet",
                "do you have a car?", "Yes",
                "how many years of work experience do you have with fortinet?", "2",
                "are you willing to work night shifts?", "No",
                "what is your nationality?", "Egyptian")));
        com.autoapply.bot.core.Profile.ratesUrl = "http://127.0.0.1:" + mockPort + "/rates/";
        TestPlatform p = new TestPlatform("real");
        Engine e = new Engine(p, d);
        Api a = new Api(e);
        Map<String, Object> info = api(a, "/api/facts", null);
        check("12 categories", Json.strings(info.get("categories")).size() == 12, info.get("categories"));
        check("saved answers became your info, sorted into categories",
                "Links".equals(Json.str(factBy(info, "linkedin"), "category"))
                        && "Experience".equals(Json.str(factBy(info, "fortinet"), "category"))
                        && "Work preferences".equals(Json.str(factBy(info, "night shifts"), "category"))
                        && "Personal".equals(Json.str(factBy(info, "nationality"), "category"))
                        && "Other".equals(Json.str(factBy(info, "car"), "category"))
                        && "saved".equals(Json.str(factBy(info, "car"), "source")), info.get("facts"));
        check("facts.json written; answers.json kept as before", new File(d, "facts.json").isFile() && answers(d).size() == 5);

        Map<String, Object> r = api(a, "/api/facts", Json.obj("op", "add", "category", "Experience", "label", "Python", "value", "4"));
        check("add a skill with years", Json.bool(r, "ok") && "you".equals(Json.str(Json.map(r.get("fact")), "source")));
        r = api(a, "/api/facts", Json.obj("op", "add", "label", "What is your email address?", "value", "ahmed@example.com"));
        check("a new fact is sorted by itself (email -> Contact)", "Contact".equals(Json.str(Json.map(r.get("fact")), "category")));
        api(a, "/api/facts", Json.obj("op", "add", "category", "Experience", "label", "Total experience", "value", "6 years"));
        String carId = Json.str(factBy(info, "car"), "id");
        r = api(a, "/api/facts", Json.obj("op", "update", "id", carId, "value", "No"));
        check("edit an answer (it becomes yours)", "No".equals(Json.str(factBy(r, "car"), "value"))
                && "you".equals(Json.str(factBy(r, "car"), "source")) && "No".equals(answers(d).get("do you have a car?")));
        r = api(a, "/api/facts", Json.obj("op", "update", "id", Json.str(factBy(info, "night"), "id"), "category", "Personal"));
        check("move a fact to another category", "Personal".equals(Json.str(factBy(r, "night"), "category")));
        r = api(a, "/api/facts", Json.obj("op", "delete", "id", Json.str(factBy(info, "nationality"), "id")));
        check("delete a fact", factBy(r, "nationality").isEmpty() && !answers(d).containsKey("what is your nationality?"));
        r = api(a, "/api/facts", Json.obj("op", "add", "label", "", "value", "x"));
        check("an empty fact is refused with a message", !Json.bool(r, "ok") && Json.str(r, "error").contains("Type the question"));
        api(a, "/api/profile", Json.obj("profile", testProfile()));

        Map<String, String> cfg = e.config.load();
        com.autoapply.bot.core.Questions.askMode = "ai";
        com.autoapply.bot.core.Ai ai = new com.autoapply.bot.core.Ai("sk-test", "x", e.config.cvText(), e.notesForAi());
        com.autoapply.bot.core.LinkedIn li = new com.autoapply.bot.core.LinkedIn(null,
                new com.autoapply.bot.core.Scripts(p.pageScripts()), e.config.debugDir);
        com.autoapply.bot.core.Bot bot = new com.autoapply.bot.core.Bot(e, cfg, opts(false), ai, li, List.of("x"), "Egypt",
                new com.autoapply.bot.core.Session("apply"));
        List<String> yn = List.of("Yes", "No");
        String url = "https://www.linkedin.com/in/ahmed-sabet";
        check("the same question: from your info", "No".equals(bot.resolve("Do you have a car?", "choice", yn, "", "", false, null, 0, null)));
        check("nearly the same words: matched here, no AI",
                url.equals(bot.resolve("What's your LinkedIn profile URL", "text", null, "", "", false, null, 0, null)));
        check("other words: the AI finds the fact",
                url.equals(bot.resolve("Please share a link to your LinkedIn page", "text", null, "", "", false, null, 0, null)));
        Map<String, Object> li1 = factBy(api(a, "/api/facts", null), "linkedin");
        check("both wordings remembered on the fact (next time without the AI)", Json.strings(li1.get("asked"))
                .containsAll(List.of("what's your linkedin profile url", "please share a link to your linkedin page")), li1.get("asked"));
        check("an answer that follows from a fact: Python 4 years -> knows Python: Yes",
                "Yes".equals(bot.resolve("Do you know Python?", "choice", yn, "", "", false, null, 0, null)));
        check("...but 'Yes' is not saved as Python's answer", !Json.strings(factBy(api(a, "/api/facts", null), "python").get("asked"))
                .contains("do you know python?"));
        Object weekends = bot.resolve("Are you willing to work weekends?", "choice", yn, "", "", false, null, 0, null);
        check("a different question is not answered from a similar fact (weekends are not night shifts)",
                !log(d).matches("(?s).*From your info[^\\n]*work weekends.*"), weekends);
        check("years you entered beat years since graduating (Python: 4, not " + com.autoapply.bot.core.Profile.yearsSince("2019",
                Util.now()) + ")", "4".equals(bot.resolve("How many years of experience do you have with Python?", "numeric", null, "",
                "", false, null, 0, null)));
        check("total experience you entered (6)", "6".equals(bot.resolve("How many years of experience do you have?", "numeric", null,
                "", "", false, null, 0, null)));
        check("a skill only in your info, asked in other words (Fortinet: 2)",
                "2".equals(bot.resolve("Fortinet experience in years?", "numeric", null, "", "", false, null, 0, null)));
        Map<String, Object> mc = calls();
        check("the AI was asked only when the wording was new", Json.strings(mc.get("matched")).stream()
                .noneMatch(m -> m.startsWith("What's your LinkedIn profile URL") || m.startsWith("Do you have a car?")), mc.get("matched"));
        Object again = bot.resolve("Do you have a car?", "choice", yn, "Please make a selection", "", true, List.of("No"), 0, null);
        check("an answer LinkedIn rejected is not given again", !"No".equals(again), again);
        com.autoapply.bot.core.Questions.askMode = "ask";
    }

    // ------------------------------------------------------------------ ask-me mode through the dashboard API
    static Map<String, Object> api(Api api, String path, Object body) {
        return Json.map(Json.parse(api.handle(path, body == null ? null : Json.stringify(body))));
    }

    static Map<String, Object> waitQuestion(Api a, String part, boolean rejected, double timeout) {
        long end = System.currentTimeMillis() + (long) (timeout * 1000);
        while (System.currentTimeMillis() < end) {
            Map<String, Object> q = Json.map(api(a, "/api/state", null).get("question"));
            if (!q.isEmpty() && Json.str(q, "question").contains(part) && !Json.bool(q, "loading")
                    && (!rejected || !Json.str(q, "hint").isEmpty())) {
                return q;
            }
            Util.realSleep(0.1);
        }
        return null;
    }

    static boolean answer(Api a, Map<String, Object> q, Object value, boolean remember) {
        Map<String, Object> r = api(a, "/api/answer", Json.obj("id", Json.str(q, "id"), "answer", value, "remember", remember));
        return !Json.map(r).containsKey("ok") || Json.bool(r, "ok");
    }

    static void waitIdle(Engine e, double timeout) throws Exception {
        long end = System.currentTimeMillis() + (long) (timeout * 1000);
        Util.realSleep(0.3);
        while (e.running() && System.currentTimeMillis() < end) {
            Util.realSleep(0.2);
        }
    }

    static void ask() throws Exception {
        System.out.println("\n== Ask-me mode through the dashboard API");
        File d = fresh("ask");
        write(d, "config.json", Json.pretty(config("NEW_QUESTIONS", "ask")));
        write(d, "about_me.txt", "Phone: 01012345678\nExpected monthly salary: 30000 EGP\n");
        CdpPage.HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + mockPort + "/__slow_phone"))
                .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        TestPlatform p = new TestPlatform("real");
        Engine e = new Engine(p, d);
        Api a = new Api(e);
        Map<String, Object> st0 = api(a, "/api/state", null);
        check("'Ask me' is the default", "ask".equals(Json.str(st0, "ask_mode")));
        check("start", Json.bool(api(a, "/api/start", Json.obj("mode", "apply", "once", true)), "ok"));

        // phone: the card shows before the suggestions are ready
        long end = System.currentTimeMillis() + 60000;
        boolean sawLoading = false;
        while (System.currentTimeMillis() < end) {
            Map<String, Object> q = Json.map(api(a, "/api/state", null).get("question"));
            if (Json.str(q, "question").contains("Mobile phone")) {
                sawLoading = Json.bool(q, "loading");
                break;
            }
            Util.realSleep(0.05);
        }
        check("question posted before the AI suggestions are ready", sawLoading);
        Map<String, Object> q = waitQuestion(a, "Mobile phone number", false, 30);
        check("phone suggestion from the notes", q != null && Json.arr(q.get("suggestions")).contains("01012345678")
                && Json.str(q, "reason").contains("notes"), q == null ? null : q.get("suggestions"));
        check("status says it waits for your answer", "question".equals(Json.str(Json.map(api(a, "/api/state", null).get("status")), "state")));
        check("job shown with the question", q != null && "Al-Mansour Automotive".equals(Json.str(Json.map(q.get("job")), "company")));
        answer(a, q, Json.arr(q.get("suggestions")).get(0), true);

        q = waitQuestion(a, "Bachelor", false, 30);
        check("choice question with options", q != null && Json.strings(q.get("options")).equals(java.util.Arrays.asList("Yes", "No")),
                q == null ? null : q.get("options"));
        Map<String, Object> bad = api(a, "/api/answer", Json.obj("id", Json.str(q, "id"), "answer", "Maybe"));
        check("an answer that is not an option is refused", !Json.bool(bad, "ok") && Json.str(bad, "error").contains("Pick one"), bad);
        answer(a, q, "yes", true);
        q = waitQuestion(a, "Fortinet", false, 30);
        answer(a, q, "2", true);
        q = waitQuestion(a, "in the network", false, 30);
        answer(a, q, "0", true);  // the form needs at least 1
        q = waitQuestion(a, "salary expectations", false, 30);
        answer(a, q, "30000", true);
        q = waitQuestion(a, "notice period", false, 30);
        answer(a, q, "30 days", true);
        q = waitQuestion(a, "current monthly", false, 30);
        check("'save for next time' on by default", q != null && Json.bool(q, "remember"));
        answer(a, q, "20,000 EGP", false);
        q = waitQuestion(a, "in the network", true, 30);
        check("LinkedIn's rejection comes back with the rejected answer", q != null && Json.str(q, "hint").contains("Invalid input")
                && Json.strings(q.get("rejected")).contains("0"), q == null ? null : q.get("hint"));
        check("suggestions include a number that fits", q != null && Json.arr(q.get("suggestions")).contains("1"),
                q == null ? null : q.get("suggestions"));
        bad = api(a, "/api/answer", Json.obj("id", Json.str(q, "id"), "answer", "0"));
        check("the same rejected answer is refused", Json.str(bad, "error").contains("already rejected"), bad);
        answer(a, q, "1", true);
        q = waitQuestion(a, "current monthly", false, 90);
        check("job 2 asks only the answer you did not save", q != null && "Konecta".equals(Json.str(Json.map(q.get("job")), "company")));
        api(a, "/api/skip-question", Json.obj("id", Json.str(q, "id")));
        waitIdle(e, 120);

        Map<String, Map<String, Object>> js = jobs(d);
        Map<String, String> qa1 = qa(js.get("4481850707"));
        Map<String, String> want = new java.util.TreeMap<>();
        want.put("Mobile phone number", "01012345678");
        want.put("Have you completed the following level of education: Bachelor's Degree?", "Yes");
        want.put("How many years of work experience do you have with Fortinet?", "2");
        want.put("How many years of experience do you have in the network?", "1");
        want.put("Mention your salary expectations.", "30000");
        want.put("How long is your notice period?", "30");
        want.put("What is your current monthly Salary?", "20000");
        check("job 1 applied with your answers", "applied".equals(Json.str(js.get("4481850707"), "status"))
                && want.equals(new java.util.TreeMap<>(qa1)), qa1);
        check("job 2 skipped by you, form discarded", "user_skipped".equals(Json.str(js.get("4481896241"), "status"))
                && p.console().contains("REAL_DISCARDED=Konecta"));
        Map<String, Object> ans = answers(d);
        check("answers saved, the unticked one not", "1".equals(ans.get("how many years of experience do you have in the network?"))
                && !ans.containsKey("what is your current monthly salary?"), ans);
        String lg = log(d);
        check("9 questions asked (job 2 used the saved answers)", count(lg, "Question for you:") == 9, count(lg, "Question for you:"));
        Map<String, Object> mine = factBy(api(a, "/api/facts", null), "mobile phone number");
        check("answers you gave on the card are your info now (Contact, yours)", "Contact".equals(Json.str(mine, "category"))
                && "you".equals(Json.str(mine, "source")) && "01012345678".equals(Json.str(mine, "value")), mine);
        check("the AI did not answer by itself", !lg.contains("AI answer:"));
        check("log shows your answers", lg.contains("Your answer: What is your current monthly Salary? -> 20000 (not saved)"));
        check("notifications: question events", p.events.stream().filter(x -> x.startsWith("question:")).count() >= 9
                && p.events.contains("question-done"));
        check("brief counts the job you skipped", Json.map(p.lastSummary.get("skipped")).containsKey("skipped by you"),
                p.lastSummary.get("skipped"));

        System.out.println("\n== Ask-me: no answer in time, answered later, retried; switching to AI answers");
        api(a, "/api/settings", Json.obj("ASK_TIMEOUT_MIN", "1"));
        e.store.deleteJob("4481896241");
        check("start again", Json.bool(api(a, "/api/start", Json.obj("mode", "apply", "once", true)), "ok"));
        q = waitQuestion(a, "current monthly", false, 90);
        check("the question has a deadline", q != null && Json.dbl(q, "timeout_at", 0) > 0);
        waitIdle(e, 120);
        js = jobs(d);
        List<Object> waiting = Json.arr(api(a, "/api/open-questions", null).get("items"));
        check("job 2 waits for your answer", "needs_answer".equals(Json.str(js.get("4481896241"), "status")));
        check("question kept with its job and suggestions", waiting.size() == 1
                && "4481896241".equals(Json.str(Json.map(Json.arr(Json.map(waiting.get(0)).get("jobs")).get(0)), "id"))
                && Json.list("20000").equals(Json.map(waiting.get(0)).get("suggestions")), waiting);
        check("dashboard count: 1 waiting", Json.num(api(a, "/api/state", null), "open_questions", -1) == 1);
        Map<String, Object> r = api(a, "/api/open-questions", Json.obj("key", "what is your current monthly salary?", "answer", "20000"));
        check("answered later: 1 job will be tried again", Json.num(r, "retried", 0) == 1, r);
        check("job marked for a retry", "answered".equals(Json.str(jobs(d).get("4481896241"), "status")));
        check("waiting list empty", Json.arr(api(a, "/api/open-questions", null).get("items")).isEmpty());
        r = api(a, "/api/answers", Json.obj("delete", Json.list("How long is your notice period?")));
        check("saved answer deleted", !answers(d).containsKey("how long is your notice period?") && Json.num(r, "count", 0) == 6, r);
        check("start again", Json.bool(api(a, "/api/start", Json.obj("mode", "apply", "once", true)), "ok"));
        q = waitQuestion(a, "notice period", false, 90);
        check("the retried job asks the deleted answer", q != null);
        api(a, "/api/ask-mode", Json.obj("mode", "ai"));
        waitIdle(e, 120);
        lg = log(d);
        check("retried job applied", "applied".equals(Json.str(jobs(d).get("4481896241"), "status")), Json.str(jobs(d).get("4481896241"), "reason"));
        check("log: retry and the AI took over the open question", lg.contains("Trying it again")
                && lg.contains("New questions are answered by the AI now.") && lg.contains("AI answer: How long is your notice period? -> 30"));
        check("switch saved", "ai".equals(e.config.load().get("NEW_QUESTIONS")));
        api(a, "/api/ask-mode", Json.obj("mode", "ask"));
        check("switch back", "ask".equals(e.config.load().get("NEW_QUESTIONS")));
        Map<String, Object> share = api(a, "/api/share", Json.obj("what", "csv"));
        String csv = Util.readText(e.store.csvFile);
        check("CSV of applied jobs shared", Json.bool(share, "ok") && p.events.contains("share:applied_jobs.csv")
                && csv != null && count(csv, "linkedin.com/jobs/view/") == 2, share);
    }

    // ------------------------------------------------------------------ app updates
    static void update() throws Exception {
        System.out.println("\n== App updates");
        com.autoapply.bot.core.Updater.githubApi = "http://127.0.0.1:" + mockPort + "/gh";
        check("version compare", com.autoapply.bot.core.Updater.compare("1.10", "1.9") > 0
                && com.autoapply.bot.core.Updater.compare("v1.0", "1.0") == 0
                && com.autoapply.bot.core.Updater.compare("1.0.1", "1.0") > 0 && com.autoapply.bot.core.Updater.compare("2", "10") < 0);
        check("repo from a link", "AhmedSabet0/JSTB".equals(com.autoapply.bot.core.Updater.repoOf("https://github.com/AhmedSabet0/JSTB"))
                && "AhmedSabet0/JSTB".equals(com.autoapply.bot.core.Updater.repoOf("https://github.com/AhmedSabet0/JSTB/releases/tag/v1.1"))
                && "a/b".equals(com.autoapply.bot.core.Updater.repoOf("a/b.git")) && com.autoapply.bot.core.Updater.repoOf("hello") == null);
        Map<String, Object> r = com.autoapply.bot.core.Updater.check("AhmedSabet0/JSTB-new", "1.0");
        check("newer release found with its APK and notes", Json.bool(r, "available") && "1.2".equals(Json.str(r, "version"))
                && Json.str(r, "url").endsWith("/AutoApply-1.2.apk") && Json.str(r, "notes").contains("Faster"), r);
        r = com.autoapply.bot.core.Updater.check("https://github.com/AhmedSabet0/JSTB-same", "1.0");
        check("same version: up to date", Json.bool(r, "ok") && !Json.bool(r, "available")
                && Json.str(r, "message").contains("latest"), r.get("message"));
        r = com.autoapply.bot.core.Updater.check("AhmedSabet0/JSTB", "1.0");
        check("nothing published yet (or private repo)", Json.bool(r, "ok") && !Json.bool(r, "available")
                && Json.str(r, "message").contains("No version"), r.get("message"));
        r = com.autoapply.bot.core.Updater.check("AhmedSabet0/JSTB-noapk", "1.0");
        check("release without an APK", !Json.bool(r, "ok") && Json.str(r, "message").contains(".apk"), r.get("message"));
        r = com.autoapply.bot.core.Updater.check("http://127.0.0.1:" + mockPort + "/manifest/latest.json", "1.0");
        check("latest.json works too", Json.bool(r, "available") && "1.3".equals(Json.str(r, "version")), r);
        r = com.autoapply.bot.core.Updater.check("not a repo", "1.0");
        check("bad source explained", !Json.bool(r, "ok") && Json.str(r, "message").contains("owner/name"), r.get("message"));

        File d = fresh("update");
        write(d, "config.json", Json.pretty(config("UPDATE_SOURCE", "AhmedSabet0/JSTB-new")));
        TestPlatform p = new TestPlatform("real");
        Engine e = new Engine(p, d);
        Api a = new Api(e);
        Map<String, Object> inst = api(a, "/api/update/install", Json.obj());
        check("install refused before a check", !Json.bool(inst, "ok"), inst.get("error"));
        api(a, "/api/update/check", Json.obj("auto", true));
        long end = System.currentTimeMillis() + 15000;
        Map<String, Object> u = Json.obj();
        while (System.currentTimeMillis() < end) {
            u = Json.map(api(a, "/api/state", null).get("update"));
            if (!Json.bool(u, "checking") && u.get("last") != null) {
                break;
            }
            Util.realSleep(0.1);
        }
        Map<String, Object> last = Json.map(u.get("last"));
        check("dashboard state carries the update", Json.bool(last, "available") && "1.2".equals(Json.str(last, "version"))
                && "1.0".equals(Json.str(Json.map(u.get("current")), "name")), u);
        check("check saved for later", new File(d, "update_check.json").isFile());
        boolean autoStarted = waitFor(() -> p.events.stream().anyMatch(x -> x.startsWith("install-auto:")
                && x.endsWith("/AutoApply-1.2.apk:1.2")), 5);
        check("found by itself: the update downloads by itself (it installs when the screen turns off)", autoStarted, p.events);
        api(a, "/api/update/check", Json.obj("auto", true));
        Util.realSleep(0.5);
        check("each version is tried by itself once", p.events.stream().filter(x -> x.startsWith("install-auto:")).count() == 1, p.events);
        File d2 = fresh("update_manual");
        write(d2, "config.json", Json.pretty(config("UPDATE_SOURCE", "AhmedSabet0/JSTB-new", "AUTO_UPDATE", "0")));
        TestPlatform p2 = new TestPlatform("real");
        Api a2 = new Api(new Engine(p2, d2));
        api(a2, "/api/update/check", Json.obj("auto", true));
        waitFor(() -> Json.map(Json.map(api(a2, "/api/state", null).get("update")).get("last")).containsKey("available"), 15);
        Util.realSleep(0.5);
        check("updates by themselves can be turned off", p2.events.stream().noneMatch(x -> x.startsWith("install-auto:"))
                && Json.bool(Json.map(Json.map(api(a2, "/api/state", null).get("update")).get("last")), "available"), p2.events);
        inst = api(a, "/api/update/install", Json.obj());
        check("install starts the download", Json.bool(inst, "ok") && p.events.stream().anyMatch(x -> x.startsWith("install:")
                && x.endsWith("/AutoApply-1.2.apk:1.2")), p.events);
        Api again = new Api(e);
        check("a restarted app remembers the last check", Json.bool(Json.map(again.updateInfo().get("last")), "available"));
        p.selfUpdate = false;  // the Google Play build
        Map<String, Object> pu = Json.map(api(a, "/api/update/check", Json.obj()).get("update"));
        Map<String, Object> pi = api(a, "/api/update/install", Json.obj());
        check("Google Play build: no update checks, no installs", Json.bool(pu, "disabled") && !pu.containsKey("last")
                && !Json.bool(pi, "ok") && Json.str(pi, "error").contains("Google Play"), pu + " " + pi);
    }

    // ------------------------------------------------------------------ your account, the server and the admin
    static Map<String, Object> serverUser() throws Exception {
        return TestServer.user(EMAIL);
    }

    static boolean waitFor(java.util.function.BooleanSupplier cond, double seconds) {
        long end = System.currentTimeMillis() + (long) (seconds * 1000);
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Util.realSleep(0.1);
        }
        return cond.getAsBoolean();
    }

    static String userState() {
        try {
            return Json.str(serverUser(), "state");
        } catch (Exception ex) {
            return "?";
        }
    }

    static void account() throws Exception {
        System.out.println("\n== Your account, the server and the admin");
        // 1. A run reports its applications; the admin sees them.
        File d = fresh("acct_run");
        write(d, "config.json", Json.pretty(config()));
        write(d, "about_me.txt", "Phone: 01012345678\n");
        TestPlatform p = new TestPlatform("classic");
        Engine e = new Engine(p, d);
        e.run(opts(false));
        check("the AI went through the server", log(d).contains("AI connected") && jobs(d).get("111") != null
                && "applied".equals(Json.str(jobs(d).get("111"), "status")), errorLines(log(d)));
        waitFor(() -> "idle".equals(userState()), 5);
        Map<String, String> seen = new java.util.TreeMap<>();
        for (Object o : TestServer.applications(EMAIL)) {
            seen.put(Json.str(Json.map(o), "job_id"), Json.str(Json.map(o), "status"));
        }
        check("the admin sees the applications (and the stuck one), not the skipped",
                "applied".equals(seen.get("111")) && "applied".equals(seen.get("222")) && "stuck".equals(seen.get("444"))
                        && !seen.containsKey("666"), seen);
        Map<String, Object> u = serverUser();
        check("after the run: idle, with today's count", "idle".equals(Json.str(u, "state")) && Json.num(u, "applied_today", 0) >= 2, u);

        // 2. Pro off: auto apply does not start.
        TestServer.setPro(EMAIL, false);
        File d2 = fresh("acct_free");
        write(d2, "config.json", Json.pretty(config()));
        Engine e2 = new Engine(new TestPlatform("classic"), d2);
        e2.run(opts(false));
        check("Pro off: it does not start, and says why", Engine.NOT_ACTIVE.equals(Json.str(Util.getStatus(), "message"))
                && jobs(d2).isEmpty() && !e2.account.pro(), Util.getStatus().get("message"));
        TestServer.setPro(EMAIL, true);

        // 3. Not signed in.
        File d3 = fresh("acct_none", false);
        write(d3, "config.json", Json.pretty(config()));
        Engine e3 = new Engine(new TestPlatform("classic"), d3);
        check("not signed in: Start says to sign in", e3.start(opts(false)).contains("Sign in"));

        // 4. The admin presses Stop while it runs.
        File d4 = fresh("acct_stop");
        write(d4, "config.json", Json.pretty(config()));
        Engine e4 = new Engine(new TestPlatform("classic"), d4);
        com.autoapply.bot.core.Bot.Options keepGoing = opts(false);
        keepGoing.once = false;
        check("started", e4.start(keepGoing).isEmpty());
        boolean running = waitFor(() -> "running".equals(userState()), 20);
        check("the admin sees it running", running, userState());
        Map<String, Object> st = TestServer.stop(EMAIL);
        check("Stop pressed", Json.bool(st, "online"), st);
        e4.join(30000);
        check("the run stopped by itself, saying the admin stopped it", !e4.running()
                && "Stopped by the admin.".equals(Json.str(Util.getStatus(), "message")) && log(d4).contains("Stopped by the admin."),
                Util.getStatus().get("message"));
        check("the admin page sees it idle again", waitFor(() -> "idle".equals(userState()), 5), userState());

        // 5. The admin turns Pro off while it runs.
        File d5 = fresh("acct_prooff");
        write(d5, "config.json", Json.pretty(config()));
        Engine e5 = new Engine(new TestPlatform("classic"), d5);
        e5.start(keepGoing);
        waitFor(() -> "running".equals(userState()), 20);
        TestServer.setPro(EMAIL, false);
        e5.join(30000);
        // Whichever comes first stops it: the next report ("turned off") or the AI refusing the next request ("isn't active").
        check("Pro turned off: the run stops", !e5.running()
                && Json.str(Util.getStatus(), "message").matches("(?s).*(turned off for your account|needs Pro on your account).*"),
                Util.getStatus().get("message"));
        TestServer.setPro(EMAIL, true);

        // 6. The admin's daily cap.
        TestServer.settings("max_per_day", 1);
        Api a = new Api(e);
        api(a, "/api/account/refresh", Json.obj());
        Map<String, Object> stats = Json.map(api(a, "/api/state", null).get("stats"));
        check("the admin's daily cap reaches the app", Json.num(stats, "per_day", 0) == 1 && Json.num(stats, "admin_cap", 0) == 1, stats);
        TestServer.settings("max_per_day", 0);
        api(a, "/api/account/refresh", Json.obj());

        // 7. Sign up, sign out, sign in, delete, from the app's screens.
        File d7 = fresh("acct_api", false);
        Engine e7 = new Engine(new TestPlatform("classic"), d7);
        Api a7 = new Api(e7);
        Map<String, Object> s7 = Json.map(api(a7, "/api/state", null).get("account"));
        check("no account yet", !Json.bool(s7, "signed_in") && Json.bool(s7, "server_built_in"), s7);
        Map<String, Object> r = api(a7, "/api/account/signup", Json.obj("email", "new@example.com", "password", "short", "name", "New"));
        check("sign up: password too short is explained", !Json.bool(r, "ok") && Json.str(r, "error").contains("8 characters"), r);
        r = api(a7, "/api/account/signup", Json.obj("email", "New@Example.com", "password", "new-pass-123", "name", "Nour"));
        Map<String, Object> acc7 = Json.map(r.get("account"));
        check("sign up from the app", Json.bool(r, "ok") && Json.bool(acc7, "signed_in") && !Json.bool(acc7, "pro")
                && "new@example.com".equals(Json.str(acc7, "email")) && "Nour".equals(Json.str(acc7, "name")), r);
        check("the admin sees the new user", TestServer.user("new@example.com") != null);
        String acctFile = Util.readText(new File(d7, "account.json"));
        check("only a sign-in token is kept on the phone (no password, no AI key)", acctFile.contains("\"token\"")
                && !acctFile.contains("new-pass-123") && !acctFile.contains("sk-test"), acctFile);
        r = api(a7, "/api/test-ai", Json.obj());
        check("Check again: account and AI work (not Pro yet)", Json.bool(r, "ok") && Json.str(r, "message").contains("AI works"), r);
        r = api(a7, "/api/account/signup", Json.obj("email", "new@example.com", "password", "new-pass-123"));
        check("same email again: told to sign in", Json.str(r, "error").contains("already exists"), r);
        api(a7, "/api/account/logout", Json.obj());
        check("sign out", !Json.bool(Json.map(api(a7, "/api/state", null).get("account")), "signed_in"));
        r = api(a7, "/api/account/login", Json.obj("email", "new@example.com", "password", "wrong-pass"));
        check("sign in: wrong password", !Json.bool(r, "ok") && Json.str(r, "error").contains("Wrong email or password"), r);
        r = api(a7, "/api/account/login", Json.obj("email", "new@example.com", "password", "new-pass-123"));
        check("sign in", Json.bool(r, "ok") && Json.bool(Json.map(r.get("account")), "signed_in"), r);
        r = api(a7, "/api/account/delete", Json.obj("password", "nope-nope"));
        check("delete needs the right password", !Json.bool(r, "ok"), r);
        r = api(a7, "/api/account/delete", Json.obj("password", "new-pass-123"));
        check("delete the account from the app", Json.bool(r, "ok") && !Json.bool(Json.map(r.get("account")), "signed_in")
                && TestServer.user("new@example.com") == null, r);
        com.autoapply.bot.core.Account.defaultServer = "";
        Engine e8 = new Engine(new TestPlatform("classic"), fresh("acct_srv", false));
        Api a8 = new Api(e8);
        r = api(a8, "/api/account/server", Json.obj("server", "http://example.com"));
        check("a typed server must be https", !Json.bool(r, "ok") && Json.str(r, "error").contains("https"), r);
        r = api(a8, "/api/account/info", Json.obj("server", TestServer.base));
        check("server typed in the app: contact and sign-up state", Json.bool(r, "ok")
                && "help@example.com".equals(Json.str(Json.map(r.get("account")), "contact")), r);
        com.autoapply.bot.core.Account.defaultServer = TestServer.base;
    }


    // ------------------------------------------------------------------ your data in your account
    static Map<String, Object> userData(String email) throws Exception {
        return Json.map(TestServer.admin("GET", "/admin/api/users/" + TestServer.uid(email), null).get("data"));
    }

    static File newPhone(String name) throws Exception {
        File d = fresh(name, false);
        new File(d, "cv.pdf").delete();
        new File(d, "cv_text.txt").delete();
        return d;
    }

    static void sync() throws Exception {
        System.out.println("\n== Your data in your account: two phones, changes both ways, a new phone, another account");
        String email = "sync@example.com";
        String pw = "sync-pass-123";
        TestServer.signup(email, pw);
        // Phone A has data from before this version: CV, setup answers, your info, a job of yours, auto apply's log.
        File a = fresh("sync_a", false);
        write(a, "config.json", Json.pretty(config("KEYWORDS", "Network Engineer;DevOps Engineer", "DAILY_GOAL", "12",
                "CV_NAME", "Sara CV.pdf", "SETUP_DONE", "1", "AUTO_UPDATE", "0")));
        Engine ea = new Engine(new TestPlatform("classic"), a);
        Api aa = new Api(ea);
        api(aa, "/api/account/login", Json.obj("email", email, "password", pw));
        api(aa, "/api/profile", Json.obj("profile", Json.obj("phone", "01012345678", "graduation_year", "2019",
                "expected_salary", "30000", "notice_days", "30", "english", "Fluent")));
        api(aa, "/api/facts", Json.obj("op", "add", "category", "Skills", "label", "Years of experience with Ansible", "value", "4"));
        Map<String, Object> added = api(aa, "/api/copilot/add", Json.obj("text", "Network Engineer at Orange https://www.linkedin.com/jobs/view/4012345678/"));
        ea.store.add("4099999999", "applied", "DevOps Engineer", "Vodafone", 8, "", null);
        Map<String, Object> r = api(aa, "/api/sync/now", Json.obj());
        Map<String, Object> d = userData(email);
        Map<String, Object> n = Json.map(d.get("counts"));
        check("phone A fills the account (CV, answers, info, jobs)", Json.bool(r, "ok") && Json.map(d.get("cv")) != null
                && "Sara CV.pdf".equals(Json.str(Json.map(d.get("cv")), "name")) && Json.num(n, "facts", 0) == 1
                && Json.num(n, "copilot", 0) == 1 && Json.num(n, "jobs", 0) == 1 && Json.num(n, "profile", 0) >= 14
                && Json.num(n, "settings", 0) == 18, Json.stringify(r) + " " + Json.stringify(d));
        check("nothing waits after the sync", Json.num(Json.map(r.get("sync")), "waiting", -1) == 0, r.get("sync"));
        String auto = Util.readText(new File(a, "config.json"));
        check("this phone's update settings stay on it", !Json.stringify(d).contains("AUTO_UPDATE"), auto);

        // Phone B: new, signs in, has everything.
        File b = newPhone("sync_b");
        Engine eb = new Engine(new TestPlatform("classic"), b);
        Api ab = new Api(eb);
        api(ab, "/api/account/login", Json.obj("email", email, "password", pw));
        check("a new phone has no CV yet", !Json.bool(Json.map(api(ab, "/api/state", null).get("setup")), "has_cv"));
        r = api(ab, "/api/sync/now", Json.obj());
        Map<String, Object> setup = Json.map(r.get("setup"));
        check("phone B gets the CV (file, text, name)", Json.bool(r, "ok") && Json.bool(setup, "has_cv")
                && Arrays.equals(Files.readAllBytes(new File(a, "cv.pdf").toPath()), Files.readAllBytes(new File(b, "cv.pdf").toPath()))
                && Util.readText(new File(b, "cv_text.txt")).equals(Util.readText(new File(a, "cv_text.txt")))
                && "Sara CV.pdf".equals(Json.str(setup, "cv_name")), Json.stringify(r));
        check("phone B gets the setup as done, the answers and job titles", Json.bool(setup, "setup_done") && Json.bool(setup, "profile_done")
                && "01012345678".equals(Json.str(eb.profile.load(), "phone"))
                && "Network Engineer;DevOps Engineer".equals(eb.config.load().get("KEYWORDS")) && "12".equals(eb.config.load().get("DAILY_GOAL")),
                setup);
        String factId = Json.str(eb.store.factsCopy().isEmpty() ? Json.obj() : eb.store.factsCopy().get(0), "id");
        check("phone B gets your info, your jobs and auto apply's log", eb.store.factCount() == 1
                && "4".equals(String.valueOf(eb.store.answer("years of experience with ansible")))
                && eb.copilot.get("li:4012345678") != null && "applied".equals(eb.store.statusOf("4099999999")));
        check("phone B's update settings are its own", !"0".equals(eb.config.load().get("AUTO_UPDATE")));

        // Changes on B reach A, changes on A reach B.
        api(ab, "/api/facts", Json.obj("op", "update", "id", factId, "value", "5"));
        api(ab, "/api/copilot/update", Json.obj("id", "li:4012345678", "stage", "interview", "note", "HR call Sunday"));
        api(ab, "/api/settings", Json.obj("DAILY_GOAL", "15"));
        api(ab, "/api/sync/now", Json.obj());
        r = api(aa, "/api/sync/now", Json.obj());
        Map<String, Object> it = ea.copilot.get("li:4012345678");
        check("B's changes reach A", "5".equals(String.valueOf(ea.store.answer("years of experience with ansible")))
                && "interview".equals(Json.str(it, "stage")) && "applied".equals(Json.str(it, "state"))
                && "HR call Sunday".equals(Json.str(it, "note")) && "15".equals(ea.config.load().get("DAILY_GOAL"))
                && Json.str(Json.map(r.get("sync")), "note").contains("from your account"), Json.stringify(r));
        api(aa, "/api/facts", Json.obj("op", "delete", "id", factId));
        api(aa, "/api/copilot/remove", Json.obj("id", "li:4012345678"));
        api(aa, "/api/sync/now", Json.obj());
        api(ab, "/api/sync/now", Json.obj());
        check("deleted on A: gone on B", eb.store.factCount() == 0 && eb.copilot.get("li:4012345678") == null);
        // The same thing changed on both: the later change wins on both phones.
        api(aa, "/api/settings", Json.obj("LOCATION", "Cairo"));      // A changes it first, and syncs later
        Thread.sleep(1100);
        api(ab, "/api/settings", Json.obj("LOCATION", "Giza"));
        api(ab, "/api/sync/now", Json.obj());
        api(aa, "/api/sync/now", Json.obj());
        check("the same setting changed on both: the later one wins everywhere", "Giza".equals(ea.config.load().get("LOCATION"))
                && "Giza".equals(eb.config.load().get("LOCATION")), ea.config.load().get("LOCATION"));
        // A new CV on B reaches A.
        byte[] cv2 = (Util.readText(new File(b, "cv_text.txt")) + "\nCertifications: CCNP Enterprise").getBytes(Util.UTF8);
        r = api(ab, "/api/cv", Json.obj("name", "Sara CV 2026.txt", "data", Util.base64(cv2)));
        api(ab, "/api/sync/now", Json.obj());
        r = api(aa, "/api/sync/now", Json.obj());
        check("a new CV on B reaches A", new File(a, "cv.txt").isFile() && !new File(a, "cv.pdf").isFile()
                && Util.readText(new File(a, "cv_text.txt")).contains("CCNP Enterprise")
                && "Sara CV 2026.txt".equals(ea.config.load().get("CV_NAME")), Json.stringify(r));
        // Auto apply's results go up too.
        ea.store.add("4011111111", "skipped_low_score", "Sales Engineer", "Acme", 3, "Low match", null);
        api(aa, "/api/sync/now", Json.obj());
        api(ab, "/api/sync/now", Json.obj());
        check("auto apply's results on A are on B (it will not look at them again)", "skipped_low_score".equals(eb.store.statusOf("4011111111")));

        // Google Drive, when the server has no Google client yet.
        Map<String, Object> s = api(ab, "/api/sync", null);
        check("Google Drive: the app learns it is not set up on the server", Json.bool(s, "ok")
                && !Json.bool(Json.map(s.get("drive")), "available") && !Json.bool(Json.map(s.get("drive")), "connected"), s);
        r = api(ab, "/api/drive/connect", Json.obj());
        check("Connect says why it cannot", !Json.bool(r, "ok") && Json.str(r, "error").contains("not set up"), r);

        // Another account on phone B: its data replaces the first account's on this phone (that one stays in its account).
        TestServer.signup("other@example.com", "other-pass-123");
        api(ab, "/api/account/logout", Json.obj());
        api(ab, "/api/account/login", Json.obj("email", "other@example.com", "password", "other-pass-123"));
        r = api(ab, "/api/sync/now", Json.obj());
        setup = Json.map(r.get("setup"));
        check("another account on phone B: the first account's data is not shown to it", !Json.bool(setup, "has_cv")
                && !Json.bool(setup, "setup_done") && eb.copilot.list().isEmpty() && eb.store.statusOf("4099999999") == null
                && "".equals(Json.str(eb.profile.load(), "phone")), Json.stringify(setup));
        Map<String, Object> other = Json.map(userData("other@example.com").get("counts"));
        check("and none of it went into the other account", Json.num(other, "facts", 0) == 0 && Json.num(other, "jobs", 0) == 0
                && Json.num(other, "copilot", 0) == 0, other);
        File c = newPhone("sync_c");
        Engine ec = new Engine(new TestPlatform("classic"), c);
        Api ac = new Api(ec);
        api(ac, "/api/account/login", Json.obj("email", email, "password", pw));
        r = api(ac, "/api/sync/now", Json.obj());
        check("the first account still has everything (phone C)", Json.bool(Json.map(r.get("setup")), "has_cv")
                && "Giza".equals(ec.config.load().get("LOCATION")) && "applied".equals(ec.store.statusOf("4099999999")), Json.stringify(r));

        // Signed out, with background syncs on: the sync thread waits quietly (no busy loop).
        com.autoapply.bot.core.Sync.auto = true;
        try {
            Engine idle = new Engine(new TestPlatform("classic"), fresh("sync_idle", false));
            idle.sync.changed();
            Thread.sleep(400);
            Thread t = null;
            for (Thread x : Thread.getAllStackTraces().keySet()) {
                if (x.getName().equals("sync") && x.isAlive()) {
                    t = x;
                }
            }
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            long c0 = t == null ? 0 : mx.getThreadCpuTime(t.getId());
            Thread.sleep(1500);
            long c1 = t == null ? 0 : mx.getThreadCpuTime(t.getId());
            check("signed out: the sync thread waits quietly", t != null && c1 - c0 < 150_000_000L, (c1 - c0) / 1_000_000 + " ms of CPU");
        } finally {
            com.autoapply.bot.core.Sync.auto = false;
        }
    }

    // ------------------------------------------------------------------ ask before sending (GitHub build)
    static void approve() throws Exception {
        System.out.println("\n== Ask before sending: filled up to Submit, then Apply / Skip / Apply all");
        File d = fresh("approve");
        write(d, "config.json", Json.pretty(config("APPROVE_FIRST", "1")));
        write(d, "about_me.txt", "Phone: 01012345678\n");
        TestPlatform p = new TestPlatform("classic");
        Engine e = new Engine(p, d);
        e.run(opts(false));
        Map<String, String> st = statuses(d);
        List<String> con = p.console();
        String lg = log(d);
        check("good matches are filled up to Submit and wait for you; nothing is sent",
                st.toString().equals("{111=ready, 222=ready, 444=stuck, 555=no_easy_apply, 666=skipped_low_score}")
                        && !con.contains("AA_FOLLOW_AT_SUBMIT=false") && count(con, "AA_DISCARDED") >= 3, st);
        check("their answers are kept for sending later", "01012345678".equals(qa(jobs(d).get("111")).get("Mobile phone number")));
        check("no pacing waits while preparing (the pace is for sending)", count(lg, "Next application slot at") == 0);
        check("the notification follows them: one, then two", p.events.contains("ready:1") && p.events.contains("ready:2")
                && p.ready.size() == 2, p.events);
        check("the brief says they wait for your yes", lg.contains("Ready to send, waiting for your yes: 2")
                && Json.arr(p.lastSummary.get("ready")).size() == 2);
        Api a = new Api(e);
        Map<String, Object> r = api(a, "/api/ready", null);
        check("the app lists them", Json.arr(r.get("items")).size() == 2 && Json.num(api(a, "/api/state", null), "ready", 0) == 2, r);
        r = api(a, "/api/ready", Json.obj("op", "skip", "ids", Json.list("222")));
        check("Skip: not sent", Json.bool(r, "ok") && "user_skipped".equals(statuses(d).get("222")) && p.events.contains("ready:1"));
        int before = p.console().size();
        r = api(a, "/api/ready", Json.obj("op", "approve", "ids", Json.list("111")));
        check("Apply while the bot is not running: a short run starts to send it", Json.bool(r, "ok") && Json.num(r, "count", 0) == 1
                && Json.bool(r, "running"), r);
        e.join(120000);
        String lg2 = log(d).substring(lg.length());
        List<String> con2 = p.console().subList(before, p.console().size());
        check("sent: applied, and only that one", "applied".equals(statuses(d).get("111")) && "user_skipped".equals(statuses(d).get("222"))
                && count(con2, "AA_FOLLOW_AT_SUBMIT=false") == 1, statuses(d));
        check("no new search and no second AI score for it", !lg2.contains("Searching") && !lg2.contains("Match ")
                && lg2.contains("Sending what you said yes to"), lg2);
        check("nothing left in the notification", p.events.get(p.events.size() - 1).startsWith("ready:0") || p.ready.isEmpty(), p.events);

        // Apply all while it runs: it keeps preparing, then sends what you said yes to.
        File d2 = fresh("approve_all");
        write(d2, "config.json", Json.pretty(config("APPROVE_FIRST", "1")));
        write(d2, "about_me.txt", "Phone: 01012345678\n");
        TestPlatform p2 = new TestPlatform("classic");
        Engine e2 = new Engine(p2, d2);
        com.autoapply.bot.core.Bot.Options keep = opts(false);
        keep.once = false;
        check("started", e2.start(keep).isEmpty());
        boolean both = waitFor(() -> "ready".equals(statuses(d2).get("111")) && "ready".equals(statuses(d2).get("222"))
                && statuses(d2).containsKey("666"), 120);
        check("while running: both prepared, the round goes on", both && e2.running(), statuses(d2));
        Api a2 = new Api(e2);
        r = api(a2, "/api/ready", Json.obj("op", "approve", "all", true));
        check("Apply all", Json.num(r, "count", 0) == 2, r);
        boolean sent = waitFor(() -> "applied".equals(statuses(d2).get("111")) && "applied".equals(statuses(d2).get("222")), 120);
        check("the running bot sends them, at your pace", sent && count(log(d2), "You said yes to this application") == 2, statuses(d2));
        e2.stop();
        e2.join(60000);
        check("stopped", !e2.running());

        // As many waiting as an hour's pace: it prepares no more until you answer some.
        File d3 = fresh("approve_cap");
        write(d3, "config.json", Json.pretty(config("APPROVE_FIRST", "1", "PER_HOUR", "1")));
        write(d3, "about_me.txt", "Phone: 01012345678\n");
        com.autoapply.bot.core.Bot.readyMin = 1;
        try {
            TestPlatform p3 = new TestPlatform("classic");
            Engine e3 = new Engine(p3, d3);
            e3.run(opts(false));
            check("a one-round run stops preparing at the cap (1 an hour here)", statuses(d3).toString().equals("{111=ready}")
                    && log(d3).contains("No more are prepared in this run."), statuses(d3));
            com.autoapply.bot.core.Bot.Options keep3 = opts(false);
            keep3.once = false;
            check("started again", e3.start(keep3).isEmpty());
            boolean waits = waitFor(() -> log(d3).contains("Preparing more after you answer some."), 60);
            Api a3 = new Api(e3);
            check("a run that keeps going waits for you", waits && Json.str(Json.map(api(a3, "/api/state", null).get("status")), "message")
                    .startsWith("Waiting for your yes"), api(a3, "/api/state", null).get("status"));
            api(a3, "/api/ready", Json.obj("op", "skip", "ids", Json.list("111")));
            boolean next = waitFor(() -> "ready".equals(statuses(d3).get("222")), 90);
            check("you answered: it goes on with the next one", next && "user_skipped".equals(statuses(d3).get("111")), statuses(d3));
            e3.stop();
            e3.join(60000);
            check("stopped", !e3.running());
        } finally {
            com.autoapply.bot.core.Bot.readyMin = 3;
        }
    }

    // ------------------------------------------------------------------ the AI answers when it is sure, else asks you
    static void unsure() throws Exception {
        System.out.println("\n== The AI answers what it is sure about, and asks you the rest");
        check("the default for new installs; the modes", "unsure".equals(com.autoapply.bot.core.Config.dflt("NEW_QUESTIONS"))
                && "unsure".equals(com.autoapply.bot.core.Questions.askModeOf("unsure"))
                && "ask".equals(com.autoapply.bot.core.Questions.askModeOf("ask"))
                && "ai".equals(com.autoapply.bot.core.Questions.askModeOf("ai")));
        File d = fresh("unsure");
        write(d, "config.json", Json.pretty(config("NEW_QUESTIONS", "unsure")));
        write(d, "about_me.txt", "Phone: 01012345678\n");
        TestPlatform p = new TestPlatform("classic");
        Engine e = new Engine(p, d);
        Api a = new Api(e);
        check("the mode shows", "unsure".equals(Json.str(api(a, "/api/state", null), "ask_mode")));
        check("start", Json.bool(api(a, "/api/start", Json.obj("mode", "apply", "once", true)), "ok"));
        List<String> asked = new ArrayList<>();
        Map<String, Map<String, Object>> cards = new java.util.LinkedHashMap<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        long end = System.currentTimeMillis() + 150000;
        Util.realSleep(0.3);
        while (e.running() && System.currentTimeMillis() < end) {
            Map<String, Object> q = Json.map(api(a, "/api/state", null).get("question"));
            if (q.isEmpty() || Json.bool(q, "loading") || done.contains(Json.str(q, "id"))) {
                Util.realSleep(0.05);
                continue;
            }
            done.add(Json.str(q, "id"));
            String text = Json.str(q, "question");
            asked.add(text);
            cards.put(text, q);
            if (text.contains("Terraform") || text.contains("hybrid")) {
                answer(a, q, text.contains("Terraform") ? "3" : "Yes", true);
            } else {
                api(a, "/api/skip-question", Json.obj("id", Json.str(q, "id")));   // job 3's odd ID format: skip that job
            }
        }
        waitIdle(e, 60);
        if (e.running()) {
            e.stop();
            e.join(30000);
        }
        Map<String, String> st = statuses(d);
        String lg = log(d);
        Map<String, String> qa1 = qa(jobs(d).get("111"));
        check("applied as usual", st.toString().equals("{111=applied, 222=applied, 444=user_skipped, 555=no_easy_apply, "
                + "666=skipped_low_score}"), st);
        check("you were asked only what the AI was not sure about, once (job 2 used your answers)",
                count(String.join("\n", asked), "Terraform") == 1 && count(String.join("\n", asked), "hybrid") == 1
                        && asked.size() == 3 && asked.stream().noneMatch(x -> x.matches("(?i).*(phone|city|english|cloud).*")), asked);
        check("what the CV or the notes say, the AI filled by itself", lg.contains("AI answer (sure 95%): Mobile phone number -> 01012345678")
                && lg.contains("AI answer (sure 90%): Location (city)") && lg.contains("AI answer (sure 85%)")
                && lg.contains("AI answer (sure 90%): Which cloud platforms"), lg);
        Map<String, Object> tf = null;
        for (Map.Entry<String, Map<String, Object>> c : cards.entrySet()) {
            if (c.getKey().contains("Terraform")) {
                tf = c.getValue();
            }
        }
        check("the card says why you are asked, with the AI's guess among the suggestions", tf != null
                && Json.str(tf, "note").contains("not sure") && Json.arr(tf.get("suggestions")).contains("0"), tf);
        check("the form has both: yours and the AI's", "3".equals(qa1.get("How many years of work experience do you have with Terraform?"))
                && "Yes".equals(qa1.get("Are you comfortable working in a hybrid setting?"))
                && "01012345678".equals(qa1.get("Mobile phone number")) && "AWS, Azure".equals(qa1.get("Which cloud platforms have you used in production?")),
                qa1);
        check("your answers are saved for next time", "3".equals(answers(d).get("how many years of work experience do you have with terraform?")));
        check("the log says when it asks", lg.contains("The AI is not sure (40%: a guess), so it asks you."));

        // You switch to "Let the AI answer" while it asks: its guess goes in, LinkedIn's check fixes it as usual.
        File d2 = fresh("unsure2");
        write(d2, "config.json", Json.pretty(config("NEW_QUESTIONS", "unsure")));
        write(d2, "about_me.txt", "Phone: 01012345678\n");
        TestPlatform p2 = new TestPlatform("classic");
        Engine e2 = new Engine(p2, d2);
        Api a2 = new Api(e2);
        check("start", Json.bool(api(a2, "/api/start", Json.obj("mode", "apply", "once", true)), "ok"));
        Map<String, Object> q = waitQuestion(a2, "Terraform", false, 60);
        check("asked", q != null);
        api(a2, "/api/ask-mode", Json.obj("mode", "ai"));
        waitIdle(e2, 120);
        String lg2 = log(d2);
        check("the AI's guess went in, then the usual fix", lg2.contains("New questions are answered by the AI now.")
                && lg2.contains("AI answer: How many years of work experience do you have with Terraform? -> 0")
                && "3".equals(qa(jobs(d2).get("111")).get("How many years of work experience do you have with Terraform?")),
                qa(jobs(d2).get("111")));
        check("applied", "applied".equals(statuses(d2).get("111")) && "applied".equals(statuses(d2).get("222")), statuses(d2));
        api(a2, "/api/ask-mode", Json.obj("mode", "unsure"));
        check("the switch is saved", "unsure".equals(e2.config.load().get("NEW_QUESTIONS")));
    }
    // ------------------------------------------------------------------ main
    // ------------------------------------------------------------------ jobs you apply to yourself (Google Play build)
    static void copilot() throws Exception {
        System.out.println("\n== Jobs you apply to yourself: links, the list, stages, reports");
        File d = fresh("copilot");
        write(d, "config.json", Json.pretty(config("KEYWORDS", "DevOps Engineer;Cloud Engineer", "DATE_POSTED", "day", "WORK_TYPE", "remote")));
        TestPlatform play = new TestPlatform("classic");
        play.selfUpdate = false;  // the Google Play build
        Engine e = new Engine(play, d);
        Api a = new Api(e);
        Map<String, Object> r = Json.map(Json.parse(a.handle("/api/copilot/add", Json.stringify(Json.obj("text",
                "Check out this job at Valeo: DevOps Engineer https://www.linkedin.com/comm/jobs/view/4012345678/?trackingId=x%3D&refId=y",
                "subject", "")))));
        Map<String, Object> it = Json.map(r.get("item"));
        check("LinkedIn share: the job's plain link, title and company", "li:4012345678".equals(Json.str(it, "id"))
                && "https://www.linkedin.com/jobs/view/4012345678/".equals(Json.str(it, "url")) && "DevOps Engineer".equals(Json.str(it, "title"))
                && "Valeo".equals(Json.str(it, "company")) && "linkedin".equals(Json.str(it, "channel")), it);
        r = Json.map(Json.parse(a.handle("/api/copilot/add", Json.stringify(Json.obj("text",
                "https://www.linkedin.com/jobs/search/?currentJobId=4012345678&keywords=devops", "subject", "")))));
        check("the same job from a search link is not added twice", Json.bool(Json.map(r.get("item")), "again")
                && Json.arr(r.get("items")).size() == 1, r.get("item"));
        r = Json.map(Json.parse(a.handle("/api/copilot/add", Json.stringify(Json.obj("text",
                "Senior SRE at Instabug https://boards.greenhouse.io/instabug/jobs/123?gh_src=abc&utm_source=linkedin#app", "subject", "")))));
        it = Json.map(r.get("item"));
        check("company site: tracking bits removed, title and company from the text",
                "https://boards.greenhouse.io/instabug/jobs/123".equals(Json.str(it, "url")) && "company".equals(Json.str(it, "channel"))
                && "Senior SRE".equals(Json.str(it, "title")) && "Instabug".equals(Json.str(it, "company"))
                && Json.str(it, "id").matches("c:[0-9a-f]{12}"), it);
        String site = Json.str(it, "id");
        r = Json.map(Json.parse(a.handle("/api/copilot/add", Json.stringify(Json.obj("text", "no link here", "subject", "")))));
        check("a share without a link is explained", Boolean.FALSE.equals(r.get("ok")) && Json.str(r, "error").contains("link"), r);
        Map<String, Object> c = Json.map(Json.parse(a.handle("/api/copilot", null)));
        List<Object> links = Json.arr(c.get("search_links"));
        check("one LinkedIn search per title: Easy Apply, past 24 hours, remote", links.size() == 2
                && Json.str(Json.map(links.get(1)), "url").equals("https://www.linkedin.com/jobs/search/?keywords=Cloud+Engineer&f_AL=true"
                + "&sortBy=DD&location=Egypt&f_TPR=r86400&f_WT=2"), links);
        check("2 waiting", Json.num(Json.map(c.get("stats")), "waiting", 0) == 2, c.get("stats"));

        a.handle("/api/copilot/open", Json.stringify(Json.obj("id", "li:4012345678")));
        a.handle("/api/copilot/open", Json.stringify(Json.obj("id", site)));
        List<String> opened = new ArrayList<>();
        for (String ev : play.events) {
            if (ev.startsWith("open:")) {
                opened.add(ev);
            }
        }
        check("LinkedIn jobs open in the LinkedIn app, company sites in the app's browser", opened.equals(Arrays.asList(
                "open:linkedin:https://www.linkedin.com/jobs/view/4012345678/ job=li:4012345678",
                "open:inapp:https://boards.greenhouse.io/instabug/jobs/123 job=" + site)), opened);
        check("auto apply cannot start on the Google Play build", Json.str(Json.map(Json.parse(a.handle("/api/start",
                Json.stringify(Json.obj("mode", "apply"))))), "error").contains("applies with you"));

        r = Json.map(Json.parse(a.handle("/api/copilot/update", Json.stringify(Json.obj("id", "li:4012345678", "state", "applied")))));
        check("marked applied: counted today", Json.num(Json.map(r.get("stats")), "today", 0) == 1
                && "applied".equals(Json.str(Json.map(r.get("item")), "stage")), r.get("stats"));
        Map<String, Object> tr = Json.map(Json.parse(a.handle("/api/tracker/stage", Json.stringify(Json.obj("id", "copilot:li:4012345678",
                "stage", "interview")))));
        check("tracker: the stage changes", Json.num(Json.map(tr.get("stages")), "interview", 0) == 1 && Json.num(tr, "total", 0) == 1, tr.get("stages"));
        r = Json.map(Json.parse(a.handle("/api/tracker/stage", Json.stringify(Json.obj("id", "copilot:li:4012345678", "stage", "hired")))));
        check("an unknown stage is refused", Boolean.FALSE.equals(r.get("ok")));
        a.handle("/api/copilot/update", Json.stringify(Json.obj("id", site, "state", "skipped")));
        a.handle("/api/copilot/remove", Json.stringify(Json.obj("id", site)));
        c = Json.map(Json.parse(a.handle("/api/copilot", null)));
        check("skip, then remove", Json.arr(c.get("items")).size() == 1, c.get("items"));
        File saved = new File(d, "copilot.json");
        check("kept in copilot.json across restarts", new Engine(new TestPlatform("classic"), d).copilot.list().size() == 1
                && saved.isFile());
        boolean reported = waitFor(() -> {
            Map<String, Object> u;
            try {
                u = TestServer.admin("GET", "/admin/api/users/" + TestServer.uid(EMAIL), null);
            } catch (Exception ex) {
                return false;
            }
            for (Object o : Json.arr(u.get("applications"))) {
                Map<String, Object> x = Json.map(o);
                if ("li:4012345678".equals(Json.str(x, "job_id")) && "interview".equals(Json.str(x, "stage"))
                        && "linkedin".equals(Json.str(x, "channel"))) {
                    return true;
                }
            }
            return false;
        }, 20);
        check("the server has it: LinkedIn, Interview", reported);
        e.stop();
    }

    public static void main(String[] args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", new File(SCRATCH, "mock_server.py").getPath());
        pb.redirectErrorStream(true);
        pb.directory(SCRATCH);
        mock = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(mock.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("MOCK_PORT=")) {
                mockPort = Integer.parseInt(line.substring(10).trim());
                break;
            }
        }
        final BufferedReader rest = r;
        Thread t = new Thread(() -> {
            try {
                String l;
                while ((l = rest.readLine()) != null) {
                    System.out.println("[mock] " + l);
                }
            } catch (Exception ignored) {
                // server ended
            }
        });
        t.setDaemon(true);
        t.start();
        Ai.apiUrl = "http://127.0.0.1:" + mockPort + "/v1/chat/completions";  // only for the Ai objects tests make directly
        TestServer.start(SCRATCH, mockPort);
        com.autoapply.bot.core.Account.defaultServer = TestServer.base;
        token = TestServer.signup(EMAIL, PASSWORD);
        TestServer.setPro(EMAIL, true);
        com.autoapply.bot.core.Reporter.every = 0.5;
        com.autoapply.bot.core.Sync.auto = false;   // tests share one account: syncs only when a test asks
        Util.speed = 0.02;
        Util.echo = Boolean.getBoolean("echo");
        String which = args.length > 0 ? args[0] : "all";
        try {
            if (which.equals("all") || which.equals("classic")) {
                classic();
            }
            if (which.equals("all") || which.equals("sdui")) {
                sdui();
            }
            if (which.equals("all") || which.equals("real")) {
                real();
            }
            if (which.equals("all") || which.equals("trusted")) {
                trusted();
            }
            if (which.equals("all") || which.equals("profile")) {
                profile();
            }
            if (which.equals("all") || which.equals("facts")) {
                facts();
            }
            if (which.equals("all") || which.equals("ask")) {
                ask();
            }
            if (which.equals("all") || which.equals("update")) {
                update();
            }
            if (which.equals("all") || which.equals("account")) {
                account();
            }
            if (which.equals("all") || which.equals("copilot")) {
                copilot();
            }
            if (which.equals("all") || which.equals("sync")) {
                sync();
            }
            if (which.equals("all") || which.equals("approve")) {
                approve();
            }
            if (which.equals("all") || which.equals("unsure")) {
                unsure();
            }
        } finally {
            CdpPage.stopChrome();
            mock.destroy();
            TestServer.stopServer();
        }
        long ok = results.stream().filter(x -> x).count();
        System.out.println("\n" + ok + "/" + results.size() + " checks passed");
        System.exit(ok == results.size() ? 0 : 1);
    }
}
