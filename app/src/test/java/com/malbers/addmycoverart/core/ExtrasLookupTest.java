package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

/** P6: which release's pictures are offered, and the none / no-pictures / network outcomes. */
public class ExtrasLookupTest {
    private static Album album(String path, int tracks) {
        return new Album(new File(path), tracks, new File(path, "01.flac"), null, false);
    }

    private static String rel(String id, int tracks, String date) {
        return "{\"id\":\"" + id + "\",\"score\":100,\"title\":\"Aventine\",\"track-count\":" + tracks + ",\"status\":\"Official\","
                + "\"date\":\"" + date + "\",\"artist-credit\":[{\"name\":\"Agnes Obel\",\"joinphrase\":\"\"}],\"release-group\":{\"id\":\"rg\"}}";
    }

    private static String img(String type, int id) {
        return "{\"types\":[\"" + type + "\"],\"front\":" + type.equals("Front") + ",\"image\":\"https://x/" + id + ".jpg\"}";
    }

    private static final Tags TAGS = new Tags("Aventine", "Agnes Obel", null, null, false);

    @Test
    public void picksTheMatchWithMostPictures() {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("musicbrainz.org/ws/2/release/?query", "{\"releases\":[" + rel("a", 11, "2013-09-30") + "," + rel("b", 11, "2014-01-01") + "]}")
                .on("coverartarchive.org/release/a/", "{\"images\":[" + img("Front", 1) + "]}")
                .on("coverartarchive.org/release/b/", "{\"images\":[" + img("Front", 2) + "," + img("Back", 3) + "," + img("Booklet", 4) + "]}");
        ExtrasLookup.Result r = new ExtrasLookup(http).find(album("/m/Agnes Obel/Aventine", 11), TAGS, true);
        assertEquals(ExtrasLookup.FOUND, r.state);
        assertEquals("b", r.releaseId);
        assertEquals(3, r.extras.size());
    }

    @Test
    public void taggedIdWinsWhenItHasPictures() {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("coverartarchive.org/release/mb-1/", "{\"images\":[" + img("Back", 1) + "]}")
                .on("musicbrainz.org/ws/2/release/?query", "{\"releases\":[" + rel("b", 11, "2014") + "]}")
                .on("coverartarchive.org/release/b/", "{\"images\":[" + img("Front", 2) + "," + img("Back", 3) + "]}");
        ExtrasLookup.Result r = new ExtrasLookup(http).find(album("/m/Agnes Obel/Aventine", 11),
                new Tags("Aventine", "Agnes Obel", null, "mb-1", false), true);
        assertEquals("mb-1", r.releaseId);
        assertEquals(1, r.extras.size());
    }

    @Test
    public void notInDatabaseAndNoPictures() {
        ArtLookupTest.FakeHttp none = new ArtLookupTest.FakeHttp().on("musicbrainz.org/ws/2/release/?query", "{\"releases\":[]}");
        assertEquals(ExtrasLookup.NOT_IN_DATABASE, new ExtrasLookup(none).find(album("/m/Agnes Obel/Aventine", 11), TAGS, true).state);
        ArtLookupTest.FakeHttp bare = new ArtLookupTest.FakeHttp()
                .on("musicbrainz.org/ws/2/release/?query", "{\"releases\":[" + rel("a", 11, "2013") + "]}");
        assertEquals(ExtrasLookup.NO_PICTURES, new ExtrasLookup(bare).find(album("/m/Agnes Obel/Aventine", 11), TAGS, true).state);
    }

    @Test
    public void networkDown() {
        ArtLookupTest.FakeHttp down = new ArtLookupTest.FakeHttp().on("musicbrainz.org", new IOException("offline"));
        assertEquals(ExtrasLookup.NETWORK, new ExtrasLookup(down).find(album("/m/Agnes Obel/Aventine", 11), TAGS, true).state);
    }

    @Test
    public void networkDetailNamesHostAndCause() {
        ArtLookupTest.FakeHttp slow = new ArtLookupTest.FakeHttp()
                .on("musicbrainz.org", new NetFailure("musicbrainz.org", new java.net.SocketTimeoutException("Read timed out")));
        ExtrasLookup.Result r = new ExtrasLookup(slow).find(album("/m/Agnes Obel/Aventine", 11), TAGS, true);
        assertEquals(ExtrasLookup.NETWORK, r.state);
        assertEquals("musicbrainz.org didn't answer in time. Try again in a moment.", r.detail);
    }

    @Test
    public void tiesPickTheEarliestOfficialEditionEveryTime() {
        // same track count and score: the earliest date wins whatever order MusicBrainz answers in
        String early = rel("early", 11, "2013-09-30"), late = rel("late", 11, "2019-05-01");
        for (String order : new String[]{early + "," + late, late + "," + early}) {
            ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                    .on("musicbrainz.org/ws/2/release/?query", "{\"releases\":[" + order + "]}");
            try {
                assertEquals("early", new ArtLookup(http).releaseCandidates(album("/m/Agnes Obel/Aventine", 11), TAGS).get(0));
            } catch (IOException e) {
                fail(e.toString());
            }
        }
    }

    /** iso11: edition listings run PARALLEL at a time (never more), most pictures first, ties in search order. */
    @Test
    public void editionsListedThreeAtATime() throws Exception {
        ArtLookupTest.FakeHttp base = new ArtLookupTest.FakeHttp()
                .on("musicbrainz.org/ws/2/release/?query", "{\"releases\":[" + rel("a", 11, "2013-01-01") + "," + rel("b", 11, "2013-02-01")
                        + "," + rel("c", 11, "2013-03-01") + "," + rel("d", 11, "2013-04-01") + "," + rel("e", 11, "2013-05-01") + "]}")
                .on("coverartarchive.org/release/a/", "{\"images\":[" + img("Back", 1) + "]}")
                .on("coverartarchive.org/release/b/", "{\"images\":[" + img("Back", 2) + "," + img("Booklet", 3) + "," + img("Medium", 4) + "]}")
                .on("coverartarchive.org/release/d/", "{\"images\":[" + img("Back", 5) + "," + img("Booklet", 6) + "]}")
                .on("coverartarchive.org/release/e/", "{\"images\":[" + img("Back", 7) + "]}");
        Http locked = url -> { synchronized (base) { return base.get(url); } };
        java.util.concurrent.atomic.AtomicInteger now = new java.util.concurrent.atomic.AtomicInteger(), max = new java.util.concurrent.atomic.AtomicInteger();
        Http slow = url -> {
            int n = now.incrementAndGet();
            max.accumulateAndGet(n, Math::max);
            try {
                Thread.sleep(80);
                return locked.get(url);
            } catch (InterruptedException e) {
                throw new IOException(e);
            } finally {
                now.decrementAndGet();
            }
        };
        ExtrasLookup.Result r = new ExtrasLookup(locked, () -> slow).editions(album("/m/Agnes Obel/Aventine", 11), TAGS, false);
        assertEquals(ExtrasLookup.CLOSE, r.state);
        StringBuilder order = new StringBuilder();
        for (ExtrasLookup.Match m : r.close) order.append(m.releaseId);
        assertEquals("bdae", order.toString());
        assertTrue("at most " + ExtrasLookup.PARALLEL + " at once, was " + max.get(), max.get() <= ExtrasLookup.PARALLEL);
        assertTrue("ran in parallel, max " + max.get(), max.get() >= 2);
    }

    private static String relFmt(String id, String format) {
        return "{\"id\":\"" + id + "\",\"score\":100,\"title\":\"Aventine\",\"track-count\":11,\"status\":\"Official\","
                + "\"date\":\"2013-09-30\",\"media\":[{\"format\":\"" + format.replace("\"", "\\\"") + "\"}],"
                + "\"artist-credit\":[{\"name\":\"Agnes Obel\",\"joinphrase\":\"\"}],\"release-group\":{\"id\":\"rg\"}}";
    }

    /** iso12: format fit first (Michael's Depeche Mode SACDs opened on LP scans), vinyl last, never hidden. */
    @Test
    public void formatRankFitsTheAlbum() {
        Album iso = new Album(new File("/m/DM"), 1, new File("/m/DM", "Violator.iso"), null, false);
        Album flac = album("/m/Agnes Obel/Aventine", 11);
        assertTrue(ExtrasLookup.formatRank("Hybrid SACD", iso) < ExtrasLookup.formatRank("CD", iso));
        assertTrue(ExtrasLookup.formatRank("CD", iso) < ExtrasLookup.formatRank("12\" Vinyl", iso));
        assertTrue(ExtrasLookup.formatRank("CD", flac) < ExtrasLookup.formatRank("SACD", flac));
        assertTrue(ExtrasLookup.formatRank("Digital Media", flac) < ExtrasLookup.formatRank("Cassette", flac));
        assertTrue(ExtrasLookup.formatRank("", flac) < ExtrasLookup.formatRank("Vinyl", flac));
    }

    /** iso12: a CD with one picture beats an LP with three for a FLAC album; the LP is still among the editions. */
    @Test
    public void sameFormatBeatsMorePictures() throws Exception {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("musicbrainz.org/ws/2/release/?query", "{\"releases\":[" + relFmt("lp", "12\" Vinyl") + "," + relFmt("cd", "CD") + "]}")
                .on("coverartarchive.org/release/lp/", "{\"images\":[" + img("Front", 1) + "," + img("Back", 2) + "," + img("Booklet", 3) + "]}")
                .on("coverartarchive.org/release/cd/", "{\"images\":[" + img("Back", 4) + "]}");
        ExtrasLookup.Result r = new ExtrasLookup(http).find(album("/m/Agnes Obel/Aventine", 11), TAGS, true);
        assertEquals(ExtrasLookup.FOUND, r.state);
        assertEquals("cd", r.releaseId);
        ExtrasLookup.Result e = new ExtrasLookup(http).editions(album("/m/Agnes Obel/Aventine", 11), TAGS, true);
        assertEquals("cd", e.close.get(0).releaseId);
        assertEquals("lp", e.close.get(1).releaseId);
    }
}
