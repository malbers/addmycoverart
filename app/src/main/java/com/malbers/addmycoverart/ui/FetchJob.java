package com.malbers.addmycoverart.ui;

import com.malbers.addmycoverart.core.Album;
import com.malbers.addmycoverart.core.ArtLookup;
import com.malbers.addmycoverart.core.ArtWriter;
import com.malbers.addmycoverart.core.LookupResult;
import com.malbers.addmycoverart.core.TagReader;
import com.malbers.addmycoverart.core.Tags;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Works through the missing albums one at a time on its own thread. Listener calls arrive on that thread. */
public final class FetchJob implements Runnable {
    public interface Listener {
        void onStart(Album album, String artist, String title, int index, int total);

        /** saved is the new folder.jpg, or null when nothing was written; error is set when saving failed. */
        void onResult(Album album, LookupResult result, File saved, String error);

        void onDone(boolean cancelled);
    }

    private static final long PAUSE_MS = 1000;

    private final List<Album> albums;
    private final ArtLookup lookup;
    private final File runLog;
    private final Listener listener;
    private volatile boolean cancelled;

    public FetchJob(List<Album> albums, ArtLookup lookup, File runLog, Listener listener) {
        this.albums = albums;
        this.lookup = lookup;
        this.runLog = runLog;
        this.listener = listener;
    }

    private volatile boolean offline, busy;
    private volatile String netDetail;

    /** 1.1.0.1: why the run stopped asking the database (host + cause), or null. */
    public String netDetail() {
        return netDetail;
    }

    /** True when the stop was the database being busy, not the network. */
    public boolean wasBusy() {
        return busy;
    }

    /** True once a lookup failed to reach the cover database in this run. */
    public boolean wentOffline() {
        return offline;
    }

    /** Finishes the album in progress, then stops. */
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void run() {
        for (int i = 0; i < albums.size() && !cancelled; i++) {
            // A breather between albums, so a big library is a gentle trickle, not a burst.
            if (i > 0 && !offline) {
                try {
                    Thread.sleep(PAUSE_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            Album a = albums.get(i);
            Tags t = TagReader.read(a.firstAudio);
            String artist = t.bestArtist();
            String title = t.album != null ? t.album : a.dir.getName();
            listener.onStart(a, artist, title, i, albums.size());
            // P4 (06c): once the cover database can't be reached, stop asking it for the rest of the run;
            // covers inside the files are still saved, the rest come back as "not tried"
            LookupResult r = offline ? ArtLookup.findOffline(a, t) : lookup.find(a, t);
            if (LookupResult.NETWORK.equals(r.reason)) {
                if (!offline) netDetail = r.detail; // the first failure is the cause; later albums aren't tried
                offline = true;
            }
            if (LookupResult.BUSY.equals(r.reason)) { offline = true; busy = true; } // be gentle: stop asking for this run
            File saved = null;
            String error = null;
            if (r.image != null) {
                try {
                    saved = ArtWriter.save(a.dir, r.image, r.source, runLog);
                    if (saved == null) {
                        // 1.1.0: a folder.jpg that isn't a picture blocks the name, and it is never overwritten
                        File f = new File(a.dir, ArtWriter.NAME);
                        String why = f.exists() ? com.malbers.addmycoverart.core.AlbumScanner.artProblem(f) : null;
                        error = why != null ? "folder.jpg " + why + ", left alone" : "art already there, left alone";
                    }
                } catch (IOException e) {
                    error = "couldn't save: " + e.getMessage();
                }
            }
            listener.onResult(a, r, saved, error);
        }
        listener.onDone(cancelled);
    }
}
