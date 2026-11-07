package com.autoapply.bot.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.autoapply.bot.core.Util.log;

/** Starts and stops the bot on its own thread and runs one session: sign in, search, apply, brief. */
public final class Engine {
    /** A setup problem with a message you can act on. */
    public static class SetupError extends RuntimeException {
        public SetupError(String m) {
            super(m);
        }
    }

    public final Platform platform;
    public final Config config;
    public final Store store;
    public final Questions.Waiting waiting;
    public final Profile profile;
    public final Account account;
    public final File summaryFile;
    public final File briefFile;
    public final File debugZip;
    private Thread thread;
    private Bot.Options options;
    /** Keeps the server up to date (activity, applications, stops from the admin). */
    public final Reporter reporter;
    /** Jobs you apply to yourself with the app's help, and their stages. */
    public final Copilot copilot;
    /** Your data, the same in your account: every phone you sign in on, and your Google Drive. */
    public final Sync sync;
    public volatile Session session;

    public Engine(Platform platform, File dataDir) {
        this.platform = platform;
        this.config = new Config(dataDir);
        this.store = new Store(dataDir);
        this.waiting = new Questions.Waiting(dataDir);
        this.profile = new Profile(dataDir);
        this.profile.store = store;
        this.account = new Account(dataDir);
        this.reporter = new Reporter(this, account);
        this.copilot = new Copilot(this, dataDir);
        this.sync = new Sync(this, dataDir);
        if (account.signedIn()) {
            reporter.ensureStarted();
        }
        this.summaryFile = new File(dataDir, "last_summary.json");
        this.briefFile = new File(config.logDir, "last_session.txt");
        this.debugZip = new File(config.logDir, "debug.zip");
        Util.logFile = new File(config.logDir, "bot.log");
        Map<String, String> cfg = config.load();
        Questions.askMode = Questions.askModeOf(cfg.get("NEW_QUESTIONS"));
        Questions.askTimeoutMin = Config.cfgInt(cfg, "ASK_TIMEOUT_MIN", 0, 1440);
    }

    // ------------------------------------------------------------------ the bot thread
    public synchronized boolean running() {
        return thread != null && thread.isAlive();
    }

    public synchronized Bot.Options options() {
        return running() ? options : null;
    }

    /** Start a session. */
    public synchronized String start(final Bot.Options opts) {
        return "1-Tap Assisted Apply is active. Tap any job in Your Jobs to review and apply.";
    }

    public void stop() {
        if (running()) {
            Util.STOP.set(true);
            Util.setStatus("message", "Stopping after the current step...");
        }
    }

    public void signInDone() {
    }

    public void join(long millis) throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            t.join(millis);
        }
    }

    // ------------------------------------------------------------------ one session
    void checkAi(Ai ai) {
        try {
            ai.ping();
            log("AI connected" + (ai.servedBy.isEmpty() ? "" : " (model: " + ai.servedBy + ")") + ".");
        } catch (Ai.FatalAIError e) {
            log("AI error: " + e.getMessage());
            throw new SetupError(e.getMessage());
        } catch (Util.StopRequested e) {
            throw e;
        } catch (RuntimeException e) {
            log("Could not reach the AI: " + Util.shortError(e));
            throw new SetupError("Could not reach the AI service. Check the internet connection and start again.");
        }
    }

    public static final String NOT_ACTIVE = "Auto apply needs Pro on your account. Jobs you apply to with the app's help work without it.";

    // ------------------------------------------------------------------ ready to send: your yes first
    /** Applications filled up to Submit that wait for your yes, and the ones you said yes to. */
    public List<Map<String, Object>> ready() {
        return store.ready();
    }

    /** You said yes (Apply, or Apply all with ids null): the bot sends them; when it is not running, a short run starts
     *  that only sends them. Returns how many. */
    public synchronized int approve(List<String> ids) {
        return 0;
    }

    /** You said no (Skip): not sent. ids null = every ready one. */
    public int skipReady(List<String> ids) {
        int n = store.changeStatus(ids == null ? store.idsWithStatus("ready") : ids, "ready", "user_skipped");
        readyChanged();
        return n;
    }

    /** The notification follows the applications that wait for you. */
    void readyChanged() {
        try {
            platform.onReady(store.ready());
        } catch (RuntimeException e) {
            log("Could not show the notification: " + Util.shortError(e, 80));
        }
    }

    /** Signed in, and Pro is on. Returns the problem, or null. */
    String checkAccount() {
        if (!account.signedIn()) {
            return "Sign in to your JSTB account first.";
        }
        try {
            Map<String, Object> me = account.refresh();
            return Json.bool(me, "pro") ? null : NOT_ACTIVE;
        } catch (Account.AuthError e) {
            return "You were signed out of your JSTB account. Sign in again in Settings.";
        } catch (Account.ServerError e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            log("Could not reach the JSTB server: " + Util.shortError(e));
            return "Could not reach the JSTB server. Check the internet connection and start again.";
        }
    }

    /** The AI, through your account on the server. */
    public Ai newAi(String cvText, String notes, String purpose) {
        Ai ai = new Ai(account.token(), "", cvText, notes);
        ai.url = account.aiUrl();
        ai.purpose = purpose;
        return ai;
    }

    /** Your setup answers and your extra notes, for the AI. */
    public String notesForAi() {
        String setup = profile.notes(Util.now());
        String about = config.about();
        return (setup + (setup.isEmpty() || about.isEmpty() ? "" : "\n") + about).trim();
    }

    List<String> pickSearch(Map<String, String> cfg, Ai ai, StringBuilder locationOut) {
        List<String> keywords = Util.splitList(cfg.get("KEYWORDS"));
        String location = cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION").trim();
        if (!keywords.isEmpty() && !location.isEmpty()) {
            locationOut.append(location);
            return keywords;
        }
        log("Asking the AI which jobs to search for, based on your CV...");
        StringBuilder aiLocation = new StringBuilder();
        List<String> aiKeywords = ai.suggestSearch(aiLocation);
        if (keywords.isEmpty()) {
            keywords = aiKeywords;
        }
        if (location.isEmpty()) {
            location = aiLocation.toString();
        }
        if (keywords.isEmpty()) {
            throw new SetupError("The AI did not suggest any keywords. Add job titles in Settings and start again.");
        }
        log("AI picked: " + Util.join(", ", keywords) + (location.isEmpty() ? "" : " in " + location)
                + " (set your own job titles and location in Settings).");
        locationOut.append(location);
        return keywords;
    }

    public void run(Bot.Options args) {
        Util.setStatus("state", "idle", "message", "1-Tap Assisted Apply is ready.", "job", null);
    }

    static void zipDir(File dir, File zip) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip));
        try {
            byte[] buf = new byte[16384];
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(f.getName()));
                InputStream in = new FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }
}
