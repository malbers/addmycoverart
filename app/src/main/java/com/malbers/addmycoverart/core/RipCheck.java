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
 * Compares one album folder with what it should contain: missing tracks, and tracks much shorter than
 * they should be. Report only; it reads headers and never touches a file.
 *
 * <p>Three ways in, most exact first:
 * <ol>
 *   <li>A MusicBrainz release ID in the tags: exact track count and track lengths.</li>
 *   <li>A track total in the tags ("3/14", TRACKTOTAL), or gaps in the track numbers: no network.</li>
 *   <li>A MusicBrainz search on artist + title, used only when every matching edition agrees on the
 *       disc's track count. Never the edition closest to the folder: that would match the broken rip.</li>
 * </ol>
 * Anything else is "not checked" rather than guessed at, for the same reason a wrong cover is worse than none.
 */
public final class RipCheck {
    /**
     * A track is short when it falls this far below the release's length (whichever is larger). Loose on
     * purpose: MusicBrainz lengths are often a few seconds off (1:35 vs 1:40 on a good rip), while a failed
     * rip is off by far more.
     */
    static final long SHORT_MS = 10_000;
    static final double SHORT_FRACTION = 0.10;
    /** Share of matching editions that must agree on a disc's track count for a search result to be used. */
    static final double MAJORITY = 2.0 / 3;
    static final int MAX_LISTED = 6;
    static final int MIN_SCORE = 90;

    public static final class Result {
        public final Album album;
        /** Empty when the rip matches. */
        public final List<String> problems;
        /** Set when the album could not be checked. */
        public final String skipped;
        /** What the album was compared with: one of the BY_ constants, or null when skipped. */
        public final String basis;

        Result(Album album, List<String> problems, String skipped, String basis) {
            this.album = album;
            this.problems = problems;
            this.skipped = skipped;
            this.basis = basis;
        }

        public boolean ok() {
            return skipped == null && problems.isEmpty();
        }
    }

    public static final String BY_ID = "MusicBrainz ID";
    public static final String BY_TAGS = "track tags";
    public static final String BY_SEARCH = "MusicBrainz search";

    public static final String NO_INFO = "no track total, not found on MusicBrainz";
    public static final String NOT_ON_MB = "MusicBrainz ID not found";
    public static final String WHICH_DISC = "can't tell which disc";
    public static final String EDITIONS_DIFFER = "editions differ on MusicBrainz";
    public static final String NO_NAME = "no artist or album name";
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
        if (disc == null) disc = discFromName(a.dir.getName());

        if (mbid != null) {
            try {
                return byId(a, tracks, mbid, disc);
            } catch (IOException e) {
                return skip(a, NETWORK);
            } catch (JSONException e) {
                return skip(a, NOT_ON_MB);
            }
        }

        // A stated total in the tags settles the count without the network.
        Integer total = statedTotal(tracks);
        if (total != null) return new Result(a, compare(tracks, total, new HashMap<>()), null, BY_TAGS);

        // Gaps in the numbers only see holes below the highest track present, so the search still runs
        // to learn the real count; the gaps stand on their own when it can't say.
        List<String> gaps = gaps(tracks);
        Result searched;
        try {
            searched = bySearch(a, tracks, files[0], disc);
        } catch (IOException e) {
            searched = skip(a, NETWORK);
        } catch (JSONException e) {
            searched = skip(a, NO_INFO);
        }
        if (searched.skipped != null && !gaps.isEmpty()) return new Result(a, gaps, null, BY_TAGS);
        return searched;
    }

    // ---- 1. MusicBrainz ID ----

    private Result byId(Album a, List<TrackInfo> tracks, String mbid, Integer disc) throws IOException, JSONException {
        Http.Response res = http.get(ArtLookup.MB + "release/" + enc(mbid) + "?inc=recordings&fmt=json");
        if (res.code == 404 || res.code == 400) return skip(a, NOT_ON_MB);
        if (res.code != 200) return skip(a, NETWORK);
        JSONArray media = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("media");
        if (media == null || media.length() == 0) return skip(a, NOT_ON_MB);
        JSONObject medium = pickMedium(media, disc);
        if (medium == null) return skip(a, WHICH_DISC);
        return new Result(a, compare(tracks, medium), null, BY_ID);
    }

    // ---- 2. tags ----

    /** The track total most files agree on, or null when no file states one. */
    static Integer statedTotal(List<TrackInfo> tracks) {
        Map<Integer, Integer> votes = new HashMap<>();
        for (TrackInfo t : tracks) if (t.trackTotal != null) votes.merge(t.trackTotal, 1, Integer::sum);
        Integer best = null;
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            if (best == null || e.getValue() > votes.get(best)) best = e.getKey();
        }
        return best;
    }

    /** "missing 3, 4" when the track numbers skip, judged up to the highest number present. */
    static List<String> gaps(List<TrackInfo> tracks) {
        TreeSet<Integer> have = new TreeSet<>();
        for (TrackInfo t : tracks) {
            if (t.track == null) return new ArrayList<>(); // unnumbered files: can't tell
            have.add(t.track);
        }
        List<String> out = new ArrayList<>();
        if (have.isEmpty()) return out;
        List<Integer> gone = new ArrayList<>();
        for (int n = 1; n <= have.last(); n++) if (!have.contains(n)) gone.add(n);
        if (!gone.isEmpty()) out.add("missing tracks " + list(gone));
        return out;
    }

    // ---- 3. search ----

    private Result bySearch(Album a, List<TrackInfo> tracks, File first, Integer disc) throws IOException, JSONException {
        Tags t = TagReader.read(first);
        String artist = t.bestArtist();
        String album = t.album;
        if (artist == null || album == null) {
            String[] g = FolderNameParser.guess(a.dir);
            if (artist == null) artist = g[0];
            if (album == null) album = g[1];
        }
        if (artist == null || album == null) return skip(a, NO_NAME);

        String q = "release:\"" + clean(ArtLookup.searchTitle(album)) + "\" AND artist:\"" + clean(artist) + "\"";
        Http.Response res = http.get(ArtLookup.MB + "release/?query=" + enc(q) + "&fmt=json&limit=25");
        if (res.code >= 500) return skip(a, NETWORK);
        if (res.code != 200) return skip(a, NO_INFO);
        JSONArray releases = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("releases");
        Integer expected = agreedCount(releases, artist, album, disc, tracks.size());
        if (expected == null) return skip(a, NO_INFO);
        if (expected < 0) return skip(a, expected == WHICH ? WHICH_DISC : EDITIONS_DIFFER);
        return new Result(a, compare(tracks, expected, new HashMap<>()), null, BY_SEARCH);
    }

    static final int DIFFER = -1;
    static final int WHICH = -2;

    /**
     * The disc's track count that a clear majority of matching CD editions share. Null when nothing
     * matches; {@link #DIFFER} when editions disagree; {@link #WHICH} when the disc can't be told.
     * {@code folderTracks} only decides whether a disc-less folder holds a whole multi-disc set; it never
     * picks an edition.
     */
    static Integer agreedCount(JSONArray releases, String artist, String album, Integer disc, int folderTracks) {
        if (releases == null) return null;
        String wantTitle = Text.norm(ArtLookup.searchTitle(album));
        String wantArtist = Text.norm(artist);
        List<JSONArray> cd = new ArrayList<>();
        List<JSONArray> other = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optInt("score") < MIN_SCORE) continue;
            if (!Text.norm(ArtLookup.searchTitle(r.optString("title"))).equals(wantTitle)) continue;
            String credit = Text.norm(credit(r));
            if (credit.isEmpty() || !(credit.equals(wantArtist) || credit.contains(wantArtist) || wantArtist.contains(credit))) continue;
            JSONArray media = r.optJSONArray("media");
            if (media == null || media.length() == 0) continue;
            (allCd(media) ? cd : other).add(media);
        }
        List<JSONArray> pool = cd.isEmpty() ? other : cd;
        if (pool.isEmpty()) return null;

        List<Integer> counts = new ArrayList<>();
        if (disc != null) {
            for (JSONArray media : pool) {
                if (disc > media.length()) continue; // a single-disc edition of a two-disc set: not comparable
                int n = trackCount(media.optJSONObject(disc - 1));
                if (n > 0) counts.add(n);
            }
        } else {
            // No disc number: single-disc editions describe this folder. Only when every edition is a set
            // can the folder be the whole set, and then only if it holds more than any one disc.
            int biggestDisc = 0;
            List<Integer> sets = new ArrayList<>();
            for (JSONArray media : pool) {
                if (media.length() == 1) {
                    int n = trackCount(media.optJSONObject(0));
                    if (n > 0) counts.add(n);
                } else {
                    int sum = 0;
                    for (int i = 0; i < media.length(); i++) {
                        int n = trackCount(media.optJSONObject(i));
                        biggestDisc = Math.max(biggestDisc, n);
                        sum += n;
                    }
                    if (sum > 0) sets.add(sum);
                }
            }
            if (counts.isEmpty()) {
                if (sets.isEmpty()) return null;
                if (folderTracks <= biggestDisc) return WHICH;
                counts = sets;
            }
        }
        if (counts.isEmpty()) return null;
        return majority(counts);
    }

    /** The value at least {@link #MAJORITY} of the list shares, else {@link #DIFFER}. */
    static int majority(List<Integer> counts) {
        Map<Integer, Integer> votes = new HashMap<>();
        for (int n : counts) votes.merge(n, 1, Integer::sum);
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            if (e.getValue() >= MAJORITY * counts.size()) return e.getKey();
        }
        return DIFFER;
    }

    private static int trackCount(JSONObject medium) {
        return medium == null ? 0 : medium.optInt("track-count");
    }

    private static boolean allCd(JSONArray media) {
        for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.optJSONObject(i);
            if (m == null || !m.optString("format").toLowerCase(Locale.ROOT).contains("cd")) return false;
        }
        return true;
    }

    private static String credit(JSONObject release) {
        JSONArray ac = release.optJSONArray("artist-credit");
        if (ac == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ac.length(); i++) {
            JSONObject c = ac.optJSONObject(i);
            if (c != null) b.append(c.optString("name")).append(c.optString("joinphrase"));
        }
        return b.toString();
    }

    // ---- comparison ----

    static List<String> compare(List<TrackInfo> files, JSONObject medium) {
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
        return compare(files, expected, lengths);
    }

    /** Count against {@code expected}, then each track's length against {@code lengths} (by track number). */
    static List<String> compare(List<TrackInfo> files, int expected, Map<Integer, Long> lengths) {
        List<String> problems = new ArrayList<>();
        if (expected > 0 && files.size() < expected) {
            String line = files.size() + " of " + expected + " tracks";
            TreeSet<Integer> have = new TreeSet<>();
            for (TrackInfo t : files) if (t.track != null) have.add(t.track);
            if (have.size() == files.size()) {
                List<Integer> gone = new ArrayList<>();
                for (int n = 1; n <= expected; n++) if (!have.contains(n)) gone.add(n);
                if (!gone.isEmpty()) line += ", missing " + list(gone);
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
        return new Result(a, new ArrayList<>(), why, null);
    }

    private static String clean(String s) {
        return s.replace("\"", " ").replace("\\", " ").trim();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
