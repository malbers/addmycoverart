package com.malbers.addmycoverart.core;

import java.text.Normalizer;
import java.util.Locale;

public final class Text {
    private Text() {}

    /** Comparison key for titles and artist names: case, accents, "&", a leading "The" and bracketed suffixes ignored. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (n.startsWith("the ")) n = n.substring(4);
        // Library-sort form, "Album II, The".
        if (n.endsWith(" the")) n = n.substring(0, n.length() - 4);
        return n;
    }
}
