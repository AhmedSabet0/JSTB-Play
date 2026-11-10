package com.autoapply.bot.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.autoapply.bot.R;
import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Util;

import android.accounts.AccountManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app's screen: the dashboard, the LinkedIn sign-in screen, Live view, and the in-app browser for applying on a
 *  company's site (it fills the form for you to check and send). Jobs shared to the app from LinkedIn or a browser
 *  join your list. */
public final class MainActivity extends Activity {
    static final String ASSET_HOST = "appassets.androidplatform.net";
    static final int REQ_FILE = 1;
    static final int REQ_NOTIFY = 2;
    static final int REQ_SITE_FILE = 3;
    static final int REQ_GOOGLE_ACCOUNT = 1009;
    static final int REQ_PICK_PROFILE_PHOTO = 1010;

    private AndroidPlatform platform;
    private FrameLayout root;
    private WebView dash;
    private LinearLayout signInPane;
    private WebView signInWeb;
    private LinearLayout googlePane;
    private WebView googleWeb;
    private ValueCallback<Uri[]> fileCallback;
    private boolean dark;
    private volatile boolean destroyed;
    private final ExecutorService bridgePool = Executors.newCachedThreadPool();
    private boolean askedNotify;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        platform = AndroidPlatform.get(this);
        platform.activity = new WeakReference<MainActivity>(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        root = new FrameLayout(this);
        dash = new WebView(this);
        WebSettings s = dash.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        dash.addJavascriptInterface(new Bridge(), "AA");
        dash.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!ASSET_HOST.equals(u.getHost())) {
                    return null;
                }
                return asset(u.getPath());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (ASSET_HOST.equals(u.getHost())) {
                    return false;
                }
                openOutside(u);  // job links, openrouter.ai/keys...: in the phone's browser
                return true;
            }
        });
        dash.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/plain"});
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        dash.setBackgroundColor(dark ? 0xff0b1017 : 0xfff3f5f8);
        root.addView(dash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fitBars();
        setContentView(root);
        styleBars();  // after setContentView: the bars' controller only exists once the window has its views
        if (Build.VERSION.SDK_INT >= 33) {
            Back33.register(this);
        }
        dash.loadUrl("https://" + ASSET_HOST + "/ui/index.html");
        handle(getIntent());
        FeedJob.schedule(this);   // new jobs about every hour, with the app closed too
    }

    private int barColor() {
        return dark ? 0xff121923 : 0xffffffff;
    }

    @SuppressWarnings("deprecation")
    private void styleBars() {
        Window w = getWindow();
        w.getDecorView();  // makes sure the window's views exist (getInsetsController needs them)
        if (Build.VERSION.SDK_INT >= 30) {
            // Edge to edge (Android 15+ always draws apps under the bars): the app pads itself in fitBars().
            w.setDecorFitsSystemWindows(false);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(false);
            }
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
            return;
        }
        int bar = barColor();
        w.setStatusBarColor(bar);
        w.setNavigationBarColor(bar);
        if (!dark) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** Keep the screens clear of the status bar, the navigation bar, a notch and the keyboard. */
    private void fitBars() {
        if (Build.VERSION.SDK_INT < 30) {
            return;  // older Android fits the window between the bars by itself
        }
        root.setBackgroundColor(barColor());
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            }
        });
    }

    private WebResourceResponse asset(String path) {
        String p = path == null ? "" : path;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("..")) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<String, String>(), null);
        }
        String mime = p.endsWith(".html") ? "text/html" : p.endsWith(".css") ? "text/css"
                : (p.endsWith(".js") || p.endsWith(".mjs")) ? "text/javascript" : p.endsWith(".svg") ? "image/svg+xml"
                : p.endsWith(".woff2") ? "font/woff2" : p.endsWith(".txt") ? "text/plain" : "application/octet-stream";
        try {
            InputStream in = getAssets().open(p);
            HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<String, String>(), null);
        }
    }

    private void openOutside(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    /** What the dashboard can call: window.AA.call(id, path, bodyJson) (answers via window.__aaDone), or AA.api. */
    final class Bridge {
        @JavascriptInterface
        public String api(String path, String body) {
            return platform.api.handle(path, body == null || body.isEmpty() || "undefined".equals(body) ? null : body);
        }

        @JavascriptInterface
        public void call(final String id, final String path, final String body) {
            bridgePool.execute(new Runnable() {
                @Override
                public void run() {
                    String out = api(path, body);
                    final String js = "window.__aaDone && window.__aaDone(" + Json.stringify(id) + "," + Json.stringify(out) + ")";
                    platform.main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) {
                                dash.evaluateJavascript(js, null);
                            }
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void liveView() {
        }

        /** Open a job site's sign-in screen: "linkedin" or "wuzzuf". */
        @JavascriptInterface
        public void signIn(final String site) {
            platform.main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) {
                        showSignIn(site);
                    }
                }
            });
        }

        /** {"linkedin": true/false, "wuzzuf": true/false}: the job sites this phone is signed in to. */
        @JavascriptInterface
        public String sites() {
            return Json.stringify(Json.obj("linkedin", platform.signedIn(), "wuzzuf", platform.wuzzufSignedIn()));
        }

        /** Copy an answer, to paste it into a form in another app (LinkedIn). */
        @JavascriptInterface
        public void copy(final String text) {
            platform.main.post(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("JSTB", text == null ? "" : text));
                    }
                }
            });
        }

        /** A job shared to the app while the dashboard was loading: {ok, item} or {ok: false, error}. */
        @JavascriptInterface
        public String pending() {
            String p = pendingShare;
            pendingShare = "";
            return p == null ? "" : p;
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void pickGoogleAccount() {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = AccountManager.newChooseAccountIntent(
                                null, null, new String[]{"com.google"}, null, null, null, null);
                        startActivityForResult(intent, REQ_GOOGLE_ACCOUNT);
                    } catch (Throwable t) {
                        bridgePool.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    platform.api.handle("/api/account/google", "{}");
                                } catch (Exception e) {}
                            }
                        });
                    }
                }
            });
        }

        @JavascriptInterface
        public void pickProfilePhoto() {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("image/*");
                        startActivityForResult(intent, REQ_PICK_PROFILE_PHOTO);
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "Cannot pick image: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ intents
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private volatile String pendingShare = "";

    private void handle(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null
                && "autoapply".equals(intent.getData().getScheme())) {
            final Uri back = intent.getData();
            intent.setAction(null);  // back from Google's page: onResume tells the screens
            closePanes();
            final String code = back.getQueryParameter("code");
            if ("auth".equals(back.getHost()) && code != null && !code.isEmpty()) {   // Sign in with Google
                bridgePool.execute(new Runnable() {
                    @Override
                    public void run() {
                        String r = platform.api.handle("/api/account/google-finish", Json.stringify(Json.obj("code", code)));
                        js("window.aaGoogle && window.aaGoogle(" + Json.stringify(r) + ")");
                    }
                });
            }
            return;
        }
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            intent.setAction(null);  // once
            closePanes();
            if (text == null && subject == null) {
                return;
            }
            bridgePool.execute(new Runnable() {
                @Override
                public void run() {
                    pendingShare = platform.api.handle("/api/copilot/add",
                            Json.stringify(Json.obj("text", text == null ? "" : text, "subject", subject == null ? "" : subject)));
                    js("window.aaPending && window.aaPending()");
                }
            });
            return;
        }
        String open = intent == null ? null : intent.getStringExtra("open");
        if ("signin".equals(open)) {
            showSignIn();
        } else if ("ready".equals(open)) {   // the notification of applications ready to send
            closePanes();
            dash.evaluateJavascript("window.aaShowReady && window.aaShowReady()", null);
        } else if ("question".equals(open) || "brief".equals(open)) {
            closePanes();
            dash.evaluateJavascript("window.aaShow && window.aaShow('dashboard')", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        platform.activityResumed = true;
        platform.onAppResumed();   // an update that waited for the app (development build)
        Notifs.cancel(this, Notifs.ID_BRIEF);
        js("window.aaResumed && window.aaResumed()");  // back from LinkedIn: did you apply?
        new Thread(new Runnable() {
            @Override
            public void run() {
                platform.api.handle("/api/here", "{}");
            }
        }).start();
    }

    @Override
    protected void onPause() {
        platform.activityResumed = false;
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        bridgePool.shutdown();
        closePanes();
        if (platform.activity() == this) {
            platform.activity = new WeakReference<MainActivity>(null);
        }
        dash.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {  // Android 12 and older; newer ones call Back33
        handleBack();
    }

    void handleBack() {
        if (browserPane != null) {
            if (browserWeb != null && browserWeb.canGoBack()) {
                browserWeb.goBack();
            } else {
                closeBrowser();
            }
            return;
        }
        if (signInPane != null) {
            signInFinished(false);   // like Done: you may have signed in already
            return;
        }
        // Back goes to the previous screen of the app; on the home screen the app goes to the background
        // (the bot keeps working).
        dash.evaluateJavascript("window.aaBack ? !!window.aaBack() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if (!"true".equals(handled) && !destroyed) {
                    moveTaskToBack(true);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
        if (request == REQ_SITE_FILE && browserFiles != null) {
            Uri[] resultUris = WebChromeClient.FileChooserParams.parseResult(result, data);
            if (resultUris != null && resultUris.length > 0 && resultUris[0] != null) {
                final Uri chosenUri = resultUris[0];
                try {
                    String mime = getContentResolver().getType(chosenUri);
                    if (mime != null && mime.startsWith("image/")) {
                        bridgePool.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    InputStream in = getContentResolver().openInputStream(chosenUri);
                                    File photoFile = new File(getFilesDir(), "profile_photo.jpg");
                                    FileOutputStream fos = new FileOutputStream(photoFile);
                                    byte[] buf = new byte[8192];
                                    int len;
                                    while ((len = in.read(buf)) != -1) {
                                        fos.write(buf, 0, len);
                                    }
                                    fos.close();
                                    in.close();
                                } catch (Exception e) {}
                            }
                        });
                    }
                } catch (Exception e) {}
            }
            browserFiles.onReceiveValue(resultUris);
            browserFiles = null;
        }
        if (request == REQ_PICK_PROFILE_PHOTO && result == RESULT_OK && data != null && data.getData() != null) {
            final Uri chosenUri = data.getData();
            bridgePool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = getContentResolver().openInputStream(chosenUri);
                        File photoFile = new File(getFilesDir(), "profile_photo.jpg");
                        FileOutputStream fos = new FileOutputStream(photoFile);
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            fos.write(buf, 0, len);
                        }
                        fos.close();
                        in.close();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Profile photo saved!", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Failed to save photo: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            });
        }
        if (request == REQ_GOOGLE_ACCOUNT) {
            if (result == RESULT_OK && data != null) {
                final String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null && !accountName.isEmpty()) {
                    bridgePool.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (googleNative(accountName)) {
                                return;   // signed in without leaving the app
                            }
                            try {
                                String url = platform.engine.account.googleStart();
                                if (url != null && !url.isEmpty()) {
                                    String fullUrl = url + (url.contains("?") ? "&" : "?") + "login_hint=" + Uri.encode(accountName);
                                    openCustomTab(fullUrl);
                                }
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, "Sign-in error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }
                    });
                    return;
                }
            }
            js("window.aaGoogle && window.aaGoogle(JSON.stringify({ok:false, error:'Sign-in cancelled'}))");
        }
    }

    /** Sign in with Google like other apps: the account picked on this phone asks Google (Google's own consent sheet,
     *  no browser) for a one-time code for the server (it connects Google Drive too), or else an ID token. False when
     *  that is not possible here (no Google Play services, the server has no client ID...): then the browser page. */
    private boolean googleNative(String accountName) {
        String clientId = platform.engine.account.googleClientId();
        if (clientId.isEmpty()) {
            return false;
        }
        android.accounts.Account acct = new android.accounts.Account(accountName, "com.google");
        AccountManager am = AccountManager.get(this);
        String[] kinds = {
            "oauth2:server:client_id:" + clientId + ":api_scope:openid email profile https://www.googleapis.com/auth/drive.file",
            "audience:server:client_id:" + clientId,
        };
        for (int k = 0; k < kinds.length; k++) {
            String token = null;
            try {
                Bundle b = am.getAuthToken(acct, kinds[k], null, this, null, null).getResult(90, java.util.concurrent.TimeUnit.SECONDS);
                token = b.getString(AccountManager.KEY_AUTHTOKEN);
            } catch (android.accounts.OperationCanceledException e) {
                js("window.aaGoogle && window.aaGoogle(JSON.stringify({ok:false, error:'Sign-in cancelled'}))");
                return true;   // you said no on Google's sheet: nothing more to do
            } catch (Exception e) {
                Util.log("Google sign-in in the app: " + Util.shortError(e, 100) + (k == 0 ? "; trying an ID token" : "; using the browser"));
            }
            if (token == null || token.isEmpty()) {
                continue;
            }
            am.invalidateAuthToken("com.google", token);   // one use only: the next sign-in gets a new one
            String r = platform.api.handle("/api/account/google-native", Json.stringify(k == 0
                    ? Json.obj("code", token) : Json.obj("id_token", token)));
            if (Boolean.FALSE.equals(Json.map(Json.tryParse(r)).get("ok")) && k == 0) {
                Util.log("The server did not take the Google code: " + Json.str(Json.map(Json.tryParse(r)), "error"));
                continue;   // an older server: try the ID token
            }
            if (Boolean.FALSE.equals(Json.map(Json.tryParse(r)).get("ok"))) {
                return false;   // the server cannot do it: the browser page
            }
            js("window.aaGoogle && window.aaGoogle(" + Json.stringify(r) + ")");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ panes
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout pane(String title, String buttonText, View.OnClickListener onButton, String note) {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundColor(dark ? 0xff0b1017 : 0xffffffff);
        pane.setClickable(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(10), dp(10));
        bar.setBackgroundColor(dark ? 0xff121923 : 0xffffffff);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(dark ? 0xffe5ebf2 : 0xff0f172a);
        texts.addView(t);
        if (note != null) {
            TextView n = new TextView(this);
            n.setText(note);
            n.setTextSize(12);
            n.setTextColor(dark ? 0xff8b98a9 : 0xff64748b);
            texts.addView(n);
        }
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(this);
        b.setText(buttonText);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xff0a66c2);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setOnClickListener(onButton);
        bar.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        pane.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(dark ? 0xff222c39 : 0xffe2e8f0);
        pane.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return pane;
    }

    void closePanes() {
        if (browserPane != null) {
            root.removeView(browserPane);
            browserPane = null;
            if (browserWeb != null) {
                browserWeb.destroy();
                browserWeb = null;
            }
            if (browserFiles != null) {
                browserFiles.onReceiveValue(null);
                browserFiles = null;
            }
        }
        if (signInPane != null) {
            root.removeView(signInPane);
            signInPane = null;
            if (signInWeb != null) {
                signInWeb.destroy();
                signInWeb = null;
            }
        }
        if (googlePane != null) {
            root.removeView(googlePane);
            googlePane = null;
            if (googleWeb != null) {
                googleWeb.destroy();
                googleWeb = null;
            }
        }
    }

    private static void configureWeb(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
    }

    private static boolean isLinkedIn(Uri u) {
        String host = u == null ? null : u.getHost();
        return host != null && (host.equals("linkedin.com") || host.endsWith(".linkedin.com"));
    }

    /** LinkedIn's sign-in page. */
    void showSignIn() {
        showSignIn("linkedin");
    }

    private String signInSite = "linkedin";

    private static boolean isWuzzuf(Uri u) {
        String host = u == null ? null : u.getHost();
        return host != null && (host.equals("wuzzuf.net") || host.endsWith(".wuzzuf.net"));
    }

    /** Wuzzuf pages only a signed-in job seeker reaches. */
    private static boolean wuzzufMemberPage(String path) {
        for (String p : new String[]{"/explore", "/me", "/applications", "/saved", "/recommended", "/profile", "/settings"}) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** The sign-in page of a job site, in the app (the in-app browser shares its cookies): "linkedin" or "wuzzuf". */
    void showSignIn(final String site) {
        closePanes();
        final boolean wz = "wuzzuf".equals(site);
        signInSite = wz ? "wuzzuf" : "linkedin";
        signInPane = pane(wz ? "Sign in to Wuzzuf" : "Sign in to LinkedIn", "Done", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signInFinished(false);
            }
        }, "Use your email and password (\"Sign in with Google\" does not work inside apps).");
        signInWeb = new WebView(this);
        configureWeb(signInWeb);
        WebSettings s = signInWeb.getSettings();
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        signInWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"https".equals(scheme) && !"http".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Uri u = Uri.parse(url);
                String path = u.getPath() == null ? "" : u.getPath();
                if (wz) {
                    if (isWuzzuf(u) && wuzzufMemberPage(path)) {
                        platform.setWuzzufSignedIn(true);
                        signInFinished(true);
                    }
                    return;
                }
                if (isLinkedIn(u) && (path.startsWith("/feed") || path.startsWith("/jobs") || path.startsWith("/mynetwork"))
                        && platform.signedIn()) {
                    signInFinished(true);
                }
            }
        });
        signInPane.addView(signInWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(signInPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        signInWeb.loadUrl(wz ? "https://wuzzuf.net/login" : "https://www.linkedin.com/login");
    }

    private void signInFinished(final boolean auto) {
        if (signInPane == null) {
            return;   // already closed (a site can finish loading its signed-in page twice)
        }
        CookieManager.getInstance().flush();
        if ("wuzzuf".equals(signInSite) && !auto && signInWeb != null) {
            // Done: signed in when the page is Wuzzuf's and has no "Log in" link left (and is not the sign-in form).
            final Uri u = Uri.parse(signInWeb.getUrl() == null ? "" : signInWeb.getUrl());
            final String path = u.getPath() == null ? "" : u.getPath();
            signInWeb.evaluateJavascript("!document.querySelector('a[href*=\"/login\"], input[type=password]')",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String noLogin) {
                            if (isWuzzuf(u) && ("true".equals(noLogin) || wuzzufMemberPage(path))
                                    && !path.startsWith("/login") && !path.startsWith("/register") && !path.startsWith("/signup")) {
                                platform.setWuzzufSignedIn(true);
                            }
                            siteSignInDone("wuzzuf", false);
                        }
                    });
            return;
        }
        siteSignInDone(signInSite, auto);
    }

    private void siteSignInDone(String site, boolean auto) {
        boolean wz = "wuzzuf".equals(site);
        boolean ok = wz ? platform.wuzzufSignedIn() : platform.signedIn();
        if (auto || ok) {
            Toast.makeText(this, ok ? (wz ? "Signed in to Wuzzuf." : "Signed in to LinkedIn.") : "Not signed in yet.",
                    Toast.LENGTH_SHORT).show();
        }
        closePanes();
        if (!wz) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    platform.api.handle("/api/signed-in", "{}");
                }
            }).start();
        }
        dash.evaluateJavascript("window.aaSignedIn && window.aaSignedIn(" + Json.stringify(site) + "," + ok + ")", null);
        dash.evaluateJavascript("window.aaRefresh && window.aaRefresh()", null);
    }

    /** In-app Google Sign-In: opens in an in-app sheet without switching away to external Chrome. */
    void showGoogleSignIn(final String url) {
        closePanes();
        googlePane = pane("Sign in with Google", "Cancel", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanes();
                js("window.aaGoogle && window.aaGoogle(JSON.stringify({ok:false, error:'Sign-in cancelled'}))");
            }
        }, "Sign in with your Google account. Your CV and data will sync automatically.");

        googleWeb = new WebView(this);
        configureWeb(googleWeb);
        WebSettings s = googleWeb.getSettings();
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // Clean desktop Chrome User-Agent to avoid disallowed_useragent
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36");

        googleWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("autoapply".equals(u.getScheme())) {
                    final String code = u.getQueryParameter("code");
                    closePanes();
                    if (code != null && !code.isEmpty()) {
                        bridgePool.execute(new Runnable() {
                            @Override
                            public void run() {
                                String r = platform.api.handle("/api/account/google-finish", Json.stringify(Json.obj("code", code)));
                                js("window.aaGoogle && window.aaGoogle(" + Json.stringify(r) + ")");
                            }
                        });
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                Uri u = Uri.parse(pageUrl);
                if ("autoapply".equals(u.getScheme())) {
                    final String code = u.getQueryParameter("code");
                    closePanes();
                    if (code != null && !code.isEmpty()) {
                        bridgePool.execute(new Runnable() {
                            @Override
                            public void run() {
                                String r = platform.api.handle("/api/account/google-finish", Json.stringify(Json.obj("code", code)));
                                js("window.aaGoogle && window.aaGoogle(" + Json.stringify(r) + ")");
                            }
                        });
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.contains("disallowed_useragent")) {
                    closePanes();
                    openCustomTab(url);
                }
            }
        });

        googlePane.addView(googleWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(googlePane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        googleWeb.loadUrl(url);
    }

    void openCustomTab(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            Bundle extras = new Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", null);
            intent.putExtras(extras);
            intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", dark ? 0xff0d1420 : 0xffffffff);
            intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
            intent.putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING", true);
            startActivity(intent);
        } catch (Throwable t) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    // ------------------------------------------------------------------ the in-app browser (applying on a company's site)
    private LinearLayout browserPane;
    private WebView browserWeb;
    private ProgressBar browserProgress;
    private TextView browserTitleView;
    private TextView browserHostView;
    private Button browserFillBtn;
    private Button browserMainBtn;
    private ValueCallback<Uri[]> browserFiles;
    private String browserJob = "";
    private String browserJobTitle = "";
    private boolean browserApplied;
    /** Fill and review: the app opens the site's form (its Apply button, or the form's own frame) and fills it. */
    private boolean browserAuto;
    private boolean autoBusy;
    private int autoTries;
    /** A fill happened on this page: the site's thank-you page marks the job Applied. */
    private boolean browserFilled;
    private LinearLayout reviewBar;
    private TextView reviewText;
    private Button reviewNext;
    private Button reviewSubmit;
    private String fitAsked = "";
    private String fillJs;

    void js(final String code) {
        platform.main.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) {
                    dash.evaluateJavascript(code, null);
                }
            }
        });
    }

    private String fillScript() {
        if (fillJs == null) {
            try {
                InputStream in = getAssets().open("fill.js");
                try {
                    fillJs = new String(Util.readAll(in), Util.UTF8);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                fillJs = "";
            }
        }
        return fillJs;
    }

    /** evaluateJavascript gives the script's value as JSON; our scripts return JSON text, so it is a string in JSON. */
    private static String jsText(String raw) {
        Object v = Json.tryParse(raw);
        return v instanceof String ? (String) v : "";
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        return g;
    }

    private Button barButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setTextColor(primary ? Color.WHITE : (dark ? 0xffe7edf6 : 0xff13213c));
        b.setBackground(primary ? round(dark ? 0xff3d8fe0 : 0xff0a66c2, 0) : round(dark ? 0xff152034 : 0xffffffff, dark ? 0xff2a3a55 : 0xffd5dde8));
        return b;
    }

    /** A company's job page in the app: Fill puts your answers in its form (you check them and send it yourself), and
     *  the file button offers your CV. `jobId` is the job in your list; without it, Save adds the page to your list. */
    void showBrowser(String url, String jobId, String title) {
        showBrowser(url, jobId, title, false);
    }

    void showBrowser(String url, String jobId, String title, boolean auto) {
        askedSkipped.clear();   // a new job: its questions are new
        askedFixed.clear();
        advanceSig = "";
        advanceSame = 0;
        closePanes();
        browserJob = jobId == null ? "" : jobId;
        browserJobTitle = title == null ? "" : title;
        browserApplied = false;
        browserAuto = auto;
        autoBusy = false;
        autoTries = 0;
        browserFilled = false;
        fitAsked = "";
        int bg = dark ? 0xff0d1420 : 0xffffffff;
        int ink = dark ? 0xffe7edf6 : 0xff13213c;
        int ink2 = dark ? 0xff9aa9c0 : 0xff55637d;
        browserPane = new LinearLayout(this);
        browserPane.setOrientation(LinearLayout.VERTICAL);
        browserPane.setBackgroundColor(bg);
        browserPane.setClickable(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(6), dp(14), dp(6));
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(ink);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeBrowser();
            }
        });
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        browserTitleView = new TextView(this);
        browserTitleView.setTextSize(15.5f);
        browserTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        browserTitleView.setTextColor(ink);
        browserTitleView.setSingleLine(true);
        browserTitleView.setEllipsize(TextUtils.TruncateAt.END);
        browserTitleView.setText(browserJobTitle.isEmpty() ? "Loading…" : browserJobTitle);
        browserHostView = new TextView(this);
        browserHostView.setTextSize(12.5f);
        browserHostView.setTextColor(ink2);
        browserHostView.setSingleLine(true);
        browserHostView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(browserTitleView);
        texts.addView(browserHostView);
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        browserPane.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        browserProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        browserProgress.setMax(100);
        browserProgress.setIndeterminate(false);
        browserPane.addView(browserProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        browserWeb = new WebView(this);
        WebSettings s = browserWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(browserWeb, true);
        browserWeb.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onStepChange() {
                platform.main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (browserWeb != null && !destroyed) {
                            fillForm();
                        }
                    }
                });
            }
        }, "AAFillHost");
        browserWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    return false;
                }
                if ("mailto".equals(scheme) || "tel".equals(scheme)) {
                    openOutside(u);
                }
                return true;  // other apps' links (intent:, market:...) stay closed
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                browserProgress.setVisibility(View.INVISIBLE);
                showBrowserPage(view);
                readJobPage(view, url);
                if (browserAuto && !browserFilled) {
                    autoStep(view);
                } else if (browserFilled) {
                    checkThanks(view);
                }
            }
        });
        browserWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                browserProgress.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
                browserProgress.setProgress(p);
            }

            @Override
            public void onReceivedTitle(WebView view, String t) {
                showBrowserPage(view);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (browserFiles != null) {
                    browserFiles.onReceiveValue(null);
                }
                browserFiles = callback;
                chooseSiteFile(params);
                return true;
            }
        });
        browserPane.addView(browserWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View line = new View(this);
        line.setBackgroundColor(dark ? 0xff22304a : 0xffe2e8f0);
        browserPane.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Fill and review: what was filled, what needs you, and the way to the site's Submit (you tap it).
        reviewBar = new LinearLayout(this);
        reviewBar.setOrientation(LinearLayout.VERTICAL);
        reviewBar.setPadding(dp(14), dp(10), dp(14), dp(2));
        reviewBar.setBackgroundColor(dark ? 0xff111a2b : 0xfff6f8fb);
        reviewBar.setVisibility(View.GONE);
        reviewText = new TextView(this);
        reviewText.setTextSize(14);
        reviewText.setTextColor(ink);
        reviewBar.addView(reviewText);
        LinearLayout reviewRow = new LinearLayout(this);
        reviewRow.setOrientation(LinearLayout.HORIZONTAL);
        reviewRow.setPadding(0, dp(8), 0, 0);
        reviewNext = barButton("Next to answer", false);
        reviewNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (browserWeb != null) {
                    browserWeb.evaluateJavascript("window.AAFill && window.AAFill.next()", null);
                }
            }
        });
        reviewSubmit = barButton("Go to Submit", false);
        reviewSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (browserWeb == null) {
                    return;
                }
                String currentText = String.valueOf(reviewSubmit.getText());
                if ("Submit Application".equals(currentText)) {
                    browserWeb.evaluateJavascript("window.AAFill && window.AAFill.doSubmit()", new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String r) {
                            Toast.makeText(MainActivity.this, "Application submitted!", Toast.LENGTH_SHORT).show();
                            markBrowserApplied();
                        }
                    });
                    return;
                }
                browserWeb.evaluateJavascript("window.AAFill && window.AAFill.advance()", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String r) {
                        platform.main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (browserWeb != null && !destroyed) {
                                    fillForm();
                                }
                            }
                        }, 700);
                    }
                });
            }
        });
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(0, dp(44), 1);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(0, dp(44), 1);
        r2.leftMargin = dp(10);
        reviewRow.addView(reviewNext, r1);
        reviewRow.addView(reviewSubmit, r2);
        reviewBar.addView(reviewRow);
        browserPane.addView(reviewBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10));
        bottom.setBackgroundColor(dark ? 0xff111a2b : 0xfff6f8fb);
        browserFillBtn = barButton("Fill the form", false);
        browserFillBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fillForm();
            }
        });
        browserMainBtn = barButton("", true);
        browserMainBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (browserJob.isEmpty()) {
                    saveBrowserJob();
                } else {
                    markBrowserApplied();
                }
            }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(50), 1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(50), 1);
        half2.leftMargin = dp(10);
        bottom.addView(browserFillBtn, half);
        bottom.addView(browserMainBtn, half2);
        browserPane.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paintBrowserButtons();
        root.addView(browserPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (auto) {
            showReview("Opening the application form…", false, false);
        }
        browserWeb.loadUrl(url);
    }

    private void showReview(String text, boolean next, boolean submit) {
        if (reviewBar == null) {
            return;
        }
        reviewBar.setVisibility(View.VISIBLE);
        reviewText.setText(text);
        reviewNext.setVisibility(next ? View.VISIBLE : View.GONE);
        reviewSubmit.setVisibility(submit ? View.VISIBLE : View.GONE);
        ((View) reviewNext.getParent()).setVisibility(next || submit ? View.VISIBLE : View.GONE);
    }

    /** Fill and review: the form on this page is filled; no form yet: the form's own frame, or the site's Apply
     *  button (a few tries, for pages that draw the form after a moment). */
    private void autoStep(final WebView view) {
        if (view != browserWeb || autoBusy || !browserAuto) {
            return;
        }
        autoBusy = true;
        platform.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (view != browserWeb || destroyed) {
                    autoBusy = false;
                    return;
                }
                view.evaluateJavascript(fillScript() + "\n;JSON.stringify({n: JSON.parse(window.AAFill.collect()).length, "
                        + "frames: JSON.parse(window.AAFill.frames())})", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String raw) {
                        autoBusy = false;
                        if (view != browserWeb || destroyed) {
                            return;
                        }
                        Map<String, Object> m = Json.map(Json.tryParse(jsText(raw)));
                        List<Object> frames = Json.arr(m.get("frames"));
                        if (Json.num(m, "n", 0) >= 1) {
                            fillForm();
                            return;
                        }
                        if (autoTries >= 4) {
                            browserAuto = false;
                            showReview("Open the application form on this page (the site's Apply button), then tap Fill the form.",
                                    false, false);
                            return;
                        }
                        autoTries++;
                        if (!frames.isEmpty()) {
                            view.loadUrl(String.valueOf(frames.get(0)));   // the page finishing calls this again
                            return;
                        }
                        view.evaluateJavascript("window.AAFill.clickStart()", new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String clicked) {
                                String label = jsText(clicked);
                                showReview(label.isEmpty() ? "Waiting for the form…" : "Opened the site's \"" + label + "\". Filling…",
                                        false, false);
                                noteApplyKind(browserJob, label);
                                platform.main.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        autoStep(view);    // the form may appear without a new page
                                    }
                                }, 1800);
                            }
                        });
                    }
                });
            }
        }, 900);
    }

    /** The LinkedIn button the app tapped tells how this job is applied to: "Easy Apply" in LinkedIn, or "Apply" on
     *  the company's site. Kept on the job, so its tag in the list is right. */
    private void noteApplyKind(final String job, String label) {
        if (job == null || !job.startsWith("li:") || label == null || label.trim().isEmpty()) {
            return;
        }
        final String kind = label.toLowerCase(java.util.Locale.ROOT).contains("easy apply") ? "easy" : "external";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    platform.api.handle("/api/copilot/update", Json.stringify(Json.obj("id", job, "apply", kind)));
                } catch (RuntimeException ignored) {
                    // only a tag
                }
            }
        }, "apply-kind").start();
    }

    /** After a fill: the site's thank-you page means it was sent; the job is marked Applied. */
    private void checkThanks(final WebView view) {
        if (view != browserWeb || destroyed || browserApplied) {
            return;
        }
        view.evaluateJavascript(fillScript() + "\n;window.AAFill.done()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String r) {
                if (view != browserWeb || destroyed || browserApplied || !"true".equals(r)) {
                    return;
                }
                if (browserJob.isEmpty()) {
                    showReview("Sent. Tap Save to my jobs to keep track of it.", false, false);
                    return;
                }
                Toast.makeText(MainActivity.this, "Sent. Added to Applied.", Toast.LENGTH_SHORT).show();
                markBrowserApplied();
            }
        });
    }

    private void watchThanks(final WebView view) {
        platform.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (view != browserWeb || destroyed || browserApplied) {
                    return;
                }
                checkThanks(view);
                watchThanks(view);      // pages that show it without loading a new page
            }
        }, 2500);
    }

    private void paintBrowserButtons() {
        if (browserMainBtn != null) {
            browserMainBtn.setText(browserJob.isEmpty() ? "Save to my jobs" : "I applied");
        }
    }

    private void showBrowserPage(WebView view) {
        if (browserWeb != view || browserTitleView == null) {
            return;
        }
        String t = view.getTitle();
        String url = view.getUrl() == null ? "" : view.getUrl();
        Uri u = Uri.parse(url);
        browserTitleView.setText(t != null && !t.trim().isEmpty() && !url.contains(t) ? t.trim()
                : (browserJobTitle.isEmpty() ? (u.getHost() == null ? "" : u.getHost()) : browserJobTitle));
        browserHostView.setText((u.getHost() == null ? "" : u.getHost().replaceFirst("^www\\.", "")) + (u.getPath() == null ? "" : u.getPath()));
    }

    /** The job's page: its text tells how well the job fits your CV (done once per job, when the AI is on). */
    private void readJobPage(final WebView view, final String url) {
        if (browserJob.isEmpty() || browserJob.equals(fitAsked)) {
            return;
        }
        final String job = browserJob;
        view.evaluateJavascript(fillScript() + "\n;window.AAFill.page()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                final Map<String, Object> pg = Json.map(Json.tryParse(jsText(raw)));
                if (Json.str(pg, "text").trim().length() < 400 || !job.equals(browserJob)) {
                    return;
                }
                fitAsked = job;
                bridgePool.execute(new Runnable() {
                    @Override
                    public void run() {
                        platform.api.handle("/api/copilot/fit", Json.stringify(Json.obj("id", job, "text", Json.str(pg, "text"),
                                "heading", Json.str(pg, "heading"), "page_title", Json.str(pg, "title"))));
                        js("window.aaRefreshJobs && window.aaRefreshJobs()");
                    }
                });
            }
        });
    }

    private boolean fillingForm = false;

    /** Fill: your answers go into the form on the page. You check them and send it yourself. */
    private void fillForm() {
        if (browserWeb == null) {
            return;
        }
        if (fillingForm) {
            return;
        }
        fillingForm = true;
        final WebView web = browserWeb;
        browserFillBtn.setEnabled(false);
        browserFillBtn.setText("Filling…");

        // Watchdog: Guarantee button is restored within 3.5 seconds if an async callback hangs
        platform.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (fillingForm) {
                    fillDone();
                }
            }
        }, 3500);

        web.evaluateJavascript(fillScript() + "\n;window.AAFill.collect()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                final Object fields = Json.tryParse(jsText(raw));
                if (!(fields instanceof List) || ((List<?>) fields).isEmpty()) {
                    // Check if modal has Next / Review button (e.g. on Resume step)
                    web.evaluateJavascript("window.AAFill && window.AAFill.review()", new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String rv) {
                            Map<String, Object> v = Json.map(Json.tryParse(jsText(rv)));
                            String btnType = Json.str(v, "button_type");
                            boolean hasModal = "next".equals(btnType) || "review".equals(btnType) || "submit".equals(btnType);
                            if (hasModal) {
                                fillDone();
                                if ("submit".equals(btnType)) {
                                    reviewSubmit.setText("Submit Application");
                                    reviewSubmit.setTextColor(Color.WHITE);
                                    reviewSubmit.setBackground(round(0xff00c853, 0));
                                    showReview("Application ready. Tap Submit Application below to send.", false, true);
                                    web.evaluateJavascript("window.AAFill && window.AAFill.toSubmit()", null);
                                } else {
                                    if (!mayAdvance(Json.strings(v.get("errors")), "none|" + btnType + "|" + jsText(raw))) {
                                        return;   // the site refuses something, or does not move on: you look
                                    }
                                    reviewSubmit.setText("review".equals(btnType) ? "Review Application" : "Next Step");
                                    reviewSubmit.setTextColor(dark ? 0xffe7edf6 : 0xff13213c);
                                    reviewSubmit.setBackground(round(dark ? 0xff152034 : 0xffffffff, dark ? 0xff2a3a55 : 0xffd5dde8));
                                    showReview("Step ready. Advancing to next step…", false, true);
                                    platform.main.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (web == browserWeb && !destroyed) {
                                                web.evaluateJavascript("window.AAFill && window.AAFill.advance()", new ValueCallback<String>() {
                                                    @Override
                                                    public void onReceiveValue(String s) {
                                                        platform.main.postDelayed(new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                if (web == browserWeb && !destroyed) {
                                                                    fillForm();
                                                                }
                                                            }
                                                        }, 650);
                                                    }
                                                });
                                            }
                                        }
                                    }, 450);
                                }
                                return;
                            }
                            web.evaluateJavascript("window.AAFill.frames()", new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String fr) {
                                    List<Object> frames = Json.arr(Json.tryParse(jsText(fr)));
                                    fillDone();
                                    if (!frames.isEmpty() && web == browserWeb) {
                                        web.loadUrl(String.valueOf(frames.get(0)));
                                        Toast.makeText(MainActivity.this, "The form was inside the page, so it is open on its own now. "
                                                + "Tap Fill the form again.", Toast.LENGTH_LONG).show();
                                    } else {
                                        Toast.makeText(MainActivity.this, "No form on this page yet. Tap the site's Apply button, then "
                                                + "Fill the form.", Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });
                    return;
                }
                final String job = browserJob;
                final String title = browserTitleView == null ? "" : String.valueOf(browserTitleView.getText());
                bridgePool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String out = platform.api.handle("/api/fill", Json.stringify(Json.obj("id", job, "title", title,
                                    "company", "", "fields", fields)));
                            final Map<String, Object> r = Json.map(Json.tryParse(out));
                            platform.main.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (web != browserWeb || destroyed) {
                                            fillDone();
                                            return;
                                        }
                                        if (Boolean.FALSE.equals(r.get("ok"))) {
                                            fillDone();
                                            Toast.makeText(MainActivity.this, Json.str(r, "error"), Toast.LENGTH_LONG).show();
                                            return;
                                        }
                                        final List<Object> skipped = Json.arr(r.get("skipped"));
                                        final List<Map<String, Object>> toAsk = new ArrayList<Map<String, Object>>();
                                        for (Object q : Json.arr(r.get("ask"))) {
                                            Map<String, Object> qm = Json.map(q);
                                            String ql = Json.str(qm, "label");
                                            if (!askedSkipped.contains(ql) && !(Json.str(qm, "error").length() > 0 && askedFixed.contains(ql))) {
                                                toAsk.add(qm);
                                            }
                                        }
                                        web.evaluateJavascript("window.AAFill.apply(" + Json.stringify(r.get("answers")) + ")",
                                                new ValueCallback<String>() {
                                                    @Override
                                                    public void onReceiveValue(String n) {
                                                        if (!toAsk.isEmpty() && web == browserWeb && !destroyed) {
                                                            fillDone();
                                                            askQuestions(web, toAsk, 0, new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    if (web == browserWeb && !destroyed) {
                                                                        fillForm();   // the answers are in: on to the next step
                                                                    }
                                                                }
                                                            });
                                                            return;
                                                        }
                                                        try {
                                                            fillDone();
                                                            final int filled = (int) Json.num(Json.obj("n", Json.tryParse(n)), "n", 0);
                                                            if (!browserFilled) {
                                                                browserFilled = true;
                                                                watchThanks(web);
                                                            }
                                                            web.evaluateJavascript("window.AAFill.review()", new ValueCallback<String>() {
                                                                @Override
                                                                public void onReceiveValue(String rv) {
                                                                    try {
                                                                        Map<String, Object> v = Json.map(Json.tryParse(jsText(rv)));
                                                                        final List<String> left = Json.strings(v.get("missing"));
                                                                        final String btnType = Json.str(v, "button_type");
                                                                        StringBuilder t = new StringBuilder(filled == 0
                                                                                ? "Fields ready."
                                                                                : "Filled " + filled + (filled == 1 ? " field" : " fields") + " (in green).");
                                                                        if (!left.isEmpty()) {
                                                                            t.append(' ').append(left.size()).append(left.size() == 1 ? " needs" : " need")
                                                                                    .append(" you: ").append(Util.cut(Util.join(", ", left), 120)).append('.');
                                                                        }
                                                                        if (Json.bool(v, "file")) {
                                                                            t.append(" Attach your CV with the site's file button.");
                                                                        }
                                                                        final List<String> refused = Json.strings(v.get("errors"));
                                                                        if (!refused.isEmpty()) {   // red messages on the page: not ready to send
                                                                            showReview("The site does not accept " + refused.size()
                                                                                    + (refused.size() == 1 ? " answer: " : " answers: ")
                                                                                    + Util.cut(Util.join("; ", refused), 160)
                                                                                    + ". Pick the right choice on the page, then tap Fill the form.", true, false);
                                                                            web.evaluateJavascript("window.AAFill && window.AAFill.focusQuestion("
                                                                                    + Json.stringify(refused.get(0).replaceFirst(":[^:]*$", "")) + ")", null);
                                                                            return;
                                                                        }
                                                                        if ("submit".equals(btnType)) {
                                                                            reviewSubmit.setText("Submit Application");
                                                                            reviewSubmit.setTextColor(Color.WHITE);
                                                                            reviewSubmit.setBackground(round(0xff00c853, 0));
                                                                            t.append(" Tap Submit Application below to send.");
                                                                        } else if ("review".equals(btnType)) {
                                                                            reviewSubmit.setText("Review Application");
                                                                            reviewSubmit.setTextColor(dark ? 0xffe7edf6 : 0xff13213c);
                                                                            reviewSubmit.setBackground(round(dark ? 0xff152034 : 0xffffffff, dark ? 0xff2a3a55 : 0xffd5dde8));
                                                                            t.append(" Tap Review Application to proceed.");
                                                                        } else {
                                                                            reviewSubmit.setText("Next Step");
                                                                            reviewSubmit.setTextColor(dark ? 0xffe7edf6 : 0xff13213c);
                                                                            reviewSubmit.setBackground(round(dark ? 0xff152034 : 0xffffffff, dark ? 0xff2a3a55 : 0xffd5dde8));
                                                                            t.append(" Tap Next Step to proceed.");
                                                                        }
                                                                        showReview(t.toString(), !left.isEmpty(), true);

                                                                        if (!left.isEmpty()) {
                                                                            final String firstMissing = left.get(0);
                                                                            final String jsPill = "window.showPopupPill && window.showPopupPill({"
                                                                                    + "type:'question',"
                                                                                    + "icon:'?',"
                                                                                    + "title:'Question Needed',"
                                                                                    + "sub:" + Json.stringify(firstMissing) + ","
                                                                                    + "actText:'Answer',"
                                                                                    + "onAct:function(){ if(window.AAFill && window.AAFill.focusQuestion) window.AAFill.focusQuestion(" + Json.stringify(firstMissing) + "); }"
                                                                                    + "})";
                                                                            web.evaluateJavascript(jsPill, null);
                                                                        } else if (("next".equals(btnType) || "review".equals(btnType))
                                                                                && mayAdvance(Json.strings(v.get("errors")), btnType + "|" + Json.stringify(fields))) {
                                                                            try {
                                                                                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                                                                                if (imm != null && web != null) {
                                                                                    imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                                                                                }
                                                                            } catch (Exception ignored) {}
                                                                            platform.main.postDelayed(new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    if (web == browserWeb && !destroyed) {
                                                                                        web.evaluateJavascript("window.AAFill && window.AAFill.advance()", new ValueCallback<String>() {
                                                                                            @Override
                                                                                            public void onReceiveValue(String s) {
                                                                                                platform.main.postDelayed(new Runnable() {
                                                                                                    @Override
                                                                                                    public void run() {
                                                                                                        if (web == browserWeb && !destroyed) {
                                                                                                            fillForm();
                                                                                                        }
                                                                                                    }
                                                                                                }, 650);
                                                                                            }
                                                                                        });
                                                                                    }
                                                                                }
                                                                            }, 450);
                                                                        } else if ("submit".equals(btnType)) {
                                                                            browserAuto = false;
                                                                            web.evaluateJavascript("window.AAFill && window.AAFill.toSubmit()", null);
                                                                        }
                                                                    } catch (Throwable t2) {
                                                                        fillDone();
                                                                    }
                                                                }
                                                            });
                                                        } catch (Throwable t1) {
                                                            fillDone();
                                                        }
                                                    }
                                                });
                                    } catch (Throwable th) {
                                        fillDone();
                                    }
                                }
                            });
                        } catch (Throwable th) {
                            platform.main.post(new Runnable() {
                                @Override
                                public void run() {
                                    fillDone();
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    /** Questions a form asks that your answers, your info and the AI do not know (for sure). */
    private final java.util.Set<String> askedSkipped = new java.util.HashSet<String>();
    private final java.util.Set<String> askedFixed = new java.util.HashSet<String>();
    /** The last step the app moved on from, and how often in a row: a site that does not move on stops the moving. */
    private String advanceSig = "";
    private int advanceSame = 0;

    /** True when the app may tap the site's Next/Review again: not when the site shows a red message, and not a third
     *  time on a page that did not change (the site refuses something: you see it and fix it). */
    private boolean mayAdvance(List<String> siteErrors, String pageSig) {
        if (!siteErrors.isEmpty()) {
            showReview("The site does not accept: " + Util.cut(Util.join("; ", siteErrors), 160)
                    + ". Fix it on the page, then tap Fill the form.", false, false);
            advanceSig = "";
            return false;
        }
        advanceSame = pageSig.equals(advanceSig) ? advanceSame + 1 : 0;
        advanceSig = pageSig;
        if (advanceSame >= 2) {
            showReview("The site did not move on. Look for a red message on the page, fix it, then tap Fill the form.", false, true);
            advanceSame = 0;
            advanceSig = "";
            return false;
        }
        return true;
    }

    /** Asks you one question after the other in the app's own window: your answer goes into the form and into your
     *  info, so the next form that asks it is filled by itself. Skip leaves it for you on the page. */
    private void askQuestions(final WebView web, final List<Map<String, Object>> qs, final int i, final Runnable done) {
        if (i >= qs.size() || web != browserWeb || destroyed) {
            done.run();
            return;
        }
        final Map<String, Object> q = qs.get(i);
        final String label = Json.str(q, "label");
        final String kind = Json.str(q, "kind");
        final List<String> options = Json.strings(q.get("options"));
        final String suggestion = Json.str(q, "suggestion");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        TextView qText = new TextView(this);
        qText.setText(label);
        qText.setTextSize(17);
        qText.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(qText);
        TextView hint = new TextView(this);
        String siteError = Json.str(q, "error");
        hint.setText(!siteError.isEmpty() ? "The site says: " + siteError + "\nYour answer is saved for next time."
                : suggestion.isEmpty() ? "Answer once: it is saved, and forms that ask it again are filled by themselves."
                : "Suggested from your CV. Check it: your answer is saved for next time.");
        if (!siteError.isEmpty()) {
            hint.setTextColor(dark ? 0xffffb4a8 : 0xffb3261e);
        }
        hint.setTextSize(13);
        hint.setPadding(0, dp(4), 0, dp(10));
        box.addView(hint);
        final android.widget.EditText input = new android.widget.EditText(this);
        final int[] picked = {-1};
        final boolean multi = "multi".equals(kind) && !options.isEmpty();
        final boolean[] ticked = new boolean[options.size()];
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(qs.size() > 1 ? "Question " + (i + 1) + " of " + qs.size() : "The form asks")
                .setCancelable(false);
        if (multi) {   // "select all that apply"
            List<String> sugg = java.util.Arrays.asList(suggestion.toLowerCase(java.util.Locale.ROOT).split("\\s*,\\s*"));
            for (int k = 0; k < options.size(); k++) {
                ticked[k] = sugg.contains(options.get(k).toLowerCase(java.util.Locale.ROOT));
            }
            b.setMultiChoiceItems(options.toArray(new String[0]), ticked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which, boolean on) {
                    ticked[which] = on;
                }
            });
        } else if (!options.isEmpty()) {
            for (int k = 0; k < options.size(); k++) {
                if (options.get(k).equalsIgnoreCase(suggestion)) {
                    picked[0] = k;
                }
            }
            b.setSingleChoiceItems(options.toArray(new String[0]), picked[0], new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    picked[0] = which;
                }
            });
        } else {
            boolean num = "number".equals(kind);
            input.setInputType(num ? (android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                    : "textarea".equals(kind) ? (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) : (android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
            input.setHint(num ? "A number, like 3" : "Your answer");
            input.setText(suggestion);
            input.setSelection(input.getText().length());
            box.addView(input);
        }
        b.setView(box);
        b.setPositiveButton(i + 1 < qs.size() ? "Save, next" : "Save and fill", null);
        b.setNegativeButton("Skip", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                askedSkipped.add(label);   // not again on this page: you answer it on the site
                askQuestions(web, qs, i + 1, done);
            }
        });
        final AlertDialog dialog = b.create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface di) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        List<String> all = new ArrayList<String>();
                        for (int k = 0; multi && k < options.size(); k++) {
                            if (ticked[k]) {
                                all.add(options.get(k));
                            }
                        }
                        final Object answer = multi ? (all.isEmpty() ? "" : all) : options.isEmpty() ? input.getText().toString().trim()
                                : picked[0] >= 0 ? options.get(picked[0]) : "";
                        if ("".equals(answer)) {
                            Toast.makeText(MainActivity.this, options.isEmpty() ? "Type an answer, or tap Skip."
                                    : "Pick an answer, or tap Skip.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        dialog.dismiss();
                        if (!Json.str(q, "error").isEmpty()) {
                            askedFixed.add(label);   // the site refused it once: if it still does, you fix it on the page
                        }
                        bridgePool.execute(new Runnable() {
                            @Override
                            public void run() {
                                final Map<String, Object> r = Json.map(Json.tryParse(platform.api.handle("/api/fill/learn",
                                        Json.stringify(Json.obj("label", label, "value", answer, "kind", kind)))));
                                platform.main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Object v = Boolean.FALSE.equals(r.get("ok")) ? answer : r.get("value");
                                        if (web == browserWeb && !destroyed) {
                                            web.evaluateJavascript("window.AAFill && window.AAFill.apply(" + Json.stringify(
                                                    Json.obj(Json.str(q, "key"), v)) + ")", null);
                                        }
                                        askQuestions(web, qs, i + 1, done);
                                    }
                                });
                            }
                        });
                    }
                });
                if (options.isEmpty()) {
                    input.requestFocus();
                    dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                }
            }
        });
        dialog.show();
    }

    private void fillDone() {
        fillingForm = false;
        if (browserFillBtn != null) {
            browserFillBtn.setEnabled(true);
            browserFillBtn.setText("Fill the form");
        }
        try {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && browserWeb != null) {
                imm.hideSoftInputFromWindow(browserWeb.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    /** The site asks for a file: your CV, photo, or another file from the phone. */
    private void chooseSiteFile(final WebChromeClient.FileChooserParams params) {
        boolean wantsImage = false;
        if (params != null && params.getAcceptTypes() != null) {
            for (String type : params.getAcceptTypes()) {
                if (type != null && (type.contains("image") || type.contains("jpg") || type.contains("jpeg") || type.contains("png"))) {
                    wantsImage = true;
                    break;
                }
            }
        }
        if (wantsImage) {
            File photo = new File(getFilesDir(), "profile_photo.jpg");
            if (photo.isFile() && browserFiles != null) {
                browserFiles.onReceiveValue(new Uri[]{FilesProvider.uriForPhoto()});
                browserFiles = null;
                Toast.makeText(this, "Uploaded profile photo", Toast.LENGTH_SHORT).show();
                return;
            }
            pickSiteFile(params);
            return;
        }

        File cvFile = platform.engine.config.cvFile(platform.engine.config.load());
        if (cvFile != null && cvFile.isFile() && cvFile.length() > 0) {
            final String cvName = cvFile.getName();
            if (browserFiles != null) {
                browserFiles.onReceiveValue(new Uri[]{FilesProvider.uriForCv(cvName)});
                browserFiles = null;
                Toast.makeText(this, "Uploaded CV: " + cvName, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        pickSiteFile(params);
    }

    private void pickSiteFile(WebChromeClient.FileChooserParams params) {
        try {
            startActivityForResult(params.createIntent(), REQ_SITE_FILE);
        } catch (ActivityNotFoundException e) {
            if (browserFiles != null) {
                browserFiles.onReceiveValue(null);
                browserFiles = null;
            }
        }
    }

    private void saveBrowserJob() {
        if (browserWeb == null) {
            return;
        }
        final String url = browserWeb.getUrl() == null ? "" : browserWeb.getUrl();
        final String title = browserTitleView == null ? "" : String.valueOf(browserTitleView.getText());
        browserMainBtn.setEnabled(false);
        bridgePool.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, Object> r = Json.map(Json.tryParse(platform.api.handle("/api/copilot/add",
                        Json.stringify(Json.obj("text", title + "\n" + url, "subject", "")))));
                platform.main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (browserMainBtn == null) {
                            return;
                        }
                        browserMainBtn.setEnabled(true);
                        if (Boolean.FALSE.equals(r.get("ok"))) {
                            Toast.makeText(MainActivity.this, Json.str(r, "error"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        Map<String, Object> item = Json.map(r.get("item"));
                        browserJob = Json.str(item, "id");
                        browserJobTitle = Json.str(item, "title");
                        paintBrowserButtons();
                        Toast.makeText(MainActivity.this, Json.bool(item, "again") ? "Already in your jobs." : "Saved to your jobs.",
                                Toast.LENGTH_SHORT).show();
                        js("window.aaRefreshJobs && window.aaRefreshJobs()");
                        if (browserWeb != null) {
                            readJobPage(browserWeb, url);
                        }
                    }
                });
            }
        });
    }

    private void markBrowserApplied() {
        final String job = browserJob;
        browserMainBtn.setEnabled(false);
        bridgePool.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, Object> r = Json.map(Json.tryParse(platform.api.handle("/api/copilot/update",
                        Json.stringify(Json.obj("id", job, "state", "applied")))));
                platform.main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (browserMainBtn != null) {
                            browserMainBtn.setEnabled(true);
                        }
                        if (Boolean.FALSE.equals(r.get("ok"))) {
                            Toast.makeText(MainActivity.this, Json.str(r, "error"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        browserApplied = true;
                        closeBrowser();
                    }
                });
            }
        });
    }

    /** Back in the app: it asks whether you applied, unless you said so already. */
    void closeBrowser() {
        String job = browserJob;
        boolean applied = browserApplied;
        closePanes();
        js("window.aaBrowserClosed && window.aaBrowserClosed(" + Json.stringify(job) + "," + applied + ")");
    }

    // ------------------------------------------------------------------ permissions
    void requestPerm(String which) {
        String pkg = getPackageName();
        try {
            if ("notifications".equals(which)) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED && !askedNotify) {
                    askedNotify = true;
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
                } else if (Build.VERSION.SDK_INT >= 26) {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg));
                } else {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                }
            } else if ("battery".equals(which)) {
                if ("play".equals(BuildConfig.FLAVOR)) {
                    // Google Play build: the list in Settings (asking directly needs a permission Play restricts).
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    Toast.makeText(this, "Pick All apps, then JSTB, then Don't optimize.", Toast.LENGTH_LONG).show();
                } else {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)));
                    } catch (ActivityNotFoundException e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                }
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Open Settings > Apps > JSTB to change this.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        dash.evaluateJavascript("window.aaRefresh && window.aaRefresh()", null);
    }

    /** Back on Android 13+ (Android 16 no longer calls onBackPressed for apps that target it). */
    static final class Back33 {
        private Back33() {
        }

        static void register(final MainActivity a) {
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            a.handleBack();
                        }
                    });
        }
    }
}
