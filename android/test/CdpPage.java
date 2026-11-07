import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Page;
import com.autoapply.bot.core.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only Page: headless Chrome over the DevTools protocol. LinkedIn URLs are answered by the mock server. */
public final class CdpPage implements Page {
    static Process chrome;
    static String browserWs;
    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(10)).build();
    static final ExecutorService POOL = Executors.newCachedThreadPool();

    final String scenario;
    final int mockPort;
    final List<String> console = Collections.synchronizedList(new ArrayList<String>());
    final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    final AtomicInteger ids = new AtomicInteger();
    final Object sendLock = new Object();
    WebSocket ws;
    String sessionId;
    String targetId;
    volatile boolean closed;

    static synchronized void startChrome() throws Exception {
        if (chrome != null && chrome.isAlive()) {
            return;
        }
        File profile = new File(System.getProperty("java.io.tmpdir"), "cdp-profile-" + System.nanoTime());
        ProcessBuilder pb = new ProcessBuilder("/usr/bin/google-chrome", "--headless=new", "--remote-debugging-port=0",
                "--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check", "--no-proxy-server",
                "--disable-background-networking", "--disable-gpu", "--window-size=1280,900", "about:blank");
        pb.redirectErrorStream(true);
        chrome = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(chrome.getInputStream()));
        String line;
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end && (line = r.readLine()) != null) {
            int i = line.indexOf("ws://");
            if (line.contains("DevTools listening on") && i >= 0) {
                browserWs = line.substring(i).trim();
                break;
            }
        }
        if (browserWs == null) {
            throw new IllegalStateException("Chrome did not start");
        }
        final BufferedReader rest = r;
        Thread drain = new Thread(() -> {
            try {
                while (rest.readLine() != null) {
                    // keep the pipe empty
                }
            } catch (Exception e) {
                // chrome exited
            }
        });
        drain.setDaemon(true);
        drain.start();
    }

    static void stopChrome() {
        if (chrome != null) {
            chrome.destroyForcibly();
        }
    }

    CdpPage(String scenario, int mockPort) {
        this.scenario = scenario;
        this.mockPort = mockPort;
        try {
            startChrome();
            ws = HTTP.newWebSocketBuilder().buildAsync(URI.create(browserWs), new Listener()).get(10, TimeUnit.SECONDS);
            targetId = Json.str(send(null, "Target.createTarget", Json.obj("url", "about:blank")), "targetId");
            sessionId = Json.str(send(null, "Target.attachToTarget", Json.obj("targetId", targetId, "flatten", true)), "sessionId");
            send(sessionId, "Page.enable", Json.obj());
            send(sessionId, "Runtime.enable", Json.obj());
            send(sessionId, "Emulation.setDeviceMetricsOverride", Json.obj("width", 1280, "height", 900,
                    "deviceScaleFactor", 1, "mobile", false));
            send(sessionId, "Fetch.enable", Json.obj("patterns", Json.list(Json.obj("urlPattern", "*://www.linkedin.com/*"))));
        } catch (Exception e) {
            throw new RuntimeException("Could not open Chrome: " + e, e);
        }
    }

    Map<String, Object> send(String session, String method, Map<String, Object> params) {
        int id = ids.incrementAndGet();
        Map<String, Object> msg = Json.obj("id", id, "method", method, "params", params);
        if (session != null) {
            msg.put("sessionId", session);
        }
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(id, f);
        synchronized (sendLock) {
            ws.sendText(Json.stringify(msg), true).join();
        }
        try {
            Map<String, Object> res = f.get(90, TimeUnit.SECONDS);
            if (res.containsKey("error")) {
                throw new RuntimeException(method + ": " + Json.str(Json.map(res.get("error")), "message"));
            }
            return Json.map(res.get("result"));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(method + " timed out", e);
        } finally {
            pending.remove(id);
        }
    }

    final class Listener implements WebSocket.Listener {
        final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                handle(Json.map(Json.tryParse(text)));
            }
            w.request(1);
            return null;
        }
    }

    void handle(Map<String, Object> m) {
        if (m.containsKey("id")) {
            CompletableFuture<Map<String, Object>> f = pending.get((int) Json.num(m, "id", -1));
            if (f != null) {
                f.complete(m);
            }
            return;
        }
        String method = Json.str(m, "method");
        Map<String, Object> p = Json.map(m.get("params"));
        if (method.equals("Fetch.requestPaused")) {
            POOL.submit(() -> fulfill(Json.str(m, "sessionId"), p));
        } else if (method.equals("Runtime.consoleAPICalled")) {
            List<String> parts = new ArrayList<>();
            for (Object a : Json.arr(p.get("args"))) {
                parts.add(Json.str(Json.map(a), "value"));
            }
            console.add(String.join(" ", parts));
        }
    }

    void fulfill(String session, Map<String, Object> p) {
        String requestId = Json.str(p, "requestId");
        try {
            URI u = URI.create(Json.str(Json.map(p.get("request")), "url"));
            String local = "http://127.0.0.1:" + mockPort + "/" + scenario + u.getRawPath()
                    + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(local)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String ctype = r.headers().firstValue("Content-Type").orElse("text/html; charset=utf-8");
            send(session, "Fetch.fulfillRequest", Json.obj("requestId", requestId, "responseCode", r.statusCode(),
                    "responseHeaders", Json.list(Json.obj("name", "Content-Type", "value", ctype)),
                    "body", Util.base64(r.body())));
        } catch (Exception e) {
            try {
                send(session, "Fetch.failRequest", Json.obj("requestId", requestId, "errorReason", "Failed"));
            } catch (RuntimeException ignored) {
                // page gone
            }
        }
    }

    // ------------------------------------------------------------------ Page
    @Override
    public String eval(String js) {
        if (closed) {
            throw new Util.BrowserClosed("closed");
        }
        try {
            Map<String, Object> r = send(sessionId, "Runtime.evaluate", Json.obj("expression", js, "returnByValue", true,
                    "awaitPromise", false));
            if (r.containsKey("exceptionDetails")) {
                return "null";
            }
            Map<String, Object> result = Json.map(r.get("result"));
            if (!result.containsKey("value")) {
                return "null";
            }
            return Json.stringify(result.get("value"));
        } catch (RuntimeException e) {
            return "null";
        }
    }

    @Override
    public void navigate(String url, double timeoutSeconds) {
        if (closed) {
            throw new Util.BrowserClosed("closed");
        }
        Map<String, Object> r = send(sessionId, "Page.navigate", Json.obj("url", url));
        if (!Json.str(r, "errorText").isEmpty()) {
            throw new RuntimeException("Page.navigate: " + Json.str(r, "errorText"));
        }
        long end = System.currentTimeMillis() + (long) (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < end) {
            String state = eval("document.readyState");
            if ("\"interactive\"".equals(state) || "\"complete\"".equals(state)) {
                return;
            }
            Util.realSleep(0.1);
        }
        throw new RuntimeException("Timeout loading " + url);
    }

    @Override
    public String url() {
        Object v = Json.tryParse(eval("location.href"));
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** A real mouse click through DevTools (the page sees isTrusted events), like a finger tap on the phone. */
    @Override
    public boolean tap(double x, double y, double viewportWidth) {
        if (closed) {
            throw new Util.BrowserClosed("closed");
        }
        try {
            taps.incrementAndGet();
            send(sessionId, "Input.dispatchMouseEvent", Json.obj("type", "mouseMoved", "x", x, "y", y));
            send(sessionId, "Input.dispatchMouseEvent", Json.obj("type", "mousePressed", "x", x, "y", y, "button", "left",
                    "buttons", 1, "clickCount", 1));
            send(sessionId, "Input.dispatchMouseEvent", Json.obj("type", "mouseReleased", "x", x, "y", y, "button", "left",
                    "buttons", 0, "clickCount", 1));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    final AtomicInteger taps = new AtomicInteger();

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            send(null, "Target.closeTarget", Json.obj("targetId", targetId));
        } catch (RuntimeException e) {
            // already closed
        }
        ws.abort();
    }
}
