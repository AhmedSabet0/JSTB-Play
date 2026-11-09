package com.autoapply.bot.app;

import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.Toast;

import com.autoapply.bot.core.Account;
import com.autoapply.bot.core.Api;
import com.autoapply.bot.core.Engine;
import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Page;
import com.autoapply.bot.core.Platform;
import com.autoapply.bot.core.Util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connects the engine to the phone: the hidden LinkedIn page, notifications, sharing, permissions. */
public final class AndroidPlatform implements Platform {
    private static AndroidPlatform instance;

    public static synchronized AndroidPlatform get(Context c) {
        if (instance == null) {
            instance = new AndroidPlatform(c.getApplicationContext());
        }
        return instance;
    }

    final Context app;
    final Handler main = new Handler(Looper.getMainLooper());
    public final Engine engine;
    public final Api api;
    volatile WeakReference<MainActivity> activity = new WeakReference<MainActivity>(null);
    volatile boolean activityResumed = false;
    private String scripts;

    private AndroidPlatform(Context app) {
        this.app = app;
        Util.echo = false;
        Account.defaultServer = BuildConfig.SERVER_URL;
        Account.device = ((Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " " + (Build.MODEL == null ? "" : Build.MODEL)).trim();
        Account.appVersion = Json.str(appVersion(), "name");
        Account.flavor = BuildConfig.FLAVOR;
        Account.versionCode = Json.num(appVersion(), "code", 0) > 0 ? (long) Json.num(appVersion(), "code", 0) : 0;
        Notifs.channels(app);
        engine = new Engine(this, app.getFilesDir());
        api = new Api(engine);
    }

    MainActivity activity() {
        return activity.get();
    }

    // ------------------------------------------------------------------ page interface
    @Override
    public Page newPage() {
        return null;
    }

    @Override
    public void closePage(Page p) {
    }

    @Override
    public synchronized String pageScripts() {
        return "";
    }

    @Override
    public String browserName() {
        return "Android WebView";
    }

    // ------------------------------------------------------------------ engine events
    @Override
    public void onRunning(boolean running) {
    }

    @Override
    public void onQuestion(Map<String, Object> item) {
        Notifs.question(app, item);
    }

    @Override
    public void onQuestionDone() {
        Notifs.cancel(app, Notifs.ID_ASK);
    }

    @Override
    public void onSignInNeeded() {
        Notifs.signIn(app);
    }

    @Override
    public void onReady(List<Map<String, Object>> ready) {
        Notifs.ready(app, ready);
    }

    @Override
    public void onSessionEnd(Map<String, Object> summary, List<String> lines) {
        Notifs.cancel(app, Notifs.ID_SIGNIN);
        Notifs.brief(app, summary, lines);
    }

    // ------------------------------------------------------------------ dashboard actions
    @Override
    public void openSignIn() {
        main.post(new Runnable() {
            @Override
            public void run() {
                MainActivity a = activity();
                if (a != null && !a.isFinishing()) {
                    a.showSignIn();
                } else {
                    Intent i = new Intent(app, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.putExtra("open", "signin");
                    app.startActivity(i);
                }
            }
        });
    }

    @Override
    public boolean signedIn() {
        try {
            String c = CookieManager.getInstance().getCookie("https://www.linkedin.com");
            return c != null && c.contains("li_at=");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Wuzzuf sets cookies before you sign in too, so the app remembers when it saw you signed in (its sign-in screen
     *  reached your Explore page, or no "Log in" link was left on the page). No cookies at all: signed out. */
    public boolean wuzzufSignedIn() {
        try {
            String c = CookieManager.getInstance().getCookie("https://wuzzuf.net");
            boolean any = c != null && !c.trim().isEmpty();
            SharedPreferences p = app.getSharedPreferences("sites", Context.MODE_PRIVATE);
            if (!any && p.getBoolean("wuzzuf", false)) {
                p.edit().putBoolean("wuzzuf", false).apply();   // the cookies were cleared: signed out
            }
            return any && p.getBoolean("wuzzuf", false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void setWuzzufSignedIn(boolean on) {
        app.getSharedPreferences("sites", Context.MODE_PRIVATE).edit().putBoolean("wuzzuf", on).apply();
    }

    @Override
    public void share(final File file, final String mime, final String title) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, FilesProvider.uriFor(file));
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.setClipData(ClipData.newRawUri(title, FilesProvider.uriFor(file)));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, title);
                MainActivity a = activity();
                if (a != null) {
                    a.startActivity(chooser);
                } else {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(chooser);
                }
            }
        });
    }

    @Override
    public Map<String, Object> permissions() {
        boolean notes;
        if (Build.VERSION.SDK_INT >= 33) {
            notes = app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        } else {
            notes = ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled();
        }
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(app.getPackageName());
        return Json.obj("notifications", notes, "battery", battery, "maker", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
    }

    @Override
    public void requestPermission(final String which) {
        main.post(new Runnable() {
            @Override
            public void run() {
                MainActivity a = activity();
                if (a != null) {
                    a.requestPerm(which);
                }
            }
        });
    }

    // ------------------------------------------------------------------ links
    static final String LINKEDIN = "com.linkedin.android";

    boolean installed(String pkg) {
        try {
            app.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void openLink(final String url, final String how, final String jobId, final String title) {
        main.post(new Runnable() {
            @Override
            public void run() {
                MainActivity a = activity();
                if ("google-auth".equals(how) && a != null && !a.isFinishing()) {
                    a.showGoogleSignIn(url);
                    return;
                }
                if (("inapp".equals(how) || "inapp-fill".equals(how)) && a != null && !a.isFinishing()) {
                    a.showBrowser(url, jobId, title, "inapp-fill".equals(how));
                    return;
                }
                Uri uri = Uri.parse(url);
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                if ("store".equals(how)) {
                    String id = uri.getQueryParameter("id");
                    Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id="
                            + (id == null || id.isEmpty() ? app.getPackageName() : id)));
                    if (market.resolveActivity(app.getPackageManager()) != null) {
                        i = market;
                    }
                } else if ("linkedin".equals(how) && installed(LINKEDIN)) {
                    Intent li = new Intent(Intent.ACTION_VIEW, uri).setPackage(LINKEDIN);
                    if (li.resolveActivity(app.getPackageManager()) != null) {
                        i = li;  // the LinkedIn app, where you are signed in and Easy Apply works
                    }
                }
                Context from = a != null && !a.isFinishing() ? a : app;
                if (from == app) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                try {
                    from.startActivity(i);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(app, "No app on this phone can open this link.", Toast.LENGTH_LONG).show();
                } catch (SecurityException e) {
                    Toast.makeText(app, "Could not open the link.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ------------------------------------------------------------------ app updates
    final Object updLock = new Object();
    Map<String, Object> upd = Json.obj("state", "idle", "progress", 0, "message", "");

    void setUpdate(String state, int progress, String message) {
        synchronized (updLock) {
            upd = Json.obj("state", state, "progress", progress, "message", message);
        }
    }

    @Override
    public Map<String, Object> updateState() {
        synchronized (updLock) {
            return new LinkedHashMap<String, Object>(upd);
        }
    }

    @SuppressWarnings("deprecation")
    long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    @Override
    public Map<String, Object> appVersion() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return Json.obj("name", pi.versionName, "code", versionCode(pi));
        } catch (Exception e) {
            return Json.obj("name", "0", "code", 0);
        }
    }

    // The development build (BuildConfig.SELF_UPDATE) downloads a new version from GitHub and installs it over itself.
    // Google Play builds never get here: they have no permission to install apps.
    private volatile String updVersion = "";
    private volatile File updFile;
    private volatile boolean installWanted;   // install as soon as the app is open (and allowed to install)
    private volatile boolean downloading;

    @Override
    public void installUpdate(final String url, final String version, final boolean auto) {
        if (!BuildConfig.SELF_UPDATE || url == null || url.isEmpty()) {
            return;
        }
        installWanted = true;   // found by itself or asked for: install when the app is open
        final File dir = new File(app.getCacheDir(), "update");
        final File apk = new File(dir, "JSTB-" + version.replaceAll("[^0-9A-Za-z.-]", "_") + ".apk");
        if (version.equals(updVersion) && apk.isFile() && apk.length() > 0) {
            installIfOpen();
            return;
        }
        if (downloading) {
            return;
        }
        downloading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) {
                        for (File f : old) {
                            f.delete();
                        }
                    }
                    setUpdate("downloading", 0, "Downloading version " + version + "…");
                    download(url, apk);
                    updVersion = version;
                    updFile = apk;
                    setUpdate("ready", 100, "Version " + version + " is downloaded.");
                    Util.log("Version " + version + " is downloaded.");
                    installIfOpen();
                } catch (Exception e) {
                    apk.delete();
                    setUpdate("error", 0, "Could not download the update: " + Util.shortError(e, 80));
                } finally {
                    downloading = false;
                }
            }
        }, "update-download").start();
    }

    private void download(String url, File to) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);   // GitHub sends the file from another host
        c.setRequestProperty("User-Agent", "JSTB-updater");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new IOException("GitHub answered " + code);
        }
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        java.io.OutputStream out = new java.io.FileOutputStream(to);
        try {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct != lastPct) {
                    lastPct = pct;
                    setUpdate("downloading", pct, "Downloading the update… " + pct + "%");
                }
            }
        } finally {
            out.close();
            in.close();
        }
        if (total > 0 && to.length() != total) {
            throw new IOException("the download stopped early");
        }
    }

    /** Back in the app: an update that waited (downloaded meanwhile, or for the install permission) goes on. */
    void onAppResumed() {
        if (installWanted && updFile != null && updFile.isFile()) {
            installIfOpen();
        }
    }

    private void installIfOpen() {
        main.post(new Runnable() {
            @Override
            public void run() {
                MainActivity a = activity();
                if (a == null || a.isFinishing() || !activityResumed || updFile == null || !updFile.isFile()) {
                    return;   // when you open the app
                }
                if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
                    setUpdate("permission", 100, "Allow JSTB to install its updates, then come back.");
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + app.getPackageName())));
                    } catch (ActivityNotFoundException e) {
                        setUpdate("error", 0, "Allow installing apps for JSTB in Android's settings.");
                    }
                    return;
                }
                installWanted = false;
                final File apk = updFile;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        install(apk);
                    }
                }, "update-install").start();
            }
        });
    }

    /** Hands the APK to Android's installer; ActionReceiver hears how it went. */
    private void install(File apk) {
        android.content.pm.PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams params = new android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        android.content.pm.PackageInstaller.Session session = null;
        try {
            setUpdate("installing", 100, "Installing version " + updVersion + "…");
            int id = pi.createSession(params);
            session = pi.openSession(id);
            InputStream in = new java.io.FileInputStream(apk);
            java.io.OutputStream out = session.openWrite("JSTB.apk", 0, apk.length());
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            } finally {
                out.close();
                in.close();
            }
            Intent done = new Intent(app, ActionReceiver.class).setAction(ActionReceiver.UPDATE_STATUS);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);   // Android adds the result
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(app, id, done, flags);
            session.commit(pending.getIntentSender());
            session.close();
            session = null;
        } catch (Exception e) {
            if (session != null) {
                session.abandon();
            }
            setUpdate("error", 0, "Could not install the update: " + Util.shortError(e, 80));
        }
    }

    /** Android's answer about the install (from ActionReceiver). */
    void onInstallStatus(Intent intent) {
        int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                setUpdate("confirm", 100, "Tap Install to update JSTB.");
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    app.startActivity(confirm);
                } catch (RuntimeException e) {
                    setUpdate("error", 0, "Open the app to install the update.");
                    installWanted = true;
                }
            }
            return;
        }
        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            setUpdate("done", 100, "Updated.");
            return;
        }
        String msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
        boolean cancelled = status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED;
        boolean conflict = status == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT
                || status == android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
        setUpdate(cancelled ? "ready" : "error", cancelled ? 100 : 0, cancelled ? "Install cancelled. Tap Install now when you want it."
                : conflict ? "This update is signed with another key: uninstall JSTB once and install JSTB-dev.apk from GitHub."
                : "The update did not install" + (msg == null ? "." : ": " + msg));
    }

    @Override
    public Map<String, Object> buildInfo() {
        return Json.obj("flavor", BuildConfig.FLAVOR, "self_update", BuildConfig.SELF_UPDATE,
                "update_repo", BuildConfig.UPDATE_REPO, "server_built_in", !BuildConfig.SERVER_URL.isEmpty());
    }
}
