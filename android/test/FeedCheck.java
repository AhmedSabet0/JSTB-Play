package com.autoapply.bot.core;

import java.util.List;
import java.util.Map;

/** Asks the real LinkedIn and Wuzzuf like the phone does (Feed), and prints what came back as GitHub notices.
 *  Run by the build in .github/workflows/android.yml. */
public class FeedCheck {
    static void notice(String s) {
        System.out.println("::notice::" + s.replace("\n", " "));
    }

    public static void main(String[] a) throws Exception {
        String[] titles = {"Network Engineer", "DevOps Engineer"};
        int bad = 0;
        for (String t : titles) {
            Thread.sleep(3000);   // as the phone does: LinkedIn answers "busy" to quick searches
            try {
                List<Map<String, Object>> li = Feed.linkedin(t, "Egypt");
                notice("LinkedIn '" + t + "': " + li.size() + " jobs" + sample(li));
                if (li.isEmpty()) {
                    bad++;
                }
            } catch (Exception e) {
                bad++;
                notice("LinkedIn '" + t + "' failed: " + e);
            }
            try {
                String body = Json.stringify(Json.obj("startIndex", 0, "pageSize", 15, "longitude", "0", "latitude", "0",
                        "query", t, "searchFilters", Json.obj(), "sortBy", "date"));
                String raw = Feed.post("https://wuzzuf.net/api/search/job", body);
                Map<String, Object> first = Json.map(Json.arr(Json.map(Json.tryParse(raw)).get("data")).isEmpty() ? null
                        : Json.arr(Json.map(Json.tryParse(raw)).get("data")).get(0));
                notice("Wuzzuf search raw keys: " + Json.map(first.get("attributes")).keySet());
                List<Map<String, Object>> wz = Feed.wuzzuf(t);
                List<Map<String, Object>> kept = new java.util.ArrayList<Map<String, Object>>();
                for (Map<String, Object> j : wz) {
                    if (Json.dbl(j, "posted_at", 0) >= Util.now() - Feed.NEW_DAYS * 86400 && Feed.nearby(Json.str(j, "location"), "Egypt")) {
                        kept.add(j);
                    }
                }
                notice("Wuzzuf '" + t + "': " + wz.size() + " jobs, " + kept.size() + " from the last week in Egypt" + sample(kept));
                if (wz.isEmpty()) {
                    bad++;
                }
            } catch (Exception e) {
                bad++;
                notice("Wuzzuf '" + t + "' failed: " + e);
            }
        }
        System.exit(bad == 0 ? 0 : 1);
    }

    static String sample(List<Map<String, Object>> js) {
        StringBuilder b = new StringBuilder();
        double now = Util.now();
        for (int i = 0; i < Math.min(3, js.size()); i++) {
            Map<String, Object> j = js.get(i);
            b.append(" | ").append(Json.str(j, "title")).append(" @ ").append(Json.str(j, "company")).append(" (")
                    .append(Json.str(j, "location")).append(", ").append(Math.round((now - Json.dbl(j, "posted_at", now)) / 3600))
                    .append(" h ago) ").append(Json.str(j, "url"));
        }
        return b.toString();
    }
}
