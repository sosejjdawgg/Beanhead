/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.live;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One RTMP publishing connection (rtmp:// or rtmps://): handshake, connect, createStream, publish, then
 * FLV audio/video tags. Pure Java so it can be tested off the phone. Not thread-safe for writers except
 * that the reader thread may send small control replies; all writes go through one lock.
 */
public final class RtmpPublisher implements Closeable {

    /** Where to publish. The app is everything in the server URL's path; the key is the stream name. */
    public static final class Target {
        public final boolean tls;
        public final String host;
        public final int port;
        public final String app;
        public final String tcUrl;
        public final String key;

        private Target(boolean tls, String host, int port, String app, String tcUrl, String key) {
            this.tls = tls; this.host = host; this.port = port; this.app = app; this.tcUrl = tcUrl; this.key = key;
        }

        /**
         * Parses the "server URL" and "stream key" that TikTok, YouTube, Twitch and OBS show. If the key is
         * empty, the last path segment of the URL is taken as the key (a single full URL).
         */
        public static Target parse(String url, String key) {
            if (url == null) throw new IllegalArgumentException("Enter the server URL.");
            url = url.trim();
            key = key == null ? "" : key.trim();
            String lower = url.toLowerCase(Locale.ROOT);
            boolean tls;
            if (lower.startsWith("rtmps://")) tls = true;
            else if (lower.startsWith("rtmp://")) tls = false;
            else throw new IllegalArgumentException("The server URL should start with rtmp:// or rtmps://");
            String rest = url.substring(tls ? 8 : 7);
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "" : rest.substring(slash + 1);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (key.isEmpty()) {
                int ls = path.lastIndexOf('/');
                if (ls < 0) throw new IllegalArgumentException("Enter the stream key.");
                key = path.substring(ls + 1);
                path = path.substring(0, ls);
            }
            String host = hostPort;
            int port = tls ? 443 : 1935;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && hostPort.indexOf(']') < colon) {
                host = hostPort.substring(0, colon);
                try { port = Integer.parseInt(hostPort.substring(colon + 1)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("The port in the server URL isn't a number."); }
            }
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            if (host.isEmpty()) throw new IllegalArgumentException("The server URL has no host name.");
            if (path.isEmpty()) throw new IllegalArgumentException("The server URL needs an app name after the host, like rtmp://host/live");
            if (key.isEmpty()) throw new IllegalArgumentException("Enter the stream key.");
            String tc = (tls ? "rtmps://" : "rtmp://") + hostPort + "/" + path;
            return new Target(tls, host, port, path, tc, key);
        }

        /** For logs: never prints the stream key. */
        @Override public String toString() { return tcUrl + "/<key>"; }
    }

    /** The server said no (bad key, stream already live, ...): retrying won't help. */
    public static final class RejectedException extends IOException {
        private static final long serialVersionUID = 1L;
        public RejectedException(String msg) { super(msg); }
    }

    /** Called once, from the reader thread, when the server closes or drops the connection while publishing. */
    public interface CloseListener { void onClosed(String reason); }

    // FLV tag / RTMP message types
    public static final int TYPE_AUDIO = 8;
    public static final int TYPE_VIDEO = 9;
    private static final int TYPE_SET_CHUNK = 1, TYPE_ABORT = 2, TYPE_ACK = 3, TYPE_USER = 4, TYPE_WINDOW = 5, TYPE_PEER_BW = 6;
    private static final int TYPE_DATA = 18, TYPE_CMD = 20;
    // chunk stream ids
    private static final int CS_CONTROL = 2, CS_CMD = 3, CS_AUDIO = 4, CS_DATA = 5, CS_VIDEO = 6;
    private static final int OUT_CHUNK = 4096;

    private final Target target;
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Map<Integer, InChunk> inStreams = new HashMap<>();
    private byte[] wbuf = new byte[64 * 1024];
    private int inChunk = 128;
    private int outChunk = 128;
    private long windowAck = 2_500_000, bytesIn, lastAck;
    private double txn = 0;
    private int streamId;
    private volatile boolean closed;
    private volatile long bytesOut;
    private volatile long writeStartedAt;           // 0 when no write is in progress (used by the stall watchdog)
    private CloseListener closeListener;
    private Thread reader;

    private RtmpPublisher(Target target, Socket socket) throws IOException {
        this.target = target;
        this.socket = socket;
        this.in = new DataInputStream(new CountingInput(new BufferedInputStream(socket.getInputStream(), 16 * 1024)));
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    /**
     * Connects and gets as far as NetStream.Publish.Start. Throws {@link RejectedException} when the server
     * refuses the app or stream key, and a plain IOException for network problems.
     */
    public static RtmpPublisher open(Target t, int timeoutMs) throws IOException {
        Socket s = new Socket();
        RtmpPublisher p = null;
        try {
            s.connect(new InetSocketAddress(t.host, t.port), timeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout(timeoutMs);
            s.setSendBufferSize(256 * 1024);      // enough for the bandwidth-delay product, small enough not to hide congestion
            if (t.tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, t.host, t.port, true);
                SSLParameters sp = ssl.getSSLParameters();
                sp.setEndpointIdentificationAlgorithm("HTTPS");   // check the certificate is for this host name
                ssl.setSSLParameters(sp);
                s = ssl;
                ssl.startHandshake();
            }
            p = new RtmpPublisher(t, s);
            p.handshake();
            p.connectApp();
            p.publish();
            s.setSoTimeout(0);
            return p;
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    /** Starts the thread that answers pings and notices when the server ends the stream. */
    public void startReader(CloseListener l) {
        closeListener = l;
        reader = new Thread(() -> {
            String why = "The server closed the connection.";
            try {
                while (!closed) {
                    Msg m = readMessage();
                    if (m.type == TYPE_CMD) {
                        List<Object> v = Amf0.readAll(m.data);
                        String err = statusError(v);
                        if (err != null) { why = err; break; }
                    }
                }
            } catch (IOException e) {
                if (!(e instanceof EOFException)) why = "Connection lost (" + e.getMessage() + ")";
            }
            if (!closed) {
                closeQuietly();
                if (closeListener != null) closeListener.onClosed(why);
            }
        }, "rtmp-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public long bytesOut() { return bytesOut; }

    /** Milliseconds the current write has been blocked for, 0 if none. A stuck socket shows up here. */
    public long writeBlockedMs() {
        long t = writeStartedAt;
        return t == 0 ? 0 : (System.nanoTime() - t) / 1_000_000;
    }

    public boolean isClosed() { return closed; }

    /** onMetaData so players and ingest servers know the format before the first frame. */
    public void sendMetadata(int width, int height, int fps, int videoKbps, int audioKbps, int sampleRate, int channels) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("duration", 0.0);
        m.put("width", (double) width);
        m.put("height", (double) height);
        m.put("videodatarate", (double) videoKbps);
        m.put("framerate", (double) fps);
        m.put("videocodecid", 7.0);
        if (audioKbps > 0) {
            m.put("audiodatarate", (double) audioKbps);
            m.put("audiosamplerate", (double) sampleRate);
            m.put("audiosamplesize", 16.0);
            m.put("stereo", channels == 2);
            m.put("audiocodecid", 10.0);
        }
        m.put("encoder", "Peanut Cam");
        m.put("filesize", 0.0);
        byte[] d = new Amf0.Writer().str("@setDataFrame").str("onMetaData").ecma(m).bytes();
        writeMessage(CS_DATA, TYPE_DATA, streamId, 0, d, 0, d.length);
    }

    /** One FLV tag body (audio or video) at a timestamp in milliseconds from the start of this connection. */
    public void sendTag(int type, long tsMs, byte[] body, int off, int len) throws IOException {
        writeMessage(type == TYPE_VIDEO ? CS_VIDEO : CS_AUDIO, type, streamId, tsMs, body, off, len);
    }

    /** Ends the stream politely if the connection is still up, then closes it. Safe to call from any thread. */
    @Override
    public void close() {
        if (closed) return;
        try {
            // only if the socket isn't stuck in a write; a held lock means it is
            if (writeLock.tryLock(300, TimeUnit.MILLISECONDS)) {
                try {
                    command(CS_CMD, 0, "FCUnpublish", null, target.key);
                    command(CS_CMD, 0, "deleteStream", null, (double) streamId);
                } finally {
                    writeLock.unlock();
                }
            }
        } catch (Exception ignored) { }
        closeQuietly();
    }

    /** Drops the socket immediately (unblocks a stuck write). */
    public void abort() { closeQuietly(); }

    private void closeQuietly() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- handshake and commands

    private void handshake() throws IOException {
        byte[] c1 = new byte[1536];
        new SecureRandom().nextBytes(c1);
        int now = (int) (System.currentTimeMillis() / 1000);
        c1[0] = (byte) (now >>> 24); c1[1] = (byte) (now >>> 16); c1[2] = (byte) (now >>> 8); c1[3] = (byte) now;
        c1[4] = c1[5] = c1[6] = c1[7] = 0;
        out.write(3);
        out.write(c1);
        out.flush();
        int ver = in.readUnsignedByte();
        if (ver != 3) throw new IOException("Not an RTMP server (version " + ver + ")");
        byte[] s1 = new byte[1536];
        in.readFully(s1);
        out.write(s1);                  // C2 echoes S1
        out.flush();
        in.readFully(new byte[1536]);  // S2
    }

    private void connectApp() throws IOException {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("app", target.app);
        o.put("type", "nonprivate");
        o.put("flashVer", "FMLE/3.0 (compatible; FMSc/1.0)");
        o.put("swfUrl", target.tcUrl);
        o.put("tcUrl", target.tcUrl);
        double id = command(CS_CMD, 0, "connect", o);
        List<Object> r = awaitResult(id, "connect");
        String err = statusError(r);
        if (err != null) throw new RejectedException(err);

        byte[] cs = {0, 0, (byte) (OUT_CHUNK >>> 8), (byte) OUT_CHUNK};
        writeMessage(CS_CONTROL, TYPE_SET_CHUNK, 0, 0, cs, 0, 4);
        outChunk = OUT_CHUNK;
    }

    private void publish() throws IOException {
        command(CS_CMD, 0, "releaseStream", null, target.key);
        command(CS_CMD, 0, "FCPublish", null, target.key);
        double id = command(CS_CMD, 0, "createStream", (Object) null);
        List<Object> r = awaitResult(id, "createStream");
        Object sid = r.size() > 3 ? r.get(3) : null;
        if (!(sid instanceof Double)) throw new RejectedException("The server didn't create a stream" + describe(r));
        streamId = ((Double) sid).intValue();

        command(CS_CMD, streamId, "publish", null, target.key, "live");
        // wait for onStatus NetStream.Publish.Start (or an error)
        long deadline = System.currentTimeMillis() + Math.max(5000, socket.getSoTimeout());
        while (System.currentTimeMillis() < deadline) {
            Msg m = readMessage();
            if (m.type != TYPE_CMD) continue;
            List<Object> v = Amf0.readAll(m.data);
            if (v.isEmpty() || !"onStatus".equals(v.get(0))) {
                if (!v.isEmpty() && "_error".equals(v.get(0))) throw new RejectedException("The server refused to publish" + describe(v));
                continue;
            }
            Map<?, ?> info = info(v);
            String code = info == null ? "" : String.valueOf(info.get("code"));
            if ("NetStream.Publish.Start".equals(code)) return;
            if (info != null && "error".equals(info.get("level"))) throw new RejectedException(friendly(code, info.get("description")));
        }
        throw new IOException("The server didn't confirm the stream started.");
    }

    /** Sends a command; returns its transaction id. */
    private double command(int cs, int sid, String name, Object... args) throws IOException {
        Amf0.Writer w = new Amf0.Writer().str(name).num(++txn);
        for (Object a : args) w.any(a);
        byte[] d = w.bytes();
        writeMessage(cs, TYPE_CMD, sid, 0, d, 0, d.length);
        return txn;
    }

    private List<Object> awaitResult(double id, String what) throws IOException {
        while (true) {
            Msg m = readMessage();
            if (m.type != TYPE_CMD) continue;
            List<Object> v = Amf0.readAll(m.data);
            if (v.size() < 2) continue;
            Object name = v.get(0), tid = v.get(1);
            if (!(tid instanceof Double) || (Double) tid != id) continue;
            if ("_result".equals(name)) return v;
            if ("_error".equals(name)) {
                Map<?, ?> info = info(v);
                throw new RejectedException(info != null ? friendly(String.valueOf(info.get("code")), info.get("description"))
                        : "The server refused " + what + describe(v));
            }
        }
    }

    private static Map<?, ?> info(List<Object> v) {
        for (int i = v.size() - 1; i >= 2; i--) if (v.get(i) instanceof Map) return (Map<?, ?>) v.get(i);
        return null;
    }

    /** An onStatus/_error that means the stream is over, as a message for the user; null otherwise. */
    private static String statusError(List<Object> v) {
        if (v.isEmpty()) return null;
        Object name = v.get(0);
        if (!"onStatus".equals(name) && !"_error".equals(name)) return null;
        Map<?, ?> info = info(v);
        if (info == null) return "_error".equals(name) ? "The server reported an error." : null;
        String code = String.valueOf(info.get("code"));
        boolean bad = "error".equals(info.get("level")) || code.endsWith(".Failed") || code.endsWith(".Rejected")
                || "NetStream.Unpublish.Success".equals(code) || "NetConnection.Connect.Closed".equals(code);
        return bad ? friendly(code, info.get("description")) : null;
    }

    private static String friendly(String code, Object desc) {
        String d = desc == null ? "" : String.valueOf(desc).trim();
        String base;
        switch (code) {
            case "NetStream.Publish.BadName": base = "The server says this stream key is already in use or not allowed."; break;
            case "NetConnection.Connect.Rejected": base = "The server rejected the connection. Check the server URL and stream key."; break;
            case "NetStream.Publish.Denied": base = "The server denied publishing. Check the stream key, or get a new one."; break;
            case "NetStream.Publish.Unauthorized":
            case "NetConnection.Connect.Unauthorized": base = "The server didn't accept the stream key. Copy a fresh one and try again."; break;
            case "NetStream.Unpublish.Success": base = "The server ended the stream."; break;
            default: base = "The server said: " + code;
        }
        return d.isEmpty() || base.contains(d) ? base : base + " (" + d + ")";
    }

    private static String describe(List<Object> v) {
        Map<?, ?> info = info(v);
        return info == null ? "." : " (" + info.get("code") + ").";
    }

    // ---------------------------------------------------------------- chunk layer

    private void writeMessage(int cs, int type, int sid, long ts, byte[] d, int off, int len) throws IOException {
        if (closed) throw new IOException("closed");
        writeLock.lock();
        try {
            boolean ext = ts >= 0xFFFFFF;
            int chunks = Math.max(1, (len + outChunk - 1) / outChunk);
            int need = 12 + (ext ? 4 : 0) + len + (chunks - 1) * (1 + (ext ? 4 : 0));
            if (wbuf.length < need) wbuf = new byte[Math.max(need, wbuf.length * 2)];
            byte[] b = wbuf;
            int p = 0;
            int t24 = ext ? 0xFFFFFF : (int) ts;
            b[p++] = (byte) (cs & 0x3F);                                  // fmt 0
            b[p++] = (byte) (t24 >>> 16); b[p++] = (byte) (t24 >>> 8); b[p++] = (byte) t24;
            b[p++] = (byte) (len >>> 16); b[p++] = (byte) (len >>> 8); b[p++] = (byte) len;
            b[p++] = (byte) type;
            b[p++] = (byte) sid; b[p++] = (byte) (sid >>> 8); b[p++] = (byte) (sid >>> 16); b[p++] = (byte) (sid >>> 24);
            if (ext) p = put32(b, p, (int) ts);
            int sent = 0;
            while (true) {
                int n = Math.min(outChunk, len - sent);
                System.arraycopy(d, off + sent, b, p, n);
                p += n; sent += n;
                if (sent >= len) break;
                b[p++] = (byte) (0xC0 | (cs & 0x3F));                     // fmt 3 continuation
                if (ext) p = put32(b, p, (int) ts);
            }
            writeStartedAt = System.nanoTime();
            try {
                out.write(b, 0, p);
                out.flush();
            } finally {
                writeStartedAt = 0;
            }
            bytesOut += p;
        } finally {
            writeLock.unlock();
        }
    }

    private static int put32(byte[] b, int p, int v) {
        b[p++] = (byte) (v >>> 24); b[p++] = (byte) (v >>> 16); b[p++] = (byte) (v >>> 8); b[p++] = (byte) v;
        return p;
    }

    private static final class InChunk {
        long ts, delta;
        int len, type, sid, filled;
        boolean ext;
        byte[] data;
    }

    private static final class Msg {
        final int type; final byte[] data;
        Msg(int type, byte[] data) { this.type = type; this.data = data; }
    }

    /** Reads chunks until one whole message has arrived, handling protocol control messages on the way. */
    private Msg readMessage() throws IOException {
        while (true) {
            int b0 = in.readUnsignedByte();
            int fmt = b0 >>> 6, cs = b0 & 0x3F;
            if (cs == 0) cs = 64 + in.readUnsignedByte();
            else if (cs == 1) cs = 64 + in.readUnsignedByte() + in.readUnsignedByte() * 256;
            InChunk st = inStreams.get(cs);
            if (st == null) { st = new InChunk(); inStreams.put(cs, st); }
            long ts = 0;
            if (fmt <= 2) ts = u24();
            if (fmt <= 1) { st.len = u24(); st.type = in.readUnsignedByte(); }
            if (fmt == 0) st.sid = Integer.reverseBytes(in.readInt());
            if (fmt <= 2) {
                st.ext = ts == 0xFFFFFF;
                if (st.ext) ts = in.readInt() & 0xFFFFFFFFL;
            } else if (st.ext) {
                in.readInt();
            }
            if (st.data == null) {
                if (fmt == 0) st.ts = ts;
                else if (fmt <= 2) { st.delta = ts; st.ts += ts; }
                else st.ts += st.delta;
                if (st.len > 16 * 1024 * 1024) throw new IOException("Oversized RTMP message");
                st.data = new byte[st.len];
                st.filled = 0;
            }
            int n = Math.min(inChunk, st.len - st.filled);
            in.readFully(st.data, st.filled, n);
            st.filled += n;
            maybeAck();
            if (st.filled < st.len) continue;
            byte[] d = st.data;
            st.data = null;
            if (handleControl(st.type, d)) continue;
            return new Msg(st.type, d);
        }
    }

    /** Protocol control and user control messages. Returns true when consumed. */
    private boolean handleControl(int type, byte[] d) throws IOException {
        switch (type) {
            case TYPE_SET_CHUNK:
                if (d.length >= 4) inChunk = Math.max(1, ((d[0] & 0x7F) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF));
                return true;
            case TYPE_ABORT:
                if (d.length >= 4) { InChunk st = inStreams.get(((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF)); if (st != null) st.data = null; }
                return true;
            case TYPE_ACK:
                return true;
            case TYPE_WINDOW:
                if (d.length >= 4) windowAck = ((d[0] & 0xFFL) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
                return true;
            case TYPE_PEER_BW:
                if (d.length >= 4) writeMessage(CS_CONTROL, TYPE_WINDOW, 0, 0, new byte[]{d[0], d[1], d[2], d[3]}, 0, 4);
                return true;
            case TYPE_USER:
                if (d.length >= 6 && d[0] == 0 && d[1] == 6) {               // ping request -> ping response
                    byte[] r = {0, 7, d[2], d[3], d[4], d[5]};
                    writeMessage(CS_CONTROL, TYPE_USER, 0, 0, r, 0, r.length);
                }
                return true;
            default:
                return false;
        }
    }

    private void maybeAck() throws IOException {
        if (windowAck > 0 && bytesIn - lastAck >= windowAck / 2) {
            lastAck = bytesIn;
            int v = (int) bytesIn;
            writeMessage(CS_CONTROL, TYPE_ACK, 0, 0, new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}, 0, 4);
        }
    }

    private int u24() throws IOException {
        return (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
    }

    private final class CountingInput extends InputStream {
        private final InputStream src;
        CountingInput(InputStream src) { this.src = src; }
        @Override public int read() throws IOException { int b = src.read(); if (b >= 0) bytesIn++; return b; }
        @Override public int read(byte[] b, int o, int n) throws IOException { int r = src.read(b, o, n); if (r > 0) bytesIn += r; return r; }
    }
}
