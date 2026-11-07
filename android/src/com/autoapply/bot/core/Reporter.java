package com.autoapply.bot.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.autoapply.bot.core.Util.log;

/** Keeps the server up to date: what the app is doing (the activity lines you see in the app), each job it applied to
 *  or could not finish, and the jobs you track yourself. While auto apply runs it reports every half minute (and right
 *  after each application) and stops the run when the server says so (the admin pressed Stop, or turned Pro off).
 *  Otherwise it sends what is new every couple of minutes. What cannot be sent waits in report_queue.json. */
public final class Reporter implements Store.Listener {
    static final List<String> KEPT = Arrays.asList("applied", "error", "stuck", "needs_answer");
    /** Seconds between reports while auto apply runs (tests use less). */
    public static volatile double every = 30;
    /** Seconds between reports otherwise, when there is something to send. */
    public static volatile double idleEvery = 120;
    /** Without any contact for this long a run stops: the admin must be able to stop it. */
    public static volatile double giveUpAfter = 600;

    private final Engine engine;
    private final Account account;
    private final File queueFile;
    private final List<Map<String, Object>> queue = new ArrayList<Map<String, Object>>();
    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean running;
    private volatile String mode = "";
    private boolean startPending;
    private boolean endPending;
    private double lastOk;
    private long logSeq;

    Reporter(Engine engine, Account account) {
        this.engine = engine;
        this.account = account;
        this.queueFile = new File(engine.config.dir, "report_queue.json");
        for (Object o : Json.arr(Json.tryParse(Util.readText(queueFile)))) {  // not sent before the app closed
            if (o instanceof Map) {
                queue.add(Json.map(o));
            }
        }
    }

    /** Starts the background sender (once). */
    void ensureStarted() {
        synchronized (lock) {
            if (thread != null && thread.isAlive()) {
                return;
            }
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "report");
            thread.setDaemon(true);
            thread.start();
        }
    }

    // ------------------------------------------------------------------ what to send
    void runStarted(String runMode) {
        synchronized (lock) {
            running = true;
            mode = runMode;
            startPending = true;
            endPending = false;
            retryAt = 0;
            lastOk = Util.now();
            engine.store.listener = this;
            lock.notifyAll();
        }
        ensureStarted();
    }

    void runEnded() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            endPending = true;
            retryAt = 0;
            if (engine.store.listener == this) {
                engine.store.listener = null;
            }
            lock.notifyAll();
        }
    }

    boolean runActive() {
        return running;
    }

    /** A job outcome of auto apply. */
    @Override
    public void onJob(Map<String, Object> job) {
        String status = Json.str(job, "status");
        if (!KEPT.contains(status)) {
            return;
        }
        add(Json.obj("job_id", Json.str(job, "id"), "title", Util.cut(Json.str(job, "title"), 200),
                "company", Util.cut(Json.str(job, "company"), 120), "status", status, "score", job.get("score"),
                "at", Json.dbl(job, "ts", Util.now()), "channel", "auto", "url", Json.str(job, "url")),
                "applied".equals(status));
    }

    /** A job you applied to yourself (with the app's help), or a new stage for one: interview, offer... */
    public void track(Map<String, Object> event) {
        add(event, true);
    }

    private void add(Map<String, Object> event, boolean now) {
        synchronized (lock) {
            queue.add(event);
            while (queue.size() > 500) {
                queue.remove(0);
            }
            Util.writeText(queueFile, Json.stringify(queue));
            if (now) {
                urgent = true;
                retryAt = 0;
                lock.notifyAll();
            }
        }
        ensureStarted();
    }

    // ------------------------------------------------------------------ the sender
    private boolean urgent;
    private double retryAt;

    private void loop() {
        double lastSend = 0;
        while (true) {
            boolean go;
            synchronized (lock) {
                double interval = running ? every : idleEvery;
                boolean pressing = startPending || endPending || urgent;
                double until = pressing ? retryAt : lastSend + interval;
                long ms = (long) ((until - Util.now()) * 1000);
                if (ms > 0) {
                    try {
                        lock.wait(ms);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;  // woken early (something new) or the time came: look again
                }
                boolean due = Util.now() - lastSend >= interval - 0.05;
                go = startPending || endPending || urgent || (due && (running || !queue.isEmpty() || hasNewLog()));
                urgent = false;
                if (!go) {
                    lastSend = Util.now();  // nothing to send this round
                }
            }
            if (!go) {
                continue;
            }
            if (account.signedIn()) {
                if (!send()) {
                    synchronized (lock) {
                        retryAt = Util.now() + Math.min(every, 30);
                    }
                }
            } else {
                synchronized (lock) {
                    startPending = false;
                    endPending = false;
                }
            }
            lastSend = Util.now();
        }
    }

    private boolean hasNewLog() {
        return !Json.arr(Util.logSince(logSeq).get("lines")).isEmpty();
    }

    /** One report. False when the server could not be reached. */
    boolean send() {
        List<Map<String, Object>> events;
        boolean isRunning;
        boolean isStart;
        boolean isEnd;
        String runMode;
        synchronized (lock) {
            events = new ArrayList<Map<String, Object>>(queue);
            isRunning = running;
            isStart = startPending;
            isEnd = endPending;
            runMode = mode;
        }
        Map<String, Object> since = Util.logSince(logSeq);
        List<Object> lines = Json.arr(since.get("lines"));
        if (lines.size() > 300) {
            lines = new ArrayList<Object>(lines.subList(0, 300));
        }
        List<Object> log = new ArrayList<Object>();
        long upTo = logSeq;
        for (Object o : lines) {
            Map<String, Object> l = Json.map(o);
            log.add(Json.obj("at", Json.dbl(l, "t", Util.now()), "text", Util.cut(Json.str(l, "text"), 600)));
            upTo = Math.max(upTo, (long) Json.num(l, "seq", 0));
        }
        Map<String, Object> st = Util.getStatus();
        int[] counts = Bot.appliedCounts(engine.store.stateCopy(), Util.now());
        Map<String, Object> body = Json.obj("start", isRunning && isStart, "running", isRunning, "mode", runMode,
                "message", Util.cut(isRunning || isEnd ? Json.str(st, "message") : "", 200), "applied_today", counts[1],
                "applied_total", engine.store.count("applied"), "app_version", Account.appVersion, "device", Account.device,
                "events", events, "log", log);
        try {
            Map<String, Object> r = account.report(body);
            lastOk = Util.now();
            logSeq = upTo;
            synchronized (lock) {
                queue.subList(0, Math.min(events.size(), queue.size())).clear();
                if (queue.isEmpty()) {
                    queueFile.delete();
                } else {
                    Util.writeText(queueFile, Json.stringify(queue));
                }
                if (isStart) {
                    startPending = false;
                }
                if (isEnd) {
                    endPending = false;
                }
            }
            account.learned(r);
            if (isRunning && running && Json.bool(r, "stop")) {
                String why = Json.str(r, "reason");
                stopRun(why.isEmpty() ? "Stopped by the admin." : why);
            }
            return true;
        } catch (Account.AuthError e) {
            synchronized (lock) {
                startPending = false;
                endPending = false;
            }
            if (isRunning && running) {
                stopRun("You were signed out of your JSTB account. Sign in again in Settings.");
            }
            return true;  // nothing to retry until you sign in again
        } catch (RuntimeException e) {
            if (isRunning && running && Util.now() - lastOk > giveUpAfter) {
                stopRun("Lost contact with the JSTB server for " + Math.round(giveUpAfter / 60) + " minutes. Start again "
                        + "when the internet is back.");
            }
            return false;
        }
    }

    private void stopRun(String why) {
        if (Util.stopReason.isEmpty()) {
            Util.stopReason = why;
            log(why);
        }
        engine.stop();
    }
}
