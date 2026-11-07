package com.autoapply.bot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.autoapply.bot.R;
import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The app's notifications: running status, questions for you, sign-in needed, and the session brief. */
final class Notifs {
    static final String CH_RUN = "run";
    static final String CH_ASK = "questions";
    static final String CH_BRIEF = "brief";
    static final String CH_READY = "ready";
    static final int ID_RUN = 1;
    static final int ID_ASK = 2;
    static final int ID_BRIEF = 3;
    static final int ID_SIGNIN = 4;
    static final int ID_READY = 6;
    static final String REPLY_KEY = "reply";

    private Notifs() {
    }

    static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel run = new NotificationChannel(CH_RUN, "Job updates", NotificationManager.IMPORTANCE_LOW);
        run.setDescription("Shows job matching and assisted apply updates.");
        run.setShowBadge(false);
        NotificationChannel ask = new NotificationChannel(CH_ASK, "Questions for you", NotificationManager.IMPORTANCE_HIGH);
        ask.setDescription("Application questions the bot needs your answer for, and sign-in requests.");
        NotificationChannel brief = new NotificationChannel(CH_BRIEF, "Session brief", NotificationManager.IMPORTANCE_DEFAULT);
        brief.setDescription("What the bot applied to and what got stuck, when a session ends.");
        NotificationChannel ready = new NotificationChannel(CH_READY, "Applications to send", NotificationManager.IMPORTANCE_HIGH);
        ready.setDescription("Applications filled up to Submit: Apply sends one, Apply all sends them all.");
        NotificationManager m = nm(c);
        m.createNotificationChannel(run);
        m.createNotificationChannel(ask);
        m.createNotificationChannel(brief);
        m.createNotificationChannel(ready);
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c, String channel) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat);
        b.setColor(0xff0a66c2);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(CH_ASK.equals(channel) ? Notification.PRIORITY_HIGH
                    : (CH_RUN.equals(channel) ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT));
            if (CH_ASK.equals(channel)) {
                b.setDefaults(Notification.DEFAULT_ALL);
            }
        }
        return b;
    }

    static int immutable() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    static int mutable() {
        return Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    static PendingIntent openApp(Context c, String open, int code) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("open", open);
        return PendingIntent.getActivity(c, code, i, immutable());
    }

    static PendingIntent action(Context c, String action, int code, Map<String, String> extras, boolean mutable) {
        Intent i = new Intent(c, ActionReceiver.class);
        i.setAction(action);
        if (extras != null) {
            for (Map.Entry<String, String> e : extras.entrySet()) {
                i.putExtra(e.getKey(), e.getValue());
            }
        }
        return PendingIntent.getBroadcast(c, code, i, mutable ? mutable() : immutable());
    }

    // ------------------------------------------------------------------ running
    static Notification running(Context c, Map<String, Object> status, String sub) {
        String state = Json.str(status, "state");
        String title = "question".equals(state) ? "JSTB · question for you"
                : "signin".equals(state) ? "JSTB · sign in to LinkedIn"
                : "waiting".equals(state) ? "JSTB · waiting" : "JSTB · working";
        String text = Json.str(status, "message");
        Notification.Builder b = builder(c, CH_RUN)
                .setContentTitle(title)
                .setContentText(text.isEmpty() ? "Working in the background" : text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c, "dashboard", 10));
        if (sub != null && !sub.isEmpty()) {
            b.setSubText(sub);
        }
        b.addAction(new Notification.Action.Builder(null, "Stop", action(c, ActionReceiver.STOP, 11, null, false)).build());
        return b.build();
    }

    // ------------------------------------------------------------------ question for you
    static void question(Context c, Map<String, Object> item) {
        String qid = Json.str(item, "id");
        String kind = Json.str(item, "kind");
        List<String> options = Json.strings(item.get("options"));
        List<String> sugg = new ArrayList<String>();
        for (Object s : Json.arr(item.get("suggestions"))) {
            sugg.add(s instanceof List ? Util.join(", ", Json.strings(s)) : String.valueOf(s));
        }
        Map<String, Object> job = Json.map(item.get("job"));
        StringBuilder big = new StringBuilder(Json.str(item, "question"));
        if (!Json.str(item, "hint").isEmpty()) {
            big.append("\nLinkedIn said: ").append(Json.str(item, "hint"));
        }
        if (Json.bool(item, "loading")) {
            big.append("\nThe AI is preparing suggestions…");
        } else if (!sugg.isEmpty()) {
            big.append("\nAI suggests: ").append(Util.join(" | ", sugg));
            if (!Json.str(item, "reason").isEmpty()) {
                big.append("\n").append(Json.str(item, "reason"));
            }
        }
        Notification.Builder b = builder(c, CH_ASK)
                .setContentTitle("Question for you")
                .setContentText(Json.str(item, "question"))
                .setStyle(new Notification.BigTextStyle().bigText(big.toString()))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openApp(c, "question", 20));
        if (!Json.str(job, "title").isEmpty()) {
            b.setSubText(Json.str(job, "title") + (Json.str(job, "company").isEmpty() ? "" : " @ " + Json.str(job, "company")));
        }
        String remember = Json.bool(item, "remember") ? "1" : "0";
        int code = 30;
        if ("choice".equals(kind) && !options.isEmpty() && options.size() <= 3) {
            for (String o : options) {
                Map<String, String> ex = new java.util.HashMap<String, String>();
                ex.put("id", qid);
                ex.put("answer", o);
                ex.put("remember", remember);
                String label = sugg.isEmpty() || !sugg.get(0).equals(o) ? o : o + " ✓";
                b.addAction(new Notification.Action.Builder(null, label, action(c, ActionReceiver.ANSWER, code++, ex, false)).build());
            }
        } else if (options.isEmpty()) {
            Map<String, String> ex = new java.util.HashMap<String, String>();
            ex.put("id", qid);
            ex.put("remember", remember);
            RemoteInput input = new RemoteInput.Builder(REPLY_KEY).setLabel("Your answer").build();
            b.addAction(new Notification.Action.Builder(null, "Reply", action(c, ActionReceiver.ANSWER, code++, ex, true))
                    .addRemoteInput(input).build());
            for (int k = 0; k < Math.min(2, sugg.size()); k++) {
                if (sugg.get(k).length() > 40) {
                    continue;
                }
                Map<String, String> ex2 = new java.util.HashMap<String, String>();
                ex2.put("id", qid);
                ex2.put("answer", sugg.get(k));
                ex2.put("remember", remember);
                b.addAction(new Notification.Action.Builder(null, sugg.get(k), action(c, ActionReceiver.ANSWER, code++, ex2, false)).build());
            }
        }
        nm(c).notify(ID_ASK, b.build());
    }

    // ------------------------------------------------------------------ ready to send: your yes first
    /** One notification for the applications filled up to Submit: Apply / Skip for one, Apply all / See them for more. */
    private static int lastReady = 0;

    static void ready(Context c, List<Map<String, Object>> items) {
        List<Map<String, Object>> waiting = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : items) {
            if ("ready".equals(Json.str(j, "status"))) {
                waiting.add(j);
            }
        }
        if (waiting.isEmpty()) {
            lastReady = 0;
            nm(c).cancel(ID_READY);
            return;
        }
        Map<String, Object> first = waiting.get(0);
        boolean more = waiting.size() > lastReady;      // a new one: it drops down again; fewer: quietly
        lastReady = waiting.size();
        Notification.Builder b = builder(c, CH_READY)
                .setAutoCancel(false)
                .setOnlyAlertOnce(!more)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openApp(c, "ready", 70));
        if (waiting.size() == 1) {
            Map<String, String> one = new java.util.HashMap<String, String>();
            one.put("id", Json.str(first, "id"));
            b.setContentTitle("Ready to send: " + Json.str(first, "title"))
                    .setContentText(line(first))
                    .addAction(new Notification.Action.Builder(null, "Apply", action(c, ActionReceiver.APPROVE, 71, one, false)).build())
                    .addAction(new Notification.Action.Builder(null, "Skip", action(c, ActionReceiver.SKIP, 72, one, false)).build());
        } else {
            Notification.InboxStyle list = new Notification.InboxStyle();
            for (int k = 0; k < Math.min(6, waiting.size()); k++) {
                list.addLine(Json.str(waiting.get(k), "title") + " · " + line(waiting.get(k)));
            }
            if (waiting.size() > 6) {
                list.setSummaryText("and " + (waiting.size() - 6) + " more");
            }
            Map<String, String> all = new java.util.HashMap<String, String>();
            all.put("all", "1");
            b.setContentTitle(waiting.size() + " applications ready to send")
                    .setContentText("Filled up to Submit. Apply all, or see them one by one.")
                    .setStyle(list)
                    .addAction(new Notification.Action.Builder(null, "Apply all", action(c, ActionReceiver.APPROVE, 73, all, false)).build())
                    .addAction(new Notification.Action.Builder(null, "See them", openApp(c, "ready", 74)).build());
        }
        nm(c).notify(ID_READY, b.build());
    }

    private static String line(Map<String, Object> j) {
        String company = Json.str(j, "company");
        Object score = j.get("score");
        return (company.isEmpty() ? "" : company + " · ") + (score == null ? "" : "match " + Json.str(j, "score") + "/10");
    }

    static void cancel(Context c, int id) {
        nm(c).cancel(id);
    }

    static void signIn(Context c) {
        Notification.Builder b = builder(c, CH_ASK)
                .setContentTitle("Sign in to LinkedIn")
                .setContentText("Open the app and sign in. The bot continues by itself.")
                .setAutoCancel(true)
                .setContentIntent(openApp(c, "signin", 40));
        nm(c).notify(ID_SIGNIN, b.build());
    }

    // ------------------------------------------------------------------ session brief
    static void brief(Context c, Map<String, Object> s, List<String> lines) {
        if (s == null) {
            return;
        }
        String mode = Json.str(s, "mode");
        int applied = Json.arr(s.get("applied")).size();
        int filled = Json.arr(s.get("filled")).size();
        int stuck = Json.arr(s.get("stuck")).size();
        int waiting = Json.arr(s.get("questions")).size();
        int ready = Json.arr(s.get("ready")).size();
        long skipped = Json.num(s, "skipped_total", 0);
        String title;
        if ("apply".equals(mode)) {
            title = applied == 0 ? "Session finished: no new applications" : "Session finished: applied to " + applied + " job" + (applied == 1 ? "" : "s");
        } else if ("test".equals(mode)) {
            title = "Test run finished: " + filled + " form" + (filled == 1 ? "" : "s") + " filled, nothing sent";
        } else {
            title = "Scoring finished: " + filled + " good match" + (filled == 1 ? "" : "es");
        }
        List<String> parts = new ArrayList<String>();
        if (ready > 0) {
            parts.add(ready + " ready to send");
        }
        if (stuck > 0) {
            parts.add(stuck + " stuck");
        }
        if (waiting > 0) {
            parts.add(waiting + " waiting for your answer");
        }
        if (skipped > 0) {
            parts.add(skipped + " skipped");
        }
        StringBuilder big = new StringBuilder();
        for (String l : lines) {
            if (l.startsWith("=====") || l.startsWith("SESSION BRIEF")) {
                continue;
            }
            String t = l.replaceAll("\\s+https://\\S+$", "");
            big.append(t).append('\n');
        }
        Notification.Builder b = builder(c, CH_BRIEF)
                .setContentTitle(title)
                .setContentText(parts.isEmpty() ? "Tap to see the brief" : Util.join(" · ", parts))
                .setStyle(new Notification.BigTextStyle().bigText(big.toString().trim()))
                .setAutoCancel(true)
                .setContentIntent(openApp(c, "brief", 50));
        nm(c).notify(ID_BRIEF, b.build());
    }

    // ------------------------------------------------------------------ updates
    static final int ID_UPDATE = 5;

    static void installPrompt(Context c, Intent confirm) {
        PendingIntent pi = PendingIntent.getActivity(c, 60, confirm, immutable());
        Notification.Builder b = builder(c, CH_ASK)
                .setContentTitle("JSTB update is ready")
                .setContentText("Tap to install it.")
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm(c).notify(ID_UPDATE, b.build());
    }

    static void updated(Context c, String version) {
        Notification.Builder b = builder(c, CH_BRIEF)
                .setContentTitle("JSTB updated to " + version)
                .setContentText("Tap to open it.")
                .setAutoCancel(true)
                .setContentIntent(openApp(c, "dashboard", 61));
        nm(c).notify(ID_UPDATE, b.build());
    }
}
