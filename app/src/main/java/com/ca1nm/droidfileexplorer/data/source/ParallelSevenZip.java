package com.ca1nm.droidfileexplorer.data.source;

import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.LZMAInputStream;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.zip.CRC32;

/**
 * Multi-core extraction of 7z archives whose data is LZMA2.
 *
 * 7-Zip's LZMA2 encoder cuts big inputs into independent blocks (each starts with a dictionary reset),
 * but the decoder bundled with the app reads them one after another on a single core. This class finds those
 * blocks by walking the LZMA2 chunk headers, decodes groups of blocks on several threads and writes each
 * result straight to its place in the output files. Every file is checked against the CRC stored in the archive.
 *
 * Anything it can't handle (filters such as BCJ, encryption, a single huge block, too little memory) throws
 * {@link Unsupported} before a byte is written, and the caller falls back to the ordinary extractor.
 * Plain JVM code on purpose: no Android classes, so it can be tested on a PC.
 */
public final class ParallelSevenZip {

    private ParallelSevenZip() {}

    /** The archive can't be handled here; nothing has been written. Use the ordinary extractor. */
    public static final class Unsupported extends IOException {
        private static final long serialVersionUID = 1L;
        public Unsupported(String message) { super(message); }
    }

    public interface Progress { void update(long done, long total); }
    public interface EntryListener { void entry(String path); }
    public interface Cancel { boolean isCancelled(); }
    public interface LogSink { void log(String message); }

    /** Thrown when {@link Cancel} reports true. */
    public static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Cancelled() { super("Extraction cancelled"); }
    }

    // ------------------------------------------------------------------ header model

    private static final class Coder {
        byte[] id;
        long numIn = 1, numOut = 1;
        byte[] props;
    }

    private static final class Folder {
        Coder[] coders;
        long[] unpackSizes;
        boolean hasCrc;
        int crc;
        int numStreams = 1;
        long packStream; // index of its pack stream among all pack streams

        long unpackSize() { return unpackSizes[unpackSizes.length - 1]; }

        boolean isPlainLzma2() {
            return coders.length == 1 && coders[0].id.length == 1 && coders[0].id[0] == 0x21
                    && coders[0].numIn == 1 && coders[0].numOut == 1
                    && coders[0].props != null && coders[0].props.length == 1;
        }
    }

    private static final class Streams {
        long packPos;
        long[] packSizes = new long[0];
        Folder[] folders = new Folder[0];
        long[] subSizes;      // every file stream, folder after folder
        boolean[] subHasCrc;
        int[] subCrc;
    }

    private static final class Entry {
        String path;
        boolean isDir;
        long size;
        boolean hasCrc;
        int crc;
        int folder = -1;
        long offset; // inside its folder's unpacked stream
    }

    // ------------------------------------------------------------------ byte reader

    private static final class R {
        final byte[] b;
        int p;
        R(byte[] b) { this.b = b; }

        int u8() throws IOException {
            if (p >= b.length) throw new IOException("Truncated 7z header");
            return b[p++] & 0xFF;
        }

        long num() throws IOException {
            int first = u8();
            int mask = 0x80;
            long value = 0;
            for (int i = 0; i < 8; i++) {
                if ((first & mask) == 0) {
                    long high = first & (mask - 1);
                    return value + (high << (8 * i));
                }
                value |= ((long) u8()) << (8 * i);
                mask >>>= 1;
            }
            return value;
        }

        int u32() throws IOException {
            int v = 0;
            for (int i = 0; i < 4; i++) v |= u8() << (8 * i);
            return v;
        }

        byte[] bytes(int n) throws IOException {
            if (n < 0 || p + n > b.length) throw new IOException("Truncated 7z header");
            byte[] out = Arrays.copyOfRange(b, p, p + n);
            p += n;
            return out;
        }

        boolean[] bits(int n) throws IOException {
            boolean[] out = new boolean[n];
            int cur = 0, mask = 0;
            for (int i = 0; i < n; i++) {
                if (mask == 0) { cur = u8(); mask = 0x80; }
                out[i] = (cur & mask) != 0;
                mask >>>= 1;
            }
            return out;
        }

        /** "AllAreDefined" byte, then a bit vector when it is 0. */
        boolean[] definedBits(int n) throws IOException {
            if (u8() != 0) {
                boolean[] all = new boolean[n];
                Arrays.fill(all, true);
                return all;
            }
            return bits(n);
        }
    }

    // ------------------------------------------------------------------ header parsing

    private static byte[] readBytes(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(len);
        long at = pos;
        while (bb.hasRemaining()) {
            int n = ch.read(bb, at);
            if (n < 0) throw new EOFException("Archive is truncated");
            at += n;
        }
        return bb.array();
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static Streams readStreamsInfo(R r) throws IOException {
        Streams si = new Streams();
        long type = r.num();
        if (type == 0x06) { // PackInfo
            si.packPos = r.num();
            int n = (int) r.num();
            si.packSizes = new long[n];
            while (true) {
                long t = r.num();
                if (t == 0) break;
                if (t == 0x09) {
                    for (int i = 0; i < n; i++) si.packSizes[i] = r.num();
                } else if (t == 0x0A) {
                    boolean[] def = r.definedBits(n);
                    for (boolean d : def) if (d) r.u32();
                } else {
                    throw new Unsupported("Unknown 7z pack property " + t);
                }
            }
            type = r.num();
        }
        if (type == 0x07) { // UnpackInfo: a Folder block, then the unpacked sizes
            if (r.num() != 0x0B) throw new IOException("Missing 7z folder list");
            int nf = (int) r.num();
            if (r.u8() != 0) throw new Unsupported("External 7z folder data");
            si.folders = new Folder[nf];
            long packIndex = 0;
            for (int i = 0; i < nf; i++) {
                Folder f = readFolder(r);
                f.packStream = packIndex;
                packIndex += packStreamCount(f);
                si.folders[i] = f;
            }
            if (r.num() != 0x0C) throw new IOException("Missing 7z unpack sizes");
            for (Folder f : si.folders) {
                long outs = 0;
                for (Coder c : f.coders) outs += c.numOut;
                f.unpackSizes = new long[(int) outs];
                for (int j = 0; j < outs; j++) f.unpackSizes[j] = r.num();
            }
            while (true) {
                long t = r.num();
                if (t == 0) break;
                if (t == 0x0A) {
                    boolean[] def = r.definedBits(nf);
                    for (int i = 0; i < nf; i++) {
                        if (def[i]) { si.folders[i].hasCrc = true; si.folders[i].crc = r.u32(); }
                    }
                } else {
                    throw new Unsupported("Unknown 7z unpack property " + t);
                }
            }
            type = r.num();
        }
        if (type == 0x08) { // SubStreamsInfo
            readSubStreams(r, si);
            type = r.num();
        } else {
            defaultSubStreams(si);
        }
        if (type != 0) throw new Unsupported("Unexpected 7z streams property " + type);
        return si;
    }

    private static long packStreamCount(Folder f) {
        long totalIn = 0, totalOut = 0;
        for (Coder c : f.coders) { totalIn += c.numIn; totalOut += c.numOut; }
        return totalIn - (totalOut - 1);
    }

    private static Folder readFolder(R r) throws IOException {
        int numCoders = (int) r.num();
        Folder f = new Folder();
        f.coders = new Coder[numCoders];
        long totalIn = 0, totalOut = 0;
        for (int i = 0; i < numCoders; i++) {
            int flags = r.u8();
            Coder c = new Coder();
            c.id = r.bytes(flags & 0x0F);
            if ((flags & 0x10) != 0) { c.numIn = r.num(); c.numOut = r.num(); }
            if ((flags & 0x20) != 0) c.props = r.bytes((int) r.num());
            if ((flags & 0x80) != 0) throw new Unsupported("7z alternative methods");
            totalIn += c.numIn;
            totalOut += c.numOut;
            f.coders[i] = c;
        }
        long bindPairs = totalOut - 1;
        for (long i = 0; i < bindPairs; i++) { r.num(); r.num(); }
        long packed = totalIn - bindPairs;
        if (packed > 1) for (long i = 0; i < packed; i++) r.num();
        return f;
    }

    private static void defaultSubStreams(Streams si) {
        int n = si.folders.length;
        si.subSizes = new long[n];
        si.subHasCrc = new boolean[n];
        si.subCrc = new int[n];
        for (int i = 0; i < n; i++) {
            Folder f = si.folders[i];
            f.numStreams = 1;
            si.subSizes[i] = f.unpackSize();
            si.subHasCrc[i] = f.hasCrc;
            si.subCrc[i] = f.crc;
        }
    }

    private static void readSubStreams(R r, Streams si) throws IOException {
        int nf = si.folders.length;
        long t = r.num();
        if (t == 0x0D) {
            for (int i = 0; i < nf; i++) si.folders[i].numStreams = (int) r.num();
            t = r.num();
        }
        int total = 0;
        for (Folder f : si.folders) total += f.numStreams;
        si.subSizes = new long[total];
        si.subHasCrc = new boolean[total];
        si.subCrc = new int[total];

        boolean haveSizes = (t == 0x09);
        int k = 0;
        for (Folder f : si.folders) {
            int n = f.numStreams;
            if (n == 0) continue;
            long sum = 0;
            if (haveSizes) {
                for (int j = 0; j < n - 1; j++) { long s = r.num(); si.subSizes[k + j] = s; sum += s; }
            } else if (n > 1) {
                throw new IOException("Missing 7z substream sizes");
            }
            si.subSizes[k + n - 1] = f.unpackSize() - sum;
            k += n;
        }
        if (haveSizes) t = r.num();

        // Folders with exactly one stream and a folder CRC already have their digest.
        int unknown = 0;
        for (Folder f : si.folders) if (!(f.numStreams == 1 && f.hasCrc)) unknown += f.numStreams;
        k = 0;
        int[] unknownIndex = new int[unknown];
        int u = 0;
        for (Folder f : si.folders) {
            if (f.numStreams == 1 && f.hasCrc) {
                si.subHasCrc[k] = true;
                si.subCrc[k] = f.crc;
                k += 1;
            } else {
                for (int j = 0; j < f.numStreams; j++) unknownIndex[u++] = k++;
            }
        }
        while (t != 0) {
            if (t == 0x0A) {
                boolean[] def = r.definedBits(unknown);
                for (int i = 0; i < unknown; i++) {
                    if (def[i]) { si.subHasCrc[unknownIndex[i]] = true; si.subCrc[unknownIndex[i]] = r.u32(); }
                }
            } else {
                throw new Unsupported("Unknown 7z substream property " + t);
            }
            t = r.num();
        }
    }

    private static final class Parsed {
        Streams main = new Streams();
        List<Entry> entries = new ArrayList<>();
    }

    private static Parsed parse(FileChannel ch) throws IOException {
        byte[] sig = readBytes(ch, 0, 32);
        if (sig[0] != '7' || sig[1] != 'z' || (sig[2] & 0xFF) != 0xBC || (sig[3] & 0xFF) != 0xAF
                || sig[4] != 0x27 || sig[5] != 0x1C) {
            throw new Unsupported("Not a 7z archive");
        }
        long nextOffset = le64(sig, 12);
        long nextSize = le64(sig, 20);
        if (nextSize == 0) return new Parsed();
        if (nextSize > Integer.MAX_VALUE) throw new Unsupported("7z header too large");
        byte[] header = readBytes(ch, 32 + nextOffset, (int) nextSize);
        while (true) {
            R r = new R(header);
            long id = r.num();
            if (id == 0x17) { // EncodedHeader
                header = decodeHeader(ch, readStreamsInfo(r));
                continue;
            }
            if (id != 0x01) throw new Unsupported("Unknown 7z header type " + id);
            return parseHeader(r);
        }
    }

    private static byte[] decodeHeader(FileChannel ch, Streams si) throws IOException {
        if (si.folders.length < 1 || si.packSizes.length < 1) throw new IOException("Bad encoded 7z header");
        Folder f = si.folders[0];
        if (f.coders.length != 1) throw new Unsupported("Filtered 7z header");
        Coder c = f.coders[0];
        long unpack = f.unpackSize();
        if (unpack > Integer.MAX_VALUE || si.packSizes[0] > Integer.MAX_VALUE) throw new Unsupported("7z header too large");
        byte[] packed = readBytes(ch, 32 + si.packPos, (int) si.packSizes[0]);
        byte[] out = new byte[(int) unpack];
        try (InputStream in = openHeaderDecoder(c, packed, unpack)) {
            int done = 0;
            while (done < out.length) {
                int n = in.read(out, done, out.length - done);
                if (n < 0) throw new EOFException("Truncated 7z header");
                done += n;
            }
        }
        return out;
    }

    private static InputStream openHeaderDecoder(Coder c, byte[] packed, long unpack) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(packed);
        if (c.id.length == 3 && c.id[0] == 3 && c.id[1] == 1 && c.id[2] == 1 && c.props != null && c.props.length >= 5) {
            int dict = (c.props[1] & 0xFF) | ((c.props[2] & 0xFF) << 8) | ((c.props[3] & 0xFF) << 16) | ((c.props[4] & 0xFF) << 24);
            return new LZMAInputStream(in, unpack, c.props[0], dict);
        }
        if (c.id.length == 1 && c.id[0] == 0x21 && c.props != null && c.props.length == 1) {
            return new LZMA2InputStream(in, lzma2Dict(c.props[0]));
        }
        throw new Unsupported("Unsupported 7z header coder");
    }

    private static Parsed parseHeader(R r) throws IOException {
        Parsed out = new Parsed();
        long t = r.num();
        if (t == 0x02) { // ArchiveProperties
            while (true) {
                long pt = r.num();
                if (pt == 0) break;
                r.p += (int) r.num();
            }
            t = r.num();
        }
        if (t == 0x03) { // AdditionalStreamsInfo
            readStreamsInfo(r);
            t = r.num();
        }
        if (t == 0x04) { // MainStreamsInfo
            out.main = readStreamsInfo(r);
            t = r.num();
        }
        if (t == 0x05) { // FilesInfo
            readFiles(r, out);
            t = r.num();
        }
        if (t != 0) throw new Unsupported("Unexpected 7z header property " + t);
        return out;
    }

    private static void readFiles(R r, Parsed out) throws IOException {
        int numFiles = (int) r.num();
        boolean[] emptyStream = new boolean[numFiles];
        boolean[] emptyFile = null;
        String[] names = new String[numFiles];
        int numEmpty = 0;
        while (true) {
            long t = r.num();
            if (t == 0) break;
            int size = (int) r.num();
            int end = r.p + size;
            if (t == 0x0E) {
                emptyStream = r.bits(numFiles);
                numEmpty = 0;
                for (boolean e : emptyStream) if (e) numEmpty++;
            } else if (t == 0x0F) {
                emptyFile = r.bits(numEmpty);
            } else if (t == 0x11) {
                if (r.u8() != 0) throw new Unsupported("External 7z file names");
                byte[] raw = r.bytes(end - r.p);
                String all = new String(raw, StandardCharsets.UTF_16LE);
                int start = 0, i = 0;
                for (int pos = 0; pos < all.length() && i < numFiles; pos++) {
                    if (all.charAt(pos) == 0) { names[i++] = all.substring(start, pos); start = pos + 1; }
                }
            }
            r.p = end;
        }

        Streams si = out.main;
        int folderIdx = 0, inFolder = 0, sub = 0;
        long offset = 0;
        int emptyIdx = 0;
        for (int i = 0; i < numFiles; i++) {
            Entry e = new Entry();
            e.path = names[i] == null ? "" : names[i].replace('\\', '/');
            if (emptyStream[i]) {
                boolean isEmptyFile = emptyFile != null && emptyIdx < emptyFile.length && emptyFile[emptyIdx];
                emptyIdx++;
                e.isDir = !isEmptyFile;
                e.size = 0;
            } else {
                while (folderIdx < si.folders.length && inFolder >= si.folders[folderIdx].numStreams) {
                    folderIdx++;
                    inFolder = 0;
                    offset = 0;
                }
                if (folderIdx >= si.folders.length) throw new IOException("7z file list does not match its streams");
                e.folder = folderIdx;
                e.offset = offset;
                e.size = si.subSizes[sub];
                e.hasCrc = si.subHasCrc[sub];
                e.crc = si.subCrc[sub];
                offset += e.size;
                inFolder++;
                sub++;
            }
            out.entries.add(e);
        }
    }

    // ------------------------------------------------------------------ LZMA2 block discovery

    static int lzma2Dict(byte propsByte) throws IOException {
        int p = propsByte & 0xFF;
        if (p > 40) throw new IOException("Bad LZMA2 dictionary size");
        if (p == 40) return Integer.MAX_VALUE & ~15;
        long size = (long) (2 | (p & 1)) << (p / 2 + 11);
        return (int) Math.min(size, Integer.MAX_VALUE & ~15);
    }

    /** Marks the end of the queue of work units. */
    private static final Unit POISON = new Unit();

    /**
     * Walks the LZMA2 chunk headers of every folder on its own thread and hands out one work unit per
     * independent block (several small blocks are merged up to {@code target} bytes). Decoding can start as soon as
     * the first units appear; the extractor itself only waits until two blocks are known, to be sure there is
     * something to run in parallel.
     */
    private static final class Scan {
        final FileChannel ch;
        final Streams si;
        final long[] packOffsets;
        final FolderCtx[] ctx;
        final List<Entry> entries;
        final boolean[] wanted;
        final BlockingQueue<Unit> queue;
        final AtomicBoolean abort;
        final Cancel cancel;
        final int threadCap;
        final AtomicInteger blocks = new AtomicInteger();
        final AtomicInteger units = new AtomicInteger();
        final CountDownLatch decided = new CountDownLatch(1);

        Scan(FileChannel ch, Streams si, long[] packOffsets, FolderCtx[] ctx, List<Entry> entries, boolean[] wanted,
             BlockingQueue<Unit> queue, AtomicBoolean abort, Cancel cancel, int threadCap) {
            this.ch = ch;
            this.si = si;
            this.packOffsets = packOffsets;
            this.ctx = ctx;
            this.entries = entries;
            this.wanted = wanted;
            this.queue = queue;
            this.abort = abort;
            this.cancel = cancel;
            this.threadCap = threadCap;
        }

        void scanAll() throws IOException {
            for (int fi = 0; fi < si.folders.length; fi++) {
                Folder f = si.folders[fi];
                if (f.numStreams == 0) continue;
                long start = packOffsets[(int) f.packStream];
                long size = si.packSizes[(int) f.packStream];
                long target = Math.max(4L << 20, f.unpackSize() / Math.max(1, threadCap * 8L));
                if (!scanFolder(fi, f, start, size, target)) return;
            }
        }

        private void emit(int fi, Folder f, long start, long packFrom, long packTo, long u0, long u1) {
            if (!overlapsWanted(ctx[fi], entries, wanted, u0, u1)) return;
            Unit u = new Unit();
            u.folder = fi;
            u.prop = f.coders[0].props[0];
            u.packStart = start + packFrom;
            u.packEnd = start + packTo;
            u.u0 = u0;
            u.u1 = u1;
            units.incrementAndGet();
            queue.add(u);
        }

        /** @return false if the scan was stopped early (cancel or failure elsewhere) */
        private boolean scanFolder(int fi, Folder f, long start, long size, long target) throws IOException {
            ByteBuffer hb = ByteBuffer.allocate(6);
            long pos = 0, u = 0;
            boolean open = false;
            long unitPack = 0, unitU = 0;
            int chunks = 0;
            while (pos < size) {
                if ((++chunks & 0x3FF) == 0 && (abort.get() || cancel.isCancelled())) return false;
                hb.clear();
                int want = (int) Math.min(6, size - pos);
                hb.limit(want);
                long at = start + pos;
                while (hb.hasRemaining()) {
                    int n = ch.read(hb, at);
                    if (n < 0) throw new EOFException("Archive is truncated");
                    at += n;
                }
                int c = hb.get(0) & 0xFF;
                if (c == 0) break; // end marker
                long usz, adv;
                boolean reset;
                if (c == 1 || c == 2) {
                    if (want < 3) throw new IOException("Corrupt LZMA2 data");
                    usz = (((hb.get(1) & 0xFF) << 8) | (hb.get(2) & 0xFF)) + 1L;
                    adv = 3 + usz;
                    reset = c == 1;
                } else if (c >= 0x80) {
                    if (want < 5) throw new IOException("Corrupt LZMA2 data");
                    usz = ((((long) (c & 0x1F)) << 16) | ((hb.get(1) & 0xFF) << 8) | (hb.get(2) & 0xFF)) + 1;
                    long psz = (((hb.get(3) & 0xFF) << 8) | (hb.get(4) & 0xFF)) + 1L;
                    adv = (c >= 0xC0 ? 6 : 5) + psz;
                    reset = c >= 0xE0;
                } else {
                    throw new IOException("Corrupt LZMA2 data");
                }
                if (!open && !reset) throw new Unsupported("LZMA2 stream does not start with a dictionary reset");
                if (reset) {
                    if (!open) {
                        open = true;
                        unitPack = pos;
                        unitU = u;
                    } else if (u - unitU >= target) {
                        emit(fi, f, start, unitPack, pos, unitU, u);
                        unitPack = pos;
                        unitU = u;
                    }
                    if (blocks.incrementAndGet() >= 2) decided.countDown();
                }
                u += usz;
                pos += adv;
            }
            if (u != f.unpackSize()) {
                String msg = "LZMA2 sizes don't match the 7z header";
                throw decided.getCount() > 0 ? new Unsupported(msg) : new IOException(msg);
            }
            if (open) emit(fi, f, start, unitPack, pos, unitU, u);
            return true;
        }
    }

    // ------------------------------------------------------------------ work units

    private static final class Unit {
        int folder;
        byte prop;                 // LZMA2 dictionary-size byte of its folder
        long packStart, packEnd;   // absolute file offsets of the LZMA2 data for this unit
        long u0, u1;               // range of the folder's unpacked stream
    }

    private static final class FolderCtx {
        int[] fileIdx;   // entries with data in this folder, in stream order
        long[] starts;
        long[] ends;
    }

    private static final class Piece {
        final long off, len;
        final int crc;
        Piece(long off, long len, int crc) { this.off = off; this.len = len; this.crc = crc; }
    }

    /** Reads an absolute range of the archive, then a single 0 byte (the LZMA2 end marker). */
    private static final class UnitStream extends InputStream {
        private final FileChannel ch;
        private long pos;
        private final long end;
        private final ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
        private boolean marker;

        UnitStream(FileChannel ch, long start, long end) {
            this.ch = ch;
            this.pos = start;
            this.end = end;
            buf.limit(0);
        }

        private boolean fill() throws IOException {
            if (pos >= end) return false;
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), end - pos));
            int n = ch.read(buf, pos);
            if (n < 0) throw new EOFException("Archive is truncated");
            pos += n;
            buf.flip();
            return true;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] dst, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!buf.hasRemaining() && !fill()) {
                if (!marker) { marker = true; dst[off] = 0; return 1; }
                return -1;
            }
            int n = Math.min(len, buf.remaining());
            buf.get(dst, off, n);
            return n;
        }
    }

    // ------------------------------------------------------------------ extraction

    /**
     * @param onlyPaths   paths inside the archive to extract, or null for everything
     * @param maxThreads  upper bound for worker threads, or 0 for "as many cores as are free"
     * @return number of files written
     * @throws Unsupported if the archive isn't suitable; nothing has been written in that case
     * @throws Cancelled   if {@code cancel} reported true
     */
    public static int extract(File archive, File destDir, Set<String> onlyPaths, int maxThreads,
                              Progress progress, EntryListener listener, Cancel cancel, LogSink log) throws IOException {
        long t0 = System.nanoTime();
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r"); FileChannel ch = raf.getChannel()) {
            Parsed parsed = parse(ch);
            Streams si = parsed.main;
            List<Entry> entries = parsed.entries;

            for (Folder f : si.folders) {
                if (!f.isPlainLzma2()) throw new Unsupported("7z uses a method other than plain LZMA2");
            }

            // ---- which files, which folders
            int nEntries = entries.size();
            boolean[] wanted = new boolean[nEntries];
            File[] targets = new File[nEntries];
            String destCanon = destDir.getCanonicalPath();
            long totalBytes = 0;
            int fileCount = 0;
            for (int i = 0; i < nEntries; i++) {
                Entry e = entries.get(i);
                String p = e.path;
                while (p.startsWith("/")) p = p.substring(1);
                while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
                if (p.isEmpty()) continue;
                if (onlyPaths != null && !e.isDir && !onlyPaths.contains(p)) continue;
                File t = new File(destDir, p);
                if (!t.getCanonicalPath().startsWith(destCanon + File.separator)) continue; // would escape: skip
                wanted[i] = true;
                targets[i] = t;
                if (!e.isDir) { totalBytes += e.size; fileCount++; }
            }

            FolderCtx[] ctx = new FolderCtx[si.folders.length];
            for (int fi = 0; fi < ctx.length; fi++) {
                List<Integer> idx = new ArrayList<>();
                for (int i = 0; i < nEntries; i++) {
                    Entry e = entries.get(i);
                    if (e.folder == fi && e.size > 0) idx.add(i);
                }
                FolderCtx c = new FolderCtx();
                c.fileIdx = new int[idx.size()];
                c.starts = new long[idx.size()];
                c.ends = new long[idx.size()];
                for (int k = 0; k < c.fileIdx.length; k++) {
                    Entry e = entries.get(idx.get(k));
                    c.fileIdx[k] = idx.get(k);
                    c.starts[k] = e.offset;
                    c.ends[k] = e.offset + e.size;
                }
                ctx[fi] = c;
            }

            // ---- thread and memory budget
            int cores = Runtime.getRuntime().availableProcessors();
            int threadCap = maxThreads > 0 ? maxThreads : Math.max(2, cores);
            int declaredDict = 0;
            for (Folder f : si.folders) {
                if (f.numStreams > 0) declaredDict = Math.max(declaredDict, lzma2Dict(f.coders[0].props[0]));
            }
            boolean nativeDecoder = Lzma2Native.AVAILABLE;
            int threads = threadCap;
            if (!nativeDecoder) {
                // The Java decoder keeps its dictionary on the Java heap, so the heap limit caps the thread count.
                long perThread = (long) declaredDict + (6L << 20);
                long budget = (long) (Runtime.getRuntime().maxMemory() * 0.5);
                threads = (int) Math.min(threads, Math.max(0, budget / perThread));
                if (threads < 2 && totalBytes > (32L << 20)) {
                    throw new Unsupported("Not enough memory to decode in parallel (dictionary " + (declaredDict >> 20) + " MB)");
                }
            }
            threads = Math.max(1, threads);

            // ---- find the independent blocks on a helper thread; decoding starts as soon as two are known
            long packBase = 32 + si.packPos;
            long[] packOffsets = new long[si.packSizes.length];
            long acc = 0;
            for (int i = 0; i < packOffsets.length; i++) { packOffsets[i] = packBase + acc; acc += si.packSizes[i]; }

            final BlockingQueue<Unit> queue = new LinkedBlockingQueue<>();
            final Run run = new Run(ch, entries, wanted, targets, ctx, queue, declaredDict, listener, cancel, totalBytes);
            final Scan scan = new Scan(ch, si, packOffsets, ctx, entries, wanted, queue, run.abort, cancel, threadCap);
            final long scanStart = System.nanoTime();
            Thread walker = new Thread(() -> {
                try {
                    scan.scanAll();
                } catch (Throwable t) {
                    run.failure.compareAndSet(null, t);
                    run.abort.set(true);
                } finally {
                    scan.decided.countDown();
                    queue.add(POISON);
                    if (log != null) {
                        log.log("parallel 7z: scan finished: " + scan.blocks.get() + " blocks, " + scan.units.get()
                                + " units in " + (System.nanoTime() - scanStart) / 1_000_000 + " ms");
                    }
                }
            }, "dfe-7z-scan");
            walker.start();
            try {
                scan.decided.await();
            } catch (InterruptedException ie) {
                run.abort.set(true);
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
            if (scan.blocks.get() < 2) {
                // The scan is over (it stops by itself when there are fewer than two blocks).
                try { walker.join(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                Throwable early = run.failure.get();
                if (early instanceof IOException) throw (IOException) early;
                if (early instanceof RuntimeException) throw (RuntimeException) early;
                if (early instanceof Error) throw (Error) early;
                if (cancel.isCancelled()) throw new Cancelled();
                if (totalBytes > (32L << 20)) {
                    throw new Unsupported("Only " + scan.blocks.get() + " independent LZMA2 block(s): nothing to run in parallel");
                }
            }
            if (log != null) {
                log.log("parallel 7z: " + threads + " threads, dict " + (declaredDict >> 20) + " MB, "
                        + (nativeDecoder ? "native" : "java") + " decoder, decoding starts after "
                        + (System.nanoTime() - t0) / 1_000_000 + " ms");
            }

            // ---- from here on we write
            for (int i = 0; i < nEntries; i++) {
                if (!wanted[i]) continue;
                Entry e = entries.get(i);
                File t = targets[i];
                if (e.isDir) { t.mkdirs(); continue; }
                File parent = t.getParentFile();
                if (parent != null) parent.mkdirs();
                try (RandomAccessFile out = new RandomAccessFile(t, "rw")) {
                    out.setLength(e.size);
                }
            }
            for (int i = 0; i < nEntries; i++) {
                Entry e = entries.get(i);
                if (wanted[i] && !e.isDir && e.size == 0) listener.entry(e.path);
            }

            long t1 = System.nanoTime();
            try {
                run.execute(threads, progress, walker);
            } catch (IOException | RuntimeException | Error ex) {
                run.deleteIncomplete();
                throw ex;
            }
            if (log != null) {
                log.log("parallel 7z: decoded " + (totalBytes >> 20) + " MB in " + (System.nanoTime() - t1) / 1_000_000 + " ms");
            }
            return fileCount;
        }
    }

    private static boolean overlapsWanted(FolderCtx c, List<Entry> entries, boolean[] wanted, long u0, long u1) {
        int k = firstOverlap(c, u0);
        for (; k < c.fileIdx.length && c.starts[k] < u1; k++) {
            if (c.ends[k] > u0 && wanted[c.fileIdx[k]]) return true;
        }
        return false;
    }

    /** Index of the first file whose end is after {@code u}. */
    private static int firstOverlap(FolderCtx c, long u) {
        int lo = 0, hi = c.ends.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c.ends[mid] <= u) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static final class Run {
        final FileChannel archive;
        final List<Entry> entries;
        final boolean[] wanted;
        final File[] targets;
        final FolderCtx[] ctx;
        final BlockingQueue<Unit> queue;
        final int dict;
        final EntryListener listener;
        final Cancel cancel;
        final long totalBytes;

        final AtomicLong done = new AtomicLong();
        final AtomicBoolean abort = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong[] remaining;
        final AtomicReferenceArray<FileChannel> channels;
        final List<List<Piece>> pieces;
        final Object openLock = new Object();

        Run(FileChannel archive, List<Entry> entries, boolean[] wanted, File[] targets, FolderCtx[] ctx,
            BlockingQueue<Unit> queue, int dict, EntryListener listener, Cancel cancel, long totalBytes) {
            this.archive = archive;
            this.entries = entries;
            this.wanted = wanted;
            this.targets = targets;
            this.ctx = ctx;
            this.queue = queue;
            this.dict = dict;
            this.listener = listener;
            this.cancel = cancel;
            this.totalBytes = totalBytes;
            int n = entries.size();
            remaining = new AtomicLong[n];
            pieces = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                remaining[i] = new AtomicLong(entries.get(i).size);
                pieces.add(wanted[i] && entries.get(i).hasCrc && entries.get(i).size > 0 ? new ArrayList<Piece>() : null);
            }
            channels = new AtomicReferenceArray<>(n);
        }

        void execute(int threads, Progress progress, Thread walker) throws IOException {
            final AtomicInteger finished = new AtomicInteger();
            Thread[] ts = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                ts[i] = new Thread(() -> {
                    try { worker(); }
                    catch (Throwable t) { failure.compareAndSet(null, t); abort.set(true); }
                    finally { finished.incrementAndGet(); }
                }, "dfe-7z-" + i);
                ts[i].setPriority(Thread.NORM_PRIORITY - 1); // keep the UI smooth while every core is busy
                ts[i].start();
            }
            try {
                while (finished.get() < threads) {
                    try { Thread.sleep(100); }
                    catch (InterruptedException ie) { abort.set(true); Thread.currentThread().interrupt(); break; }
                    if (cancel.isCancelled()) abort.set(true);
                    if (progress != null) progress.update(done.get(), totalBytes);
                }
                for (Thread t : ts) {
                    try { t.join(); } catch (InterruptedException ie) { abort.set(true); Thread.currentThread().interrupt(); }
                }
                try { walker.join(); } catch (InterruptedException ie) { abort.set(true); Thread.currentThread().interrupt(); }
            } finally {
                closeAll();
            }
            Throwable f = failure.get();
            if (f instanceof IOException) throw (IOException) f;
            if (f instanceof RuntimeException) throw (RuntimeException) f;
            if (f instanceof Error) throw (Error) f;
            if (f != null) throw new IOException(f);
            if (cancel.isCancelled()) throw new Cancelled();
            if (progress != null) progress.update(totalBytes, totalBytes);
            verifyChecksums();
        }

        void worker() throws IOException {
            byte[] buf = new byte[1 << 20];
            while (!abort.get()) {
                Unit u;
                try {
                    u = queue.take();
                } catch (InterruptedException ie) {
                    abort.set(true);
                    return;
                }
                if (u == POISON) { queue.add(POISON); return; } // pass the end marker on to the other workers
                if (abort.get()) return;
                decodeUnit(u, buf);
            }
        }

        void decodeUnit(Unit u, byte[] buf) throws IOException {
            FolderCtx c = ctx[u.folder];
            UnitStream raw = new UnitStream(archive, u.packStart, u.packEnd);
            try (InputStream in = Lzma2Native.AVAILABLE ? new Lzma2Native.Stream(raw, u.prop) : new LZMA2InputStream(raw, dict)) {
                long at = u.u0;
                CRC32 crc = new CRC32();
                int curFile = -1;
                long curOff = 0, curLen = 0;
                while (at < u.u1) {
                    if (abort.get()) return;
                    if (cancel.isCancelled()) { abort.set(true); return; }
                    int want = (int) Math.min(buf.length, u.u1 - at);
                    int n = 0;
                    while (n < want) {
                        int r = in.read(buf, n, want - n);
                        if (r < 0) throw new EOFException("LZMA2 stream ended early");
                        n += r;
                    }
                    long end = at + n;
                    for (int k = firstOverlap(c, at); k < c.fileIdx.length && c.starts[k] < end; k++) {
                        int fi = c.fileIdx[k];
                        if (!wanted[fi]) continue;
                        long from = Math.max(at, c.starts[k]);
                        long to = Math.min(end, c.ends[k]);
                        if (to <= from) continue;
                        int bufOff = (int) (from - at);
                        int len = (int) (to - from);
                        long fileOff = from - c.starts[k];

                        FileChannel out = channelFor(fi);
                        ByteBuffer bb = ByteBuffer.wrap(buf, bufOff, len);
                        long w = fileOff;
                        while (bb.hasRemaining()) w += out.write(bb, w);

                        if (pieces.get(fi) != null) {
                            if (curFile != fi || curOff + curLen != fileOff) {
                                flushPiece(curFile, curOff, curLen, crc);
                                crc.reset();
                                curFile = fi; curOff = fileOff; curLen = 0;
                            }
                            crc.update(buf, bufOff, len);
                            curLen += len;
                        }
                        done.addAndGet(len);
                        if (remaining[fi].addAndGet(-len) == 0) {
                            closeChannel(fi);
                            listener.entry(entries.get(fi).path);
                        }
                    }
                    at = end;
                }
                flushPiece(curFile, curOff, curLen, crc);
            }
        }

        void flushPiece(int file, long off, long len, CRC32 crc) {
            if (file < 0 || len <= 0) return;
            List<Piece> list = pieces.get(file);
            synchronized (list) { list.add(new Piece(off, len, (int) crc.getValue())); }
        }

        FileChannel channelFor(int file) throws IOException {
            FileChannel ch = channels.get(file);
            if (ch != null) return ch;
            synchronized (openLock) {
                ch = channels.get(file);
                if (ch == null) {
                    ch = new RandomAccessFile(targets[file], "rw").getChannel();
                    channels.set(file, ch);
                }
                return ch;
            }
        }

        void closeChannel(int file) {
            synchronized (openLock) {
                FileChannel ch = channels.getAndSet(file, null);
                if (ch != null) try { ch.close(); } catch (IOException ignored) { }
            }
        }

        void closeAll() {
            for (int i = 0; i < channels.length(); i++) closeChannel(i);
        }

        void verifyChecksums() throws IOException {
            for (int i = 0; i < entries.size(); i++) {
                List<Piece> list = pieces.get(i);
                if (list == null) continue;
                Entry e = entries.get(i);
                Collections.sort(list, new Comparator<Piece>() {
                    @Override public int compare(Piece a, Piece b) { return Long.compare(a.off, b.off); }
                });
                int crc = 0;
                long expectOff = 0;
                boolean first = true;
                for (Piece p : list) {
                    if (p.off != expectOff) throw new IOException(e.path + " could not be fully decoded");
                    crc = first ? p.crc : Crc32Combine.combine(crc, p.crc, p.len);
                    first = false;
                    expectOff += p.len;
                }
                if (expectOff != e.size || crc != e.crc) {
                    targets[i].delete();
                    throw new IOException(targets[i].getName() + " is damaged (checksum mismatch)");
                }
            }
        }

        void deleteIncomplete() {
            closeAll();
            for (int i = 0; i < entries.size(); i++) {
                if (wanted[i] && !entries.get(i).isDir && entries.get(i).size > 0 && remaining[i].get() != 0) {
                    targets[i].delete();
                }
            }
        }
    }

    // ------------------------------------------------------------------ CRC-32 combine (zlib's algorithm)

    static final class Crc32Combine {
        private static int times(int[] mat, int vec) {
            int sum = 0;
            for (int i = 0; vec != 0; vec >>>= 1, i++) if ((vec & 1) != 0) sum ^= mat[i];
            return sum;
        }

        private static void square(int[] sq, int[] mat) {
            for (int n = 0; n < 32; n++) sq[n] = times(mat, mat[n]);
        }

        static int combine(int crc1, int crc2, long len2) {
            if (len2 <= 0) return crc1;
            int[] even = new int[32], odd = new int[32];
            odd[0] = 0xEDB88320;
            int row = 1;
            for (int n = 1; n < 32; n++) { odd[n] = row; row <<= 1; }
            square(even, odd);
            square(odd, even);
            do {
                square(even, odd);
                if ((len2 & 1) != 0) crc1 = times(even, crc1);
                len2 >>= 1;
                if (len2 == 0) break;
                square(odd, even);
                if ((len2 & 1) != 0) crc1 = times(odd, crc1);
                len2 >>= 1;
            } while (len2 != 0);
            return crc1 ^ crc2;
        }
    }
}
