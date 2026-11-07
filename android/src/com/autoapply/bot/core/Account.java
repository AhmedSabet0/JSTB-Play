package com.autoapply.bot.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/** Your JSTB account on the server: sign up / sign in, whether Pro is on, and the token that the AI requests
 *  and the reports use. The OpenRouter key stays on the server; the app only ever holds this token. */
public final class Account {
    /** The server built into the app. A server typed in the app (when none is built in) is saved in account.json. */
    public static volatile String defaultServer = "";
    /** "Samsung SM-A546E" (shown to the admin). */
    public static volatile String device = "";
    public static volatile String appVersion = "";
    /** The build ("github" or "play") and its versionCode: the server tells an app that is too old to update. */
    public static volatile String flavor = "";
    public static volatile long versionCode = 0;

    /** The server does not know this sign-in any more: sign in again. */
    public static class AuthError extends RuntimeException {
        public AuthError(String m) {
            super(m);
        }
    }

    /** The server answered with an error you can read. */
    public static class ServerError extends RuntimeException {
        public final int status;
        public final String code;

        public ServerError(int status, String message, String code) {
            super(message);
            this.status = status;
            this.code = code == null ? "" : code;
        }
    }

    /** The server could not be reached. */
    public static class NetError extends RuntimeException {
        public NetError(String m) {
            super(m);
        }
    }

    private final File file;
    private final Map<String, Object> data;

    public Account(File dir) {
        file = new File(dir, "account.json");
        Object saved = Json.tryParse(Util.readText(file));
        data = saved instanceof Map ? Json.map(saved) : Json.obj();
    }

    private void save() {
        Util.writeText(file, Json.pretty(data));
    }

    // ------------------------------------------------------------------ state
    public synchronized String server() {
        String s = Json.str(data, "server").trim();
        if (s.isEmpty()) {
            s = defaultServer == null ? "" : defaultServer.trim();
        }
        return s.replaceAll("/+$", "");
    }

    /** True when the server is built into the app (it cannot be changed then). */
    public static boolean serverBuiltIn() {
        return defaultServer != null && !defaultServer.trim().isEmpty();
    }

    /** "autoapply.example.com" -> "https://autoapply.example.com". Plain http only for this computer (tests). */
    public static String normalizeServer(String raw) {
        String s = String.valueOf(raw == null ? "" : raw).trim().replaceAll("/+$", "");
        if (s.isEmpty()) {
            return "";
        }
        if (!s.matches("(?i)^https?://.*")) {
            s = "https://" + s;
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("http://") && !s.matches("(?i)^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?$")) {
            throw new IllegalArgumentException("The server address must start with https://");
        }
        if (!s.matches("(?i)^https?://[a-z0-9.\\-]+(:\\d+)?(/[\\w./\\-]*)?$")) {
            throw new IllegalArgumentException("That does not look like a server address (for example autoapply.example.com).");
        }
        return s;
    }

    public synchronized void setServer(String raw) {
        if (serverBuiltIn()) {
            throw new IllegalArgumentException("This app's server is built in.");
        }
        String s = normalizeServer(raw);
        if (!s.equals(Json.str(data, "server"))) {
            data.put("server", s);
            data.remove("token");
            data.remove("user");
            save();
        }
    }

    public synchronized String token() {
        return Json.str(data, "token");
    }

    public synchronized boolean signedIn() {
        return !token().isEmpty();
    }

    /** The account's ID on the server ("" before the first answer from it). */
    public synchronized String userId() {
        return Json.str(Json.map(data.get("user")), "id");
    }

    public synchronized boolean pro() {
        return Json.bool(Json.map(data.get("user")), "pro");
    }

    /** The admin's cap on applications a day (0 = none). */
    public synchronized int maxPerDay() {
        return (int) Json.num(Json.map(data.get("user")), "max_per_day", 0);
    }

    /** The admin's cap on applications an hour (0 = none). */
    public synchronized int maxPerHour() {
        return (int) Json.num(Json.map(data.get("user")), "max_per_hour", 0);
    }

    /** True when the server says this version is too old to use. */
    public synchronized boolean updateRequired() {
        return Json.bool(Json.map(Json.map(data.get("user")).get("update")), "required");
    }

    /** What the last report learned: Pro, the caps, the admin's message and whether an update is required. */
    synchronized void learned(Map<String, Object> r) {
        Map<String, Object> u = Json.map(data.get("user"));
        String before = Json.stringify(u);
        u.put("pro", Json.bool(r, "pro"));
        for (String k : new String[]{"max_per_day", "max_per_hour", "announcement", "update"}) {
            if (r.containsKey(k)) {
                u.put(k, r.get(k));
            }
        }
        if (!Json.stringify(u).equals(before)) {
            data.put("user", u);
            save();
        }
    }

    /** For the dashboard (no network). */
    public synchronized Map<String, Object> info() {
        Map<String, Object> u = Json.map(data.get("user"));
        return Json.obj("signed_in", signedIn(), "email", Json.str(data, "email"), "name", Json.str(u, "name"),
                "google", Json.bool(u, "google"), "has_password", !u.containsKey("has_password") || Json.bool(u, "has_password"),
                "google_signin", Json.bool(data, "google_signin"),
                "password_signups", !data.containsKey("password_signups") || Json.bool(data, "password_signups"),
                "pro", Json.bool(u, "pro"), "max_per_day", Json.num(u, "max_per_day", 0),
                "max_per_hour", Json.num(u, "max_per_hour", 0), "announcement", Json.str(u, "announcement"),
                "update", Json.map(u.get("update")), "server", server(),
                "server_built_in", serverBuiltIn(), "checked_at", Json.dbl(data, "checked_at", 0),
                "ai_per_day", Json.num(u, "ai_per_day", 0), "ai_used_today", Json.num(u, "ai_used_today", 0),
                "contact", Json.str(data, "contact"), "signups_open", !data.containsKey("signups_open") || Json.bool(data, "signups_open"));
    }

    public String aiUrl() {
        return server() + "/v1/ai/chat";
    }

    private synchronized void signedInAs(Map<String, Object> reply, String email) {
        data.put("token", Json.str(reply, "token"));
        data.put("email", email.trim().toLowerCase(Locale.ROOT));
        data.put("user", Json.map(reply.get("user")));
        data.put("checked_at", Util.now());
        save();
    }

    private synchronized void forget(boolean all) {
        data.remove("token");
        data.remove("user");
        if (all) {
            data.remove("email");
        }
        save();
    }

    // ------------------------------------------------------------------ requests
    public Map<String, Object> signUp(String email, String password, String name) {
        Map<String, Object> r = call("POST", "/v1/auth/signup", Json.obj("email", email, "password", password, "name", name,
                "device", device), false);
        signedInAs(r, email);
        return info();
    }

    public Map<String, Object> signIn(String email, String password) {
        Map<String, Object> r = call("POST", "/v1/auth/login", Json.obj("email", email, "password", password, "device", device), false);
        signedInAs(r, email);
        return info();
    }

    public void signOut() {
        if (signedIn()) {
            try {
                call("POST", "/v1/auth/logout", Json.obj(), true);
            } catch (RuntimeException e) {
                // signed out on this phone anyway
            }
        }
        forget(false);
    }

    /** Your account as the server has it now (Pro, limits). Throws AuthError when signed out, NetError when offline. */
    public Map<String, Object> refresh() {
        Map<String, Object> r = call("GET", "/v1/me", null, true);
        synchronized (this) {
            data.put("user", Json.map(r.get("user")));
            data.put("checked_at", Util.now());
            save();
        }
        return Json.map(r.get("user"));
    }

    /** The contact address and whether new accounts are open (shown on the sign-up screen). */
    public Map<String, Object> serverInfo() {
        Map<String, Object> r = call("GET", "/v1/config", null, false);
        synchronized (this) {
            data.put("contact", Json.str(r, "contact_email"));
            data.put("signups_open", Json.bool(r, "signups_open"));
            data.put("google_signin", Json.bool(r, "google_signin"));
            data.put("password_signups", !r.containsKey("password_signups") || Json.bool(r, "password_signups"));
            save();
        }
        return r;
    }

    /** Deletes the account: its password, or for an account made with Google its email typed to confirm. */
    public void delete(String password, String confirm) {
        call("POST", "/v1/me/delete", Json.obj("password", password, "confirm", confirm), true);
        forget(true);
    }

    // ------------------------------------------------------------------ Sign in with Google
    /** Step 1: Google's page, to open in the browser. A PKCE verifier stays on this phone (in account.json, so it
     *  survives the trip to the browser): the code Google's page leads back with is worth nothing without it. */
    public String googleStart() {
        byte[] raw = new byte[48];
        new java.security.SecureRandom().nextBytes(raw);
        String verifier = b64url(raw);
        String challenge;
        try {
            challenge = b64url(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(Util.UTF8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        synchronized (this) {
            data.put("google_verifier", verifier);
            save();
        }
        Map<String, Object> r = call("POST", "/v1/auth/google/start", Json.obj("challenge", challenge, "device", device), false);
        return Json.str(r, "url");
    }

    /** An account made with an email and a password adds Google: Google's page, to open in the browser. */
    public String googleLink() {
        return Json.str(call("POST", "/v1/me/google/link", Json.obj(), true), "url");
    }

    /** One Google sign-in finishes at a time (the link and the app coming back can arrive together). */
    private final Object googleLock = new Object();

    /** Step 2: back from Google's page (autoapply://auth?code=...): the code and the verifier make a session. */
    public Map<String, Object> googleFinish(String code) {
        synchronized (googleLock) {
            String verifier;
            synchronized (this) {
                verifier = Json.str(data, "google_verifier");
            }
            if (verifier.isEmpty()) {
                if (signedIn()) {
                    return info();      // finished already, when the app came back
                }
                throw new ServerError(400, "Tap Continue with Google again.", "bad_code");
            }
            Map<String, Object> r;
            try {
                r = call("POST", "/v1/auth/google/finish", Json.obj("code", code, "verifier", verifier, "device", device), false);
            } finally {
                synchronized (this) {
                    data.remove("google_verifier");
                    save();
                }
            }
            signedInAs(r, Json.str(Json.map(r.get("user")), "email"));
            return info();
        }
    }

    /** Back in the app without the link (the browser did not open the app): the verifier alone finishes the sign-in it
     *  started. Null while Google's page is not done, or when no sign-in waits. */
    public Map<String, Object> googleClaim() {
        synchronized (googleLock) {
            String verifier;
            synchronized (this) {
                verifier = Json.str(data, "google_verifier");
            }
            if (verifier.isEmpty() || signedIn()) {
                return null;
            }
            Map<String, Object> r;
            try {
                r = call("POST", "/v1/auth/google/claim", Json.obj("verifier", verifier, "device", device), false);
            } catch (ServerError e) {
                synchronized (this) {   // too old: start again
                    data.remove("google_verifier");
                    save();
                }
                throw e;
            }
            if (Json.bool(r, "pending")) {
                return null;
            }
            synchronized (this) {
                data.remove("google_verifier");
                save();
            }
            signedInAs(r, Json.str(Json.map(r.get("user")), "email"));
            return info();
        }
    }

    static String b64url(byte[] raw) {
        return Util.base64(raw).replace('+', '-').replace('/', '_').replaceAll("=+$", "");
    }

    public Map<String, Object> report(Map<String, Object> body) {
        return call("POST", "/v1/report", body, true);
    }

    Map<String, Object> call(String method, String path, Object body, boolean auth) {
        String base = server();
        if (base.isEmpty()) {
            throw new ServerError(0, "Type the server address first.", "no_server");
        }
        String token = token();
        if (auth && token.isEmpty()) {
            throw new AuthError("Sign in to your account first.");
        }
        int code;
        String text;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            try {
                c.setRequestMethod(method);
                c.setConnectTimeout(20000);
                c.setReadTimeout(40000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "AutoApply-Android/" + appVersion);
                c.setRequestProperty("X-App-Flavor", flavor);
                c.setRequestProperty("X-App-Version", String.valueOf(versionCode));
                if (auth) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                }
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    OutputStream out = c.getOutputStream();
                    try {
                        out.write(Json.stringify(body).getBytes(Util.UTF8));
                    } finally {
                        out.close();
                    }
                }
                code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                text = in == null ? "" : new String(Util.readAll(in), Util.UTF8);
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            throw new NetError("Could not reach the server (" + Util.shortError(e, 80) + ")");
        }
        Object parsed = Json.tryParse(text);
        Map<String, Object> d = parsed instanceof Map ? Json.map(parsed) : Json.obj();
        if (code == 401 && auth) {
            forget(false);
            throw new AuthError(errorMessage(d, "You were signed out of your account. Sign in again."));
        }
        if (code >= 400 || (d.containsKey("ok") && !Json.bool(d, "ok"))) {
            Object err = d.get("error");
            throw new ServerError(code, errorMessage(d, "The server answered HTTP " + code + "."),
                    err instanceof Map ? Json.str(Json.map(err), "code") : "");
        }
        if (!(parsed instanceof Map)) {
            throw new ServerError(code, "The server sent an unexpected answer. Check the server address.", "bad_reply");
        }
        return d;
    }

    static String errorMessage(Map<String, Object> d, String fallback) {
        Object err = d.get("error");
        if (err instanceof Map && !Json.str(Json.map(err), "message").isEmpty()) {
            return Json.str(Json.map(err), "message");
        }
        if (err instanceof String && !((String) err).isEmpty()) {
            return (String) err;
        }
        return fallback;
    }
}
