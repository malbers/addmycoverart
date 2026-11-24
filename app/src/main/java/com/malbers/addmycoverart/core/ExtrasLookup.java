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
    public static final int FOUND = 0, NOT_IN_DATABASE = 1, NO_PICTURES = 2, NETWORK = 3, BUSY = 4;

    public static final class Result {
        public final int state;
        public final String releaseId;
        public final List<CaaExtras.Extra> extras;

        Result(int state, String releaseId, List<CaaExtras.Extra> extras) {
            this.state = state;
            this.releaseId = releaseId;
            this.extras = extras;
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
            if (ids.isEmpty()) return new Result(NOT_IN_DATABASE, null, new ArrayList<>());
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
            return new Result(ArtLookup.isBusy(e) ? BUSY : NETWORK, null, new ArrayList<>());
        }
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
