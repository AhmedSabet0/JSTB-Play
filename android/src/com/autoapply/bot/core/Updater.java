package com.autoapply.bot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versions, and the development build's updates from GitHub releases. The Google Play build is updated by Google Play. */
public final class Updater {

    private Updater() {
    }

    /** Compare versions like "1.10.2" and "v1.9" (numbers only). */
    public static int compare(String a, String b) {
        List<Long> x = numbers(a);
        List<Long> y = numbers(b);
        for (int k = 0; k < Math.max(x.size(), y.size()); k++) {
            long p = k < x.size() ? x.get(k) : 0;
            long q = k < y.size() ? y.get(k) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static List<Long> numbers(String v) {
        List<Long> out = new ArrayList<Long>();
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : v);
        while (m.find() && out.size() < 6) {
            try {
                out.add(Long.parseLong(m.group()));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out;
    }

    /** The repo the development build updates from, unless Settings names another. */
    public static final String DEFAULT_REPO = "AhmedSabet0/JSTB-Play";

    /** Which repo to ask: Settings' "owner/name" (or a GitHub link to it); the old default meant this repo. */
    static String repoOf(String source, String fallback) {
        String s = source == null ? "" : source.trim().replaceAll("(?i)^https?://github\\.com/", "").replaceAll("/+$", "");
        if (s.isEmpty() || s.equalsIgnoreCase("AhmedSabet0/JSTB")) {
            s = fallback == null || fallback.isEmpty() ? DEFAULT_REPO : fallback;
        }
        return s.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") ? s : "";
    }

    /** The newest GitHub release of the development build: {ok, available, version, url, notes, message, checked_at}.
     *  Its app file is the release's APK with "dev" in its name (signed with the development key). */
    public static Map<String, Object> check(String source, Object current) {
        String repo = repoOf(source, "");
        double now = Util.now();
        if (repo.isEmpty()) {
            return Json.obj("ok", false, "available", false, "checked_at", now,
                    "message", "The update source should look like owner/name.");
        }
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "JSTB-updater");
            int code = c.getResponseCode();
            if (code == 404) {
                return Json.obj("ok", false, "available", false, "checked_at", now, "message", "No release in " + repo + " yet.");
            }
            if (code != 200) {
                return Json.obj("ok", false, "available", false, "checked_at", now,
                        "message", "GitHub answered " + code + ". Trying again later.");
            }
            String body;
            java.io.InputStream in = c.getInputStream();
            try {
                body = new String(Util.readAll(in), Util.UTF8);
            } finally {
                in.close();
            }
            Map<String, Object> rel = Json.map(Json.tryParse(body));
            String version = Json.str(rel, "tag_name").replaceFirst("^[vV]", "");
            String url = "";
            for (Object o : Json.arr(rel.get("assets"))) {
                Map<String, Object> a = Json.map(o);
                String name = Json.str(a, "name").toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".apk") && name.contains("dev")) {
                    url = Json.str(a, "browser_download_url");
                    break;
                }
            }
            String cur = current == null ? "" : String.valueOf(current);
            boolean newer = !version.isEmpty() && compare(version, cur) > 0;
            if (newer && url.isEmpty()) {
                return Json.obj("ok", false, "available", false, "checked_at", now, "version", version,
                        "message", "Version " + version + " has no development app file (JSTB-dev.apk).");
            }
            return Json.obj("ok", true, "available", newer, "version", version, "url", url,
                    "notes", Util.cut(Json.str(rel, "body"), 1500), "checked_at", now,
                    "message", newer ? "Version " + version + " is out." : "You have the newest version (" + cur + ").");
        } catch (java.io.IOException e) {
            return Json.obj("ok", false, "available", false, "checked_at", now, "message", "No connection to GitHub. Trying again later.");
        }
    }
}
