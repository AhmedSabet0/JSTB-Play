import com.autoapply.bot.core.Ai;
import com.autoapply.bot.core.Api;
import com.autoapply.bot.core.Engine;
import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Page;
import com.autoapply.bot.core.Platform;
import com.autoapply.bot.core.Util;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Serves the Android dashboard and its API (the real engine) over HTTP, for UI tests in Chrome. */
public class UiServer {
    static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean signedIn = false;
    static volatile boolean notifications = false;
    static int mockPort;

    static class P implements Platform {
        public Page newPage() {
            return new CdpPage("real", mockPort);
        }

        public void closePage(Page page) {
            ((CdpPage) page).close();
        }

        public String pageScripts() {
            try {
                return new String(Files.readAllBytes(new File(System.getProperty("assets"), "li.js").toPath()), "UTF-8");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String browserName() {
            return "headless Chrome (test)";
        }

        public void onRunning(boolean running) {
            EVENTS.add("running=" + running);
        }

        public void onQuestion(Map<String, Object> item) {
            EVENTS.add("question:" + Json.str(item, "question"));
        }

        public void onQuestionDone() {
            EVENTS.add("question-done");
        }

        public void onSignInNeeded() {
            EVENTS.add("signin-needed");
        }

        public void onSessionEnd(Map<String, Object> summary, List<String> lines) {
            EVENTS.add("session-end");
        }

        public void onReady(List<Map<String, Object>> items) {
            EVENTS.add("ready:" + items.size());
        }

        public void openSignIn() {
            EVENTS.add("open-signin");
            signedIn = true;  // the test pretends you signed in on the LinkedIn screen
        }

        public boolean signedIn() {
            return signedIn;
        }

        public void share(File file, String mime, String title) {
            EVENTS.add("share:" + file.getName());
        }

        public Map<String, Object> permissions() {
            return Json.obj("notifications", notifications, "battery", true, "maker", "Xiaomi");
        }

        public void requestPermission(String which) {
            EVENTS.add("permission:" + which);
            if (which.equals("notifications")) {
                notifications = true;
            }
        }

        public void openLink(String url, String how, String jobId, String title) {
            EVENTS.add("open:" + how + ":" + url + (jobId == null || jobId.isEmpty() ? "" : " job=" + jobId));
        }

        volatile String updState = "idle";

        static final String FLAVOR = System.getProperty("flavor", "github");

        public Map<String, Object> buildInfo() {
            return Json.obj("flavor", FLAVOR, "self_update", !"play".equals(FLAVOR));
        }

        public Map<String, Object> appVersion() {
            return Json.obj("name", "1.0", "code", 1);
        }

        public void installUpdate(String url, String version, boolean auto) {
            EVENTS.add((auto ? "install-auto:" : "install:") + url + ":" + version);
            updState = "downloading";
        }

        public Map<String, Object> updateState() {
            return Json.obj("state", updState, "progress", 40, "message", "downloading".equals(updState) ? "Downloading version 1.2... 40%" : "");
        }
    }

    public static void main(String[] args) throws Exception {
        File scratch = new File(System.getProperty("scratch"));
        Process mock = new ProcessBuilder("python3", new File(scratch, "mock_server.py").getPath()).directory(scratch)
                .redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(mock.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("MOCK_PORT=")) {
                mockPort = Integer.parseInt(line.substring(10).trim());
                break;
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mock.destroy();
            CdpPage.stopChrome();
        }));
        Ai.apiUrl = "http://127.0.0.1:" + mockPort + "/v1/chat/completions";
        TestServer.start(scratch, mockPort);  // the app's server; the setup types its address (none is built in here)
        if (Boolean.getBoolean("builtIn")) {
            com.autoapply.bot.core.Account.defaultServer = TestServer.base;   // like a release build: the server is built in
        }
        com.autoapply.bot.core.Reporter.every = 0.5;
        com.autoapply.bot.core.Updater.githubApi = "http://127.0.0.1:" + mockPort + "/gh";
        com.autoapply.bot.core.Profile.ratesUrl = "http://127.0.0.1:" + mockPort + "/rates/";
        Util.speed = 0.02;
        Util.echo = false;
        File data = new File(scratch, "jrun_ui");
        EngineTest.deleteTree(data);
        data.mkdirs();
        Util.writeText(new File(data, "config.json"), Json.pretty(Json.obj("KEYWORDS", "", "PAGES_PER_KEYWORD", "1")));
        com.autoapply.bot.core.Account.flavor = P.FLAVOR;
        com.autoapply.bot.core.Account.versionCode = Long.getLong("versionCode", 1L);
        final Engine[] engine = {new Engine(new P(), data)};
        final Api[] api = {new Api(engine[0])};
        File ui = new File(System.getProperty("assets"), "ui");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            String path = ex.getRequestURI().getPath();
            byte[] out;
            String type = "application/json; charset=utf-8";
            int code = 200;
            if (path.startsWith("/api/")) {
                String body = ex.getRequestMethod().equals("POST")
                        ? new String(ex.getRequestBody().readAllBytes(), "UTF-8") : null;
                String q = ex.getRequestURI().getRawQuery();
                out = api[0].handle(path + (q == null ? "" : "?" + q), body == null || body.isEmpty() ? (ex.getRequestMethod().equals("POST") ? "{}" : null) : body)
                        .getBytes("UTF-8");
            } else if (path.equals("/__newphone")) {   // the same app on a new phone: nothing on it yet
                File d2 = new File(scratch, "jrun_ui_phone" + System.nanoTime());
                d2.mkdirs();
                engine[0] = new Engine(new P(), d2);
                api[0] = new Api(engine[0]);
                out = Json.stringify(Json.obj("ok", true, "data", d2.getPath())).getBytes("UTF-8");
            } else if (path.equals("/__events")) {
                out = Json.stringify(new ArrayList<>(EVENTS)).getBytes("UTF-8");
            } else if (path.startsWith("/ui/")) {
                File f = new File(ui, path.substring(4));
                if (!f.getCanonicalPath().startsWith(ui.getCanonicalPath()) || !f.isFile()) {
                    code = 404;
                    out = "not found".getBytes();
                    type = "text/plain";
                } else {
                    out = Files.readAllBytes(f.toPath());
                    String n = f.getName();
                    type = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css"
                            : n.endsWith("js") ? "text/javascript" : n.endsWith(".woff2") ? "font/woff2" : "application/octet-stream";
                }
            } else {
                code = 404;
                out = "not found".getBytes();
                type = "text/plain";
            }
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("UI_PORT=" + server.getAddress().getPort());
        System.out.println("SERVER=" + TestServer.base);
        System.out.println("MOCK=" + mockPort);
        System.out.println("DATA=" + data);
        Thread.currentThread().join();
    }
}
