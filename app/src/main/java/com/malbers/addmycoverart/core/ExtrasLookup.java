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

        Match(String releaseId, String label, List<CaaExtras.Extra> extras) {
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

    public ExtrasLookup(Http http) {
        this.http = http;
        this.lookup = new ArtLookup(http);
    }

    public Result find(Album a, Tags t, boolean albumHasCover) {
        try {
            List<String> ids = lookup.releaseCandidates(a, t);
            if (ids.isEmpty()) return closeMatches(a, t, albumHasCover);
            String bestId = null;
            List<CaaExtras.Extra> best = null;
            for (int i = 0; i < ids.size(); i++) {
                List<CaaExtras.Extra> x = listing(ids.get(i), albumHasCover);
                if (x == null || x.isEmpty()) continue;
                // the tagged ID is the exact edition: take it as soon as it has pictures
                if (i == 0 && t.mbAlbumId != null && t.mbAlbumId.equals(ids.get(0))) return new Result(FOUND, ids.get(0), x);
                if (best == null || x.size() > best.size()) {
                    best = x;
                    bestId = ids.get(i);
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
            List<Match> matches = new ArrayList<>();
            for (ArtLookup.Close c : lookup.editions(a, t)) {
                List<CaaExtras.Extra> x = listing(c.releaseId, albumHasCover);
                if (x != null && !x.isEmpty()) matches.add(new Match(c.releaseId, c.label, x));
            }
            if (matches.isEmpty()) return new Result(NOT_IN_DATABASE, null, new ArrayList<>());
            matches.sort((p, q) -> q.extras.size() - p.extras.size()); // stable: sure matches stay ahead on a tie
            Match m = matches.get(0);
            return new Result(CLOSE, m.releaseId, m.extras, null, matches);
        } catch (IOException e) {
            if (ArtLookup.isBusy(e)) return new Result(BUSY, null, new ArrayList<>());
            return new Result(NETWORK, null, new ArrayList<>(), NetFailure.describe(e, new java.util.Date()));
        }
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

    /** The archive's pictures for one release; null when it has none (404) or the answer can't be read. */
    private List<CaaExtras.Extra> listing(String id, boolean albumHasCover) throws IOException {
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
