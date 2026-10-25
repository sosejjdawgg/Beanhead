/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.live;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The page's "PeanutLive" bridge. The page encodes the scene to H.264 itself (WebCodecs, which uses the
 * phone's hardware encoder) and passes each compressed frame here; this adds the microphone and
 * publishes over RTMP. Status goes back to the page as window.onLive({type: ...}) events:
 * state (connecting, live, reconnecting, stopped, error), stats, key (send a key frame next),
 * bitrate (encode at this rate) and mic (the microphone isn't available).
 *
 * Everything streaming lives in this package; MainActivity only registers this object, forwards
 * permission results and calls {@link #release()}. Stream keys only pass through memory here.
 */
public final class LiveController {

    public static final int REQ_PERMISSIONS = 40;

    private final Activity act;
    private final WebView web;
    private volatile LiveSession session;   // read from the bridge thread without the lock
    private volatile MicEncoder mic;
    private LiveSession.Config pending;      // waiting for the permission dialog
    private boolean pendingSound;
    private int pendingOffset;
    private long t0;

    public LiveController(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    /**
     * Starts streaming. Returns "" when it's under way (progress arrives as events), or a message to show
     * when the settings can't work. Fields: url, key, width, height, fps, kbps, sound, offsetMs.
     */
    @JavascriptInterface
    public synchronized String start(String json) {
        try {
            if (session != null || pending != null) return "Already live.";
            JSONObject o = new JSONObject(json);
            LiveSession.Config c = new LiveSession.Config();
            c.target = RtmpPublisher.Target.parse(o.optString("url"), o.optString("key"));
            c.width = clampEven(o.optInt("width", 720), 160, 1920);
            c.height = clampEven(o.optInt("height", 1280), 160, 1920);
            c.fps = Math.max(10, Math.min(60, o.optInt("fps", 30)));
            c.videoKbps = Math.max(300, Math.min(12000, o.optInt("kbps", 3500)));
            c.audioKbps = MicEncoder.KBPS;
            c.sampleRate = MicEncoder.RATE;
            c.channels = MicEncoder.CHANNELS;
            t0 = System.nanoTime();             // the page sets its clock's zero just before this call
            pending = c;
            pendingSound = o.optBoolean("sound", true);
            pendingOffset = o.optInt("offsetMs", 0);

            List<String> ask = new ArrayList<>();
            if (pendingSound && !has(Manifest.permission.RECORD_AUDIO)) ask.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) ask.add(Manifest.permission.POST_NOTIFICATIONS);
            if (ask.isEmpty()) begin();
            else act.runOnUiThread(() -> act.requestPermissions(ask.toArray(new String[0]), REQ_PERMISSIONS));
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            pending = null;
            return "Couldn't start: " + e.getMessage();
        }
    }

    /** From MainActivity.onRequestPermissionsResult. Streams either way; without the mic it's silent. */
    public synchronized void onPermissionsResult() {
        if (pending != null) { begin(); return; }
        if (mic == null) return;
        boolean ok = has(Manifest.permission.RECORD_AUDIO);
        mic.setMicAllowed(ok);
        if (ok) LiveService.start(act, true);  // so the mic keeps working in the background
    }

    private void begin() {
        LiveSession.Config c = pending;
        pending = null;
        final LiveSession[] self = new LiveSession[1];
        final LiveSession s = new LiveSession(c, new LiveSession.Listener() {
            @Override public void onState(String state, String message) {
                emit(ev("state").put2("state", state).put2("msg", message));
                if ("stopped".equals(state) || "error".equals(state)) ended(self[0]);
            }
            @Override public void onStats(int kbps, int target, int fps, int queueMs, int dropped, long secs) {
                emit(ev("stats").put2("kbps", kbps).put2("target", target).put2("fps", fps)
                        .put2("queueMs", queueMs).put2("dropped", dropped).put2("secs", secs));
            }
            @Override public void onNeedKeyFrame() { emit(ev("key")); }
            @Override public void onBitrate(int kbps) { emit(ev("bitrate").put2("kbps", kbps)); }
        });
        self[0] = s;
        session = s;
        boolean micOk = has(Manifest.permission.RECORD_AUDIO);
        mic = new MicEncoder(t0, new MicEncoder.Sink() {
            @Override public void audioConfig(byte[] asc) { s.audioConfig(asc); }
            @Override public void audio(byte[] aac, int off, int len, long tsMs) { s.audio(aac, off, len, tsMs); }
            @Override public void micUnavailable() { emit(ev("mic")); }
        });
        mic.setMuted(!pendingSound);
        mic.setMicAllowed(micOk);
        mic.setOffsetMs(pendingOffset);
        if (pendingSound && !micOk) emit(ev("mic"));
        s.start();
        mic.start();
        LiveService.onStopRequested = this::stop;
        LiveService.start(act, pendingSound && micOk);
    }

    /** The encoder's avcC (decoderConfig.description), base64. */
    @JavascriptInterface
    public void config(String b64) {
        LiveSession s = session;
        if (s != null) s.videoConfig(Base64.decode(b64, Base64.DEFAULT));
    }

    /** One encoded frame (AVCC), base64, with its time in ms since the page's clock zero. */
    @JavascriptInterface
    public void video(String b64, double tsMs, boolean key) {
        LiveSession s = session;
        if (s != null) s.video(Base64.decode(b64, Base64.DEFAULT), Math.max(0, Math.round(tsMs)), key);
    }

    /** Sound on or off during a stream. Turning it on may ask for the mic permission. */
    @JavascriptInterface
    public synchronized void sound(boolean on) {
        if (mic == null) return;
        mic.setMuted(!on);
        if (on && !has(Manifest.permission.RECORD_AUDIO)) {
            act.runOnUiThread(() -> act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS));
        } else if (on) {
            LiveService.start(act, true);      // upgrade the service so the mic keeps working in the background
        }
    }

    @JavascriptInterface
    public void soundDelay(int ms) {
        MicEncoder m = mic;
        if (m != null) m.setOffsetMs(ms);
    }

    @JavascriptInterface
    public synchronized void stop() {
        if (pending != null) {
            pending = null;
            emit(ev("state").put2("state", "stopped"));
            return;
        }
        if (session != null) session.stop();  // reports "stopped" when the goodbye is done
    }

    /** The page is going away (reload or app closing): end any stream without waiting. */
    public synchronized void release() {
        pending = null;
        if (session != null) session.stop();
        cleanup();
    }

    /** A session finished by itself (error, or after stop). Ignores one that was already replaced. */
    private synchronized void ended(LiveSession s) {
        if (s != null && s == session) cleanup();
    }

    private void cleanup() {
        if (mic != null) mic.stop();
        mic = null;
        session = null;
        LiveService.onStopRequested = null;
        LiveService.stop(act);
    }

    // ---------------------------------------------------------------- helpers

    private boolean has(String perm) {
        return act.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static int clampEven(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v)) & ~1;
    }

    /** JSONObject with a chainable put that can't throw. */
    private static final class Ev extends JSONObject {
        Ev put2(String k, Object v) {
            try { put(k, v == null ? NULL : v); } catch (Exception ignored) { }
            return this;
        }
    }

    private static Ev ev(String type) { return new Ev().put2("type", type); }

    private void emit(JSONObject o) {
        final String js = "window.onLive&&window.onLive(" + o + ")";
        act.runOnUiThread(() -> web.evaluateJavascript(js, null));
    }
}
