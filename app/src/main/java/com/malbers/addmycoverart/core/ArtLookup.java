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

    /**
     * What to search MusicBrainz for, best first, never the same pair twice: the tags (gaps filled from the
     * folder name), then the folder name alone, then the tag album with the folder's artist (a typo in the
     * artist tag, e.g. Michael's "Cold play" in a "Coldplay" folder). Empty when there is no album name at all.
     */
    public static List<String[]> searchTerms(Album a, Tags t) {
        String[] g = FolderNameParser.guess(a);
        String tagAlbum = FolderNameParser.isPlaceholder(t.album) ? null : t.album;
        String tagArtist = t.bestArtist();
        List<String[]> out = new ArrayList<>();
        addTerm(out, tagArtist != null ? tagArtist : g[0], tagAlbum != null ? tagAlbum : g[1]);
        addTerm(out, g[0], g[1]);
        addTerm(out, g[0], tagAlbum);
        // 1.1.2 Q6: an SACD's disc text can be swapped (Michael's Violator: title "Depeche Mode", artist "Violator"),
        // so an ISO also tries it the other way round, last.
        boolean iso = a.firstAudio != null && a.firstAudio.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso");
        if (iso && tagAlbum != null && tagArtist != null) addTerm(out, tagAlbum, tagArtist);
        return out;
    }

    private static void addTerm(List<String[]> out, String artist, String album) {
        if (artist == null || album == null || Text.norm(artist).isEmpty() || Text.norm(album).isEmpty()) return;
        for (String[] s : out) {
            if (Text.norm(s[0]).equals(Text.norm(artist)) && Text.norm(s[1]).equals(Text.norm(album))) return;
        }
        out.add(new String[]{artist, album});
    }

    /** "“Album” by Artist", or two of them joined with "or"; null when there was nothing to search for. */
    public static String describeSearch(Album a, Tags t) {
        List<String[]> terms = searchTerms(a, t);
        if (terms.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(2, terms.size()); i++) {
            if (i > 0) b.append(", or ");
            b.append('“').append(searchTitle(terms.get(i)[1])).append("” by ").append(terms.get(i)[0]);
        }
        return b.toString();
    }

    public LookupResult find(Album a, Tags t) {
        List<String[]> terms = searchTerms(a, t);
        String[] g = FolderNameParser.guess(a);
        String artist = !terms.isEmpty() ? terms.get(0)[0] : t.bestArtist() != null ? t.bestArtist() : g[0];
        String album = !terms.isEmpty() ? terms.get(0)[1] : t.album != null ? t.album : g[1];
        LookupResult inFiles = inFiles(a, artist, album);
        if (inFiles != null) return inFiles;
        try {
            if (t.mbAlbumId != null) {
                LookupResult r = byReleaseId(t.mbAlbumId, artist, album);
                if (r != null) return r;
            }
            if (terms.isEmpty()) return LookupResult.notFound(LookupResult.NO_TAGS, artist, album);
            List<JSONObject> candidates = new ArrayList<>();
            for (String[] s : terms) {
                candidates = search(s[0], s[1], tracksFor(a));
                if (!candidates.isEmpty()) {
                    artist = s[0];
                    album = s[1];
                    break;
                }
            }
            if (candidates.isEmpty()) {
                // Fetch all stays strict (it saves without asking), but says when there's a close match to check
                boolean close = false;
                try {
                    close = !closeCandidates(a, t, 1).isEmpty();
                } catch (IOException ignored) {
                    // the miss itself is still a plain "no match"
                }
                return LookupResult.notFound(close ? LookupResult.CLOSE : LookupResult.NO_MATCH, artist, album);
            }
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
            return isBusy(e) ? LookupResult.notFound(LookupResult.BUSY, artist, album) : LookupResult.network(e, artist, album);
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

    /**
     * 1.1.2: the track count to rank releases by; 0 = unknown. An SACD ISO is one FILE holding the whole disc, so its
     * "1 track" made one-track singles beat the album (Michael's Eagles: Hotel California the single won).
     */
    static int tracksFor(Album a) {
        boolean iso = a.firstAudio != null && a.firstAudio.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso");
        return iso ? 0 : a.trackCount;
    }

    /** 1.1.2: 0 for an album, 1 for anything else (single, EP, broadcast) or unknown. */
    static int albumFirst(JSONObject r) {
        JSONObject rg = r.optJSONObject("release-group");
        return rg != null && "Album".equalsIgnoreCase(rg.optString("primary-type")) ? 0 : 1;
    }

    /** Releases that match title and artist with a high score, closest track count first. */
    private List<JSONObject> search(String artist, String album, int trackCount) throws IOException, JSONException {
        // Search on the bare title: "1967-1970 (Disc 1)" scores below the bar, "1967-1970" scores 100.
        // searchTitle also turns a library-sort artist ("Police, The") back into "The Police"
        String q = "release:\"" + clean(searchTitle(album)) + "\" AND artist:\"" + clean(searchTitle(artist)) + "\"";
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
            noteFormat(r);
            if (r.optInt("score") < MIN_SCORE) continue;
            String title = Text.norm(r.optString("title"));
            // 1.1.2: budget series title every disc "<Series>: <Artist>" (Michael's "Supreme Jazz: Duke Ellington")
            if (!title.equals(wantTitle) && !title.equals(Text.norm(album + " " + artist))) continue;
            String credit = Text.norm(credit(r));
            if (!(credit.equals(wantArtist) || credit.contains(wantArtist) || wantArtist.contains(credit)) || credit.isEmpty()) continue;
            out.add(r);
        }
        // P6: ties broken the same way every time (official, earliest date, then id), so a refetch picks the same
        // edition. Before, MusicBrainz's order decided, and Michael's Miss Perfumado got a different cover each run.
        // 1.1.2: an unknown track count (SACD ISO) ranks albums before singles/EPs instead
        out.sort(Comparator.<JSONObject>comparingInt(r -> trackCount <= 0 ? albumFirst(r) : Math.abs(r.optInt("track-count") - trackCount))
                .thenComparing(r -> albumFirst(r))
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
        List<String> ids = new ArrayList<>();
        if (t.mbAlbumId != null) ids.add(t.mbAlbumId);
        for (String[] s : searchTerms(a, t)) {
            int before = ids.size();
            try {
                for (JSONObject r : search(s[0], s[1], tracksFor(a))) {
                    String id = r.optString("id");
                    if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
                    if (ids.size() >= MAX_CANDIDATES + (t.mbAlbumId != null ? 1 : 0)) break;
                }
            } catch (JSONException e) {
                // an unreadable answer is "no confident match"
            }
            if (ids.size() > before) break; // the first search that matched decides
        }
        return ids;
    }

    // ---- 1.1.1 close matches (Michael 2026-11-24: "even a directional cover might be fine ... I get to decide") ----

    static final int CLOSE_SCORE = 70;
    static final int MAX_CLOSE = 3;

    /** A release that is probably, not certainly, the album: shown on the album page for Michael to check. */
    public static final class Close {
        public final String releaseId;
        /** "Parachutes (2000, GB, CD) by Coldplay" */
        public final String label;

        Close(String releaseId, String label) {
            this.releaseId = releaseId;
            this.label = label;
        }
    }

    /**
     * Looser search for when there is no confident match: words instead of an exact title, a lower score, and a
     * title and artist that only need to overlap. One release per album (release group), best first. Only for the
     * album page, where nothing is saved until Michael picks it; Fetch all stays strict.
     */
    public List<Close> closeCandidates(Album a, Tags t) throws IOException {
        return closeCandidates(a, t, Integer.MAX_VALUE);
    }

    /** As above, trying at most {@code maxSearches} of the search terms (Fetch all uses 1, to stay quick). */
    List<Close> closeCandidates(Album a, Tags t, int maxSearches) throws IOException {
        List<Close> out = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        List<String[]> terms = searchTerms(a, t);
        for (String[] s : terms.subList(0, Math.min(maxSearches, terms.size()))) {
            String q = "release:(" + words(searchTitle(s[1])) + ") AND artist:(" + words(searchTitle(s[0])) + ")";
            Http.Response res = http.get(MB + "release/?query=" + enc(q) + "&fmt=json&limit=25");
            if (res.code >= 500) throw new IOException("HTTP " + res.code);
            if (res.code != 200) continue;
            try {
                JSONArray releases = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("releases");
                if (releases == null) continue;
                for (int i = 0; i < releases.length() && out.size() < MAX_CLOSE; i++) {
                    JSONObject r = releases.getJSONObject(i);
                    if (r.optInt("score") < CLOSE_SCORE) continue;
                    if (!near(r.optString("title"), s[1]) || !near(credit(r), s[0])) continue;
                    JSONObject rg = r.optJSONObject("release-group");
                    String group = rg != null ? rg.optString("id") : r.optString("id");
                    if (groups.contains(group)) continue;
                    groups.add(group);
                    out.add(new Close(r.optString("id"), label(r)));
                }
            } catch (JSONException e) {
                // unreadable answer: nothing close from this search
            }
            if (out.size() >= MAX_CLOSE) break;
        }
        return out;
    }

    static final int MAX_EDITIONS = 8;

    /**
     * tags4 (D3, "More editions"): every edition worth a look, sure matches first, then close ones, by release (not
     * by album, so several editions of the same album can show). Up to {@link #MAX_EDITIONS}. Only on Michael's tap.
     */
    public List<Close> editions(Album a, Tags t) throws IOException {
        List<Close> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<String[]> terms = searchTerms(a, t);
        try {
            for (String[] s : terms) {
                for (JSONObject r : search(s[0], s[1], tracksFor(a))) {
                    if (out.size() >= MAX_EDITIONS) return out;
                    if (ids.contains(r.optString("id"))) continue;
                    ids.add(r.optString("id"));
                    out.add(new Close(r.optString("id"), label(r)));
                }
            }
            for (String[] s : terms) {
                String q = "release:(" + words(searchTitle(s[1])) + ") AND artist:(" + words(searchTitle(s[0])) + ")";
                Http.Response res = http.get(MB + "release/?query=" + enc(q) + "&fmt=json&limit=25");
                if (res.code >= 500) throw new IOException("HTTP " + res.code);
                if (res.code != 200) continue;
                JSONArray releases = new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("releases");
                if (releases == null) continue;
                for (int i = 0; i < releases.length() && out.size() < MAX_EDITIONS; i++) {
                    JSONObject r = releases.getJSONObject(i);
                    noteFormat(r);
                    if (r.optInt("score") < CLOSE_SCORE || ids.contains(r.optString("id"))) continue;
                    if (!near(r.optString("title"), s[1]) || !near(credit(r), s[0])) continue;
                    ids.add(r.optString("id"));
                    out.add(new Close(r.optString("id"), label(r)));
                }
                if (out.size() >= MAX_EDITIONS) break;
            }
        } catch (JSONException e) {
            // keep what we have
        }
        return out;
    }

    /**
     * Title or artist "close": equal; every word of the shorter one is in the longer one ("Jazz Gold" in
     * "Jazz Gold - Charlie Parker"); or a small spelling difference ("Parachute" / "Parachutes"). Not "Help" vs
     * "Helpless", not "Live at Red Rocks" vs "Live at Luther College": those are other albums.
     */
    static boolean near(String found, String want) {
        String f = Text.norm(found), w = Text.norm(want);
        if (f.isEmpty() || w.isEmpty()) return false;
        if (f.equals(w)) return true;
        java.util.List<String> fw = java.util.Arrays.asList(f.split(" "));
        java.util.List<String> ww = java.util.Arrays.asList(w.split(" "));
        java.util.List<String> shorter = fw.size() <= ww.size() ? fw : ww, longer = shorter == fw ? ww : fw;
        if (shorter.size() >= 2 || shorter.get(0).length() >= 4) {
            if (longer.containsAll(shorter)) return true;
        }
        int len = Math.max(f.length(), w.length());
        if (len >= 6 && distance(f, w) <= Math.max(1, len / 10)) return true;
        // tags3: same words but one spelled differently from the 5th letter on (Michael's "Midnight Vultures" is
        // Beck's "Midnite Vultures"). Needs 2+ words, so "Help" vs "Helpless" stays apart.
        if (fw.size() != ww.size() || fw.size() < 2) return false;
        int differ = 0;
        for (int i = 0; i < fw.size(); i++) {
            String a = fw.get(i), b = ww.get(i);
            if (a.equals(b)) continue;
            if (++differ > 1) return false;
            if (a.length() < 5 || b.length() < 5 || !a.regionMatches(0, b, 0, 4) || Math.abs(a.length() - b.length()) > 2) return false;
        }
        return true;
    }

    /** Edit distance (insert, delete, change one character). */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /**
     * Lucene-safe words for a loose query, each 4+ letter word fuzzy ("parachute~" finds "Parachutes", "gypsy~"
     * finds "Gipsy"; measured on MusicBrainz 2026-11-24, where the exact search found neither).
     */
    static String words(String s) {
        String w = s.replaceAll("[+\\-!(){}\\[\\]^\"~*?:\\\\/&|]", " ").replaceAll("\\s+", " ").trim();
        if (w.isEmpty()) return "_";
        StringBuilder b = new StringBuilder();
        for (String x : w.split(" ")) {
            if (b.length() > 0) b.append(' ');
            b.append(x);
            if (x.length() >= 4 && !x.equalsIgnoreCase("and") && !x.equalsIgnoreCase("not")) b.append('~');
        }
        return b.toString();
    }

    /** iso12: each release's first medium format ("12\" Vinyl", "Hybrid SACD", "CD"), as the searches saw it. */
    private final java.util.Map<String, String> formats = new java.util.concurrent.ConcurrentHashMap<>();

    private void noteFormat(JSONObject r) {
        String id = r.optString("id");
        JSONArray media = r.optJSONArray("media");
        JSONObject m = media == null || media.length() == 0 ? null : media.optJSONObject(0);
        String fmt = m == null ? "" : m.optString("format");
        if (!id.isEmpty() && !fmt.isEmpty()) formats.put(id, fmt);
    }

    /** iso12: the format a search reported for this release, or "" when unknown (e.g. an ID from the tags). */
    public String formatOf(String releaseId) {
        String f = releaseId == null ? null : formats.get(releaseId);
        return f == null ? "" : f;
    }

    static String label(JSONObject r) {
        List<String> bits = new ArrayList<>();
        String date = r.optString("date");
        if (date.length() >= 4) bits.add(date.substring(0, 4));
        if (!r.optString("country").isEmpty()) bits.add(r.optString("country"));
        JSONArray media = r.optJSONArray("media");
        if (media != null && media.length() > 0) {
            JSONObject m = media.optJSONObject(0);
            String fmt = m == null ? "" : m.optString("format");
            if (!fmt.isEmpty()) bits.add(media.length() > 1 ? media.length() + "×" + fmt : fmt);
        }
        String c = credit(r);
        return r.optString("title") + (bits.isEmpty() ? "" : " (" + String.join(", ", bits) + ")") + (c.isEmpty() ? "" : " by " + c);
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
