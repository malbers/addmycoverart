package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RipCheckTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ---- FLAC with STREAMINFO + Vorbis comments ----

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static void block(ByteArrayOutputStream out, int type, boolean last, byte[] data) {
        out.write((last ? 0x80 : 0) | type);
        out.write(data.length >> 16);
        out.write(data.length >> 8);
        out.write(data.length);
        out.write(data, 0, data.length);
    }

    static byte[] streamInfo(int rate, long samples) {
        byte[] d = new byte[34];
        d[10] = (byte) (rate >> 12);
        d[11] = (byte) (rate >> 4);
        d[12] = (byte) ((rate & 0x0f) << 4 | 0x02); // low nibble: channels/bps, ignored
        d[13] = (byte) (0x70 | (samples >> 32) & 0x0f);
        d[14] = (byte) (samples >> 24);
        d[15] = (byte) (samples >> 16);
        d[16] = (byte) (samples >> 8);
        d[17] = (byte) samples;
        return d;
    }

    private static byte[] flac(long ms, String... comments) throws Exception {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(le32(4));
        c.write("test".getBytes(StandardCharsets.UTF_8));
        c.write(le32(comments.length));
        for (String s : comments) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            c.write(le32(b.length));
            c.write(b);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        block(out, 0, false, streamInfo(44100, ms * 44100 / 1000));
        block(out, 4, true, c.toByteArray());
        return out.toByteArray();
    }

    private File albumDir(String name) throws Exception {
        return tmp.newFolder(name);
    }

    private static void track(File dir, int n, long ms, String mbid, Integer disc) throws Exception {
        String[] tags = disc == null
                ? new String[]{"TRACKNUMBER=" + n, "MUSICBRAINZ_ALBUMID=" + mbid}
                : new String[]{"TRACKNUMBER=" + n, "DISCNUMBER=" + disc, "MUSICBRAINZ_ALBUMID=" + mbid};
        try (FileOutputStream o = new FileOutputStream(new File(dir, String.format("%02d.flac", n)))) {
            o.write(flac(ms, tags));
        }
    }

    private static Album album(File dir) {
        String[] names = dir.list();
        return new Album(dir, names == null ? 0 : names.length, null, null, false);
    }

    /** A medium JSON: position, and track lengths in ms (null = unknown). */
    private static String medium(int position, Long... lengths) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < lengths.length; i++) {
            if (i > 0) t.append(',');
            t.append("{\"position\":").append(i + 1).append(",\"length\":").append(lengths[i]).append('}');
        }
        return "{\"position\":" + position + ",\"track-count\":" + lengths.length + ",\"tracks\":[" + t + "]}";
    }

    private static String release(String... media) {
        return "{\"media\":[" + String.join(",", media) + "]}";
    }

    // ---- STREAMINFO ----

    @Test
    public void readsFlacDurationAndNumbers() throws Exception {
        File d = albumDir("A");
        track(d, 3, 185_000, "mb", 2);
        TrackInfo t = TagReader.readTrack(new File(d, "03.flac"));
        assertEquals(Integer.valueOf(3), t.track);
        assertEquals(Integer.valueOf(2), t.disc);
        assertEquals(Long.valueOf(185_000), t.durationMs);
        assertEquals("mb", t.mbAlbumId);
    }

    @Test
    public void zeroStreamInfoHasNoDuration() {
        assertNull(TagReader.streamInfoMs(new byte[34]));
    }

    // ---- check ----

    @Test
    public void completeRipIsOk() throws Exception {
        File d = albumDir("Help");
        track(d, 1, 140_000, "mb-help", null);
        track(d, 2, 150_000, "mb-help", null);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/mb-help", release(medium(1, 140_500L, 149_000L)));
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertNull(r.skipped);
        assertTrue(r.problems.toString(), r.ok());
        assertTrue(http.asked.get(0).contains("inc=recordings"));
    }

    @Test
    public void missingTracksListedByNumber() throws Exception {
        File d = albumDir("1967-1970 (Disc 2)");
        track(d, 1, 100_000, "mb-blue", 2);
        track(d, 2, 100_000, "mb-blue", 2);
        track(d, 5, 100_000, "mb-blue", 2);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/mb-blue",
                release(medium(1, 1L, 1L), medium(2, 100_000L, 100_000L, 100_000L, 100_000L, 100_000L)));
        List<String> p = new RipCheck(http).check(album(d)).problems;
        assertEquals(List.of("3 of 5 tracks, missing 3, 4"), p);
    }

    @Test
    public void shortTrackFlagged() throws Exception {
        File d = albumDir("X");
        track(d, 1, 41_000, "mb-x", null);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/mb-x", release(medium(1, 252_000L)));
        assertEquals(List.of("01 is 0:41, expected 4:12"), new RipCheck(http).check(album(d)).problems);
    }

    @Test
    public void smallDifferenceIsNotShort() throws Exception {
        JSONObject m = new JSONObject(medium(1, 300_000L));
        // 10% of 5:00 is 30 s; 8 s short is within it.
        assertTrue(RipCheck.compare(List.of(new TrackInfo(1, null, null, 292_000L, "x")), m).isEmpty());
    }

    @Test
    public void unknownLengthIsNotChecked() throws Exception {
        JSONObject m = new JSONObject(medium(1, (Long) null));
        assertTrue(RipCheck.compare(List.of(new TrackInfo(1, null, null, 10_000L, "x")), m).isEmpty());
    }

    @Test
    public void emptyTrackFlagged() throws Exception {
        JSONObject m = new JSONObject(medium(1, (Long) null));
        assertEquals(List.of("track 1 is empty"), RipCheck.compare(List.of(new TrackInfo(1, null, null, 300L, "x")), m));
    }

    // ---- ripcheck4: the Commodores case and friends ----

    private static void raw(File dir, String name, byte[] bytes) throws Exception {
        try (FileOutputStream o = new FileOutputStream(new File(dir, name))) { o.write(bytes); }
    }

    @Test
    public void tinyFileWithNoLengthIsEmpty() throws Exception {
        File d = albumDir("Tiny");
        tagged(d, 1, "TRACKNUMBER=1", "TRACKTOTAL=2");
        // A FLAC header with zero samples: the 1,139-byte files the ripper left behind.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        block(out, 0, true, new byte[34]);
        raw(d, "02.Three Times a Lady.flac", out.toByteArray());
        assertEquals(List.of("02.Three Times a Lady is empty"), new RipCheck(new ArtLookupTest.FakeHttp()).check(album(d)).problems);
    }

    @Test
    public void searchBringsLengthsFromAMajorityEdition() throws Exception {
        File d = new File(tmp.newFolder("Commodores"), "Anthology");
        assertTrue(d.mkdir());
        raw(d, "01.Zoom-Commodores.flac", flac(14_400, "TRACKNUMBER=1"));
        raw(d, "02.Brick House-Commodores.flac", flac(203_600, "TRACKNUMBER=2"));
        String rel = "{\"id\":\"ed-1\",\"score\":100,\"title\":\"Anthology\",\"artist-credit\":[{\"name\":\"Commodores\",\"joinphrase\":\"\"}],"
                + "\"media\":[{\"format\":\"CD\",\"track-count\":2}]}";
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("release/?query", search(rel))
                .on("release/ed-1", release(medium(1, 440_000L, 204_000L)));
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertEquals(RipCheck.BY_SEARCH, r.basis);
        assertEquals(List.of("01.Zoom-Commodores is 0:14, expected 7:20"), r.problems);
        assertEquals("Commodores", r.artist);
    }

    @Test
    public void zeroBasedTagsAreShiftedAndReported() throws Exception {
        // The Eversolo CD Ripper case: file "01" tagged 0, "02" tagged 1. Lengths must still line up.
        File d = new File(tmp.newFolder("Commodores"), "Anthology");
        assertTrue(d.mkdir());
        raw(d, "01.Machine Gun.flac", flac(163_000, "TRACKNUMBER=0"));
        raw(d, "02.Zoom.flac", flac(14_400, "TRACKNUMBER=1"));
        raw(d, "03.Brick House.flac", flac(203_600, "TRACKNUMBER=2"));
        String rel = "{\"id\":\"ed-1\",\"score\":100,\"title\":\"Anthology\",\"artist-credit\":[{\"name\":\"Commodores\",\"joinphrase\":\"\"}],"
                + "\"media\":[{\"format\":\"CD\",\"track-count\":3}]}";
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("release/?query", search(rel))
                .on("release/ed-1", release(medium(1, 165_000L, 440_000L, 204_000L)));
        assertEquals(List.of("02.Zoom is 0:14, expected 7:20", RipCheck.ZERO_BASED), new RipCheck(http).check(album(d)).problems);
    }

    @Test
    public void lengthsFollowFileNamesWhenTagsAreShifted() throws Exception {
        // Tags shifted by one (not from 0): file names line up with the release, so they are used.
        List<TrackInfo> t = List.of(
                new TrackInfo(2, null, null, 163_000L, null).withFile(new File(tmp.getRoot(), "01.A.flac")),
                new TrackInfo(3, null, null, 440_000L, null).withFile(new File(tmp.getRoot(), "02.B.flac")),
                new TrackInfo(4, null, null, 204_000L, null).withFile(new File(tmp.getRoot(), "03.C.flac")));
        JSONObject m = new JSONObject(medium(1, 165_000L, 440_000L, 204_000L, 300_000L));
        assertEquals(List.of("3 of 4 tracks, missing 4"), RipCheck.compare(t, m));
    }

    @Test
    public void tagsOneLowerThanFileNames() {
        List<TrackInfo> t = List.of(info(15, "16.Cebu"), info(16, "17.Zoom"), info(17, "18.Brick House"));
        assertEquals("track tags are 1 lower than the file names (3 of 3 files)", RipCheck.tagNameMismatch(t));
    }

    @Test
    public void tagsShiftedPartWay() {
        List<TrackInfo> t = List.of(info(1, "01.A"), info(2, "02.B"), info(2, "03.C"), info(3, "04.D"));
        assertEquals("track tags are 1 lower than the file names from about file 3 (2 of 4 files)", RipCheck.tagNameMismatch(t));
    }

    @Test
    public void matchingNamesAndDiscTrackNamesAreFine() {
        assertNull(RipCheck.tagNameMismatch(List.of(info(1, "01.A"), info(2, "02 B"))));
        assertNull(RipCheck.tagNameMismatch(List.of(info(3, "1-03 Title"), info(4, "1-04 Next"))));
        assertNull(RipCheck.tagNameMismatch(List.of(info(1, "1967-1970 Intro"))));
    }

    private TrackInfo info(int track, String fileName) {
        try {
            File f = new File(tmp.getRoot(), fileName + ".flac");
            return new TrackInfo(track, null, null, 100_000L, null).withFile(f);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void strayFileListedWhenShort() throws Exception {
        File d = albumDir("Aventine");
        tagged(d, 1, "TRACKNUMBER=1/2");
        raw(d, "02.Fuel to Fire", flac(100_000, "TRACKNUMBER=2/2")); // lost its .flac
        raw(d, "Aventine.cue", new byte[]{1});
        raw(d, "folder.jpg", new byte[]{1});
        assertEquals(List.of("1 of 2 tracks, missing 2", "1 file isn't recognised as audio: 02.Fuel to Fire"),
                new RipCheck(new ArtLookupTest.FakeHttp()).check(album(d)).problems);
    }

    @Test
    public void noStrayListWhenComplete() throws Exception {
        File d = albumDir("Done");
        tagged(d, 1, "TRACKNUMBER=1/1");
        raw(d, "notes", new byte[]{1});
        assertTrue(new RipCheck(new ArtLookupTest.FakeHttp()).check(album(d)).ok());
    }

    private static void tagged(File dir, int n, String... tags) throws Exception {
        try (FileOutputStream o = new FileOutputStream(new File(dir, String.format("%02d.flac", n)))) {
            o.write(flac(100_000, tags));
        }
    }

    private static String found(String title, String artist, String format, int... discs) {
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < discs.length; i++) {
            if (i > 0) m.append(',');
            m.append("{\"format\":\"").append(format).append("\",\"track-count\":").append(discs[i]).append('}');
        }
        return "{\"score\":100,\"title\":\"" + title + "\",\"artist-credit\":[{\"name\":\"" + artist
                + "\",\"joinphrase\":\"\"}],\"media\":[" + m + "]}";
    }

    private static String search(String... releases) {
        return "{\"releases\":[" + String.join(",", releases) + "]}";
    }

    // ---- 2. tags, no network ----

    @Test
    public void statedTotalFlagsMissingWithoutNetwork() throws Exception {
        File d = albumDir("Total");
        tagged(d, 1, "TRACKNUMBER=1/4");
        tagged(d, 2, "TRACKNUMBER=2/4");
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp();
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertEquals(RipCheck.BY_TAGS, r.basis);
        assertEquals(List.of("2 of 4 tracks, missing 3, 4"), r.problems);
        assertTrue(http.asked.isEmpty());
    }

    @Test
    public void trackTotalTagCounts() throws Exception {
        File d = albumDir("Total2");
        tagged(d, 1, "TRACKNUMBER=1", "TRACKTOTAL=1");
        RipCheck.Result r = new RipCheck(new ArtLookupTest.FakeHttp()).check(album(d));
        assertEquals(RipCheck.BY_TAGS, r.basis);
        assertTrue(r.ok());
    }

    @Test
    public void gapsStandWhenSearchCantSay() throws Exception {
        File d = albumDir("Gaps");
        tagged(d, 1, "TRACKNUMBER=1");
        tagged(d, 2, "TRACKNUMBER=2");
        tagged(d, 5, "TRACKNUMBER=5");
        RipCheck.Result r = new RipCheck(new ArtLookupTest.FakeHttp()).check(album(d));
        assertEquals(RipCheck.BY_TAGS, r.basis);
        assertEquals(List.of("missing tracks 3, 4"), r.problems);
    }

    @Test
    public void gapsPlusSearchFindTheMissingLastTrack() throws Exception {
        // The 1967-1970 Disc 1 case: 1-10 and 13 present; the gaps alone can't see 14.
        File d = new File(tmp.newFolder("The Beatles"), "1967-1970 (Disc 1)");
        assertTrue(d.mkdir());
        for (int n = 1; n <= 10; n++) tagged(d, n, "TRACKNUMBER=" + n);
        tagged(d, 13, "TRACKNUMBER=13");
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search(
                found("1967-1970", "The Beatles", "CD", 14, 14)));
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertEquals(RipCheck.BY_SEARCH, r.basis);
        assertEquals(List.of("11 of 14 tracks, missing 11, 12, 14"), r.problems);
    }

    @Test
    public void majorityOfEditionsIsEnough() {
        assertEquals(14, RipCheck.majority(List.of(14, 14, 17)));
        assertEquals(RipCheck.DIFFER, RipCheck.majority(List.of(14, 17)));
        assertEquals(14, RipCheck.majority(List.of(14)));
    }

    @Test
    public void oddDeluxeEditionDoesNotBlock() throws Exception {
        File d = new File(tmp.newFolder("The Beatles"), "Abbey Road");
        assertTrue(d.mkdir());
        for (int n = 1; n <= 17; n++) tagged(d, n, "TRACKNUMBER=" + n);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search(
                found("Abbey Road", "The Beatles", "CD", 17),
                found("Abbey Road", "The Beatles", "CD", 17),
                found("Abbey Road", "The Beatles", "CD", 23),
                // a three-disc anniversary box: ignored for a folder with no disc number
                found("Abbey Road", "The Beatles", "CD", 17, 23, 13)));
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertNull(r.skipped);
        assertTrue(r.problems.toString(), r.ok());
    }

    @Test
    public void wholeSetInOneFolder() throws Exception {
        File d = new File(tmp.newFolder("The Beatles"), "Live at the BBC");
        assertTrue(d.mkdir());
        for (int n = 1; n <= 15; n++) tagged(d, n, "TRACKNUMBER=" + n);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search(
                found("Live at the BBC", "The Beatles", "CD", 10, 10)));
        assertEquals(List.of("15 of 20 tracks, missing 16, 17, 18, 19, 20"), new RipCheck(http).check(album(d)).problems);
    }

    @Test
    public void fiveSecondsShortOnAShortTrackIsFine() throws Exception {
        // Blood, Sweat & Tears track 10: 1:35 on the rip, 1:40 on MusicBrainz, a good rip.
        JSONObject m = new JSONObject(medium(1, 100_000L));
        assertTrue(RipCheck.compare(List.of(new TrackInfo(1, null, null, 95_000L, "x")), m).isEmpty());
    }

    // ---- 3. search ----

    private File beatlesDisc(int disc, int tracks) throws Exception {
        File d = new File(tmp.newFolder("The Beatles"), "1967-1970 (Disc " + disc + ")");
        assertTrue(d.mkdir());
        for (int n = 1; n <= tracks; n++) tagged(d, n, "TRACKNUMBER=" + n);
        return d;
    }

    @Test
    public void searchUsesCountAllEditionsAgreeOn() throws Exception {
        File d = beatlesDisc(1, 11);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search(
                found("1967-1970", "The Beatles", "CD", 14, 14),
                found("1967-1970", "The Beatles", "CD", 14, 14),
                // a vinyl edition with different sides does not count while CD editions exist
                found("1967-1970", "The Beatles", "12\\\" Vinyl", 8, 8, 7, 7)));
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertEquals(RipCheck.BY_SEARCH, r.basis);
        assertEquals(List.of("11 of 14 tracks, missing 12, 13, 14"), r.problems);
    }

    @Test
    public void editionsThatDisagreeAreNotChecked() throws Exception {
        File d = beatlesDisc(1, 11);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search(
                found("1967-1970", "The Beatles", "CD", 14, 14),
                found("1967-1970", "The Beatles", "CD", 11, 17)));
        assertEquals(RipCheck.EDITIONS_DIFFER, new RipCheck(http).check(album(d)).skipped);
    }

    @Test
    public void otherTitlesAndArtistsIgnored() throws Exception {
        File d = beatlesDisc(2, 3);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search(
                found("1967-1970", "The Beatles", "CD", 14, 14),
                found("1962-1966", "The Beatles", "CD", 13, 13),
                found("1967-1970", "Tribute Band", "CD", 20, 20)));
        assertEquals(List.of("3 of 14 tracks, missing 4, 5, 6, 7, 8, 9 and 5 more"), new RipCheck(http).check(album(d)).problems);
    }

    @Test
    public void nothingFoundIsNotChecked() throws Exception {
        File d = albumDir("Mix CD");
        tagged(d, 1, "TRACKNUMBER=1");
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search());
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertEquals(RipCheck.NO_INFO, r.skipped);
        assertTrue(http.asked.get(0).contains("release/?query"));
    }

    @Test
    public void singleDiscFolderAgainstSetNeedsDisc() throws Exception {
        File d = new File(tmp.newFolder("Artist"), "Album");
        assertTrue(d.mkdir());
        tagged(d, 1, "TRACKNUMBER=1");
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/?query", search(found("Album", "Artist", "CD", 10, 10)));
        assertEquals(RipCheck.WHICH_DISC, new RipCheck(http).check(album(d)).skipped);
    }

    @Test
    public void multiDiscWithoutDiscNumberIsSkipped() throws Exception {
        File d = albumDir("Plain");
        track(d, 1, 100_000, "mb-2", null);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/mb-2", release(medium(1, 1L), medium(2, 1L)));
        assertEquals(RipCheck.WHICH_DISC, new RipCheck(http).check(album(d)).skipped);
    }

    @Test
    public void discFromFolderName() {
        assertEquals(Integer.valueOf(2), RipCheck.discFromName("1967-1970 (Disc 2)"));
        assertEquals(Integer.valueOf(1), RipCheck.discFromName("Greatest Hits CD1"));
        assertNull(RipCheck.discFromName("Discovery"));
    }

    @Test
    public void unknownIdIsSkipped() throws Exception {
        File d = albumDir("Gone");
        track(d, 1, 100_000, "mb-gone", null);
        assertEquals(RipCheck.NOT_ON_MB, new RipCheck(new ArtLookupTest.FakeHttp()).check(album(d)).skipped);
    }

    @Test
    public void networkFailureIsSkipped() throws Exception {
        File d = albumDir("Net");
        track(d, 1, 100_000, "mb-net", null);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/mb-net", new java.io.IOException("down"));
        assertEquals(RipCheck.NETWORK, new RipCheck(http).check(album(d)).skipped);
    }

    @Test
    public void clockFormat() {
        assertEquals("4:12", RipCheck.clock(252_000));
        assertEquals("0:09", RipCheck.clock(9_400));
    }
}
