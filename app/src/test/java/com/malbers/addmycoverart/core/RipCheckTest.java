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
        assertEquals(List.of("track 1 is 0:41, expected 4:12"), new RipCheck(http).check(album(d)).problems);
    }

    @Test
    public void smallDifferenceIsNotShort() throws Exception {
        JSONObject m = new JSONObject(medium(1, 300_000L));
        // 3% of 5:00 is 9 s; 8 s short is within it.
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
        assertEquals(List.of("track 1 is empty (0:00)"), RipCheck.compare(List.of(new TrackInfo(1, null, null, 300L, "x")), m));
    }

    @Test
    public void noIdIsSkippedWithoutNetwork() throws Exception {
        File d = albumDir("NoId");
        try (FileOutputStream o = new FileOutputStream(new File(d, "01.flac"))) { o.write(flac(1000, "TRACKNUMBER=1")); }
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp();
        RipCheck.Result r = new RipCheck(http).check(album(d));
        assertEquals(RipCheck.NO_ID, r.skipped);
        assertTrue(http.asked.isEmpty());
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
