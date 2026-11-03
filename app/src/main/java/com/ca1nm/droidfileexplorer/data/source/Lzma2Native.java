package com.ca1nm.droidfileexplorer.data.source;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * LZMA2 decoding in C (the public-domain LZMA SDK, built with -O3 by the NDK).
 *
 * Faster than the pure-Java decoder and, just as important here, its dictionary lives outside the Java heap,
 * so several 128 MB decoders can run at once on a phone whose heap limit is 512 MB.
 * {@link #AVAILABLE} is false when the library can't be loaded (unsupported ABI, plain JVM); callers then use
 * the Java decoder.
 */
final class Lzma2Native {
    private Lzma2Native() {}

    static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("dfelzma");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    /** @return a handle, or 0 if the dictionary could not be allocated */
    static native long create(int prop);

    /** @param res receives {consumed, produced, status}; @return 0 on success, an LZMA SDK SRes error otherwise */
    static native int decode(long handle, byte[] in, int inOff, int inLen, byte[] out, int outOff, int outLen, int[] res);

    static native void destroy(long handle);

    /** LZMA SDK's ELzmaStatus values we care about. */
    private static final int STATUS_FINISHED_WITH_MARK = 1;

    /** Decodes a raw LZMA2 stream (as stored inside 7z) read from {@code src}. */
    static final class Stream extends InputStream {
        private final InputStream src;
        private long handle;
        private final byte[] in = new byte[256 * 1024];
        private int inPos, inEnd;
        private boolean srcEof, finished;
        private final int[] res = new int[3];

        Stream(InputStream src, byte prop) throws IOException {
            this.src = src;
            this.handle = create(prop & 0xFF);
            if (handle == 0) throw new IOException("Not enough memory for the LZMA2 dictionary");
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (finished) return -1;
            if (handle == 0) throw new IOException("Stream closed");
            int idle = 0;
            while (true) {
                if (inPos == inEnd && !srcEof) {
                    int n = src.read(in, 0, in.length);
                    if (n < 0) { srcEof = true; inPos = inEnd = 0; }
                    else { inPos = 0; inEnd = n; }
                }
                int r = decode(handle, in, inPos, inEnd - inPos, b, off, len, res);
                if (r != 0) throw new IOException("LZMA2 data is corrupt (error " + r + ")");
                inPos += res[0];
                int produced = res[1];
                if (produced > 0) return produced;
                if (res[2] == STATUS_FINISHED_WITH_MARK) { finished = true; return -1; }
                if (res[0] == 0) {
                    if (srcEof && inPos == inEnd) throw new EOFException("LZMA2 stream ended early");
                    if (++idle > 2) throw new IOException("LZMA2 decoder made no progress");
                } else {
                    idle = 0;
                }
            }
        }

        @Override public void close() throws IOException {
            long h = handle;
            handle = 0;
            if (h != 0) destroy(h);
            src.close();
        }
    }
}
