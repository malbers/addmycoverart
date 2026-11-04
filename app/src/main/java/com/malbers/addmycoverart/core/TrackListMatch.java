package com.malbers.addmycoverart.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fallback for an album whose title finds nothing confident: the same artist, the same number of tracks and
 * most of a handful of its track titles, in the same order. Catches compilations and reissues sold under a
 * title the tags don't use. Pure logic; {@link ArtLookup} does the requests.
 *
 * <p>The rule, all of which must hold for a release to be used:
 * <ul>
 *   <li>its artist credit matches the album artist, by the same test as the title search;</li>
 *   <li>its total track count equals the number of audio files in the folder;</li>
 *   <li>of up to {@link #SAMPLE} titles sampled across the album (at least {@link #MIN_TITLES}), all but one in
 *       five appear on it, in the same order as in the folder;</li>
 *   <li>no release from a different release group passes too (two different albums fit: skip).</li>
 * </ul>
 */
final class TrackListMatch {
    /** Titles sampled from the folder: first, last and evenly between. */
    static final int SAMPLE = 5;
    /** Fewer usable titles than this and the fallback is not tried. */
    static final int MIN_TITLES = 3;
    /** Release lookups per album, each a different release group. */
    static final int MAX_LOOKUPS = 2;

    private static final Pattern GENERIC = Pattern.compile("(track|piste|pista|titel|title|audio track|untitled|unknown)?( ?\\d{1,3})?");
    private static final Pattern REMASTER_SUFFIX = Pattern.compile("(?i)\\s+-\\s+[^-]*\\bremaster[^-]*$");

    private TrackListMatch() {}

    /** One sampled track: its title as tagged, and the comparison key. Samples are kept in track order. */
    static final class Sample {
        final String title;
        final String key;

        Sample(String title, String key) {
            this.title = title;
            this.key = key;
        }
    }

    /** A release that passed the rule. */
    static final class Match {
        final String releaseId;
        final String releaseGroupId;

        Match(String releaseId, String releaseGroupId) {
            this.releaseId = releaseId;
            this.releaseGroupId = releaseGroupId;
        }
    }

    /** Titles ignored: absent, or what rippers write when they found nothing ("Track 01", "Untitled"). */
    static boolean usable(String key) {
        return key != null && !key.isEmpty() && !GENERIC.matcher(key).matches();
    }

    /** Comparison key for a track title: {@link Text#norm}, after dropping a " - 2004 Remaster" style suffix. */
    static String titleKey(String title) {
        if (title == null) return "";
        return Text.norm(withoutRemaster(title));
    }

    private static String withoutRemaster(String title) {
        return REMASTER_SUFFIX.matcher(title).replaceAll("");
    }

    /** Matches needed from {@code sampled} titles: all but one in five (5 → 4, 4 → 4, 3 → 3). */
    static int needed(int sampled) {
        return sampled - sampled / 5;
    }

    /**
     * Up to {@link #SAMPLE} titles from the folder's audio files, spread from first to last, in track order:
     * by disc and track tags when every sampled file has a track number, else by file name. Empty when fewer
     * than {@link #MIN_TITLES} are usable.
     */
    static List<Sample> sample(File dir, Function<File, TrackInfo> reader) {
        File[] files = dir.listFiles(f -> f.isFile() && AlbumScanner.isAudioName(f.getName()));
        if (files == null || files.length < MIN_TITLES) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        int n = files.length;
        int s = Math.min(SAMPLE, n);
        List<TrackInfo> picked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < s; k++) {
            TrackInfo t = reader.apply(files[k * (n - 1) / (s - 1)]);
            String key = titleKey(t.title);
            // A title twice in the folder ("Intro") says nothing about order; use it once.
            if (usable(key) && seen.add(key)) picked.add(t);
        }
        if (picked.size() < MIN_TITLES) return new ArrayList<>();
        boolean numbered = true;
        for (TrackInfo t : picked) if (t.track == null) numbered = false;
        if (numbered) {
            // Stable sort, so equal positions keep file order.
            picked.sort(Comparator.<TrackInfo>comparingInt(t -> t.disc == null ? 1 : t.disc).thenComparingInt(t -> t.track));
        }
        List<Sample> out = new ArrayList<>();
        for (TrackInfo t : picked) out.add(new Sample(t.title, titleKey(t.title)));
        return out;
    }

    /**
     * MusicBrainz recording search for the sampled titles by this artist on releases of this many tracks.
     * One request covers all titles.
     */
    static String query(String artist, int trackCount, List<Sample> samples) {
        StringBuilder q = new StringBuilder("artist:\"").append(ArtLookup.clean(artist)).append("\" AND tracksrelease:")
                .append(trackCount).append(" AND (");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) q.append(" OR ");
            q.append("recording:\"").append(ArtLookup.clean(ArtLookup.searchTitle(withoutRemaster(samples.get(i).title)))).append('"');
        }
        return q.append(')').toString();
    }

    /**
     * Release IDs worth a lookup, from a recording search: the right track count and enough sampled titles,
     * best first, at most one per release group and {@link #MAX_LOOKUPS} in all. The lookup has the final say.
     */
    static List<String> candidates(JSONObject recordingSearch, int trackCount, List<Sample> samples) {
        Set<String> wanted = new HashSet<>();
        for (Sample s : samples) wanted.add(s.key);
        Map<String, Set<String>> hits = new LinkedHashMap<>();
        Map<String, String> groupOf = new LinkedHashMap<>();
        JSONArray recs = recordingSearch.optJSONArray("recordings");
        if (recs == null) return new ArrayList<>();
        for (int i = 0; i < recs.length(); i++) {
            JSONObject rec = recs.optJSONObject(i);
            if (rec == null) continue;
            String key = titleKey(rec.optString("title"));
            if (!wanted.contains(key)) continue;
            JSONArray rels = rec.optJSONArray("releases");
            if (rels == null) continue;
            for (int j = 0; j < rels.length(); j++) {
                JSONObject rel = rels.optJSONObject(j);
                if (rel == null || rel.optString("id").isEmpty()) continue;
                if (rel.has("track-count") && rel.optInt("track-count") != trackCount) continue;
                String id = rel.optString("id");
                hits.computeIfAbsent(id, x -> new HashSet<>()).add(key);
                JSONObject rg = rel.optJSONObject("release-group");
                groupOf.put(id, rg != null ? rg.optString("id", id) : id);
            }
        }
        int need = needed(samples.size());
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : hits.entrySet()) if (e.getValue().size() >= need) ids.add(e.getKey());
        // Most titles first; ties keep MusicBrainz's order (List.sort is stable).
        ids.sort(Comparator.comparingInt(id -> -hits.get(id).size()));
        List<String> out = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        for (String id : ids) {
            if (out.size() >= MAX_LOOKUPS) break;
            if (groups.add(groupOf.get(id))) out.add(id);
        }
        return out;
    }

    /** Whether a release lookup ({@code inc=recordings+artist-credits+release-groups}) passes the rule. Null if not. */
    static Match confirm(JSONObject release, String artist, int trackCount, List<Sample> samples) {
        // An artist that normalises to nothing ("!!!") would "contain" every credit.
        if (Text.norm(artist).isEmpty() || !ArtLookup.artistMatches(ArtLookup.credit(release), artist)) return null;
        List<String> keys = new ArrayList<>();
        JSONArray media = release.optJSONArray("media");
        if (media == null) return null;
        for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.optJSONObject(i);
            JSONArray tracks = m == null ? null : m.optJSONArray("tracks");
            if (tracks == null) return null; // a medium without its track list: can't tell, so no
            for (int j = 0; j < tracks.length(); j++) {
                JSONObject t = tracks.optJSONObject(j);
                keys.add(t == null ? "" : titleKey(t.optString("title")));
            }
        }
        if (keys.size() != trackCount) return null;
        List<List<Integer>> positions = new ArrayList<>();
        for (Sample s : samples) {
            List<Integer> at = new ArrayList<>();
            for (int p = 0; p < keys.size(); p++) if (keys.get(p).equals(s.key)) at.add(p);
            positions.add(at);
        }
        if (inOrder(positions, 0, -1) < needed(samples.size())) return null;
        JSONObject rg = release.optJSONObject("release-group");
        String id = release.optString("id");
        return id.isEmpty() ? null : new Match(id, rg != null ? rg.optString("id", null) : null);
    }

    /** Most samples, from {@code i} on, that can sit on the release at increasing positions after {@code after}. */
    private static int inOrder(List<List<Integer>> positions, int i, int after) {
        if (i == positions.size()) return 0;
        int best = inOrder(positions, i + 1, after);
        for (int p : positions.get(i)) {
            if (p > after) {
                best = Math.max(best, 1 + inOrder(positions, i + 1, p));
                break; // the earliest usable position leaves the most room for the rest
            }
        }
        return best;
    }
}
