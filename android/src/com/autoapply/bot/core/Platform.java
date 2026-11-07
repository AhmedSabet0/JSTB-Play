package com.autoapply.bot.core;

import java.io.File;
import java.util.List;
import java.util.Map;

/** What the engine needs from the phone (or from the test harness). */
public interface Platform {
    /** A fresh browser page for LinkedIn. Called on the bot's thread. */
    Page newPage();

    void closePage(Page page);

    /** The text of li.js. */
    String pageScripts();

    String browserName();

    /** The bot started (true) or stopped (false): keep the app alive in the background meanwhile. */
    void onRunning(boolean running);

    /** A question for you was posted or got its suggestions. */
    void onQuestion(Map<String, Object> item);

    void onQuestionDone();

    void onSignInNeeded();

    void onSessionEnd(Map<String, Object> summary, List<String> lines);

    /** The applications ready to send changed: show them (a notification with Apply / Skip, or Apply all), or hide it
     *  when none waits. Items: {id, title, company, score, url, status: ready|approved}. */
    void onReady(List<Map<String, Object>> ready);

    // ------------------------------------------------------------------ dashboard actions
    /** Show the LinkedIn sign-in screen. */
    void openSignIn();

    /** True when a LinkedIn session cookie is present. */
    boolean signedIn();

    void share(File file, String mime, String title);

    /** {notifications, battery, overlay: true/false} */
    Map<String, Object> permissions();

    void requestPermission(String which);

    /** Open a link: how = "linkedin" (the LinkedIn app when it is installed), "inapp" (the app's own browser, with Fill,
     *  for a company's site), "browser" (the phone's browser) or "store" (Google Play). `jobId` and `title` name the job
     *  for the in-app browser. */
    void openLink(String url, String how, String jobId, String title);

    /** Build and store metadata. */
    Map<String, Object> buildInfo();

    // ------------------------------------------------------------------ app updates
    /** {"name": "1.1", "code": 2} */
    Map<String, Object> appVersion();

    /** Download the APK at `url` and ask Android to install it over this app. Progress via updateState(). `auto`: the
     *  app found it by itself; then it is installed when nobody is using the app (the screen turns off). */
    void installUpdate(String url, String version, boolean auto);

    /** {"state": idle|downloading|installing|confirm|done|error|permission, "progress": 0-100, "message": ""} */
    Map<String, Object> updateState();
}
