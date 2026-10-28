package com.malbers.addmycoverart.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** HttpURLConnection client. Follows redirects across hosts and spaces MusicBrainz calls 1.1 s apart, as its rules ask. */
public final class UrlHttp implements Http {
    // MusicBrainz allows 1 request/s; stay well under it.
    private static final long MB_GAP_MS = 1500;
    private static final int MAX_BODY = 15 * 1024 * 1024;

    private static final int RETRIES = 3;

    private final String userAgent;
    private final long backoffMs;
    private long lastMb;

    public UrlHttp(String userAgent) {
        this(userAgent, 2000);
    }

    UrlHttp(String userAgent, long backoffMs) {
        this.userAgent = userAgent;
        this.backoffMs = backoffMs;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Retries "busy" answers (503/429), which MusicBrainz sends even to well-paced clients
     * and one transport failure, with growing pauses.
     */
    @Override
    public synchronized Response get(String url) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            if (attempt > 0) sleep(backoffMs << (attempt - 1));
            try {
                Response r = once(url);
                if ((r.code == 503 || r.code == 429) && attempt < RETRIES) continue;
                return r;
            } catch (IOException e) {
                lastError = e;
                if (attempt >= 1) throw e;
            }
        }
        throw lastError != null ? lastError : new IOException("gave up");
    }

    private Response once(String url) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            URL u = new URL(url);
            if (u.getHost().endsWith("musicbrainz.org")) pace();
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/json, image/*");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(u, c.getHeaderField("Location")).toString();
                    continue;
                }
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                return new Response(code, in == null ? new byte[0] : readAll(in));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private void pace() {
        long wait = lastMb + MB_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastMb = System.currentTimeMillis();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("response too large");
            }
            return out.toByteArray();
        }
    }
}
