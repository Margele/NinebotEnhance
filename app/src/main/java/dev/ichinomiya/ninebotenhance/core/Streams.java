package dev.ichinomiya.ninebotenhance.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Whole-stream reads with a size cap; the platform's own readAllBytes and readNBytes exist only from Android 13. */
public final class Streams {
    public static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (out.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
            if (count < 0) break;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private Streams() {}
}
