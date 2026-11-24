package com.malbers.addmycoverart.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;

import java.io.File;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 1.1.0 (handoff "Reading covers"): decodes cover pictures down to the size they are shown at, caches them in an
 * LruCache of 1/8 of the heap, on two background threads. Newest requests first (the tiles on screen), and a
 * request is cancelled when its view is reused for another album. Read-only: nothing here writes.
 */
public final class CoverLoader {
    public interface Callback {
        /** On the main thread; null when the picture could not be read. */
        void onCover(Bitmap b);
    }

    private static CoverLoader instance;

    public static CoverLoader get() {
        if (instance == null) instance = new CoverLoader();
        return instance;
    }

    private final LruCache<String, Bitmap> cache;
    private final ThreadPoolExecutor pool;
    private final Handler main = new Handler(Looper.getMainLooper());

    private CoverLoader() {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(kb) {
            @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
        };
        // LIFO: the tiles the user just scrolled to load before the ones scrolled past
        LinkedBlockingDeque<Runnable> q = new LinkedBlockingDeque<Runnable>() {
            @Override public boolean offer(Runnable r) { return offerFirst(r); }
        };
        pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, q, r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "covers");
            t.setDaemon(true);
            return t;
        });
    }

    private static String key(File f, int px, boolean embedded) {
        // P6: size + modified time too, so a file replaced while the app is open is read again (Michael's Cesaria
        // refetch kept showing the old cover on its tile)
        return (embedded ? "e:" : "f:") + f.getPath() + "@" + px + "#" + f.length() + ":" + f.lastModified();
    }

    /** The cached picture, or null (then call {@link #load}). */
    public Bitmap cached(File art, int px) {
        return art == null ? null : cache.get(key(art, px, false));
    }

    /** Loads an image file at about {@code px} on its short side. Returns a handle to cancel, or null if served from cache. */
    public Future<?> load(File art, int px, Callback cb) {
        return load(art, px, false, cb);
    }

    /** As {@link #load}, but the picture embedded in an audio file (MediaMetadataRetriever). */
    public Future<?> loadEmbedded(File audio, int px, Callback cb) {
        return load(audio, px, true, cb);
    }

    private Future<?> load(File f, int px, boolean embedded, Callback cb) {
        String k = key(f, px, embedded);
        Bitmap hit = cache.get(k);
        if (hit != null) {
            cb.onCover(hit);
            return null;
        }
        return pool.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            Bitmap b = embedded ? decodeEmbedded(f, px) : decodeFile(f, px);
            if (b != null) cache.put(k, b);
            main.post(() -> cb.onCover(b));
        });
    }

    private static int sample(int w, int h, int px) {
        int s = 1;
        while (Math.min(w, h) / (s * 2) >= px) s *= 2;
        return s;
    }

    private static Bitmap decodeFile(File f, int px) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample(o.outWidth, o.outHeight, px);
            return BitmapFactory.decodeFile(f.getPath(), d);
        } catch (Throwable t) {
            return null; // a broken or huge file shows as no picture, never a crash
        }
    }

    private static Bitmap decodeEmbedded(File audio, int px) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(audio.getPath());
            byte[] pic = r.getEmbeddedPicture();
            if (pic == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample(o.outWidth, o.outHeight, px);
            return BitmapFactory.decodeByteArray(pic, 0, pic.length, d);
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }
}
