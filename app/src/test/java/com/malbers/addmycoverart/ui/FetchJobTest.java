package com.malbers.addmycoverart.ui;

import static org.junit.Assert.*;

import com.malbers.addmycoverart.core.Album;
import com.malbers.addmycoverart.core.ArtLookup;
import com.malbers.addmycoverart.core.FetchText;
import com.malbers.addmycoverart.core.Http;
import com.malbers.addmycoverart.core.LookupResult;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** P4, network down (handoff 06c): the first failed lookup stops online lookups for the rest of the run. */
public class FetchJobTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Album album(String artist, String title) throws Exception {
        File d = new File(tmp.getRoot(), artist + "/" + title);
        assertTrue(d.mkdirs());
        File t = new File(d, "01.flac");
        assertTrue(t.createNewFile());
        return new Album(d, 1, t, null, false);
    }

    @Test
    public void offlineAfterFirstNetworkFailure() throws Exception {
        int[] calls = {0};
        Http down = url -> {
            calls[0]++;
            throw new IOException("no route to host");
        };
        List<Album> albums = new ArrayList<>();
        albums.add(album("Agnes Obel", "Aventine"));
        albums.add(album("Cesaria Evora", "Miss Perfumado"));
        albums.add(album("Diana Krall", "The Girl in the Other Room"));
        List<String> reasons = new ArrayList<>();
        boolean[] done = {false};
        FetchJob job = new FetchJob(albums, new ArtLookup(down), new File(tmp.getRoot(), "run.log"), new FetchJob.Listener() {
            @Override public void onStart(Album a, String artist, String title, int index, int total) { }
            @Override public void onResult(Album a, LookupResult r, File saved, String error) { reasons.add(r.reason); }
            @Override public void onDone(boolean cancelled) { done[0] = true; }
        });
        job.run();
        assertTrue(done[0]);
        assertTrue(job.wentOffline());
        assertEquals(1, calls[0]); // only the first album went to the network
        assertEquals(LookupResult.NETWORK, reasons.get(0));
        assertEquals(FetchText.NOT_TRIED_OFFLINE, reasons.get(1));
        assertEquals(FetchText.NOT_TRIED_OFFLINE, reasons.get(2));
    }

    /** iso7 Q1: a database that stays busy gets BUSY_TRIES waits on the same album, then the run stops asking. */
    @Test
    public void busyWaitsThenStops() throws Exception {
        Http busy = url -> { throw new IOException("HTTP 503"); };
        List<Album> albums = new ArrayList<>();
        albums.add(album("Depeche Mode", "Violator"));
        albums.add(album("Eagles", "Hotel California"));
        List<String> reasons = new ArrayList<>();
        int[] waits = {0};
        FetchJob job = new FetchJob(albums, new ArtLookup(busy), new File(tmp.getRoot(), "run.log"), new FetchJob.Listener() {
            @Override public void onStart(Album a, String artist, String title, int index, int total) { }
            @Override public void onResult(Album a, LookupResult r, File saved, String error) { reasons.add(r.reason); }
            @Override public void onDone(boolean cancelled) { }
            @Override public void onWait(Album a, int seconds, int attempt, int of) { waits[0]++; }
        });
        job.busyWaitMs = 1;
        job.run();
        assertEquals(FetchJob.BUSY_TRIES, waits[0]);
        assertTrue(job.wasBusy());
        assertEquals(LookupResult.BUSY, reasons.get(0));
        assertEquals(FetchText.NOT_TRIED_OFFLINE, reasons.get(1));
    }

    /** iso7 Q1: after one wait the same album is asked again (here the retry meets a network failure). */
    @Test
    public void busyThenAsksSameAlbumAgain() throws Exception {
        boolean[] first = {true};
        Http flaky = url -> {
            if (first[0]) throw new IOException("HTTP 503");
            throw new IOException("no route to host");
        };
        List<Album> albums = new ArrayList<>();
        albums.add(album("Duke Ellington", "Supreme Jazz"));
        List<String> reasons = new ArrayList<>();
        int[] waits = {0};
        FetchJob job = new FetchJob(albums, new ArtLookup(flaky), new File(tmp.getRoot(), "run.log"), new FetchJob.Listener() {
            @Override public void onStart(Album a, String artist, String title, int index, int total) { }
            @Override public void onResult(Album a, LookupResult r, File saved, String error) { reasons.add(r.reason); }
            @Override public void onDone(boolean cancelled) { }
            @Override public void onWait(Album a, int seconds, int attempt, int of) { waits[0]++; first[0] = false; }
        });
        job.busyWaitMs = 1;
        job.run();
        assertEquals(1, waits[0]);
        assertEquals(1, reasons.size());
        assertEquals(LookupResult.NETWORK, reasons.get(0));
        assertFalse(job.wasBusy());
    }
}
