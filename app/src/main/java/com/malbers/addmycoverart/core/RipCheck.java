package com.malbers.addmycoverart.core;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Compares one album folder with its MusicBrainz release: missing tracks, and tracks much shorter than
 * they should be. Report only; it reads headers and never touches a file. Albums without a MusicBrainz
 * ID are skipped rather than guessed at, for the same reason a wrong cover is worse than none.
 */
public final class RipCheck {
    /** A track is short when it falls this far below the release's length (whichever is larger). */
    static final long SHORT_MS = 2000;
    static final double SHORT_FRACTION = 0.03;
    static final int MAX_LISTED = 6;

    public static final class Result {
        public final Album album;
        /** Empty when the rip matches the release. */
        public final List<String> problems;
        /** Set when the album could not be checked. */
        public final String skipped;

        Result(Album album, List<String> problems, String skipped) {
            this.album = album;
            this.problems = problems;
            this.skipped = skipped;
        }

        public boolean ok() {
            return skipped == null && problems.isEmpty();
        }
    }

    public static final String NO_ID = "no MusicBrainz ID";
    public static final String NOT_ON_MB = "MusicBrainz ID not found";
    public static final String WHICH_DISC = "can't tell which disc";
    public static final String NETWORK = "network error";

    private static final Pattern DISC = Pattern.compile("(?i)\\b(?:disc|disk|cd)\\s*(\\d+)");

    private final Http http;

    public RipCheck(Http http) {
        this.http = http;
    }

    public Result check(Album a) {
        File[] files = a.dir.listFiles(f -> f.isFile() && AlbumScanner.isAudioName(f.getName()));
        if (files == null || files.length == 0) return skip(a, "no tracks");
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        List<TrackInfo> tracks = new ArrayList<>();
        for (File f : files) tracks.add(TagReader.readTrack(f));

        String mbid = null;
        Integer disc = null;
        for (TrackInfo t : tracks) {
            if (mbid == null) mbid = t.mbAlbumId;
            if (disc == null) disc = t.disc;
        }
        if (mbid == null) return skip(a, NO_ID);
        if (disc == null) disc = discFromName(a.dir.getName());

        JSONArray media;
        try {
            Http.Response res = http.get(ArtLookup.MB + "release/" + enc(mbid) + "?inc=recordings&fmt=json");
            if (res.code == 404 || res.code == 400) return skip(a, NOT_ON_MB);
            if (res.code != 200) return skip(a, NETWORK);
            media = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("media");
        } catch (IOException e) {
            return skip(a, NETWORK);
        } catch (JSONException e) {
            return skip(a, NOT_ON_MB);
        }
        if (media == null || media.length() == 0) return skip(a, NOT_ON_MB);

        JSONObject medium = pickMedium(media, disc);
        if (medium == null) return skip(a, WHICH_DISC);
        return new Result(a, compare(tracks, medium), null);
    }

    static List<String> compare(List<TrackInfo> files, JSONObject medium) {
        List<String> problems = new ArrayList<>();
        JSONArray rel = medium.optJSONArray("tracks");
        int expected = medium.optInt("track-count", rel == null ? 0 : rel.length());
        Map<Integer, Long> lengths = new HashMap<>();
        if (rel != null) {
            for (int i = 0; i < rel.length(); i++) {
                JSONObject t = rel.optJSONObject(i);
                if (t == null) continue;
                int pos = t.optInt("position", i + 1);
                if (!t.isNull("length")) lengths.put(pos, t.optLong("length"));
            }
        }

        if (expected > 0 && files.size() < expected) {
            String line = files.size() + " of " + expected + " tracks";
            TreeSet<Integer> have = new TreeSet<>();
            for (TrackInfo t : files) if (t.track != null) have.add(t.track);
            if (have.size() == files.size()) {
                List<Integer> gone = new ArrayList<>();
                for (int n = 1; n <= expected; n++) if (!have.contains(n)) gone.add(n);
                line += ", missing " + list(gone);
            }
            problems.add(line);
        }

        for (TrackInfo t : files) {
            if (t.track == null || t.durationMs == null) continue;
            if (t.durationMs < 1000) {
                problems.add("track " + t.track + " is empty (" + clock(t.durationMs) + ")");
                continue;
            }
            Long want = lengths.get(t.track);
            if (want == null || want <= 0) continue;
            long slack = Math.max(SHORT_MS, (long) (want * SHORT_FRACTION));
            if (t.durationMs < want - slack) {
                problems.add("track " + t.track + " is " + clock(t.durationMs) + ", expected " + clock(want));
            }
        }
        return problems;
    }

    /** The medium for this folder: its disc number, or the only medium there is. */
    static JSONObject pickMedium(JSONArray media, Integer disc) {
        if (media.length() == 1) return media.optJSONObject(0);
        if (disc == null) return null;
        for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.optJSONObject(i);
            if (m != null && m.optInt("position", i + 1) == disc) return m;
        }
        return null;
    }

    static Integer discFromName(String folder) {
        Matcher m = DISC.matcher(folder);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static String clock(long ms) {
        long s = Math.round(ms / 1000.0);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private static String list(List<Integer> n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_LISTED, n.size()); i++) {
            if (i > 0) b.append(", ");
            b.append(n.get(i));
        }
        if (n.size() > MAX_LISTED) b.append(" and ").append(n.size() - MAX_LISTED).append(" more");
        return b.toString();
    }

    private static Result skip(Album a, String why) {
        return new Result(a, new ArrayList<>(), why);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
