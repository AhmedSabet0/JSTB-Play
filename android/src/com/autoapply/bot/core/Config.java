package com.autoapply.bot.core;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Settings (config.json), the "About me" notes and the CV, all in the app's data folder. */
public final class Config {
    public static final String[][] DEFAULTS = {
        {"CV_FILE", ""},
        {"CV_NAME", ""},
        {"KEYWORDS", ""},
        {"LOCATION", ""},
        {"DATE_POSTED", "week"},
        {"WORK_TYPE", ""},
        {"EXPERIENCE", ""},
        {"PAGES_PER_KEYWORD", "3"},
        {"SEARCH_EVERY_MIN", "10"},   // the wait between rounds: short, so it keeps going
        {"PER_HOUR", "15"},
        {"PER_DAY", "60"},
        {"MIN_SCORE", "6"},
        {"SKIP_COMPANIES", ""},
        {"SKIP_TITLE_WORDS", ""},
        {"NEW_QUESTIONS", "unsure"},   // the AI answers what it is sure about, you the rest
        {"ASK_TIMEOUT_MIN", "10"},
        {"UPDATE_SOURCE", "AhmedSabet0/JSTB-Play"},
        {"DAILY_GOAL", "10"},
        {"AUTO_UPDATE", "1"},
        {"APPROVE_FIRST", "1"},
        {"SETUP_DONE", ""},
    };

    public static final String ABOUT_TEMPLATE = "# Extra info for the AI (optional, but it makes the answers better).\n"
            + "# Used for application questions that your CV does not answer. One fact per line.\n"
            + "# Lines starting with # are ignored, and so are empty lines like \"Phone:\".\n\n"
            + "Phone:\nNotice period:\nExpected monthly salary:\nAuthorized to work in:\nWilling to relocate:\n"
            + "Preferred work type (remote / hybrid / on-site):\n\n"
            + "# Years of experience the AI should use for tools that are not clear in your CV, for example:\n"
            + "# Years of experience with Fortinet: 2\n";

    public final File dir;
    public final File configFile;
    public final File aboutFile;
    public final File cvTextFile;
    public final File logDir;
    public final File debugDir;
    private final Object lock = new Object();

    public Config(File dir) {
        this.dir = dir;
        dir.mkdirs();
        configFile = new File(dir, "config.json");
        aboutFile = new File(dir, "about_me.txt");
        cvTextFile = new File(dir, "cv_text.txt");
        logDir = new File(dir, "logs");
        debugDir = new File(logDir, "debug");
        logDir.mkdirs();
    }

    public static String dflt(String key) {
        for (String[] kv : DEFAULTS) {
            if (kv[0].equals(key)) {
                return kv[1];
            }
        }
        return "";
    }

    public Map<String, String> load() {
        synchronized (lock) {
            Map<String, String> cfg = new LinkedHashMap<String, String>();
            for (String[] kv : DEFAULTS) {
                cfg.put(kv[0], kv[1]);
            }
            Map<String, Object> saved = Json.map(Json.tryParse(Util.readText(configFile)));
            // Versions before 2.0 kept your own OpenRouter key here. The server holds the key now: remove it.
            if (saved.remove("OPENROUTER_API_KEY") != null | saved.remove("MODEL") != null) {
                Util.writeText(configFile, Json.pretty(saved));
            }
            for (Map.Entry<String, Object> e : saved.entrySet()) {
                cfg.put(e.getKey().trim().toUpperCase(Locale.ROOT), e.getValue() == null ? "" : String.valueOf(e.getValue()).trim());
            }
            if (!aboutFile.exists()) {
                Util.writeText(aboutFile, ABOUT_TEMPLATE);
            }
            return cfg;
        }
    }

    public void set(String key, String value) {
        synchronized (lock) {
            Map<String, Object> saved = Json.map(Json.tryParse(Util.readText(configFile)));
            saved.put(key, value == null ? "" : value);
            Util.writeText(configFile, Json.pretty(saved));
        }
    }

    public static int cfgInt(Map<String, String> cfg, String key, int low, int high) {
        int value;
        String raw = cfg.get(key);
        try {
            value = (int) Double.parseDouble(raw == null || raw.trim().isEmpty() ? dflt(key) : raw.trim());
        } catch (NumberFormatException e) {
            value = (int) Double.parseDouble(dflt(key));
        }
        return Math.max(low, Math.min(high, value));
    }

    public String about() {
        String text = Util.readText(aboutFile);
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || l.matches("^[^:]+:\\s*$")) {
                continue;
            }
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(l);
        }
        return b.toString();
    }

    public File cvFile(Map<String, String> cfg) {
        String name = cfg.get("CV_FILE");
        if (name != null && !name.isEmpty()) {
            File f = new File(dir, name);
            if (f.isFile()) {
                return f;
            }
        }
        for (String cand : new String[]{"cv.pdf", "cv.txt"}) {
            File f = new File(dir, cand);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** Keeps a CV file and the text read from it (chosen on this phone, or from your account). */
    public File saveCv(String name, byte[] raw, String text) {
        String ext = name.toLowerCase(Locale.ROOT).endsWith(".txt") ? ".txt" : ".pdf";
        for (String old : new String[]{"cv.pdf", "cv.txt"}) {
            new File(dir, old).delete();
        }
        File dest = new File(dir, "cv" + ext);
        Util.writeBytes(dest, raw);
        Util.writeText(cvTextFile, text);
        set("CV_FILE", dest.getName());
        set("CV_NAME", name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        return dest;
    }

    /** Signed in with another account: this CV belonged to the one before. */
    public void clearCv() {
        for (String old : new String[]{"cv.pdf", "cv.txt"}) {
            new File(dir, old).delete();
        }
        cvTextFile.delete();
        set("CV_FILE", "");
        set("CV_NAME", "");
    }

    public String cvText() {
        String t = Util.readText(cvTextFile);
        return t == null ? "" : t;
    }

    /** Your CV, for the dashboard. */
    // The screen asks for the status every second or so: the CV text's length is counted again only when the file changes.
    private long cvCharsStamp = Long.MIN_VALUE;
    private int cvChars;

    private int cvChars() {
        synchronized (lock) {
            long stamp = cvTextFile.isFile() ? cvTextFile.lastModified() * 31 + cvTextFile.length() : -1;
            if (stamp != cvCharsStamp) {
                cvChars = cvText().length();
                cvCharsStamp = stamp;
            }
            return cvChars;
        }
    }

    public Map<String, Object> status(Map<String, String> cfg) {
        File cv = cvFile(cfg);
        int chars = cv == null ? 0 : cvChars();
        String cvName = cfg.get("CV_NAME");
        return Json.obj("has_cv", cv != null && cv.isFile() && cv.length() > 0,
                "cv_name", cv == null ? "" : (cvName == null || cvName.isEmpty() ? cv.getName() : cvName), "cv_chars", chars);
    }
}
