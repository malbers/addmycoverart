package com.malbers.addmycoverart.core;

/** P4 (handoff 05/06): the words the fetch screens use for each outcome. Plain text, no red: a miss is not an error. */
public final class FetchText {
    /** Reason for an album that was skipped because the streamer went offline earlier in the run. */
    public static final String NOT_TRIED_OFFLINE = "not tried, database unreachable";

    private FetchText() {}

    /** The big status line under the current cover once it is done. */
    public static String status(LookupResult r, boolean saved) {
        if (saved) return r.fromTracks() ? "Found inside the files · saved folder.jpg" : "Found on MusicBrainz · saved folder.jpg";
        return "Not found · left as it was";
    }

    /** The report's one-line reason for an album that got no cover. */
    public static String reason(String why) {
        if (why == null) return "Not found.";
        switch (why) {
            case LookupResult.NO_TAGS:
                return "No artist or album name to search with. The folder name may not be the album title.";
            case LookupResult.NO_MATCH:
                return "Nothing on MusicBrainz by that name. The folder name may not be the album title.";
            case LookupResult.CLOSE:
                return "No sure match, but a close one to check: open the album and tap Find more art.";
            case LookupResult.NO_ART:
                return "Found on MusicBrainz, but nobody has added a cover picture for it yet.";
            case LookupResult.NETWORK:
                return "Couldn’t reach the cover database.";
            case LookupResult.BUSY:
                return "The cover database was busy. Try again in a minute.";
            case NOT_TRIED_OFFLINE:
                return "Not tried. The cover database couldn’t be reached.";
            default:
                return Character.toUpperCase(why.charAt(0)) + why.substring(1) + ".";
        }
    }

    /** True for an outcome that a "Try again" should re-run (no answer, as opposed to a real "not found"). */
    public static boolean retryable(String why) {
        return LookupResult.NETWORK.equals(why) || LookupResult.BUSY.equals(why) || NOT_TRIED_OFFLINE.equals(why);
    }

    public static String plural(int n, String w) {
        return n + " " + w + (n == 1 ? "" : "s");
    }

    /** "5 albums have new covers" / "All 7 albums have covers now" / "1 album has a new cover" (06a/06b). */
    public static String reportTitle(int ok, int bad, boolean network) {
        if (network) return "Couldn’t reach the cover database";
        if (ok == 0) return "No new covers this time";
        if (bad == 0) return ok == 1 ? "The album has a cover now" : "All " + ok + " albums have covers now";
        return ok == 1 ? "1 album has a new cover" : ok + " albums have new covers";
    }
}
