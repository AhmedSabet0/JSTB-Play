package com.autoapply.bot.core;

/** The browser tab the bot drives (an Android WebView on the phone, headless Chrome in tests). */
public interface Page {
    /** Run a JavaScript expression in the page; returns its value as JSON text ("null" if it failed or is undefined). */
    String eval(String js);

    /** Load a URL and wait until the document is parsed. Throws a RuntimeException if it cannot be loaded. */
    void navigate(String url, double timeoutSeconds);

    String url();

    boolean isClosed();

    /** Tap the page like a finger (real input, not a script click) at (x, y): CSS pixels of the visible area, which is
     *  `viewportWidth` CSS pixels wide. Returns false when this page cannot do it. */
    boolean tap(double x, double y, double viewportWidth);
}
