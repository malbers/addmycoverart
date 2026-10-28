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

    /** Finishes the album in progress, then stops. */
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void run() {
        for (int i = 0; i < albums.size() && !cancelled; i++) {
            // A breather between albums, so a big library is a gentle trickle, not a burst.
            if (i > 0) {
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
            LookupResult r = lookup.find(a, t);
            File saved = null;
            String error = null;
            if (r.image != null) {
                try {
                    saved = ArtWriter.save(a.dir, r.image, r.source, runLog);
                    if (saved == null) error = "art already there, left alone";
                } catch (IOException e) {
                    error = "couldn't save: " + e.getMessage();
                }
            }
            listener.onResult(a, r, saved, error);
        }
        listener.onDone(cancelled);
    }
}
