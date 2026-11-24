package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class FetchTextTest {
    @Test
    public void titles() {
        assertEquals("5 albums have new covers", FetchText.reportTitle(5, 2, false));
        assertEquals("1 album has a new cover", FetchText.reportTitle(1, 3, false));
        assertEquals("All 7 albums have covers now", FetchText.reportTitle(7, 0, false));
        assertEquals("The album has a cover now", FetchText.reportTitle(1, 0, false));
        assertEquals("No new covers this time", FetchText.reportTitle(0, 4, false));
        assertEquals("Couldn’t reach the cover database", FetchText.reportTitle(2, 5, true));
    }

    @Test
    public void reasons() {
        assertTrue(FetchText.reason(LookupResult.NO_MATCH).startsWith("Nothing on MusicBrainz"));
        assertEquals("Not tried. The cover database couldn’t be reached.", FetchText.reason(FetchText.NOT_TRIED_OFFLINE));
        assertTrue(FetchText.retryable(LookupResult.BUSY));
        assertTrue(ArtLookup.isBusy(new java.io.IOException("HTTP 503")));
        assertFalse(ArtLookup.isBusy(new java.io.IOException("Unable to resolve host")));
        assertEquals("Folder.jpg is empty, left alone.", FetchText.reason("folder.jpg is empty, left alone"));
        assertTrue(FetchText.retryable(LookupResult.NETWORK));
        assertTrue(FetchText.retryable(FetchText.NOT_TRIED_OFFLINE));
        assertFalse(FetchText.retryable(LookupResult.NO_MATCH));
    }
}
