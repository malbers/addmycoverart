package com.malbers.addmycoverart.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Guesses {artist, album} from folder names when a file has no usable tags. Either may be null.
 *
 * <p>Handles "Artist/Album", "Artist - Album", and longer library names such as
 * "Al Di Meola - 2006 - Consequence Of Chaos - DSF - 64" or "Abba = 1977 - The Album (Deluxe Ed 2007) - FLAC"
 * (1.1.1, Václav H.'s library): a year right after the artist and format parts at the end are dropped.
 */
public final class FolderNameParser {
    /** 1.1.2: Roman numerals too ("CD I", "Disc II"; Václav H. 2026-11-25: "CD I" was searched as the album). */
    private static final Pattern DISC = Pattern.compile("(?i)(cd|disc|disk)\\s*(\\d+|[ivx]{1,4}\\b).*");
    /** " - ", " = " or " – " with spaces around it; a dash inside a word ("Limelight-The Best") is not a separator. */
    private static final Pattern SEP = Pattern.compile("\\s+[-=\u2013]\\s+");
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    /** A format part: "FLAC", "FLAC 24-96", "DSF 512", "Mp3", "Wv", "APE", "SACD"... */
    private static final Pattern FORMAT = Pattern.compile(
            "(?i)(flac|alac|wav|aiff?|dsf|dff|dsd\\d*|sacd(\\s+iso)?|iso|ape|wv|wavpack|mp3|m4a|aac|ogg|opus|mqa|hi-?res)"
                    + "(\\s+[0-9][0-9./x -]*\\s*(k|khz|kbps|bit)?)?");
    /** A bare number left after a format word: the "64" in "DSF - 64". */
    private static final Pattern NUMBERS = Pattern.compile("(?i)[0-9][0-9./x -]*\\s*(k|khz|kbps|bit)?");
    /** An edition note as its own part: "Jap Ed", "Deluxe Edition", "Remastered". */
    private static final Pattern EDITION = Pattern.compile(
            "(?i)((jap|japan|japanese|uk|us|eu|deluxe|limited|lim|special|expanded|anniversary|\\d{4})\\s+)*(ed|edition)\\.?"
                    + "(\\s+\\d{4})?|remaster(ed)?(\\s+\\d{4})?");
    private static final Pattern UNKNOWN = Pattern.compile("(?i)unknown( album)?|untitled( album)?|no (album|title)");
    /** Folders that hold albums but name no artist (Michael's "Compilations", the "Music" root). */
    private static final Pattern NOT_AN_ARTIST = Pattern.compile(
            "(?i)compilations?|various( artists)?|va|soundtracks?|samples?|music|mp3s?|flac|albums?|storage|download|eversolomusic"
                    + "|isos?|sacds?|dsd|dsf|hi-?res");

    private FolderNameParser() {}

    /** iso7 Q3: a holder folder ("ISO", "Music", "Compilations", a one-letter shelf) that names no artist. */
    public static boolean isNotAnArtist(String folder) {
        String p = folder == null ? "" : folder.trim();
        return p.length() <= 1 || NOT_AN_ARTIST.matcher(p).matches();
    }

    /**
     * 1.1.2: for a SACD ISO album the ISO's own file name says more than its folder (Michael's ISO/<Artist>/ layout:
     * "Boston - Boston (1976) [SACD] (2000 Remaster ISO).iso"). Bracketed notes, "(... ISO)" and a bare year in
     * brackets are dropped. Any other album: the folder, as before.
     */
    public static String[] guess(Album a) {
        File first = a.firstAudio;
        if (first == null || !first.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso")) return guess(a.dir);
        String n = first.getName().substring(0, first.getName().length() - 4);
        n = n.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("(?i)\\([^()]*\\biso\\b[^()]*\\)", " ")
                .replaceAll("\\((19|20)\\d{2}\\)", " ")
                .replaceAll("(?i)\\([^()]*\\b((19|20)\\d{2}|remaster(ed)?|sacd|deluxe|edition|mfsl)\\b[^()]*\\)", " ")
                .replaceAll("\\s+", " ").trim()
                .replaceAll("(?<=\\S)[-_]((19|20)\\d{2})$", "");
        return guess(new File(a.dir, n));
    }

    public static String[] guess(File albumDir) {
        File dir = albumDir;
        if (DISC.matcher(dir.getName()).matches() && dir.getParentFile() != null) dir = dir.getParentFile();
        List<String> parts = new ArrayList<>();
        for (String s : SEP.split(dir.getName().trim())) {
            if (!s.trim().isEmpty()) parts.add(s.trim());
        }
        // format parts at the end
        while (parts.size() > 1) {
            String last = parts.get(parts.size() - 1);
            boolean format = FORMAT.matcher(last).matches() || EDITION.matcher(last).matches();
            boolean numberAfterFormat = parts.size() > 2 && NUMBERS.matcher(last).matches() && !YEAR.matcher(last).matches()
                    && FORMAT.matcher(parts.get(parts.size() - 2)).matches();
            if (!format && !numberAfterFormat) break;
            parts.remove(parts.size() - 1);
        }
        // 1.1.2 Q5 (Michael's "1990 - Violator (DMCD7 …).iso" was searched as artist "1990"): a name that STARTS
        // with a year is "year - album", so the year goes and the artist comes from the folder above.
        if (parts.size() >= 2 && YEAR.matcher(parts.get(0)).matches()) parts.remove(0);
        String artist;
        if (parts.size() >= 2) {
            artist = parts.remove(0);
            // a year right after the artist, when something is left for the title ("Prince - 1999" keeps it)
            if (parts.size() >= 2 && YEAR.matcher(parts.get(0)).matches()) parts.remove(0);
        } else {
            File parent = dir.getParentFile();
            String p = parent == null ? "" : parent.getName().trim();
            // a one-letter folder is an A-Z shelf ("A/Adele - 25"), not an artist; nor is "Compilations"
            artist = p.length() <= 1 || NOT_AN_ARTIST.matcher(p).matches() ? null : p;
        }
        String album = String.join(" - ", parts).trim();
        if (album.isEmpty() || UNKNOWN.matcher(album).matches()) album = null;
        return new String[]{artist, album};
    }

    /** True for an album tag that names nothing: "Unknown Album", "Untitled". */
    public static boolean isPlaceholder(String album) {
        return album == null || album.trim().isEmpty() || UNKNOWN.matcher(album.trim()).matches();
    }
}
