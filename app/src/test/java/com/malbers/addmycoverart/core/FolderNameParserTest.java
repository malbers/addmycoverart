package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Test;

public class FolderNameParserTest {
    @Test
    public void artistSlashAlbum() {
        assertArrayEquals(new String[]{"Beatles", "Help"}, FolderNameParser.guess(new File("/x/Beatles/Help")));
    }

    @Test
    public void artistDashAlbum() {
        assertArrayEquals(new String[]{"Van Morrison", "A Night in San Francisco"},
                FolderNameParser.guess(new File("/x/Music/Van Morrison - A Night in San Francisco")));
    }

    @Test
    public void discFolderUsesParents() {
        assertArrayEquals(new String[]{"The Beatles", "1967-1970"},
                FolderNameParser.guess(new File("/x/The Beatles/1967-1970/Disc 2")));
    }

    @Test
    public void storageRootGivesNoArtist() {
        assertArrayEquals(new String[]{null, "Help"}, FolderNameParser.guess(new File("/Help")));
    }
}
