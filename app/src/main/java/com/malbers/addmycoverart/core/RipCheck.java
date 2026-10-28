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
 * Compares one album folder with what it should contain: missing tracks, empty or much-too-short tracks,
 * and track tags that disagree with the file names. Report only; it reads headers and never touches a file.
 *
 * <p>Three ways in, most exact first:
 * <ol>
 *   <li>A MusicBrainz release ID in the tags: exact track count and track lengths.</li>
 *   <li>A track total in the tags ("3/14", TRACKTOTAL): no network.</li>
 *   <li>A MusicBrainz search on artist + title, used only when a clear majority of matching CD editions
 *       agree on the disc's track count. Never the edition closest to the folder: that would match the
 *       broken rip. Lengths then come from one edition with that count.</li>
 * </ol>
 * Gaps in the track numbers are reported when nothing else can say. Anything else is "not checked"
 * rather than guessed at, for the same reason a wrong cover is worse than none.
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
    /** Under this size an audio file holds no real audio (a 1,139-byte FLAC is a header and nothing else). */
    static final long EMPTY_BYTES = 64 * 1024;
    static final int MAX_LISTED = 6;
    static final int MIN_SCORE = 90;

    /** Files that are expected next to a rip and are not stray. */
    private static final Pattern KNOWN_EXTRA = Pattern.compile(
            "(?i).*\\.(jpe?g|png|gif|bmp|webp|cue|log|m3u8?|pls|nfo|txt|accurip|md5|sfv|ffp|pdf)|desktop\\.ini|thumbs\\.db|\\.ds_store");

    public static final class Result {
        public final Album album;
        /** From the tags, else the parent folder; may be null. */
        public final String artist;
        /** Empty when the rip matches. */
        public final List<String> problems;
        /** Set when the album could not be checked. */
        public final String skipped;
        /** What the album was compared with: one of the BY_ constants, or null when skipped. */
        public final String basis;

        Result(Album album, String artist, List<String> problems, String skipped, String basis) {
            this.album = album;
            this.artist = artist;
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
    public static final String ZERO_BASED = "track tags start at 0 instead of 1 (counted from 1 here)";

    private static final Pattern DISC = Pattern.compile("(?i)\\b(?:disc|disk|cd)\\s*(\\d+)");

    private final Http http;

    public RipCheck(Http http) {
        this.http = http;
    }

    public Result check(Album a) {
        File[] files = a.dir.listFiles(f -> f.isFile() && AlbumScanner.isAudioName(f.getName()));
        if (files == null || files.length == 0) return skip(a, null, "no tracks");
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        List<TrackInfo> tracks = new ArrayList<>();
        for (File f : files) tracks.add(TagReader.readTrack(f).withFile(f));
        // Tags that count from 0 are shifted up before anything is compared, and reported once.
        boolean zeroBased = tracks.stream().anyMatch(t -> t.track != null && t.track == 0);
        if (zeroBased) {
            List<TrackInfo> shifted = new ArrayList<>();
            for (TrackInfo t : tracks) shifted.add(t.track == null ? t : t.withTrack(t.track + 1));
            tracks = shifted;
        }

        Tags tags = TagReader.read(files[0]);
        String artist = tags.bestArtist();
        if (artist == null && a.dir.getParentFile() != null) artist = a.dir.getParentFile().getName();

        String mbid = null;
        Integer disc = null;
        for (TrackInfo t : tracks) {
            if (mbid == null) mbid = t.mbAlbumId;
            if (disc == null) disc = t.disc;
        }
        if (disc == null) disc = discFromName(a.dir.getName());

        Result r = verdict(a, artist, tracks, tags, files[0], mbid, disc);
        if (r.skipped != null) return r;

        // Additions that hold whatever the verdict was compared with.
        List<String> problems = new ArrayList<>(r.problems);
        if (zeroBased) problems.add(ZERO_BASED);
        String mismatch = tagNameMismatch(tracks);
        if (mismatch != null) problems.add(mismatch);
        if (r.problems.stream().anyMatch(p -> p.contains(" of ") || p.startsWith("missing"))) {
            String stray = strayFiles(a.dir);
            if (stray != null) problems.add(stray);
        }
        return new Result(a, artist, problems, null, r.basis);
    }

    private Result verdict(Album a, String artist, List<TrackInfo> tracks, Tags tags, File first, String mbid, Integer disc) {
        if (mbid != null) {
            try {
                return byId(a, artist, tracks, mbid, disc);
            } catch (IOException e) {
                return skip(a, artist, NETWORK);
            } catch (JSONException e) {
                return skip(a, artist, NOT_ON_MB);
            }
        }

        // A stated total in the tags settles the count without the network.
        Integer total = statedTotal(tracks);
        if (total != null) return new Result(a, artist, compare(tracks, total, new HashMap<>()), null, BY_TAGS);

        // Gaps in the numbers only see holes below the highest track present, so the search still runs
        // to learn the real count; the gaps stand on their own when it can't say.
        List<String> gaps = gaps(tracks);
        Result searched;
        try {
            searched = bySearch(a, artist, tracks, tags, first, disc);
        } catch (IOException e) {
            searched = skip(a, artist, NETWORK);
        } catch (JSONException e) {
            searched = skip(a, artist, NO_INFO);
        }
        if (searched.skipped == null) return searched;
        // Nothing to compare the count with, but an empty file is a problem on its own.
        List<String> local = new ArrayList<>(gaps);
        local.addAll(empties(tracks));
        if (!local.isEmpty()) return new Result(a, artist, local, null, BY_TAGS);
        return searched;
    }

    // ---- 1. MusicBrainz ID ----

    private Result byId(Album a, String artist, List<TrackInfo> tracks, String mbid, Integer disc) throws IOException, JSONException {
        Http.Response res = http.get(ArtLookup.MB + "release/" + enc(mbid) + "?inc=recordings&fmt=json");
        if (res.code == 404 || res.code == 400) return skip(a, artist, NOT_ON_MB);
        if (res.code != 200) return skip(a, artist, NETWORK);
        JSONArray media = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("media");
        if (media == null || media.length() == 0) return skip(a, artist, NOT_ON_MB);
        JSONObject medium = pickMedium(media, disc);
        if (medium == null) return skip(a, artist, WHICH_DISC);
        return new Result(a, artist, compare(tracks, medium), null, BY_ID);
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

    /** "missing tracks 3, 4" when the track numbers skip, judged up to the highest number present. */
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

    /**
     * "track tags are 1 lower than the file names" when most numbered files disagree by the same amount,
     * else "track tags don't match the file names on N files". Null when they agree.
     */
    static String tagNameMismatch(List<TrackInfo> tracks) {
        int both = 0;
        Map<Integer, Integer> offsets = new HashMap<>();
        for (TrackInfo t : tracks) {
            if (t.track == null || t.fileNumber == null) continue;
            both++;
            int d = t.track - t.fileNumber;
            if (d != 0) offsets.merge(d, 1, Integer::sum);
        }
        int off = 0;
        for (int n : offsets.values()) off += n;
        if (off == 0) return null;
        if (offsets.size() == 1) {
            int d = offsets.keySet().iterator().next();
            String how = Math.abs(d) + (d < 0 ? " lower" : " higher");
            String scope = off == both ? "" : " from about file " + firstMismatch(tracks);
            return "track tags are " + how + " than the file names" + scope + " (" + off + " of " + both + " files)";
        }
        return "track tags don't match the file names on " + off + " of " + both + " files";
    }

    private static int firstMismatch(List<TrackInfo> tracks) {
        int first = Integer.MAX_VALUE;
        for (TrackInfo t : tracks) {
            if (t.track != null && t.fileNumber != null && !t.track.equals(t.fileNumber)) first = Math.min(first, t.fileNumber);
        }
        return first;
    }

    /** Files in the folder that are neither audio nor the usual extras, e.g. a FLAC that lost its extension. */
    static String strayFiles(File dir) {
        File[] all = dir.listFiles(File::isFile);
        if (all == null) return null;
        List<String> stray = new ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (AlbumScanner.isAudioName(n) || KNOWN_EXTRA.matcher(n).matches()) continue;
            stray.add(n);
        }
        if (stray.isEmpty()) return null;
        stray.sort(String.CASE_INSENSITIVE_ORDER);
        String names = String.join(", ", stray.subList(0, Math.min(3, stray.size()))) + (stray.size() > 3 ? " and " + (stray.size() - 3) + " more" : "");
        return (stray.size() == 1 ? "1 file isn't recognised as audio: " : stray.size() + " files aren't recognised as audio: ") + names;
    }

    // ---- 3. search ----

    private Result bySearch(Album a, String artistOrFolder, List<TrackInfo> tracks, Tags t, File first, Integer disc) throws IOException, JSONException {
        String artist = t.bestArtist();
        String album = t.album;
        if (artist == null || album == null) {
            String[] g = FolderNameParser.guess(a.dir);
            if (artist == null) artist = g[0];
            if (album == null) album = g[1];
        }
        if (artist == null || album == null) return skip(a, artistOrFolder, NO_NAME);

        String q = "release:\"" + clean(ArtLookup.searchTitle(album)) + "\" AND artist:\"" + clean(artist) + "\"";
        Http.Response res = http.get(ArtLookup.MB + "release/?query=" + enc(q) + "&fmt=json&limit=25");
        if (res.code >= 500) return skip(a, artistOrFolder, NETWORK);
        if (res.code != 200) return skip(a, artistOrFolder, NO_INFO);
        JSONArray releases = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("releases");
        List<JSONObject> pool = candidates(releases, artist, album);
        Integer expected = agreedCount(pool, disc, tracks.size());
        if (expected == null) return skip(a, artistOrFolder, NO_INFO);
        if (expected < 0) return skip(a, artistOrFolder, expected == WHICH ? WHICH_DISC : EDITIONS_DIFFER);
        return new Result(a, artistOrFolder, compare(tracks, expected, lengthsFor(pool, disc, expected)), null, BY_SEARCH);
    }

    /**
     * Track lengths from one edition that has the agreed count for this disc. Empty when none can be had
     * (whole-set folders, or the lookup fails): the count still stands without them.
     */
    private Map<Integer, Long> lengthsFor(List<JSONObject> pool, Integer disc, int expected) {
        for (JSONObject r : pool) {
            JSONArray media = r.optJSONArray("media");
            JSONObject m = medium(media, disc);
            if (m == null || trackCount(m) != expected || r.optString("id").isEmpty()) continue;
            try {
                Http.Response res = http.get(ArtLookup.MB + "release/" + enc(r.optString("id")) + "?inc=recordings&fmt=json");
                if (res.code != 200) return new HashMap<>();
                JSONArray full = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("media");
                JSONObject fm = full == null ? null : pickMedium(full, disc);
                return fm == null || trackCount(fm) != expected ? new HashMap<>() : lengths(fm);
            } catch (IOException | JSONException e) {
                return new HashMap<>();
            }
        }
        return new HashMap<>();
    }

    /** The search medium for this folder: by disc number, or the only one; null when neither applies. */
    private static JSONObject medium(JSONArray media, Integer disc) {
        if (media == null) return null;
        if (disc == null) return media.length() == 1 ? media.optJSONObject(0) : null;
        return disc <= media.length() ? media.optJSONObject(disc - 1) : null;
    }

    static final int DIFFER = -1;
    static final int WHICH = -2;

    /** Matching releases (title, artist, score), CD editions only when there are any. */
    static List<JSONObject> candidates(JSONArray releases, String artist, String album) {
        List<JSONObject> cd = new ArrayList<>();
        List<JSONObject> other = new ArrayList<>();
        if (releases == null) return cd;
        String wantTitle = Text.norm(ArtLookup.searchTitle(album));
        String wantArtist = Text.norm(artist);
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optInt("score") < MIN_SCORE) continue;
            if (!Text.norm(ArtLookup.searchTitle(r.optString("title"))).equals(wantTitle)) continue;
            String credit = Text.norm(credit(r));
            if (credit.isEmpty() || !(credit.equals(wantArtist) || credit.contains(wantArtist) || wantArtist.contains(credit))) continue;
            JSONArray media = r.optJSONArray("media");
            if (media == null || media.length() == 0) continue;
            (allCd(media) ? cd : other).add(r);
        }
        return cd.isEmpty() ? other : cd;
    }

    /**
     * The disc's track count that a clear majority of the editions share. Null when nothing matches;
     * {@link #DIFFER} when editions disagree; {@link #WHICH} when the disc can't be told.
     * {@code folderTracks} only decides whether a disc-less folder holds a whole multi-disc set; it never
     * picks an edition.
     */
    static Integer agreedCount(List<JSONObject> pool, Integer disc, int folderTracks) {
        if (pool.isEmpty()) return null;
        List<Integer> counts = new ArrayList<>();
        if (disc != null) {
            for (JSONObject r : pool) {
                JSONArray media = r.optJSONArray("media");
                if (disc > media.length()) continue; // a single-disc edition of a two-disc set: not comparable
                int n = trackCount(media.optJSONObject(disc - 1));
                if (n > 0) counts.add(n);
            }
        } else {
            // No disc number: single-disc editions describe this folder. Only when every edition is a set
            // can the folder be the whole set, and then only if it holds more than any one disc.
            int biggestDisc = 0;
            List<Integer> sets = new ArrayList<>();
            for (JSONObject r : pool) {
                JSONArray media = r.optJSONArray("media");
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
        return compare(files, expected, lengths(medium));
    }

    private static Map<Integer, Long> lengths(JSONObject medium) {
        Map<Integer, Long> out = new HashMap<>();
        JSONArray rel = medium.optJSONArray("tracks");
        if (rel == null) return out;
        for (int i = 0; i < rel.length(); i++) {
            JSONObject t = rel.optJSONObject(i);
            if (t == null) continue;
            int pos = t.optInt("position", i + 1);
            if (!t.isNull("length")) out.put(pos, t.optLong("length"));
        }
        return out;
    }

    /** Count against {@code expected}, then each track against {@code lengths} (by track number). */
    static List<String> compare(List<TrackInfo> files, int expected, Map<Integer, Long> lengths) {
        List<String> problems = new ArrayList<>();
        // Measure each track against its own song: by tag number, unless the file names line up with the
        // release's lengths better (tags can be shifted; one wrong number turns every comparison wrong).
        boolean byName = fits(files, lengths, true) > fits(files, lengths, false);
        if (expected > 0 && files.size() < expected) {
            String line = files.size() + " of " + expected + " tracks";
            TreeSet<Integer> have = new TreeSet<>();
            for (TrackInfo t : files) {
                Integer n = byName ? t.fileNumber : t.track;
                if (n != null) have.add(n);
            }
            if (have.size() == files.size()) {
                List<Integer> gone = new ArrayList<>();
                for (int n = 1; n <= expected; n++) if (!have.contains(n)) gone.add(n);
                if (!gone.isEmpty()) line += ", missing " + list(gone);
            }
            problems.add(line);
        }
        problems.addAll(empties(files));
        for (TrackInfo t : files) {
            Integer n = byName ? t.fileNumber : t.track;
            if (isEmpty(t) || n == null || t.durationMs == null) continue;
            Long want = lengths.get(n);
            if (want == null || want <= 0) continue;
            if (t.durationMs < want - slack(want)) {
                problems.add(t.label() + " is " + clock(t.durationMs) + ", expected " + clock(want));
            }
        }
        return problems;
    }

    private static long slack(long want) {
        return Math.max(SHORT_MS, (long) (want * SHORT_FRACTION));
    }

    /** How many tracks land within the length tolerance of the release, numbering by file name or by tag. */
    private static int fits(List<TrackInfo> files, Map<Integer, Long> lengths, boolean byName) {
        int ok = 0;
        for (TrackInfo t : files) {
            Integer n = byName ? t.fileNumber : t.track;
            if (n == null || t.durationMs == null || isEmpty(t)) continue;
            Long want = lengths.get(n);
            if (want != null && Math.abs(t.durationMs - want) <= slack(want)) ok++;
        }
        return ok;
    }

    /** Tracks with no real audio: under a second, or a tiny file that states no length. */
    static List<String> empties(List<TrackInfo> files) {
        List<String> out = new ArrayList<>();
        for (TrackInfo t : files) if (isEmpty(t)) out.add(t.label() + " is empty");
        return out;
    }

    static boolean isEmpty(TrackInfo t) {
        if (t.durationMs != null) return t.durationMs < 1000;
        return t.bytes >= 0 && t.bytes < EMPTY_BYTES;
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

    private static Result skip(Album a, String artist, String why) {
        return new Result(a, artist, new ArrayList<>(), why, null);
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
