package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Test;

public class UpdateCheckTest {
    private static final String RELEASE = "{\"tag_name\":\"v1.0.3\",\"html_url\":\"https://github.com/x/releases/tag/v1.0.3\","
            + "\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\"https://x/notes.txt\"},"
            + "{\"name\":\"AddMyCoverArt-1.0.3.apk\",\"browser_download_url\":\"https://x/AddMyCoverArt-1.0.3.apk\"}]}";

    @Test
    public void readsVersionAndApkUrl() {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("releases/latest", RELEASE);
        UpdateCheck.Release r = UpdateCheck.latest(http);
        assertEquals("1.0.3", r.version);
        assertEquals("https://x/AddMyCoverArt-1.0.3.apk", r.url);
    }

    @Test
    public void fallsBackToReleasePageWithoutApk() {
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("releases/latest", "{\"tag_name\":\"v2.0\",\"html_url\":\"https://github.com/x/releases/tag/v2.0\",\"assets\":[]}");
        assertEquals("https://github.com/x/releases/tag/v2.0", UpdateCheck.latest(http).url);
    }

    @Test
    public void failuresAreSilent() {
        assertNull(UpdateCheck.latest(new ArtLookupTest.FakeHttp()));
        assertNull(UpdateCheck.latest(new ArtLookupTest.FakeHttp().on("releases/latest", new IOException("offline"))));
        assertNull(UpdateCheck.latest(new ArtLookupTest.FakeHttp().on("releases/latest", "not json")));
    }

    @Test
    public void versionCompare() {
        assertTrue(UpdateCheck.isNewer("1.0.3", "1.0.2"));
        assertTrue(UpdateCheck.isNewer("1.0.2", "1.0.1-updatetest"));
        assertTrue(UpdateCheck.isNewer("1.1", "1.0.9"));
        assertTrue(UpdateCheck.isNewer("1.0.10", "1.0.9"));
        assertFalse(UpdateCheck.isNewer("1.0.2", "1.0.2"));
        assertFalse(UpdateCheck.isNewer("1.0.1", "1.0.2"));
        assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"));
    }
}
