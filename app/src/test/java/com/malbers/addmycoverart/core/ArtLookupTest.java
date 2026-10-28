package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ArtLookupTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    /** Answers by URL substring; unmatched URLs get a 404. Records every URL asked for. */
    static final class FakeHttp implements Http {
        final Map<String, Object> routes = new LinkedHashMap<>();
        final List<String> asked = new ArrayList<>();

        FakeHttp on(String urlPart, Object answer) {
            routes.put(urlPart, answer);
            return this;
        }

        @Override
        public Response get(String url) throws IOException {
            asked.add(url);
            for (Map.Entry<String, Object> e : routes.entrySet()) {
                if (!url.contains(e.getKey())) continue;
                Object a = e.getValue();
                if (a instanceof IOException) throw (IOException) a;
                if (a instanceof byte[]) return new Response(200, (byte[]) a);
                return new Response(200, ((String) a).getBytes(StandardCharsets.UTF_8));
            }
            return new Response(404, new byte[0]);
        }
    }

    private static Album album(String path, int tracks) {
        return new Album(new File(path), tracks, new File(path, "01.flac"), null, false);
    }

    private static String release(String id, String title, String artist, int tracks, int score, String rg) {
        return "{\"id\":\"" + id + "\",\"score\":" + score + ",\"title\":\"" + title + "\",\"track-count\":" + tracks
                + ",\"artist-credit\":[{\"name\":\"" + artist + "\",\"joinphrase\":\"\"}],\"release-group\":{\"id\":\"" + rg + "\"}}";
    }

    private static String search(String... releases) {
        return "{\"releases\":[" + String.join(",", releases) + "]}";
    }

    @Test
    public void mbidHit() {
        FakeHttp http = new FakeHttp().on("release/mb-1/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags("Help", "The Beatles", null, "mb-1", false));
        assertArrayEquals(JPEG, r.image);
        assertNull(r.reason);
        assertTrue(r.source.contains("release/mb-1/front-1200"));
    }

    @Test
    public void mbidWithoutArtFallsBackToReleaseGroup() {
        FakeHttp http = new FakeHttp()
                .on("ws/2/release/mb-1", "{\"id\":\"mb-1\",\"release-group\":{\"id\":\"rg-9\"}}")
                .on("release-group/rg-9/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags(null, null, null, "mb-1", false));
        assertArrayEquals(JPEG, r.image);
        assertTrue(r.source.contains("release-group/rg-9"));
    }

    @Test
    public void searchPrefersMatchingTrackCountOverSingles() {
        FakeHttp http = new FakeHttp()
                .on("ws/2/release/?query", search(
                        release("single", "Help!", "The Beatles", 2, 100, "rg-single"),
                        release("deluxe", "Help!", "The Beatles", 16, 100, "rg-album"),
                        release("lp", "Help!", "The Beatles", 14, 100, "rg-album")))
                .on("release/lp/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags("Help", "Beatles", null, null, false));
        assertArrayEquals(JPEG, r.image);
        assertTrue(r.source.contains("release/lp/"));
        for (String u : http.asked) assertFalse(u.contains("release/single/"));
    }

    @Test
    public void titleMismatchIsNoConfidentMatch() {
        FakeHttp http = new FakeHttp().on("ws/2/release/?query", search(release("x", "Helpless", "The Beatles", 14, 95, "rg")));
        LookupResult r = new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags("Help", "The Beatles", null, null, false));
        assertNull(r.image);
        assertEquals(LookupResult.NO_MATCH, r.reason);
    }

    @Test
    public void lowScoreIsNoConfidentMatch() {
        FakeHttp http = new FakeHttp().on("ws/2/release/?query", search(release("x", "Help", "The Beatles", 14, 60, "rg")));
        assertEquals(LookupResult.NO_MATCH,
                new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags("Help", "The Beatles", null, null, false)).reason);
    }

    @Test
    public void noTagsAndNoParentIsNoTags() {
        LookupResult r = new ArtLookup(new FakeHttp()).find(album("/Help", 3), Tags.EMPTY);
        assertEquals(LookupResult.NO_TAGS, r.reason);
    }

    @Test
    public void folderNamesUsedWhenTagsMissing() {
        FakeHttp http = new FakeHttp()
                .on("ws/2/release/?query", search(release("vm", "A Night in San Francisco", "Van Morrison", 14, 100, "rg")))
                .on("release/vm/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(album("/m/Van Morrison - A Night in San Francisco", 14), Tags.EMPTY);
        assertArrayEquals(JPEG, r.image);
    }

    @Test
    public void matchButNoArtAnywhere() {
        FakeHttp http = new FakeHttp().on("ws/2/release/?query", search(release("a", "Help", "The Beatles", 14, 100, "rg")));
        assertEquals(LookupResult.NO_ART,
                new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags("Help", "The Beatles", null, null, false)).reason);
    }

    @Test
    public void networkErrorStopsThisAlbumOnly() {
        FakeHttp http = new FakeHttp().on("musicbrainz.org", new IOException("offline"));
        LookupResult r = new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags("Help", "The Beatles", null, null, false));
        assertNull(r.image);
        assertEquals(LookupResult.NETWORK, r.reason);
    }

    @Test
    public void nonImageBodyIsNotAccepted() {
        FakeHttp http = new FakeHttp().on("release/mb-1/front-1200", "<html>oops</html>");
        LookupResult r = new ArtLookup(http).find(album("/m/Beatles/Help", 14), new Tags(null, null, null, "mb-1", false));
        assertNull(r.image);
    }

    @Test
    public void discSuffixLeftOutOfSearchButStillMatches() {
        FakeHttp http = new FakeHttp()
                .on("ws/2/release/?query", search(release("bb", "1967–1970", "The Beatles", 28, 100, "rg")))
                .on("release/bb/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(album("/m/The Beatles/1967-1970 (Disc 1)", 11),
                new Tags("1967-1970 (Disc 1)", "The Beatles", null, null, false));
        assertArrayEquals(JPEG, r.image);
        String query = http.asked.get(0);
        assertFalse(query, query.contains("Disc"));
        assertTrue(query, query.contains("1967-1970"));
    }

    @Test
    public void curlyApostropheMatchesStraight() {
        FakeHttp http = new FakeHttp()
                .on("ws/2/release/?query", search(release("hdn", "A Hard Day’s Night", "The Beatles", 13, 99, "rg")))
                .on("release/hdn/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(album("/m/The Beatles/A Hard Day's Night", 13),
                new Tags("A Hard Day's Night", "The Beatles", null, null, false));
        assertArrayEquals(JPEG, r.image);
    }

    @Test
    public void embeddedArtIsCopiedWithoutNetwork() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("amca").toFile();
        File track = new File(dir, "01.flac");
        java.nio.file.Files.write(track.toPath(), TagReaderTest.flacWithPictures(3, JPEG));
        FakeHttp http = new FakeHttp();
        LookupResult r = new ArtLookup(http).find(new Album(dir, 12, track, null, true), new Tags("x", "y", null, null, true));
        assertArrayEquals(JPEG, r.image);
        assertTrue(r.fromTracks());
        assertTrue(http.asked.isEmpty());
    }

    @Test
    public void embeddedPngFallsBackToOnline() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("amca").toFile();
        File track = new File(dir, "01.flac");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1};
        java.nio.file.Files.write(track.toPath(), TagReaderTest.flacWithPictures(3, png));
        FakeHttp http = new FakeHttp().on("release/mb-1/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(new Album(dir, 12, track, null, true), new Tags(null, null, null, "mb-1", true));
        assertArrayEquals(JPEG, r.image);
        assertFalse(r.fromTracks());
    }

    @Test
    public void normalisation() {
        assertEquals("beatles and friends", Text.norm("The Beatles & Friends (Remastered 2009)"));
        assertEquals("bjork", Text.norm("Björk"));
        assertEquals("help", Text.norm("Help!"));
        assertEquals("help", Text.norm("Help [Deluxe]"));
        assertEquals(Text.norm("The Album II"), Text.norm("Album II, The"));
        assertEquals("The Album II", ArtLookup.searchTitle("Album II, The"));
        assertEquals("1967-1970", ArtLookup.searchTitle("1967-1970 (Disc 1)"));
    }
}
