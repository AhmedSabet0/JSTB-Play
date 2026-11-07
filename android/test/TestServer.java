import com.autoapply.bot.core.Json;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Runs the real Auto Apply server (FastAPI, autoapply-server) for the tests, with mock_server.py as its OpenRouter,
 *  and drives its admin API like the admin page does. */
public class TestServer {
    static final String ADMIN = "admin-test-pass";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static int port;
    static String base;
    static Process proc;
    static String cookie = "";

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static void start(File scratch, int mockPort) throws Exception {
        for (String n : new String[]{"srv_test.db", "srv_test.db-wal", "srv_test.db-shm"}) {
            new File(scratch, n).delete();
        }
        port = freePort();
        base = "http://127.0.0.1:" + port;
        ProcessBuilder pb = new ProcessBuilder("python3", "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port",
                String.valueOf(port), "--no-access-log");
        pb.directory(new File(System.getProperty("server", "/home/claude/autoapply-server")));
        Map<String, String> env = pb.environment();
        env.put("DB_PATH", new File(scratch, "srv_test.db").getPath());
        env.put("ADMIN_PASSWORD", ADMIN);
        env.put("OPENROUTER_URL", "http://127.0.0.1:" + mockPort + "/v1/chat/completions");
        env.put("OPENROUTER_KEY", "sk-test");
        env.put("OPENROUTER_MODEL", "openai/gpt-4o-mini");
        env.put("CONTACT_EMAIL", "help@example.com");
        env.put("JAVA_TOOL_OPTIONS", "");
        String google = System.getProperty("google", "");   // a fake Google (UI tests of Google Drive)
        if (!google.isEmpty()) {
            env.put("PUBLIC_URL", base);
            env.put("GOOGLE_AUTH_URL", google + "/auth");
            env.put("GOOGLE_TOKEN_URL", google + "/oauth2/token");
            env.put("GOOGLE_REVOKE_URL", google + "/oauth2/revoke");
            env.put("GOOGLE_DRIVE_URL", google + "/drive/v3");
            env.put("GOOGLE_UPLOAD_URL", google + "/upload/drive/v3");
            env.put("GOOGLE_SHEETS_URL", google + "/v4/spreadsheets");
            env.put("DRIVE_CHECK_EVERY", "20");
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(scratch, "srv_test.log"));
        proc = pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> proc.destroy()));
        long end = System.currentTimeMillis() + 30000;
        while (true) {
            try {
                if (call("GET", "/health", null, false).get("ok") == Boolean.TRUE) {
                    break;
                }
            } catch (Exception e) {
                if (System.currentTimeMillis() > end) {
                    throw new IllegalStateException("The Auto Apply server did not start (see srv_test.log)", e);
                }
                Thread.sleep(200);
            }
        }
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/admin/api/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        Json.stringify(Json.obj("password", ADMIN)))).build(), HttpResponse.BodyHandlers.ofString());
        for (String c : r.headers().allValues("set-cookie")) {
            if (c.startsWith("aa_admin=")) {
                cookie = c.split(";")[0];
            }
        }
        if (cookie.isEmpty()) {
            throw new IllegalStateException("admin sign-in failed: " + r.body());
        }
    }

    static void stopServer() {
        if (proc != null) {
            proc.destroy();
        }
    }

    /** JSON answer, with "_status" added. */
    static Map<String, Object> call(String method, String path, Object body, boolean admin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (admin) {
            b.header("Cookie", cookie).header("X-Requested-With", "admin");
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Object parsed = Json.tryParse(r.body());
        Map<String, Object> out = parsed instanceof Map ? Json.map(parsed) : Json.obj("_text", r.body());
        out.put("_status", r.statusCode());
        return out;
    }

    static Map<String, Object> admin(String method, String path, Object body) throws Exception {
        return call(method, path, body, true);
    }

    /** A new account; returns its token. */
    static String signup(String email, String password) throws Exception {
        Map<String, Object> r = call("POST", "/v1/auth/signup", Json.obj("email", email, "password", password, "name", "Test"), false);
        return Json.str(r, "token");
    }

    static Map<String, Object> user(String email) throws Exception {
        for (Object o : Json.arr(admin("GET", "/admin/api/overview", null).get("users"))) {
            Map<String, Object> u = Json.map(o);
            if (email.equals(Json.str(u, "email"))) {
                return u;
            }
        }
        return null;
    }

    static long uid(String email) throws Exception {
        return Json.num(user(email), "id", -1);
    }

    static void setPro(String email, boolean on) throws Exception {
        admin("POST", "/admin/api/users/" + uid(email), Json.obj("pro", on));
    }

    static Map<String, Object> stop(String email) throws Exception {
        return admin("POST", "/admin/api/users/" + uid(email) + "/stop", Json.obj());
    }

    static List<Object> applications(String email) throws Exception {
        return Json.arr(admin("GET", "/admin/api/users/" + uid(email), null).get("applications"));
    }

    static void settings(Object... kv) throws Exception {
        admin("POST", "/admin/api/settings", Json.obj(kv));
    }
}
