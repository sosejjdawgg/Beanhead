/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.live;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one live stream from "Go live" to "End stream": takes already-encoded H.264 (from the page) and
 * AAC (from the microphone), queues them, and publishes over RTMP on its own thread. Reconnects with
 * backoff when the network drops, and when the upload can't keep up it drops video to the next key
 * frame and asks the encoder for a lower bitrate (raising it again once things are calm).
 *
 * Timestamps are milliseconds on one clock that starts when the stream starts; each connection is
 * rebased so it begins at 0 on its first key frame. Pure Java so it can be tested off the phone.
 */
public final class LiveSession {

    public interface Listener {
        /** state: connecting, live, reconnecting, stopped or error (message says why). */
        void onState(String state, String message);
        void onStats(int kbps, int targetKbps, int fps, int queueMs, int dropped, long liveSecs);
        /** The next video frame should be a key frame (just connected, or video was dropped). */
        void onNeedKeyFrame();
        /** Encode at this video bitrate from now on. */
        void onBitrate(int kbps);
    }

    public static final class Config {
        public RtmpPublisher.Target target;
        public int width, height, fps, videoKbps;
        public int audioKbps, sampleRate, channels;
        /** Lowest bitrate the congestion control may ask for. */
        public int minKbps() { return Math.max(400, videoKbps * 35 / 100); }
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final long GIVE_UP_MS = 120_000;      // keep trying to reconnect for this long
    private static final long STALL_MS = 8_000;          // a write blocked this long means the network is gone

    private static final class Packet {
        final int type; final long ts; final byte[] body; final boolean key, config;
        Packet(int type, long ts, byte[] body, boolean key, boolean config) {
            this.type = type; this.ts = ts; this.body = body; this.key = key; this.config = config;
        }
    }
    private static final Packet WAKE = new Packet(0, 0, new byte[0], false, false);

    private final Config cfg;
    private final Listener listener;
    private final LinkedBlockingDeque<Packet> queue = new LinkedBlockingDeque<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-ticker"); t.setDaemon(true); return t;
    });
    private Thread sender;

    private volatile boolean stopping;
    private volatile boolean accepting;                   // connected: frames are queued, not dropped
    private volatile boolean dropToKey = true;            // skip video until the next key frame
    private volatile RtmpPublisher pub;
    private volatile String closedReason;
    private volatile byte[] avcSeq, aacSeq;               // FLV sequence-header bodies, resent on reconnect
    private volatile int curKbps;
    private volatile long liveSince;                      // first successful publish, for the timer
    private volatile long lastVideoTs;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong videoSent = new AtomicLong();
    private long lastCongestion, lastBytes, lastFrames;
    private int calmSecs;

    public LiveSession(Config cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
        this.curKbps = cfg.videoKbps;
    }

    public void start() {
        sender = new Thread(this::run, "live-sender");
        sender.start();
        ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    /** Ends the stream. Returns at once; the sender thread says goodbye to the server and reports "stopped". */
    public void stop() {
        if (stopping) return;
        stopping = true;
        accepting = false;
        queue.offer(WAKE);
        // if the socket is stuck in a write the polite goodbye can't happen: drop it after a moment
        try {
            ticker.schedule(() -> { RtmpPublisher p = pub; if (p != null && sender.isAlive()) p.abort(); }, 1500, TimeUnit.MILLISECONDS);
        } catch (RuntimeException alreadyFinished) { /* the sender has already ended */ }
    }

    public boolean isStopping() { return stopping; }

    // ---------------------------------------------------------------- input (any thread)

    /** The encoder's avcC record (WebCodecs decoderConfig.description). */
    public void videoConfig(byte[] avcC) {
        byte[] body = new byte[5 + avcC.length];
        body[0] = 0x17;                                   // key frame, AVC
        body[1] = 0;                                      // sequence header
        System.arraycopy(avcC, 0, body, 5, avcC.length);
        byte[] old = avcSeq;
        if (old != null && Arrays.equals(old, body)) return;
        avcSeq = body;
        if (old != null && accepting) {                   // encoder was rebuilt mid-stream: send in order
            enqueue(new Packet(RtmpPublisher.TYPE_VIDEO, lastVideoTs, body, false, true));
            dropToKey = true;
        }
    }

    /** One encoded frame, AVCC (length-prefixed NAL units). */
    public void video(byte[] avcc, long tsMs, boolean key) {
        if (!accepting) return;
        if (dropToKey) {
            if (!key) { dropped.incrementAndGet(); return; }
            dropToKey = false;
        }
        if (queuedBytes.get() > budgetBytes()) {
            congestion();
            if (!key) { dropped.incrementAndGet(); return; }
            dropToKey = false;
        }
        byte[] body = new byte[5 + avcc.length];
        body[0] = (byte) (key ? 0x17 : 0x27);
        body[1] = 1;                                      // NAL units; composition time 0 (no B-frames)
        System.arraycopy(avcc, 0, body, 5, avcc.length);
        lastVideoTs = tsMs;
        enqueue(new Packet(RtmpPublisher.TYPE_VIDEO, tsMs, body, key, false));
    }

    /** The AAC AudioSpecificConfig (MediaCodec csd-0). */
    public void audioConfig(byte[] asc) {
        byte[] body = new byte[2 + asc.length];
        body[0] = (byte) 0xAF;                            // AAC, 44 kHz flag, 16-bit, stereo (fixed for AAC)
        body[1] = 0;
        System.arraycopy(asc, 0, body, 2, asc.length);
        aacSeq = body;
    }

    /** One raw AAC frame. */
    public void audio(byte[] aac, int off, int len, long tsMs) {
        if (!accepting) return;
        byte[] body = new byte[2 + len];
        body[0] = (byte) 0xAF;
        body[1] = 1;
        System.arraycopy(aac, off, body, 2, len);
        enqueue(new Packet(RtmpPublisher.TYPE_AUDIO, tsMs, body, false, false));
    }

    private void enqueue(Packet p) {
        queuedBytes.addAndGet(p.body.length);
        queue.offer(p);
    }

    /** About two seconds of upload at the current bitrate. */
    private long budgetBytes() {
        return Math.max(256 * 1024, (long) (curKbps + cfg.audioKbps) * 125 * 2);
    }

    /** The upload is falling behind: throw away queued video, restart from a key frame, lower the bitrate. */
    private synchronized void congestion() {
        long now = System.nanoTime();
        if (now - lastCongestion < 2_000_000_000L) return;
        lastCongestion = now;
        for (Packet k : queue) {
            if (k.type == RtmpPublisher.TYPE_VIDEO && !k.config && queue.removeFirstOccurrence(k)) {
                queuedBytes.addAndGet(-k.body.length);
                dropped.incrementAndGet();
            }
        }
        dropToKey = true;
        listener.onNeedKeyFrame();
        int nk = Math.max(cfg.minKbps(), curKbps * 7 / 10);
        if (nk < curKbps) { curKbps = nk; listener.onBitrate(nk); }
        calmSecs = 0;
    }

    // ---------------------------------------------------------------- sender thread

    private void run() {
        int attempt = 0;
        long failingSince = 0;
        boolean wasLive = false;
        String why = null;
        while (!stopping) {
            listener.onState(wasLive ? "reconnecting" : "connecting", why);
            RtmpPublisher p;
            try {
                p = RtmpPublisher.open(cfg.target, CONNECT_TIMEOUT_MS);
            } catch (RtmpPublisher.RejectedException e) {
                finish("error", e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                if (stopping) break;
                long now = System.currentTimeMillis();
                if (failingSince == 0) failingSince = now;
                // certificate problems won't fix themselves; other first-time failures get three tries
                boolean hopeless = !wasLive && (attempt >= 2 || e instanceof javax.net.ssl.SSLHandshakeException);
                if (hopeless || now - failingSince > GIVE_UP_MS) {
                    finish("error", wasLive ? "Lost the connection to the server and couldn't get it back." : connectError(e));
                    return;
                }
                pause(Math.min(10_000, 1000L << Math.min(attempt, 4)));
                attempt++;
                continue;
            }
            if (stopping) { p.close(); break; }
            pub = p;
            closedReason = null;
            try {
                p.sendMetadata(cfg.width, cfg.height, cfg.fps, cfg.videoKbps, cfg.audioKbps, cfg.sampleRate, cfg.channels);
                p.startReader(reason -> { closedReason = reason; queue.offer(WAKE); });
                attempt = 0;
                failingSince = 0;
                wasLive = true;
                if (liveSince == 0) liveSince = System.currentTimeMillis();
                queue.clear();
                queuedBytes.set(0);
                dropToKey = true;
                accepting = true;
                listener.onNeedKeyFrame();
                listener.onState("live", null);
                pump(p);
            } catch (IOException e) {
                accepting = false;
                p.abort();
                if (stopping) break;
                why = closedReason != null ? closedReason : e.getMessage();
                failingSince = System.currentTimeMillis();
                pause(1000);
            }
        }
        RtmpPublisher p = pub;
        if (p != null) p.close();
        finish("stopped", null);
    }

    /** Sends queued packets until stopped or the connection fails. */
    private void pump(RtmpPublisher p) throws IOException {
        boolean started = false, aacSent = false;
        long base = 0, lastV = 0, lastA = 0;
        while (!stopping) {
            Packet k;
            try { k = queue.poll(250, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { return; }
            if (p.isClosed()) throw new IOException(closedReason != null ? closedReason : "Connection lost.");
            if (k == null || k == WAKE) continue;
            queuedBytes.addAndGet(-k.body.length);
            if (k.type == RtmpPublisher.TYPE_VIDEO) {
                if (k.config) {
                    if (started) p.sendTag(RtmpPublisher.TYPE_VIDEO, lastV, k.body, 0, k.body.length);
                    continue;
                }
                if (!started) {
                    byte[] seq = avcSeq;
                    if (!k.key || seq == null) continue;
                    base = k.ts;
                    p.sendTag(RtmpPublisher.TYPE_VIDEO, 0, seq, 0, seq.length);
                    started = true;
                }
                long ts = Math.max(lastV, k.ts - base);
                p.sendTag(RtmpPublisher.TYPE_VIDEO, ts, k.body, 0, k.body.length);
                lastV = ts;
                videoSent.incrementAndGet();
            } else {
                if (!started) continue;                   // sound starts with the first picture
                long ts = k.ts - base;
                if (ts < 0) continue;
                if (!aacSent) {
                    byte[] seq = aacSeq;
                    if (seq == null) continue;
                    p.sendTag(RtmpPublisher.TYPE_AUDIO, ts, seq, 0, seq.length);
                    aacSent = true;
                }
                ts = Math.max(lastA, ts);
                p.sendTag(RtmpPublisher.TYPE_AUDIO, ts, k.body, 0, k.body.length);
                lastA = ts;
            }
        }
    }

    private void pause(long ms) {
        try {
            Packet k = queue.poll(ms, TimeUnit.MILLISECONDS);
            if (k != null && k != WAKE) queuedBytes.addAndGet(-k.body.length);
        } catch (InterruptedException ignored) { }
    }

    private void finish(String state, String msg) {
        stopping = true;
        accepting = false;
        ticker.shutdown();
        queue.clear();
        listener.onState(state, msg);
    }

    private static String connectError(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "Couldn't find the server. Check the server URL and your internet connection.";
        if (e instanceof java.net.SocketTimeoutException) return "The server didn't answer. Check the server URL, and that the server is running.";
        if (e instanceof java.net.ConnectException) return "The server refused the connection. Check the server URL and port, and that the server is running.";
        if (e instanceof javax.net.ssl.SSLHandshakeException) return "The server's security certificate isn't valid for this address. Check the server URL.";
        if (e instanceof javax.net.ssl.SSLException) return "Secure connection failed (" + m + ").";
        return "Couldn't connect: " + m;
    }

    // ---------------------------------------------------------------- once a second

    private void tick() {
        RtmpPublisher p = pub;
        if (p != null && !p.isClosed() && p.writeBlockedMs() > STALL_MS) {
            closedReason = "The network stalled.";
            p.abort();
        }
        long bytes = p == null ? lastBytes : p.bytesOut();
        if (bytes < lastBytes) lastBytes = 0;             // new connection
        int kbps = (int) ((bytes - lastBytes) * 8 / 1000);
        lastBytes = bytes;
        long frames = videoSent.get();
        int fps = (int) (frames - lastFrames);
        lastFrames = frames;
        int rate = curKbps;
        int queueMs = (int) (queuedBytes.get() * 8 / Math.max(1, rate + cfg.audioKbps));
        if (accepting) {
            calmSecs = queueMs < 300 ? calmSecs + 1 : 0;
            if (calmSecs >= 15 && rate < cfg.videoKbps) {
                int nk = Math.min(cfg.videoKbps, rate * 115 / 100 + 50);
                curKbps = nk;
                calmSecs = 0;
                listener.onBitrate(nk);
            }
        }
        long secs = liveSince == 0 ? 0 : (System.currentTimeMillis() - liveSince) / 1000;
        listener.onStats(kbps, curKbps, fps, queueMs, (int) dropped.get(), secs);
    }
}
