package com.autoapply.bot.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared helpers: live log and status (read by the dashboard), stop requests, waiting, formatting, files. */
public final class Util {
    private Util() {
    }

    public static final Charset UTF8 = Charset.forName("UTF-8");
    public static final Random RANDOM = new Random();

    /** Test hook: scales every human-like delay and pacing wait (1 = real time). */
    public static volatile double speed = 1.0;

    // ------------------------------------------------------------------ exceptions
    /** The browser page is gone. */
    public static class BrowserClosed extends RuntimeException {
        public BrowserClosed(String m) {
            super(m);
        }
    }

    /** You pressed Stop. */
    public static class StopRequested extends RuntimeException {
        public StopRequested() {
            super("Stopped");
        }
    }

    // ------------------------------------------------------------------ stop requests
    public static final AtomicBoolean STOP = new AtomicBoolean(false);
    /** Why the run is stopping when it was not you: "Stopped by the admin." (set before STOP). */
    public static volatile String stopReason = "";
    private static final ThreadLocal<int[]> SHIELD = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    /** Ignore Stop until unshield() (e.g. right after Submit, so the result is still recorded). */
    public static void shield() {
        SHIELD.get()[0]++;
    }

    public static void unshield() {
        SHIELD.get()[0]--;
    }

    public static void checkStop() {
        if (STOP.get() && SHIELD.get()[0] <= 0) {
            throw new StopRequested();
        }
    }

    // ------------------------------------------------------------------ live log
    private static final Object LOG_LOCK = new Object();
    private static final ArrayDeque<Map<String, Object>> LOG_LINES = new ArrayDeque<Map<String, Object>>();
    private static long logSeq = 0;
    public static volatile File logFile;
    public static volatile boolean echo = true;

    public static void log(String msg) {
        String m = msg == null ? "" : msg;
        Date now = new Date();
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(now);
        if (echo) {
            System.out.println(m.isEmpty() ? "" : "[" + t + "] " + m);
        }
        synchronized (LOG_LOCK) {
            logSeq++;
            LOG_LINES.addLast(Json.obj("seq", logSeq, "t", t, "text", m));
            while (LOG_LINES.size() > 3000) {
                LOG_LINES.removeFirst();
            }
            File f = logFile;
            if (f != null) {
                try {
                    if (f.length() > 3_000_000L) {
                        File old = new File(f.getPath() + ".1");
                        old.delete();
                        f.renameTo(old);
                    }
                    Writer w = new OutputStreamWriter(new FileOutputStream(f, true), UTF8);
                    try {
                        w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now) + "  " + m + "\n");
                    } finally {
                        w.close();
                    }
                } catch (IOException e) {
                    // logging must never stop the bot
                }
            }
        }
    }

    /** Log lines after `since`, and the last sequence number. */
    public static Map<String, Object> logSince(long since) {
        List<Object> lines = new ArrayList<Object>();
        long last;
        synchronized (LOG_LOCK) {
            for (Map<String, Object> l : LOG_LINES) {
                if (Json.num(l, "seq", 0) > since) {
                    lines.add(l);
                }
            }
            last = logSeq;
        }
        if (lines.size() > 800) {
            lines = new ArrayList<Object>(lines.subList(lines.size() - 800, lines.size()));
        }
        return Json.obj("lines", lines, "next", last);
    }

    // ------------------------------------------------------------------ live status
    private static final Object STATUS_LOCK = new Object();
    private static final Map<String, Object> STATUS = new LinkedHashMap<String, Object>();

    static {
        STATUS.put("state", "idle");
        STATUS.put("message", "Ready");
        STATUS.put("job", null);
        STATUS.put("next_slot_at", 0);
        STATUS.put("updated", 0);
    }

    public interface StatusListener {
        void onStatus(Map<String, Object> status);
    }

    public static volatile StatusListener statusListener;

    public static void setStatus(Object... kv) {
        Map<String, Object> copy;
        synchronized (STATUS_LOCK) {
            for (int k = 0; k + 1 < kv.length; k += 2) {
                STATUS.put(String.valueOf(kv[k]), kv[k + 1]);
            }
            STATUS.put("updated", now());
            copy = new LinkedHashMap<String, Object>(STATUS);
        }
        StatusListener l = statusListener;
        if (l != null) {
            try {
                l.onStatus(copy);
            } catch (RuntimeException e) {
                // a notification problem must not stop the bot
            }
        }
    }

    public static Map<String, Object> getStatus() {
        synchronized (STATUS_LOCK) {
            return new LinkedHashMap<String, Object>(STATUS);
        }
    }

    // ------------------------------------------------------------------ waiting
    /** Seconds since 1970, like Python's time.time(). */
    public static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    public static void realSleep(double seconds) {
        double left = Math.max(0, seconds);
        while (left > 0) {
            checkStop();
            double chunk = Math.min(left, 0.5);
            try {
                Thread.sleep((long) (chunk * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StopRequested();
            }
            left -= chunk;
        }
        checkStop();
    }

    /** A human-like or pacing delay. */
    public static void sleep(double seconds) {
        realSleep(Math.max(0, seconds) * speed);
    }

    public static void pause(double low, double high) {
        sleep(low + RANDOM.nextDouble() * (high - low));
    }

    public static double uniform(double low, double high) {
        return low + RANDOM.nextDouble() * (high - low);
    }

    public interface Check<T> {
        T get() throws Exception;
    }

    /** Call fn until it returns something truthy or `timeout` seconds pass. Returns the value or null. */
    public static <T> T waitUntil(Check<T> fn, double timeout) {
        return waitUntil(fn, timeout, 0.35);
    }

    public static <T> T waitUntil(Check<T> fn, double timeout, double interval) {
        long end = System.nanoTime() + (long) (timeout * 1e9);
        while (true) {
            checkStop();
            T value;
            try {
                value = fn.get();
            } catch (BrowserClosed e) {
                throw e;
            } catch (StopRequested e) {
                throw e;
            } catch (RuntimeException e) {
                value = null;
            } catch (Exception e) {
                value = null;
            }
            if (Json.truthy(value)) {
                return value;
            }
            if (System.nanoTime() >= end) {
                return null;
            }
            realSleep(interval);
        }
    }

    // ------------------------------------------------------------------ formatting
    public static String fmtTime(double ts) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date((long) (ts * 1000)));
    }

    public static String fmtWhen(double ts) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis((long) (ts * 1000));
        Calendar b = Calendar.getInstance();
        boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm" : "EEE dd MMM, HH:mm", Locale.US).format(a.getTime());
    }

    public static String fmtDuration(double seconds) {
        long s = Math.round(Math.max(0, seconds));
        long h = s / 3600;
        long m = (s % 3600) / 60;
        s = s % 60;
        if (h > 0) {
            return String.format(Locale.US, "%dh %02dm", h, m);
        }
        if (m > 0) {
            return String.format(Locale.US, "%dm %02ds", m, s);
        }
        return s + "s";
    }

    public static String shortError(Throwable e) {
        return shortError(e, 200);
    }

    public static String shortError(Throwable e, int limit) {
        String text = e.getMessage();
        if (text == null || text.trim().isEmpty()) {
            text = e.getClass().getSimpleName();
        }
        String first = text.trim().split("\n")[0].trim();
        return first.length() > limit ? first.substring(0, limit) : first;
    }

    public static List<String> splitList(String value) {
        List<String> out = new ArrayList<String>();
        for (String x : String.valueOf(value == null ? "" : value).replace("\n", ";").split(";")) {
            if (!x.trim().isEmpty()) {
                out.add(x.trim());
            }
        }
        return out;
    }

    public static String join(String sep, List<?> items) {
        StringBuilder b = new StringBuilder();
        for (Object o : items) {
            if (b.length() > 0) {
                b.append(sep);
            }
            b.append(o);
        }
        return b.toString();
    }

    public static String cut(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    public static String normKey(String question) {
        return String.valueOf(question == null ? "" : question).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------ files
    public static String readText(File f) {
        try {
            return new String(readBytes(f), UTF8);
        } catch (IOException e) {
            return null;
        }
    }

    public static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Write via a temporary file and rename, so a crash never leaves half a file. */
    public static void writeText(File f, String text) {
        writeBytes(f, text.getBytes(UTF8));
    }

    public static void writeBytes(File f, byte[] data) {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        File tmp = new File(f.getPath() + ".tmp");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) {
                    throw new IOException("rename failed");
                }
            }
        } catch (IOException e) {
            try {
                OutputStream out = new FileOutputStream(f);
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
            } catch (IOException e2) {
                log("Could not save " + f.getName() + ": " + e2.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ base64 (java.util.Base64 needs Android 8)
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64(byte[] data) {
        StringBuilder b = new StringBuilder((data.length + 2) / 3 * 4);
        for (int k = 0; k < data.length; k += 3) {
            int n = (data[k] & 0xff) << 16;
            if (k + 1 < data.length) {
                n |= (data[k + 1] & 0xff) << 8;
            }
            if (k + 2 < data.length) {
                n |= data[k + 2] & 0xff;
            }
            b.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]);
            b.append(k + 1 < data.length ? B64[(n >> 6) & 63] : '=');
            b.append(k + 2 < data.length ? B64[n & 63] : '=');
        }
        return b.toString();
    }

    public static byte[] unbase64(String s) {
        int[] rev = new int[128];
        java.util.Arrays.fill(rev, -1);
        for (int k = 0; k < B64.length; k++) {
            rev[B64[k]] = k;
        }
        rev['-'] = 62;
        rev['_'] = 63;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4);
        int buf = 0;
        int bits = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '=') {
                break;
            }
            if (c >= 128 || rev[c] < 0) {
                continue;
            }
            buf = (buf << 6) | rev[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
