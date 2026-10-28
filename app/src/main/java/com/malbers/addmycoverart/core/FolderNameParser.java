package com.malbers.addmycoverart.core;

import java.io.File;
import java.util.regex.Pattern;

/** Guesses {artist, album} from folder names when a file has no usable tags. Artist may be null. */
public final class FolderNameParser {
    private static final Pattern DISC = Pattern.compile("(?i)(cd|disc|disk)\\s*\\d+.*");

    private FolderNameParser() {}

    public static String[] guess(File albumDir) {
        File dir = albumDir;
        if (DISC.matcher(dir.getName()).matches() && dir.getParentFile() != null) dir = dir.getParentFile();
        String name = dir.getName().trim();
        int dash = name.indexOf(" - ");
        if (dash > 0) return new String[]{name.substring(0, dash).trim(), name.substring(dash + 3).trim()};
        File parent = dir.getParentFile();
        String artist = parent == null || parent.getName().isEmpty() ? null : parent.getName().trim();
        return new String[]{artist, name};
    }
}
