/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.live;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The stream's sound: microphone -> AAC-LC (hardware MediaCodec), 48 kHz stereo. There is always an audio
 * track, because some ingest servers won't start a stream without one: when sound is off, or the mic is
 * blocked or taken by another app, it encodes silence on the same clock. The mic is only opened while
 * sound is on, so Android's mic indicator matches the switch.
 */
final class MicEncoder {

    interface Sink {
        void audioConfig(byte[] asc);
        void audio(byte[] aac, int off, int len, long tsMs);
        /** The mic couldn't be opened or stopped working; the stream carries on silent. */
        void micUnavailable();
    }

    static final int RATE = 48000, CHANNELS = 2, KBPS = 128;
    private static final int FRAME = 1024;                // samples per AAC frame

    private final long t0Nanos;
    private final Sink sink;
    private volatile boolean muted;
    private volatile boolean micAllowed;
    private volatile int offsetMs;
    private volatile boolean running;
    private Thread thread;

    /** @param t0Nanos the stream clock's zero ({@link System#nanoTime()}), shared with the video timestamps */
    MicEncoder(long t0Nanos, Sink sink) {
        this.t0Nanos = t0Nanos;
        this.sink = sink;
    }

    void setMuted(boolean m) { muted = m; }
    /** Whether RECORD_AUDIO is granted; the mic opens when this is true and sound is on. */
    void setMicAllowed(boolean a) { micAllowed = a; }
    /** Delays the sound against the picture (the picture is a little behind, after face tracking). */
    void setOffsetMs(int ms) { offsetMs = Math.max(0, Math.min(1000, ms)); }

    // ---- other sound mixed in, each 48 kHz stereo interleaved through its own small jitter buffer: the page's
    // video sound (pcm() over the bridge) and the phone sound (PhoneAudio, straight from its capture thread).
    // Each source runs on a slightly different clock than the mic, so a buffer trims itself when it grows and
    // waits to refill when it runs dry, instead of drifting.
    private static final class Ring {
        private final short[] buf = new short[RATE * CHANNELS];                     // one second
        private int head, count;
        private boolean primed;
        private static final int START = RATE * CHANNELS * 60 / 1000;              // 60 ms before mixing starts
        private static final int MAX = RATE * CHANNELS * 300 / 1000;               // more than this: drop the oldest down to 100 ms

        synchronized void put(short[] pcm, int n) {
            for (int i = 0; i < n; i++) {
                if (count == buf.length) { head = (head + 1) % buf.length; count--; }
                buf[(head + count) % buf.length] = pcm[i];
                count++;
            }
        }

        /** Adds up to n samples into dst; returns how many were real. */
        synchronized int addTo(int[] dst, int n) {
            if (count > MAX) { int drop = (count - RATE * CHANNELS / 10) & ~1; head = (head + drop) % buf.length; count -= drop; }
            if (!primed) { if (count < START) return 0; primed = true; }
            int k = Math.min(n, count);
            for (int i = 0; i < k; i++) dst[i] += buf[(head + i) % buf.length];
            head = (head + k) % buf.length; count -= k;
            if (k < n) primed = false;                                             // ran dry: wait to refill
            return k;
        }
    }
    private final Ring videoMix = new Ring(), phoneMix = new Ring();

    void mixIn(short[] pcm, int n) { videoMix.put(pcm, n); }
    /** The phone sound (other apps), from {@link com.beanhead.peanutcam.audio.PhoneAudio}. */
    public void mixPhone(short[] pcm, int n) { phoneMix.put(pcm, n); }

    void start() {
        running = true;
        thread = new Thread(this::run, "live-audio");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void run() {
        MediaCodec codec = null;
        AudioRecord rec = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, KBPS * 1000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * CHANNELS * 2 * 2);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            short[] mono = new short[FRAME];
            int[] mix = new int[FRAME * CHANNELS];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] out = new byte[8192];
            long startNs = System.nanoTime(), samples = 0, nextMicTry = 0;
            boolean warned = false;

            while (running) {
                boolean wantMic = !muted && micAllowed;
                if (wantMic && rec == null && System.nanoTime() >= nextMicTry) {
                    rec = openMic();
                    if (rec == null) {
                        nextMicTry = System.nanoTime() + 3_000_000_000L;
                        if (!warned) { warned = true; sink.micUnavailable(); }
                    } else {
                        warned = false;
                        // the mic starts "now": keep the sample clock on wall time
                        samples = Math.max(samples, (System.nanoTime() - startNs) * RATE / 1_000_000_000L);
                    }
                } else if (!wantMic && rec != null) {
                    closeMic(rec);
                    rec = null;
                }

                int n = FRAME;
                if (rec != null) {
                    n = rec.read(mono, 0, FRAME);
                    if (n <= 0) {                         // taken by a call or another app
                        closeMic(rec);
                        rec = null;
                        nextMicTry = System.nanoTime() + 3_000_000_000L;
                        if (!warned) { warned = true; sink.micUnavailable(); }
                        continue;
                    }
                    if (muted) Arrays.fill(mono, 0, n, (short) 0);
                } else {
                    // silence, paced to real time
                    long due = startNs + samples * 1_000_000_000L / RATE;
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); }
                        catch (InterruptedException e) { break; }
                    }
                    Arrays.fill(mono, (short) 0);
                }

                int ix = codec.dequeueInputBuffer(20_000);
                if (ix >= 0) {
                    ByteBuffer in = codec.getInputBuffer(ix);
                    in.clear();
                    in.order(ByteOrder.LITTLE_ENDIAN);
                    Arrays.fill(mix, 0);
                    videoMix.addTo(mix, n * CHANNELS);
                    phoneMix.addTo(mix, n * CHANNELS);
                    for (int i = 0; i < n; i++) {
                        int l = mono[i] + mix[i * 2], r = mono[i] + mix[i * 2 + 1];
                        in.putShort((short) Math.max(-32768, Math.min(32767, l)));
                        in.putShort((short) Math.max(-32768, Math.min(32767, r)));
                    }
                    long ptsUs = (startNs - t0Nanos) / 1000 + samples * 1_000_000L / RATE;
                    codec.queueInputBuffer(ix, 0, n * CHANNELS * 2, Math.max(0, ptsUs), 0);
                }
                samples += n;

                while (true) {
                    int ox = codec.dequeueOutputBuffer(info, 0);
                    if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ByteBuffer csd = codec.getOutputFormat().getByteBuffer("csd-0");
                        if (csd != null) { byte[] asc = new byte[csd.remaining()]; csd.get(asc); sink.audioConfig(asc); }
                        continue;
                    }
                    if (ox < 0) break;
                    ByteBuffer ob = codec.getOutputBuffer(ox);
                    if (ob != null && info.size > 0) {
                        if (out.length < info.size) out = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(out, 0, info.size);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) sink.audioConfig(Arrays.copyOf(out, info.size));
                        else sink.audio(out, 0, info.size, info.presentationTimeUs / 1000 + offsetMs);
                    }
                    codec.releaseOutputBuffer(ox, false);
                }
            }
        } catch (Exception e) {
            if (running) sink.micUnavailable();
        } finally {
            if (rec != null) closeMic(rec);
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
        }
    }

    private static AudioRecord openMic() {
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, FRAME * 2 * 8));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic not initialised");
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("mic busy");
            return r;
        } catch (SecurityException e) {                   // the mic permission is missing
            return null;
        } catch (Exception e) {
            if (r != null) r.release();
            return null;
        }
    }

    private static void closeMic(AudioRecord r) {
        try { r.stop(); } catch (Exception ignored) { }
        r.release();
    }
}
