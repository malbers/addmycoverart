package com.malbers.addmycoverart.core;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Finds a front cover for one album: MusicBrainz ID tag first, then a strict MusicBrainz search on
 * artist + album (from tags, else folder names), with art from the Cover Art Archive.
 * A skipped album beats a wrong cover, so only confident matches are used.
 */
public final class ArtLookup {
    static final String MB = "https://musicbrainz.org/ws/2/";
    static final String CAA = "https://coverartarchive.org/";
    static final int MIN_SCORE = 90;
    static final int MAX_CANDIDATES = 3;

    private final Http http;

    public ArtLookup(Http http) {
        this.http = http;
    }

    public LookupResult find(Album a, Tags t) {
        String artist = t.bestArtist();
        String album = t.album;
        if (artist == null || album == null) {
            String[] g = FolderNameParser.guess(a.dir);
            if (artist == null) artist = g[0];
            if (album == null) album = g[1];
        }
        LookupResult inFiles = inFiles(a, artist, album);
        if (inFiles != null) return inFiles;
        try {
            if (t.mbAlbumId != null) {
                LookupResult r = byReleaseId(t.mbAlbumId, artist, album);
                if (r != null) return r;
            }
            if (artist == null || album == null) return LookupResult.notFound(LookupResult.NO_TAGS, artist, album);
            List<JSONObject> candidates = search(artist, album, a.trackCount);
            if (candidates.isEmpty()) return LookupResult.notFound(LookupResult.NO_MATCH, artist, album);
            for (int i = 0; i < Math.min(MAX_CANDIDATES, candidates.size()); i++) {
                LookupResult r = cover("release/" + candidates.get(i).getString("id"), artist, album);
                if (r != null) return r;
            }
            JSONObject rg = candidates.get(0).optJSONObject("release-group");
            if (rg != null) {
                LookupResult r = cover("release-group/" + rg.getString("id"), artist, album);
                if (r != null) return r;
            }
            return LookupResult.notFound(LookupResult.NO_ART, artist, album);
        } catch (IOException e) {
            return LookupResult.notFound(isBusy(e) ? LookupResult.BUSY : LookupResult.NETWORK, artist, album);
        } catch (JSONException e) {
            return LookupResult.notFound(LookupResult.NO_MATCH, artist, album);
        }
    }

    /**
     * Art already inside the tracks: copy that exact image out; no network, no edition guesswork.
     * JPEG only, because the file is always named folder.jpg. Null when there is none.
     */
    private static LookupResult inFiles(Album a, String artist, String album) {
        if (!a.embeddedArt) return null;
        byte[] pic = TagReader.readPicture(a.firstAudio);
        return isJpeg(pic) ? LookupResult.found(pic, LookupResult.FROM_TRACKS + a.firstAudio.getName(), artist, album) : null;
    }

    /** P4, network down (06c): only the cover inside the files, never the network; "not tried" when there is none. */
    public static LookupResult findOffline(Album a, Tags t) {
        String artist = t.bestArtist();
        String album = t.album;
        LookupResult r = inFiles(a, artist, album);
        return r != null ? r : LookupResult.notFound(FetchText.NOT_TRIED_OFFLINE, artist, album);
    }

    private LookupResult byReleaseId(String mbid, String artist, String album) throws IOException, JSONException {
        LookupResult r = cover("release/" + mbid, artist, album);
        if (r != null) return r;
        Http.Response res = http.get(MB + "release/" + enc(mbid) + "?fmt=json");
        if (res.code != 200) return null;
        JSONObject rg = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONObject("release-group");
        return rg == null ? null : cover("release-group/" + rg.getString("id"), artist, album);
    }

    /** Releases that match title and artist with a high score, closest track count first. */
    private List<JSONObject> search(String artist, String album, int trackCount) throws IOException, JSONException {
        // Search on the bare title: "1967-1970 (Disc 1)" scores below the bar, "1967-1970" scores 100.
        String q = "release:\"" + clean(searchTitle(album)) + "\" AND artist:\"" + clean(artist) + "\"";
        Http.Response res = http.get(MB + "release/?query=" + enc(q) + "&fmt=json&limit=25");
        if (res.code != 200) {
            // 503 = MusicBrainz rate limiting or maintenance; treat like any other transport failure.
            if (res.code >= 500) throw new IOException("HTTP " + res.code);
            return new ArrayList<>();
        }
        JSONArray releases = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("releases");
        List<JSONObject> out = new ArrayList<>();
        if (releases == null) return out;
        String wantTitle = Text.norm(album);
        String wantArtist = Text.norm(artist);
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            if (r.optInt("score") < MIN_SCORE) continue;
            if (!Text.norm(r.optString("title")).equals(wantTitle)) continue;
            String credit = Text.norm(credit(r));
            if (!(credit.equals(wantArtist) || credit.contains(wantArtist) || wantArtist.contains(credit)) || credit.isEmpty()) continue;
            out.add(r);
        }
        // P6: ties broken the same way every time (official, earliest date, then id), so a refetch picks the same
        // edition. Before, MusicBrainz's order decided, and Michael's Miss Perfumado got a different cover each run.
        out.sort(Comparator.<JSONObject>comparingInt(r -> Math.abs(r.optInt("track-count") - trackCount))
                .thenComparing(r -> -r.optInt("score"))
                .thenComparing(r -> "Official".equalsIgnoreCase(r.optString("status")) ? 0 : 1)
                .thenComparing(r -> date(r.optString("date")))
                .thenComparing(r -> r.optString("id")));
        return out;
    }

    /** Missing dates sort last. */
    private static String date(String d) {
        return d == null || d.isEmpty() ? "9999" : d;
    }

    /**
     * P6, Find more art: the releases this album most likely is, best first: its MusicBrainz ID tag, then up to
     * {@link #MAX_CANDIDATES} confident search matches. Empty when there is no confident match. Network trouble throws.
     */
    public List<String> releaseCandidates(Album a, Tags t) throws IOException {
        String artist = t.bestArtist();
        String album = t.album;
        if (artist == null || album == null) {
            String[] g = FolderNameParser.guess(a.dir);
            if (artist == null) artist = g[0];
            if (album == null) album = g[1];
        }
        List<String> ids = new ArrayList<>();
        if (t.mbAlbumId != null) ids.add(t.mbAlbumId);
        if (artist == null || album == null) return ids;
        try {
            for (JSONObject r : search(artist, album, a.trackCount)) {
                String id = r.optString("id");
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
                if (ids.size() >= MAX_CANDIDATES + (t.mbAlbumId != null ? 1 : 0)) break;
            }
        } catch (JSONException e) {
            // an unreadable answer is "no confident match"
        }
        return ids;
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

    /** front-1200, then front-500, for a "release/{id}" or "release-group/{id}" path. Null when none. */
    private LookupResult cover(String path, String artist, String album) throws IOException {
        for (String size : new String[]{"front-1200", "front-500"}) {
            String url = CAA + path + "/" + size;
            Http.Response res = http.get(url);
            if (res.code == 200 && isImage(res.body)) return LookupResult.found(res.body, url, artist, album);
            if (res.code >= 500) throw new IOException("HTTP " + res.code);
        }
        return null;
    }

    /** A 5xx answer (thrown as "HTTP 503" here) means the server is busy, not that the streamer is offline. */
    public static boolean isBusy(IOException e) {
        return e.getMessage() != null && e.getMessage().startsWith("HTTP ");
    }

    static boolean isJpeg(byte[] b) {
        return b != null && b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8;
    }

    static boolean isImage(byte[] b) {
        if (b == null || b.length < 4) return false;
        boolean jpeg = (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8;
        boolean png = (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        return jpeg || png;
    }

    /** Bare title for the search: no bracketed suffixes, and "Album II, The" back to "The Album II". */
    static String searchTitle(String album) {
        String t = stripBrackets(album);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^(.*),\\s*(the|a|an)$").matcher(t);
        return m.matches() ? m.group(2) + " " + m.group(1) : t;
    }

    static String stripBrackets(String s) {
        String t = s.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? s : t;
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
