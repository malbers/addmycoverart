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
}
