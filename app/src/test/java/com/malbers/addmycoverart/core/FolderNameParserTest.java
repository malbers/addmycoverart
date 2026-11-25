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

    // ---- 1.1.1: Václav H.'s library names ----

    private static String[] g(String path) {
        return FolderNameParser.guess(new File(path));
    }

    @Test
    public void yearAndFormatAreDropped() {
        assertArrayEquals(new String[]{"Al Di Meola", "Consequence Of Chaos"}, g("/s/A/Al Di Meola - 2006 - Consequence Of Chaos - DSF - 64"));
        assertArrayEquals(new String[]{"After Forever", "After Forever"}, g("/s/A/After Forever - 2007 - After Forever - APE"));
        assertArrayEquals(new String[]{"Adele", "Adele"}, g("/s/A/Adele - 2015 - Adele - DSF 512"));
        assertArrayEquals(new String[]{"Alan Parsons Project", "Eye In The Sky (Jap Ed 2013)"},
                g("/s/A/Alan Parsons Project - 1982 - Eye In The Sky (Jap Ed 2013) - FLAC"));
    }

    @Test
    public void editionNoteAtTheEndIsDropped() {
        assertArrayEquals(new String[]{"Action", "Action"}, g("/s/A/Action - 2008 - Action - Jap Ed - Mp3"));
        assertArrayEquals(new String[]{"Alzira", "Live"}, g("/s/A/Alzira - Live - Deluxe Edition - FLAC"));
    }

    @Test
    public void equalsSignSeparator() {
        assertArrayEquals(new String[]{"Abba", "The Album (Deluxe Ed 2007)"}, g("/f/AA/Abba = 1977 - The Album (Deluxe Ed 2007) - FLAC"));
        assertArrayEquals(new String[]{"A Hadelich", "Sibelius Ades Violin Concertos"}, g("/f/AA/A Hadelich = 2014 - Sibelius Ades Violin Concertos - FLAC 24-96"));
    }

    @Test
    public void albumThatIsANumberIsKept() {
        // "25" is Adele's album, not a format number: no format word before it
        assertArrayEquals(new String[]{"Adele", "25"}, g("/f/AA/Adele = 2015 - 25 - FLAC 24-96"));
        // "1999" is the album when nothing else is left
        assertArrayEquals(new String[]{"Prince", "1999"}, g("/x/Prince - 1999 - FLAC"));
    }

    @Test
    public void formatLookalikeTitleIsKept() {
        assertArrayEquals(new String[]{"Pixies", "Wave of Mutilation"}, g("/x/Pixies - Wave of Mutilation"));
    }

    @Test
    public void dashInsideAWordIsNotASeparator() {
        assertArrayEquals(new String[]{"Alan Parsons Project", "Limelight-The Best Of Vol 2"},
                g("/x/Alan Parsons Project - 1988 - Limelight-The Best Of Vol 2 - Wv"));
    }

    @Test
    public void oneLetterShelfIsNotAnArtist() {
        assertArrayEquals(new String[]{null, "Greatest Hits"}, g("/s/A/Greatest Hits"));
    }

    @Test
    public void compilationsFolderIsNotAnArtist() {
        assertArrayEquals(new String[]{null, "City Of Angels"}, g("/m/Music/Compilations/City Of Angels"));
        assertArrayEquals(new String[]{null, "Tone-Tests-Speaker"}, g("/m/Music/Tone-Tests-Speaker"));
    }

    @Test
    public void unknownAlbumHasNoTitle() {
        assertArrayEquals(new String[]{"Beck", null}, g("/m/Beck/Unknown Album"));
        assertArrayEquals(new String[]{"Jack Johnson", null}, g("/m/Jack Johnson/no title"));
        assertTrue(FolderNameParser.isPlaceholder("Unknown Album"));
        assertFalse(FolderNameParser.isPlaceholder("Parachutes"));
    }
}
