package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The track-list fallback, end to end through {@link ArtLookup} with real FLAC tags on disk and hand-built
 * MusicBrainz JSON from src/test/resources/musicbrainz (the shapes of real responses; the artist and IDs are made up).
 */
public class TrackListMatchTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    /** IDs in the fixtures. */
    private static final String NIGHT_DRIVES = "d02697dd-6684-5623-aacc-05c80f4a4ebf";
    private static final String NIGHT_DRIVES_RG = "b890c800-e45f-58ab-bece-42565d591fd2";
    private static final String NORTHBOUND_US = "0d630ddc-751c-5335-98c9-949dbb266b41";
    private static final String COASTLINE = "7767ed50-affc-572e-9d31-fa89f6303423";

    private static final String RELEASE_SEARCH = "ws/2/release/?query";
    private static final String RECORDING_SEARCH = "ws/2/recording/?query";
    private static final String LOOKUP = "?inc=recordings+artist-credits+release-groups";

    static final String[] TITLES = {"Harbour Lights", "Paper Moon Motel", "Static on the Radio", "Glass Houses",
            "Northbound", "Kerosene Heart", "Slow Fade", "The Long Way Round", "Undertow", "Saltwater",
            "Satellite Town", "Last Train Home"};

    static String fixture(String name) {
        try (InputStream in = TrackListMatchTest.class.getResourceAsStream("/musicbrainz/" + name)) {
            assertNotNull("missing fixture " + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---- a folder of tagged FLAC files ----

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] flac(String... comments) throws Exception {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(le32(4));
        c.write("test".getBytes(StandardCharsets.UTF_8));
        c.write(le32(comments.length));
        for (String s : comments) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            c.write(le32(b.length));
            c.write(b);
        }
        byte[] d = c.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        out.write(0x80 | 4);
        out.write(d.length >> 16);
        out.write(d.length >> 8);
        out.write(d.length);
        out.write(d);
        return out.toByteArray();
    }

    /** One file per title, "01 Title.flac", tagged with album, artist, title and track number. */
    private Album folder(String album, String... titles) throws Exception {
        // ':' is illegal in Windows (and FAT/exFAT/SMB) folder names; only the ALBUM tag keeps it.
        File dir = new File(tmp.getRoot(), "The Lanterns/" + album.replace(':', ' '));
        assertTrue(dir.mkdirs());
        File first = null;
        for (int i = 0; i < titles.length; i++) {
            File f = new File(dir, String.format("%02d %s.flac", i + 1, titles[i].replace('/', ' ')));
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(flac("ALBUM=" + album, "ARTIST=The Lanterns", "TITLE=" + titles[i], "TRACKNUMBER=" + (i + 1)));
            }
            if (first == null) first = f;
        }
        return new Album(dir, titles.length, first, null, false);
    }

    private static Tags tags(String album) {
        return new Tags(album, "The Lanterns", null, null, false);
    }

    /** The folder as tagged by a ripper: titles a little different from MusicBrainz in case and suffix. */
    private static String[] greatestHits() {
        String[] t = TITLES.clone();
        t[2] = "Static On The Radio";
        t[5] = "Kerosene Heart - 2004 Remaster";
        return t;
    }

    private static int count(List<String> urls, String part) {
        int n = 0;
        for (String u : urls) if (u.contains(part)) n++;
        return n;
    }

    // ---- the fallback through ArtLookup ----

    @Test
    public void confidentFallbackMatch() throws Exception {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, fixture("release-search-other-titles.json"))
                .on(RECORDING_SEARCH, fixture("recording-search-night-drives.json"))
                .on("ws/2/release/" + NIGHT_DRIVES + LOOKUP, fixture("release-night-drives.json"))
                .on("coverartarchive.org/release/" + NIGHT_DRIVES + "/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", greatestHits()), tags("Greatest Hits"));
        assertArrayEquals(JPEG, r.image);
        assertNull(r.reason);
        assertTrue(r.source, r.source.contains("release/" + NIGHT_DRIVES + "/front-1200"));
        assertTrue("marked as a track-list match", r.byTrackList);
        // One search each, one lookup: "Coastline" had only three of the five titles and was never fetched.
        assertEquals(1, count(http.asked, RELEASE_SEARCH));
        assertEquals(1, count(http.asked, RECORDING_SEARCH));
        assertEquals(1, count(http.asked, LOOKUP));
        assertEquals(0, count(http.asked, COASTLINE));
        String q = URLDecoder.decode(http.asked.get(1), "UTF-8");
        assertTrue(q, q.contains("artist:\"The Lanterns\" AND tracksrelease:12 AND (recording:\"Harbour Lights\""));
        assertTrue(q, q.contains("recording:\"Last Train Home\")"));
        // Searched as the bare song title; the tag's "- 2004 Remaster" would miss the recording.
        assertTrue(q, q.contains(" OR recording:\"Kerosene Heart\" OR "));
        assertFalse(q, q.contains("Remaster"));
    }

    @Test
    public void confidentFallbackAfterZeroTitleResults() throws Exception {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, fixture("release-search-zero.json"))
                .on(RECORDING_SEARCH, fixture("recording-search-night-drives.json"))
                .on("ws/2/release/" + NIGHT_DRIVES + LOOKUP, fixture("release-night-drives.json"))
                .on("coverartarchive.org/release-group/" + NIGHT_DRIVES_RG + "/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(folder("Best Of", greatestHits()), tags("Best Of"));
        // No art on the release itself: the release group's cover, as the title search does.
        assertArrayEquals(JPEG, r.image);
        assertTrue(r.source, r.source.contains("release-group/" + NIGHT_DRIVES_RG));
        assertTrue("marked as a track-list match", r.byTrackList);
    }

    @Test
    public void wrongTrackCountIsRejected() throws Exception {
        // Eleven files: one song missing from the folder, so it is not the twelve-track release.
        List<String> titles = new ArrayList<>(Arrays.asList(greatestHits()));
        titles.remove(6);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, fixture("release-search-other-titles.json"))
                .on(RECORDING_SEARCH, fixture("recording-search-night-drives.json"))
                .on("ws/2/release/" + NIGHT_DRIVES + LOOKUP, fixture("release-night-drives.json"))
                .on("coverartarchive.org/", JPEG);
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", titles.toArray(new String[0])), tags("Greatest Hits"));
        assertNull(r.image);
        assertEquals(LookupResult.NO_MATCH, r.reason);
        assertEquals(0, count(http.asked, LOOKUP));
        assertEquals(0, count(http.asked, "coverartarchive.org"));
    }

    @Test
    public void tooFewTitlesMatchIsRejected() throws Exception {
        // Another twelve-track compilation sharing three of the five sampled songs (tracks 3 and 9 differ).
        String[] titles = TITLES.clone();
        titles[2] = "Driftwood";
        titles[8] = "Paper Lanterns";
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, fixture("release-search-other-titles.json"))
                .on(RECORDING_SEARCH, fixture("recording-search-three-titles.json"))
                .on("ws/2/release/" + NIGHT_DRIVES + LOOKUP, fixture("release-night-drives.json"))
                .on("coverartarchive.org/", JPEG);
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", titles), tags("Greatest Hits"));
        assertNull(r.image);
        assertEquals(LookupResult.NO_MATCH, r.reason);
        assertEquals(0, count(http.asked, LOOKUP));
    }

    @Test
    public void twoDifferentAlbumsThatFitAreBothSkipped() throws Exception {
        // The same twelve songs, sold as a UK and a US compilation with different covers: can't tell which.
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, fixture("release-search-zero.json"))
                .on(RECORDING_SEARCH, fixture("recording-search-two-editions.json"))
                .on("ws/2/release/" + NIGHT_DRIVES + LOOKUP, fixture("release-night-drives.json"))
                .on("ws/2/release/" + NORTHBOUND_US + LOOKUP, fixture("release-northbound-us.json"))
                .on("coverartarchive.org/", JPEG);
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", greatestHits()), tags("Greatest Hits"));
        assertNull(r.image);
        assertEquals(LookupResult.NO_MATCH, r.reason);
        assertEquals(2, count(http.asked, LOOKUP));
        assertEquals(0, count(http.asked, "coverartarchive.org"));
    }

    // ---- wording ----

    @Test
    public void zeroResultsIsNotOnMusicBrainz() throws Exception {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, fixture("release-search-zero.json"))
                .on(RECORDING_SEARCH, fixture("recording-search-zero.json"));
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", greatestHits()), tags("Greatest Hits"));
        assertNull(r.image);
        assertEquals(LookupResult.NOT_ON_MB, r.reason);
        assertEquals("not on MusicBrainz", r.reason);
        assertEquals(1, count(http.asked, RECORDING_SEARCH));
    }

    @Test
    public void zeroResultsWithoutUsableTitlesIsNotOnMusicBrainz() throws Exception {
        // Ripped without a track list: "Track 01"… says nothing, so no track-list search is made.
        String[] titles = new String[12];
        for (int i = 0; i < titles.length; i++) titles[i] = String.format("Track %02d", i + 1);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on(RELEASE_SEARCH, fixture("release-search-zero.json"));
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", titles), tags("Greatest Hits"));
        assertEquals(LookupResult.NOT_ON_MB, r.reason);
        assertEquals(1, http.asked.size());
    }

    @Test
    public void resultsButNoneConfidentIsStillNoConfidentMatch() throws Exception {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, fixture("release-search-other-titles.json"))
                .on(RECORDING_SEARCH, fixture("recording-search-zero.json"));
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", greatestHits()), tags("Greatest Hits"));
        assertEquals(LookupResult.NO_MATCH, r.reason);
    }

    @Test
    public void titleSearchErrorIsNotCalledZeroResults() throws Exception {
        // A 4xx from the title search is not an empty answer.
        Http http = url -> url.contains(RELEASE_SEARCH)
                ? new Http.Response(400, new byte[0])
                : new Http.Response(200, fixture("recording-search-zero.json").getBytes(StandardCharsets.UTF_8));
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", greatestHits()), tags("Greatest Hits"));
        assertEquals(LookupResult.NO_MATCH, r.reason);
    }

    // ---- albums that already match pay nothing ----

    @Test
    public void albumThatMatchesByTitleNeverTriggersTheFallback() throws Exception {
        String hit = "{\"releases\":[{\"id\":\"nd\",\"score\":100,\"title\":\"Night Drives: The Collection\",\"track-count\":12,"
                + "\"artist-credit\":[{\"name\":\"The Lanterns\"}],\"release-group\":{\"id\":\"rg\"}}]}";
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, hit)
                .on(RECORDING_SEARCH, fixture("recording-search-night-drives.json"))
                .on("release/nd/front-1200", JPEG);
        Album a = folder("Night Drives: The Collection", greatestHits());
        LookupResult r = new ArtLookup(http).find(a, tags("Night Drives: The Collection"));
        assertArrayEquals(JPEG, r.image);
        assertFalse("a title match is not marked as a track-list match", r.byTrackList);
        assertEquals(Arrays.asList(
                ArtLookup.MB + "release/?query=" + "release%3A%22Night%20Drives%3A%20The%20Collection%22%20AND%20artist%3A%22The%20Lanterns%22&fmt=json&limit=25",
                ArtLookup.CAA + "release/nd/front-1200"), http.asked);
    }

    @Test
    public void matchedButNoArtDoesNotTriggerTheFallback() throws Exception {
        String hit = "{\"releases\":[{\"id\":\"nd\",\"score\":100,\"title\":\"Greatest Hits\",\"track-count\":12,"
                + "\"artist-credit\":[{\"name\":\"The Lanterns\"}],\"release-group\":{\"id\":\"rg\"}}]}";
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on(RELEASE_SEARCH, hit)
                .on(RECORDING_SEARCH, fixture("recording-search-night-drives.json"));
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", greatestHits()), tags("Greatest Hits"));
        assertEquals(LookupResult.NO_ART, r.reason);
        assertEquals(0, count(http.asked, RECORDING_SEARCH));
    }

    @Test
    public void musicBrainzIdHitDoesNotTriggerTheFallback() throws Exception {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/mb-1/front-1200", JPEG);
        LookupResult r = new ArtLookup(http).find(folder("Greatest Hits", greatestHits()),
                new Tags("Greatest Hits", "The Lanterns", null, "mb-1", false));
        assertArrayEquals(JPEG, r.image);
        assertEquals(1, http.asked.size());
    }

    // ---- the rule itself, on the release lookup ----

    private static List<TrackListMatch.Sample> samples(String... titles) {
        List<TrackListMatch.Sample> out = new ArrayList<>();
        for (String t : titles) out.add(new TrackListMatch.Sample(t, TrackListMatch.titleKey(t)));
        return out;
    }

    private static JSONObject nightDrives() {
        // Android's org.json throws a checked JSONException here; the plain-Java jar does not.
        try {
            return new JSONObject(fixture("release-night-drives.json"));
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void confirmAcceptsFourOfFiveInOrder() {
        TrackListMatch.Match m = TrackListMatch.confirm(nightDrives(), "The Lanterns", 12,
                samples("Harbour Lights", "Static on the Radio", "Not On This Album", "Undertow", "Last Train Home"));
        assertNotNull(m);
        assertEquals(NIGHT_DRIVES, m.releaseId);
        assertEquals(NIGHT_DRIVES_RG, m.releaseGroupId);
    }

    @Test
    public void confirmRejectsWrongTrackCount() {
        assertNull(TrackListMatch.confirm(nightDrives(), "The Lanterns", 11,
                samples("Harbour Lights", "Static on the Radio", "Kerosene Heart", "Undertow", "Last Train Home")));
    }

    @Test
    public void confirmRejectsThreeOfFive() {
        assertNull(TrackListMatch.confirm(nightDrives(), "The Lanterns", 12,
                samples("Harbour Lights", "Driftwood", "Kerosene Heart", "Paper Lanterns", "Last Train Home")));
    }

    @Test
    public void confirmRejectsSameSongsInAnotherOrder() {
        // All five titles are there, but only two can line up in the folder's order.
        assertNull(TrackListMatch.confirm(nightDrives(), "The Lanterns", 12,
                samples("Last Train Home", "Undertow", "Kerosene Heart", "Static on the Radio", "Harbour Lights")));
    }

    @Test
    public void confirmRejectsAnotherArtist() {
        assertNull(TrackListMatch.confirm(nightDrives(), "The Lantern Society", 12,
                samples("Harbour Lights", "Static on the Radio", "Kerosene Heart", "Undertow", "Last Train Home")));
        assertNull(TrackListMatch.confirm(nightDrives(), "!!!", 12,
                samples("Harbour Lights", "Static on the Radio", "Kerosene Heart", "Undertow", "Last Train Home")));
    }

    @Test
    public void confirmWithThreeSamplesNeedsAllThree() {
        assertNotNull(TrackListMatch.confirm(nightDrives(), "The Lanterns", 12, samples("Harbour Lights", "Undertow", "Saltwater")));
        assertNull(TrackListMatch.confirm(nightDrives(), "The Lanterns", 12, samples("Harbour Lights", "Undertow", "Driftwood")));
    }

    @Test
    public void neededMatches() {
        assertEquals(3, TrackListMatch.needed(3));
        assertEquals(4, TrackListMatch.needed(4));
        assertEquals(4, TrackListMatch.needed(5));
    }

    // ---- sampling ----

    private static File[] names(File dir, int n) throws Exception {
        assertTrue(dir.mkdirs());
        File[] files = new File[n];
        for (int i = 0; i < n; i++) {
            files[i] = new File(dir, String.format("%02d.flac", i + 1));
            assertTrue(files[i].createNewFile());
        }
        assertTrue(new File(dir, "folder.txt").createNewFile()); // not audio: ignored
        return files;
    }

    @Test
    public void sampleSpreadsFromFirstToLastAndSkipsGenericTitles() throws Exception {
        File dir = new File(tmp.getRoot(), "a");
        names(dir, 12);
        Map<String, TrackInfo> tags = new HashMap<>();
        for (int i = 0; i < 12; i++) tags.put(String.format("%02d.flac", i + 1), new TrackInfo(i + 1, null, null, null, null, TITLES[i]));
        tags.put("06.flac", new TrackInfo(6, null, null, null, null, "Track 06"));
        List<TrackListMatch.Sample> s = TrackListMatch.sample(dir, f -> tags.get(f.getName()));
        List<String> got = new ArrayList<>();
        for (TrackListMatch.Sample x : s) got.add(x.title);
        assertEquals(Arrays.asList("Harbour Lights", "Static on the Radio", "Undertow", "Last Train Home"), got);
    }

    @Test
    public void sampleFollowsTrackTagsOverFileNames() throws Exception {
        File dir = new File(tmp.getRoot(), "b");
        names(dir, 3);
        // Files named against the track order; the tags have it right.
        Map<String, TrackInfo> tags = new HashMap<>();
        tags.put("01.flac", new TrackInfo(3, null, null, null, null, "Gamma"));
        tags.put("02.flac", new TrackInfo(1, null, null, null, null, "Alpha"));
        tags.put("03.flac", new TrackInfo(2, null, null, null, null, "Beta"));
        List<TrackListMatch.Sample> s = TrackListMatch.sample(dir, f -> tags.get(f.getName()));
        assertEquals("Alpha", s.get(0).title);
        assertEquals("Beta", s.get(1).title);
        assertEquals("Gamma", s.get(2).title);
    }

    @Test
    public void tooFewUsableTitlesMeansNoSample() throws Exception {
        File dir = new File(tmp.getRoot(), "c");
        names(dir, 4);
        Map<String, TrackInfo> tags = new HashMap<>();
        tags.put("01.flac", new TrackInfo(1, null, null, null, null, "Alpha"));
        tags.put("02.flac", new TrackInfo(2, null, null, null, null, null));
        tags.put("03.flac", new TrackInfo(3, null, null, null, null, "Untitled"));
        tags.put("04.flac", new TrackInfo(4, null, null, null, null, "Delta"));
        assertTrue(TrackListMatch.sample(dir, f -> tags.get(f.getName())).isEmpty());
        assertTrue(TrackListMatch.sample(new File(tmp.getRoot(), "missing"), f -> TrackInfo.EMPTY).isEmpty());
    }

    @Test
    public void titleKeys() {
        assertEquals(TrackListMatch.titleKey("Kerosene Heart"), TrackListMatch.titleKey("Kerosene Heart - 2004 Remaster"));
        assertEquals(TrackListMatch.titleKey("Kerosene Heart"), TrackListMatch.titleKey("Kerosene Heart (Remastered)"));
        assertEquals(TrackListMatch.titleKey("Static on the Radio"), TrackListMatch.titleKey("Static On The Radio"));
        // A live take is a different recording, likely a different album: kept apart.
        assertNotEquals(TrackListMatch.titleKey("Kerosene Heart"), TrackListMatch.titleKey("Kerosene Heart - Live"));
        assertFalse(TrackListMatch.usable(TrackListMatch.titleKey("Track 7")));
        assertFalse(TrackListMatch.usable(TrackListMatch.titleKey("Piste 12")));
        assertFalse(TrackListMatch.usable(TrackListMatch.titleKey("")));
        assertTrue(TrackListMatch.usable(TrackListMatch.titleKey("1999")));
    }
}
