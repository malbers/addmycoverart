package com.malbers.addmycoverart.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;

/**
 * P6, Find more art (handoff 07c-g): which release this album is, and what pictures the Cover Art Archive has for
 * it. The album's own MusicBrainz ID wins when the archive has pictures for it (that exact edition); otherwise,
 * of the confident search matches, the one with the most pictures. Read-only: it only lists, nothing is saved here.
 */
public final class ExtrasLookup {
    /** CLOSE (1.1.1): no confident match, but up to 3 likely albums with pictures, in {@link Result#close}. */
    public static final int FOUND = 0, NOT_IN_DATABASE = 1, NO_PICTURES = 2, NETWORK = 3, BUSY = 4, CLOSE = 5;

    /** One close match and its pictures. */
    public static final class Match {
        public final String releaseId;
        public final String label;
        public final List<CaaExtras.Extra> extras;

        public Match(String releaseId, String label, List<CaaExtras.Extra> extras) {
            this.releaseId = releaseId;
            this.label = label;
            this.extras = extras;
        }
    }

    public static final class Result {
        public final int state;
        public final String releaseId;
        public final List<CaaExtras.Extra> extras;
        /** 1.1.0.1: for NETWORK, one plain sentence naming the host and the cause; else null. */
        public final String detail;
        /** For CLOSE: the matches, best first; the first one is also in releaseId/extras. Empty otherwise. */
        public final List<Match> close;

        Result(int state, String releaseId, List<CaaExtras.Extra> extras) {
            this(state, releaseId, extras, null);
        }

        Result(int state, String releaseId, List<CaaExtras.Extra> extras, String detail) {
            this(state, releaseId, extras, detail, new ArrayList<>());
        }

        Result(int state, String releaseId, List<CaaExtras.Extra> extras, String detail, List<Match> close) {
            this.state = state;
            this.releaseId = releaseId;
            this.extras = extras;
            this.detail = detail;
            this.close = close;
        }
    }

    private final Http http;
    private final ArtLookup lookup;
    /** iso11: a fresh client per parallel archive listing (UrlHttp answers one request at a time per instance). */
    private final java.util.function.Supplier<Http> more;
    /** iso11 (Michael: "maybe do 3 at a time? I see usually no more than 9"): archive listings checked at once. */
    static final int PARALLEL = 3;

    public ExtrasLookup(Http http) {
        this(http, () -> http);
    }

    public ExtrasLookup(Http http, java.util.function.Supplier<Http> more) {
        this.http = http;
        this.lookup = new ArtLookup(http);
        this.more = more;
    }

    public Result find(Album a, Tags t, boolean albumHasCover) {
        try {
            List<String> ids = lookup.releaseCandidates(a, t);
            if (ids.isEmpty()) return closeMatches(a, t, albumHasCover);
            String bestId = null;
            List<CaaExtras.Extra> best = null;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < ids.size(); i++) {
                List<CaaExtras.Extra> x = listing(ids.get(i), albumHasCover);
                if (x == null || x.isEmpty()) continue;
                // the tagged ID is the exact edition: take it as soon as it has pictures
                if (i == 0 && t.mbAlbumId != null && t.mbAlbumId.equals(ids.get(0))) return new Result(FOUND, ids.get(0), x);
                // iso12 (Michael: "w many depeche. The first images are from vinyl"): the edition in the album's
                // own format wins, then the one with the most pictures (LPs have the most scans, so they won before)
                int rank = formatRank(lookup.formatOf(ids.get(i)), a);
                if (best == null || rank < bestRank || (rank == bestRank && x.size() > best.size())) {
                    best = x;
                    bestId = ids.get(i);
                    bestRank = rank;
                }
            }
            return best == null ? new Result(NO_PICTURES, ids.get(0), new ArrayList<>()) : new Result(FOUND, bestId, best);
        } catch (IOException e) {
            if (ArtLookup.isBusy(e)) return new Result(BUSY, null, new ArrayList<>());
            return new Result(NETWORK, null, new ArrayList<>(), NetFailure.describe(e, new java.util.Date()));
        }
    }

    /**
     * tags4 (D3): other editions with pictures, most pictures first, for "More editions". CLOSE with the list, or
     * NOT_IN_DATABASE when none has pictures.
     */
    public Result editions(Album a, Tags t, boolean albumHasCover) {
        try {
            List<ArtLookup.Close> eds = lookup.editions(a, t);
            List<Match> matches = listAll(eds, albumHasCover);
            if (matches.isEmpty()) return new Result(NOT_IN_DATABASE, null, new ArrayList<>());
            // iso12: the album's own format first, then most pictures (stable: sure matches stay ahead on a tie)
            matches.sort((p, q) -> {
                int byFormat = formatRank(lookup.formatOf(p.releaseId), a) - formatRank(lookup.formatOf(q.releaseId), a);
                return byFormat != 0 ? byFormat : q.extras.size() - p.extras.size();
            });
            Match m = matches.get(0);
            return new Result(CLOSE, m.releaseId, m.extras, null, matches);
        } catch (IOException e) {
            if (ArtLookup.isBusy(e)) return new Result(BUSY, null, new ArrayList<>());
            return new Result(NETWORK, null, new ArrayList<>(), NetFailure.describe(e, new java.util.Date()));
        }
    }

    /**
     * iso12: how well a release's format fits the album, lower is better. An SACD ISO or DSD file: SACD, then CD,
     * then digital. Anything else: CD, then digital, then SACD. Unknown in the middle; vinyl and cassette last
     * (still offered, just later: Michael may want LP scans).
     */
    static int formatRank(String format, Album a) {
        String f = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT);
        String n = a.firstAudio == null ? "" : a.firstAudio.getName().toLowerCase(java.util.Locale.ROOT);
        boolean dsd = n.endsWith(".iso") || n.endsWith(".dsf") || n.endsWith(".dff");
        if (f.isEmpty()) return 3;
        if (f.contains("vinyl") || f.contains("cassette") || f.contains("\"") || f.contains("lp")) return 9;
        if (f.contains("sacd")) return dsd ? 0 : 2;
        if (f.contains("cd")) return dsd ? 1 : 0;
        if (f.contains("digital")) return dsd ? 2 : 1;
        return 4;
    }

    /** No confident match: the close ones that have pictures, else NOT_IN_DATABASE. */
    private Result closeMatches(Album a, Tags t, boolean albumHasCover) throws IOException {
        List<Match> matches = new ArrayList<>();
        for (ArtLookup.Close c : lookup.closeCandidates(a, t)) {
            List<CaaExtras.Extra> x = listing(c.releaseId, albumHasCover);
            if (x != null && !x.isEmpty()) matches.add(new Match(c.releaseId, c.label, x));
        }
        if (matches.isEmpty()) return new Result(NOT_IN_DATABASE, null, new ArrayList<>());
        Match m = matches.get(0);
        return new Result(CLOSE, m.releaseId, m.extras, null, matches);
    }

    /**
     * iso11: each edition's archive listing, {@link #PARALLEL} at a time (the archive has no one-a-second rule; only
     * MusicBrainz does, and the edition search above stays on the paced client). Keeps the search's order; editions
     * without pictures drop out. The first busy or network failure is thrown, as the one-at-a-time loop did.
     */
    private List<Match> listAll(List<ArtLookup.Close> eds, boolean albumHasCover) throws IOException {
        List<Match> out = new ArrayList<>();
        if (eds.isEmpty()) return out;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(PARALLEL, eds.size()));
        try {
            List<java.util.concurrent.Future<List<CaaExtras.Extra>>> fs = new ArrayList<>();
            for (ArtLookup.Close c : eds) {
                Http h = more.get();
                fs.add(pool.submit(() -> listing(h, c.releaseId, albumHasCover)));
            }
            for (int i = 0; i < eds.size(); i++) {
                List<CaaExtras.Extra> x;
                try {
                    x = fs.get(i).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                    throw new IOException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                if (x != null && !x.isEmpty()) out.add(new Match(eds.get(i).releaseId, eds.get(i).label, x));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The archive's pictures for one release; null when it has none (404) or the answer can't be read. */
    private List<CaaExtras.Extra> listing(String id, boolean albumHasCover) throws IOException {
        return listing(http, id, albumHasCover);
    }

    private static List<CaaExtras.Extra> listing(Http http, String id, boolean albumHasCover) throws IOException {
        Http.Response res = http.get(ArtLookup.CAA + "release/" + id + "/");
        if (res.code >= 500) throw new IOException("HTTP " + res.code);
        if (res.code != 200) return null;
        try {
            return CaaExtras.parse(new String(res.body, StandardCharsets.UTF_8), albumHasCover);
        } catch (JSONException e) {
            return null;
        }
    }
}
