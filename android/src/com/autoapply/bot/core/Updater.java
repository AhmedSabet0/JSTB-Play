package com.autoapply.bot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version comparison utilities. In the Google Play edition, updates are managed by the Play Store. */
public final class Updater {

    private Updater() {
    }

    /** Compare versions like "1.10.2" and "v1.9" (numbers only). */
    public static int compare(String a, String b) {
        List<Long> x = numbers(a);
        List<Long> y = numbers(b);
        for (int k = 0; k < Math.max(x.size(), y.size()); k++) {
            long p = k < x.size() ? x.get(k) : 0;
            long q = k < y.size() ? y.get(k) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static List<Long> numbers(String v) {
        List<Long> out = new ArrayList<Long>();
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : v);
        while (m.find() && out.size() < 6) {
            try {
                out.add(Long.parseLong(m.group()));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out;
    }

    public static Map<String, Object> check(String source, Map<String, Object> current) {
        return Json.obj("available", false, "store", "play");
    }
}
