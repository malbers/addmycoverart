package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Real network, real services. Skipped unless AMCA_LIVE=1; AMCA_SAMPLE_FLAC may point at a real rip.
 * Run: AMCA_LIVE=1 gradlew testDebugUnitTest --tests '*LiveSmokeTest*'
 */
public class LiveSmokeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final Http http = new UrlHttp("AddMyCoverArt/test ( https://youtube.com/@malbers )");

    @Test
    public void taglessHelpFolderGetsTheAlbumCover() throws Exception {
        assumeTrue("1".equals(System.getenv("AMCA_LIVE")));
        File dir = new File(tmp.getRoot(), "Beatles/Help");
        assertTrue(dir.mkdirs());
        for (int i = 1; i <= 14; i++) assertTrue(new File(dir, String.format("%02d.flac", i)).createNewFile());
        Album a = AlbumScanner.scan(tmp.getRoot()).missing.get(0);
        LookupResult r = new ArtLookup(http).find(a, TagReader.read(a.firstAudio));
        System.out.println("Help: " + (r.image == null ? r.reason : r.image.length + " bytes from " + r.source));
        assertNotNull(r.image);
        File saved = ArtWriter.save(dir, r.image, r.source, new File(tmp.getRoot(), "run.log"));
        assertNotNull(saved);
        assertEquals(1, AlbumScanner.scan(tmp.getRoot()).withArt.size());
    }

    @Test
    public void realRipByMusicBrainzId() throws Exception {
        assumeTrue("1".equals(System.getenv("AMCA_LIVE")));
        String sample = System.getenv("AMCA_SAMPLE_FLAC");
        assumeTrue(sample != null && new File(sample).isFile());
        File dir = new File(tmp.getRoot(), "Van Morrison/A Night in San Francisco");
        assertTrue(dir.mkdirs());
        Files.copy(new File(sample).toPath(), new File(dir, "14.flac").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Tags t = TagReader.read(new File(dir, "14.flac"));
        System.out.println("Tags: " + t.album + " / " + t.bestArtist() + " / " + t.mbAlbumId + " / embedded=" + t.hasPicture);
        assertNotNull(t.mbAlbumId);
        assertTrue(t.hasPicture);
        byte[] pic = TagReader.readPicture(new File(dir, "14.flac"));
        System.out.println("Embedded picture: " + (pic == null ? "none" : pic.length + " bytes, jpeg=" + ArtLookup.isJpeg(pic)));
        assertTrue(ArtLookup.isJpeg(pic));
        Album a = new Album(dir, 14, new File(dir, "14.flac"), null, false);
        LookupResult r = new ArtLookup(http).find(a, t);
        System.out.println("VM: " + (r.image == null ? r.reason : r.image.length + " bytes from " + r.source));
        assertNotNull(r.image);
        assertTrue(r.source.contains(t.mbAlbumId));
    }
}
