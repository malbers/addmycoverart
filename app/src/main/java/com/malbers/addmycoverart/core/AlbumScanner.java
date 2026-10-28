package com.malbers.addmycoverart.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Walks a folder tree, read-only, and sorts album folders into "has art" and "missing art". */
public final class AlbumScanner {
    private static final int MAX_DEPTH = 8;
    private static final Pattern ART = Pattern.compile("(folder|album|cover|front)\\.(jpe?g|png)");
    private static final Pattern AUDIO = Pattern.compile(".*\\.(flac|wav|mp3|m4a|aiff?|dsf|dff|ape|wv|ogg|opus)");

    private AlbumScanner() {}

    public static boolean isArtName(String name) {
        return ART.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    public static boolean isAudioName(String name) {
        return AUDIO.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    /** Name of an art file currently in {@code dir}, or null. */
    public static String findArtFile(File dir) {
        String[] names = dir.list();
        if (names == null) return null;
        for (String n : names) if (isArtName(n)) return n;
        return null;
    }

    public static ScanResult scan(File root) {
        List<Album> missing = new ArrayList<>();
        List<Album> withArt = new ArrayList<>();
        walk(root, 0, missing, withArt);
        Comparator<Album> byPath = Comparator.comparing(a -> a.dir.getPath().toLowerCase(Locale.ROOT));
        missing.sort(byPath);
        withArt.sort(byPath);
        return new ScanResult(missing, withArt);
    }

    private static void walk(File dir, int depth, List<Album> missing, List<Album> withArt) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        int tracks = 0;
        File first = null;
        String art = null;
        for (File k : kids) {
            if (!k.isFile()) continue;
            String n = k.getName();
            if (isArtName(n)) art = n;
            else if (isAudioName(n)) {
                tracks++;
                if (first == null) first = k;
            }
        }
        if (tracks > 0) {
            boolean embedded = art == null && TagReader.read(first).hasPicture;
            Album a = new Album(dir, tracks, first, art, embedded);
            (a.hasArt() ? withArt : missing).add(a);
        }
        if (depth >= MAX_DEPTH) return;
        for (File k : kids) {
            if (k.isDirectory() && !k.getName().startsWith(".")) walk(k, depth + 1, missing, withArt);
        }
    }
}
