package com.autoapply.bot.core;

import java.util.List;

/** Helpers and interfaces for job applications and formatting. */
public final class LinkedIn {
    public static final String JOB_URL = "https://www.linkedin.com/jobs/view/%s/";

    public interface Resolver {
        Object resolve(String question, String kind, List<String> options, String hint, String placeholder, boolean noCache,
                       List<String> rejected, int maxlen, List<String> extra);
    }

    public interface Hooks {
        void remember(String question, Object value);

        void forget(String question);

        boolean manual();

        void signInNeeded();
    }

    public static final class Outcome {
        public final String status;
        public final String note;
        public final List<Object> qa;

        public Outcome(String status, String note, List<Object> qa) {
            this.status = status;
            this.note = note;
            this.qa = qa;
        }
    }

    private LinkedIn() {
    }

    public static String shorten(String text, int maxlen) {
        if (maxlen <= 0 || text.length() <= maxlen) {
            return text;
        }
        String cut = text.substring(0, maxlen);
        if (cut.contains(" ")) {
            cut = cut.substring(0, cut.lastIndexOf(' '));
        }
        String trimmed = cut.replaceAll("[ ,.;:\\-]+$", "");
        return trimmed.isEmpty() ? text.substring(0, maxlen) : trimmed;
    }
}
