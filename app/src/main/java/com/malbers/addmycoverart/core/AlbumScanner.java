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

    /**
     * Name of a real art file currently in {@code dir}, or null. 1.1.0: a picture-named file that is empty or isn't
     * a picture (found on Michael's A6: 0-byte album.jpg from failed rips, PDF booklets named album.jpg) does not
     * count, so the album is offered a fetch. The bad file itself is never touched.
     */
    public static String findArtFile(File dir) {
        String[] names = dir.list();
        if (names == null) return null;
        for (String n : names) if (isArtName(n) && artProblem(new File(dir, n)) == null) return n;
        return null;
    }

    /**
     * Null when the file is a picture (JPEG or PNG, by its first bytes); otherwise why not, as the end of a sentence
     * starting with the file name: "is empty", "isn't a picture (PDF)", "isn't a picture", "can't be read".
     */
    public static String artProblem(File f) {
        long len = f.length();
        if (len == 0) return "is empty";
        byte[] h = new byte[8];
        int n;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            n = in.read(h);
        } catch (java.io.IOException e) {
            return "can't be read";
        }
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return null;
        if (n >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return null;
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return "isn't a picture (PDF)";
        return "isn't a picture";
    }

    /** Picture-named files in {@code dir} that don't count (empty, not a picture), with why. */
    public static java.util.List<String[]> badArtFiles(File dir) {
        java.util.List<String[]> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) return out;
        Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (String n : names) {
            if (!isArtName(n)) continue;
            String why = artProblem(new File(dir, n));
            if (why != null) out.add(new String[]{n, why});
        }
        return out;
    }

    /** {@link #bestArtFile(String[])} among the names that are real pictures in {@code dir}. */
    public static String bestArtFile(File dir, String[] names) {
        if (names == null) return null;
        List<String> ok = new ArrayList<>();
        for (String n : names) if (isArtName(n) && artProblem(new File(dir, n)) == null) ok.add(n);
        return bestArtFile(ok.toArray(new String[0]));
    }

    /**
     * 1.1.0: the picture to SHOW for a folder, in the handoff's order (folder, cover, front, then album), any case.
     * Null when there is none. {@link #findArtFile} stays the "is there art at all" check.
     */
    public static String bestArtFile(String[] names) {
        if (names == null) return null;
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (String n : names) {
            if (!isArtName(n)) continue;
            String l = n.toLowerCase(Locale.ROOT);
            int rank = l.startsWith("folder") ? 0 : l.startsWith("cover") ? 1 : l.startsWith("front") ? 2 : 3;
            if (l.endsWith(".png")) rank += 4; // a jpg of the same name first
            if (rank < bestRank) { bestRank = rank; best = n; }
        }
        return best;
    }

    /** 1.1.0, live scanning tiles (03a): told about each album as it is found; returns true to stop early. */
    public interface Progress {
        boolean onAlbum(Album a);
    }

    public static ScanResult scan(File root) {
        return scan(root, null);
    }

    public static ScanResult scan(File root, Progress progress) {
        List<Album> missing = new ArrayList<>();
        List<Album> withArt = new ArrayList<>();
        walk(root, 0, missing, withArt, progress);
        Comparator<Album> byPath = Comparator.comparing(a -> a.dir.getPath().toLowerCase(Locale.ROOT));
        missing.sort(byPath);
        withArt.sort(byPath);
        return new ScanResult(missing, withArt);
    }

    /** Returns false when the progress listener asked to stop. */
    private static boolean walk(File dir, int depth, List<Album> missing, List<Album> withArt, Progress progress) {
        File[] kids = dir.listFiles();
        if (kids == null) return true;
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
        if (art != null) {
            // the one to show when there are several (folder.jpg before cover.jpg ...)
            String[] names = new String[kids.length];
            for (int i = 0; i < kids.length; i++) names[i] = kids[i].isFile() ? kids[i].getName() : "";
            art = bestArtFile(dir, names); // null when every picture-named file is empty or not a picture
        }
        if (tracks > 0) {
            boolean embedded = art == null && TagReader.read(first).hasPicture;
            Album a = new Album(dir, tracks, first, art, embedded);
            (a.hasArt() ? withArt : missing).add(a);
            if (progress != null && progress.onAlbum(a)) return false;
        }
        if (depth >= MAX_DEPTH) return true;
        for (File k : kids) {
            if (k.isDirectory() && !k.getName().startsWith(".")
                    && !walk(k, depth + 1, missing, withArt, progress)) return false;
        }
        return true;
    }
}
