package com.autoapply.bot.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** The page scripts in li.js: sections that start with a "//# name" line. */
public final class Scripts {
    private final Map<String, String> sections = new LinkedHashMap<String, String>();

    public Scripts(String text) {
        String name = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("//# ")) {
                if (name != null) {
                    sections.put(name, body.toString().trim());
                }
                name = line.substring(4).trim();
                body.setLength(0);
            } else if (name != null) {
                body.append(line).append('\n');
            }
        }
        if (name != null) {
            sections.put(name, body.toString().trim());
        }
        if (!sections.containsKey("HELPERS")) {
            throw new IllegalArgumentException("li.js has no HELPERS section");
        }
    }

    public String get(String name) {
        String s = sections.get(name);
        if (s == null) {
            throw new IllegalArgumentException("No page script named " + name);
        }
        return s;
    }

    public String helpers() {
        return sections.get("HELPERS");
    }
}
