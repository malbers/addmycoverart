package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateNotYetValidException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import javax.net.ssl.SSLHandshakeException;

import org.junit.Test;

/** 1.1.0.1 (Václav H.): the screen names the host and the real cause, and says "offline" only when nothing answers. */
public class NetFailureTest {
    private static final Date NOW = new GregorianCalendar(2019, Calendar.JANUARY, 1).getTime();

    @Test
    public void timeoutNamesHostAndIsNotOffline() {
        IOException e = new NetFailure("ia800.us.archive.org", new SocketTimeoutException("Read timed out"));
        assertEquals("ia800.us.archive.org didn't answer in time. Try again in a moment.", NetFailure.describe(e, NOW));
        assertFalse(NetFailure.looksOffline(e));
    }

    @Test
    public void expiredLookingCertificatePointsAtTheClock() {
        SSLHandshakeException ssl = new SSLHandshakeException("Chain validation failed");
        ssl.initCause(new CertificateNotYetValidException("not valid until 2025"));
        String s = NetFailure.describe(new NetFailure("coverartarchive.org", ssl), NOW);
        assertTrue(s, s.startsWith("The secure connection to coverartarchive.org failed because its certificate looks out of date."));
        assertTrue(s, s.contains("it says 1 Jan 2019"));
    }

    @Test
    public void otherTlsFailureNamesTheCauseAndTheDate() {
        String s = NetFailure.describe(new NetFailure("musicbrainz.org", new SSLHandshakeException("Connection reset")), NOW);
        assertTrue(s, s.contains("musicbrainz.org") && s.contains("SSLHandshakeException: Connection reset") && s.contains("1 Jan 2019"));
    }

    @Test
    public void noNameLookupLooksOffline() {
        IOException e = new NetFailure("musicbrainz.org", new UnknownHostException("Unable to resolve host"));
        assertTrue(NetFailure.looksOffline(e));
        assertTrue(NetFailure.describe(e, NOW).startsWith("The streamer couldn't look up musicbrainz.org."));
    }

    @Test
    public void refusedConnectionLooksOffline() {
        IOException e = new NetFailure("archive.org", new ConnectException("Connection refused"));
        assertTrue(NetFailure.looksOffline(e));
        assertTrue(NetFailure.describe(e, NOW).startsWith("The streamer couldn't connect to archive.org."));
    }

    @Test
    public void notBusy() {
        assertFalse(ArtLookup.isBusy(new NetFailure("musicbrainz.org", new SocketTimeoutException())));
    }

    @Test
    public void unwrappedErrorStillDescribed() {
        assertEquals("Couldn't reach the cover database (IOException: boom).", NetFailure.describe(new IOException("boom"), NOW));
    }
}
