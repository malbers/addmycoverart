package com.malbers.addmycoverart.core;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * 1.1.0.1 (Václav H., 2026-11-24: "the streamer seems to be offline" while his A6 streamed radio fine): a transport
 * failure that remembers which host it was and why. Before, every failure read "offline" and the cause was dropped.
 */
public final class NetFailure extends IOException {
    public final String host;

    public NetFailure(String host, IOException cause) {
        super(host + ": " + cause, cause);
        this.host = host;
    }

    /** True only when nothing could be reached at all: no name lookup, or no connection. Timeouts and TLS are not "offline". */
    public static boolean looksOffline(Throwable e) {
        return has(e, UnknownHostException.class) || has(e, ConnectException.class) || has(e, NoRouteToHostException.class);
    }

    /** One plain sentence for the screen, naming the host and the cause. {@code now} is the streamer's clock. */
    public static String describe(Throwable e, Date now) {
        String host = hostOf(e);
        String where = host != null ? host : "the cover database";
        if (has(e, CertificateExpiredException.class) || has(e, CertificateNotYetValidException.class)) {
            return "The secure connection to " + where + " failed because its certificate looks out of date. Check the "
                    + "streamer's date and time: it says " + new SimpleDateFormat("d MMM yyyy", Locale.US).format(now) + ".";
        }
        if (has(e, SSLException.class)) {
            return "The secure connection to " + where + " failed (" + rootName(e) + "). If the streamer's date and time "
                    + "are wrong, fix them and try again: it says " + new SimpleDateFormat("d MMM yyyy", Locale.US).format(now) + ".";
        }
        if (has(e, SocketTimeoutException.class)) return where + " didn't answer in time. Try again in a moment.";
        if (has(e, UnknownHostException.class)) {
            return "The streamer couldn't look up " + where + ". It may be offline, or its network can't find that site.";
        }
        if (has(e, ConnectException.class) || has(e, NoRouteToHostException.class)) {
            return "The streamer couldn't connect to " + where + ". It may be offline, or something on the network blocks it.";
        }
        return "Couldn't reach " + where + " (" + rootName(e) + ").";
    }

    static String hostOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof NetFailure) return ((NetFailure) t).host;
        return null;
    }

    private static boolean has(Throwable e, Class<? extends Throwable> c) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) if (c.isInstance(t)) return true;
        return false;
    }

    /** The innermost cause's class plus a short message, e.g. "SSLHandshakeException: Connection reset". */
    private static String rootName(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        if (r instanceof NetFailure && r.getCause() == null) return "unknown error";
        String m = r.getMessage();
        if (m != null && m.length() > 80) m = m.substring(0, 80) + "…";
        return r.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }
}
