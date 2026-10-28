/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.audio;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * "Phone sound": everything other apps play (music, videos, games), captured with Android's playback capture
 * (Android 10+). It needs the user's consent each time (the system's screen-recording prompt; no picture is
 * captured) and a foreground service while it runs (PhoneAudioService). Peanut Cam's own sound is left out,
 * because the page mixes its videos in itself. Apps can refuse to be captured (calls, some DRM players).
 *
 * The page's "PeanutAudio" bridge: start() asks for consent, read() hands over what was captured since the last
 * call (48 kHz stereo 16-bit little-endian PCM, base64), stop() ends it. State changes arrive as
 * window.onPhoneAudio({state: "on" | "off" | "denied" | "error", msg}). A live stream takes the sound directly
 * through setLiveSink, so it never crosses the bridge twice.
 */
public final class PhoneAudio {

    public static final int REQ_CONSENT = 41, REQ_MIC = 42;
    static final int RATE = 48000, CHANNELS = 2;
    private static final int BLOCK = RATE / 50 * CHANNELS;          // 20 ms of samples

    /** Something that mixes the phone sound in (the live stream's audio encoder). */
    public interface Mix { void mixIn(short[] pcm, int n); }
    private static volatile Mix liveSink;
    public static void setLiveSink(Mix m) { liveSink = m; }

    static volatile PhoneAudio instance;
    private final Activity act;
    private final WebView web;
    private volatile String state = "off";
    private MediaProjection projection;
    private volatile AudioRecord rec;
    private Thread thread;

    // what the page hasn't read yet: one second of 16-bit stereo
    private final byte[] ring = new byte[RATE * CHANNELS * 2];
    private int head, count;

    public PhoneAudio(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        instance = this;
    }

    @JavascriptInterface
    public boolean supported() { return Build.VERSION.SDK_INT >= 29; }

    @JavascriptInterface
    public String state() { return state; }

    /** Asks for the microphone permission if needed (playback capture requires it), then the capture consent. */
    @JavascriptInterface
    public synchronized void start() {
        if (Build.VERSION.SDK_INT < 29) { emit("error", "Phone sound needs Android 10 or newer."); return; }
        if ("on".equals(state) || "asking".equals(state)) { emit(state, null); return; }
        state = "asking";
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread(() -> act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
            return;
        }
        askConsent();
    }

    /** From MainActivity.onRequestPermissionsResult. */
    public void onPermissionsResult() {
        if (!"asking".equals(state)) return;
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = "off"; emit("denied", "Phone sound needs the microphone permission (Android requires it to capture sound).");
            return;
        }
        askConsent();
    }

    private void askConsent() {
        act.runOnUiThread(() -> {
            try {
                MediaProjectionManager m = act.getSystemService(MediaProjectionManager.class);
                Intent i = Build.VERSION.SDK_INT >= 34
                        ? m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())  // whole phone, so every app's sound
                        : m.createScreenCaptureIntent();
                act.startActivityForResult(i, REQ_CONSENT);
            } catch (Exception e) {
                state = "off"; emit("error", "Couldn't ask to capture phone sound: " + e.getMessage());
            }
        });
    }

    /** From MainActivity.onActivityResult: the consent answer. The service has to be running before the projection is taken. */
    public void onConsent(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) { state = "off"; emit("denied", null); return; }
        PhoneAudioService.start(act, resultCode, data);
    }

    /** From the service, once it's in the foreground (only reached on Android 10+, see start()). */
    @TargetApi(29)
    synchronized void begin(Context ctx, int resultCode, Intent data) {
        try {
            MediaProjectionManager m = ctx.getSystemService(MediaProjectionManager.class);
            projection = m.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("no projection");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stop(); }                 // ended from the system (notification, lock)
            }, new Handler(Looper.getMainLooper()));
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())                           // our own videos are mixed in by the page
                    .build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                    .setBufferSizeInBytes(Math.max(min * 2, BLOCK * 2 * 8))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("capture didn't start");
            rec.startRecording();
            synchronized (ring) { head = count = 0; }
            final AudioRecord r = rec;
            thread = new Thread(() -> pump(r), "phone-audio");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
            state = "on"; emit("on", null);
        } catch (SecurityException e) {
            release(); emit("denied", "Phone sound needs the microphone permission.");
        } catch (Exception e) {
            release(); emit("error", "Couldn't capture phone sound: " + e.getMessage());
        }
    }

    private void pump(AudioRecord r) {
        short[] s = new short[BLOCK];
        byte[] b = new byte[BLOCK * 2];
        while (r == rec && !Thread.currentThread().isInterrupted()) {
            int n = r.read(s, 0, BLOCK);
            if (n <= 0) { if (n < 0) break; continue; }
            Mix m = liveSink;
            if (m != null) m.mixIn(s, n);
            for (int i = 0; i < n; i++) { b[i * 2] = (byte) s[i]; b[i * 2 + 1] = (byte) (s[i] >> 8); }
            synchronized (ring) {
                for (int i = 0; i < n * 2; i++) {
                    if (count == ring.length) { head = (head + 1) % ring.length; count--; }   // the page isn't reading: keep the newest
                    ring[(head + count) % ring.length] = b[i];
                    count++;
                }
            }
        }
    }

    /** What was captured since the last call, base64 ("" when there's nothing). */
    @JavascriptInterface
    public String read() {
        byte[] out;
        synchronized (ring) {
            int n = count & ~3;                                            // whole stereo frames
            if (n == 0) return "";
            out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = ring[(head + i) % ring.length];
            head = (head + n) % ring.length; count -= n;
        }
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    @JavascriptInterface
    public synchronized void stop() {
        boolean was = "on".equals(state) || rec != null;
        release();
        if (was) emit("off", null);
    }

    private synchronized void release() {
        state = "off";
        AudioRecord r = rec; rec = null;
        if (thread != null) thread.interrupt();
        thread = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) { } r.release(); }
        MediaProjection p = projection; projection = null;
        if (p != null) try { p.stop(); } catch (Exception ignored) { }
        PhoneAudioService.stop(act);
    }

    private void emit(String st, String msg) {
        String js = "window.onPhoneAudio&&window.onPhoneAudio({state:" + q(st) + (msg != null ? ",msg:" + q(msg) : "") + "})";
        act.runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static String q(String s) { return org.json.JSONObject.quote(s); }
}
