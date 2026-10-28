package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Test;

/** Against a local socket server: "busy" answers are retried, real errors are not. */
public class UrlHttpTest {
    private ServerSocket server;

    /** Answers call N with codes[N] (the last code repeats), body "call N". */
    private String serve(int... codes) throws Exception {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            for (int i = 0; !server.isClosed(); i++) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) { /* drain request headers */ }
                    int code = codes[Math.min(i, codes.length - 1)];
                    byte[] body = ("call " + (i + 1)).getBytes(StandardCharsets.US_ASCII);
                    OutputStream o = s.getOutputStream();
                    o.write(("HTTP/1.1 " + code + " X\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    o.write(body);
                    o.flush();
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/x";
    }

    @After
    public void stop() throws Exception {
        if (server != null) server.close();
    }

    @Test
    public void retriesBusyThenSucceeds() throws Exception {
        Http.Response r = new UrlHttp("t", 10).get(serve(503, 503, 200));
        assertEquals(200, r.code);
        assertEquals("call 3", new String(r.body, StandardCharsets.US_ASCII));
    }

    @Test
    public void givesUpAfterThreeRetries() throws Exception {
        Http.Response r = new UrlHttp("t", 10).get(serve(503));
        assertEquals(503, r.code);
        assertEquals("call 4", new String(r.body, StandardCharsets.US_ASCII));
    }

    @Test
    public void notFoundIsNotRetried() throws Exception {
        Http.Response r = new UrlHttp("t", 10).get(serve(404, 200));
        assertEquals(404, r.code);
        assertEquals("call 1", new String(r.body, StandardCharsets.US_ASCII));
    }
}
