package com.autoapply.bot.app;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.autoapply.bot.core.Json;

import java.util.Map;

/** Buttons in the notifications: Stop, an option, a suggestion, or an inline reply to a question; Apply, Skip and Apply
 *  all for applications filled up to Submit. */
public final class ActionReceiver extends BroadcastReceiver {
    static final String STOP = "com.autoapply.bot.STOP";
    static final String ANSWER = "com.autoapply.bot.ANSWER";
    static final String APPROVE = "com.autoapply.bot.APPROVE";
    static final String SKIP = "com.autoapply.bot.SKIP";

    @Override
    public void onReceive(Context context, Intent intent) {
        final AndroidPlatform p = AndroidPlatform.get(context);
        String action = intent.getAction();
        if (STOP.equals(action)) {
            p.engine.stop();
            return;
        }
        if (APPROVE.equals(action) || SKIP.equals(action)) {   // your yes (or no): sending starts on the bot's thread
            final boolean yes = APPROVE.equals(action);
            final String id = intent.getStringExtra("id");
            final boolean all = "1".equals(intent.getStringExtra("all"));
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.util.List<String> ids = all || id == null ? null : java.util.Collections.singletonList(id);
                        final int n = yes ? p.engine.approve(ids) : p.engine.skipReady(ids);
                        if (yes && n > 0) {
                            p.main.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(app, n == 1 ? "Sending it now." : "Sending " + n + " applications.",
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    } finally {
                        pending.finish();
                    }
                }
            }).start();
            return;
        }
        if (!ANSWER.equals(action)) {
            return;
        }
        String answer = intent.getStringExtra("answer");
        Bundle typed = RemoteInput.getResultsFromIntent(intent);
        if (typed != null && typed.getCharSequence(Notifs.REPLY_KEY) != null) {
            answer = String.valueOf(typed.getCharSequence(Notifs.REPLY_KEY));
        }
        final String body = Json.stringify(Json.obj("id", intent.getStringExtra("id"), "answer", answer,
                "remember", "1".equals(intent.getStringExtra("remember"))));
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> r = Json.map(Json.tryParse(p.api.handle("/api/answer", body)));
                    if (r.containsKey("ok") && !Json.bool(r, "ok")) {
                        final String err = Json.str(r, "error");
                        p.main.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(app, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        Map<String, Object> q = com.autoapply.bot.core.Questions.DESK.get();
                        if (q != null) {
                            Notifs.question(app, q);  // show it again so you can answer differently
                        }
                    } else {
                        Notifs.cancel(app, Notifs.ID_ASK);
                    }
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }
}
