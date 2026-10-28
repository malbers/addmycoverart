package com.malbers.addmycoverart.core;

import java.io.IOException;

/** One GET, redirects already followed. Throws IOException only for transport failures; HTTP errors come back as codes. */
public interface Http {
    Response get(String url) throws IOException;

    final class Response {
        public final int code;
        public final byte[] body;

        public Response(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }
}
