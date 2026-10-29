/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.beanhead.peanutcam.audio.PhoneAudio;
import com.beanhead.peanutcam.live.LiveController;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts the Peanut Cam page in a WebView. Files are served from assets/www on
 * https://appassets.androidplatform.net so the page counts as a secure origin
 * (needed for the camera, ES modules and WebAssembly).
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_STORAGE = 3;

    private WebView web;
    private LiveController live;
    private PhoneAudio phoneAudio;
    private PermissionRequest pendingRequest;
    private ValueCallback<Uri[]> fileCallback;
    private volatile Uri sharedUri;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0705);
        setContentView(web);
        hideSystemBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        WebView.setWebContentsDebuggingEnabled(true);

        web.addJavascriptInterface(new NativeBridge(), "PeanutNative");
        live = new LiveController(this, web);
        web.addJavascriptInterface(live, "PeanutLive");
        phoneAudio = new PhoneAudio(this, web);
        web.addJavascriptInterface(phoneAudio, "PeanutAudio");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serveAsset(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                live.release();             // a reloaded page can't feed a stream it doesn't know about
                phoneAudio.stop();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handlePermissionRequest(request));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        // Ask for the camera up front so the menu buttons work on the first tap.
        List<String> missing = new ArrayList<>();
        if (!has(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_CAMERA);

        handleShare(getIntent());
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    /** A picture shared from another app: the page fetches it from /__shared and makes a character. */
    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType();
        if (type == null || !type.startsWith("image/")) return;
        @SuppressWarnings("deprecation")
        Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        sharedUri = u;
        if (pageReady && web != null) web.evaluateJavascript("window.onSharedImage && window.onSharedImage()", null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    private WebResourceResponse serveAsset(Uri url) {
        if (url == null || !"https".equals(url.getScheme()) || !HOST.equals(url.getHost())) return null;
        String path = url.getPath();
        if ("/__shared".equals(path)) {
            Uri u = sharedUri;
            try {
                if (u == null) throw new IOException("nothing shared");
                String t = getContentResolver().getType(u);
                return new WebResourceResponse(t != null ? t : "image/*", null, getContentResolver().openInputStream(u));
            } catch (Exception e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
            }
        }
        if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
        String mime = mimeFor(path);
        try {
            InputStream in = getAssets().open("www" + path);
            boolean text = mime.startsWith("text/") || mime.equals("application/json");
            WebResourceResponse res = new WebResourceResponse(mime, text ? "utf-8" : null, in);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-cache");
            res.setResponseHeaders(headers);
            return res;
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** The page asked for camera and/or microphone: get any missing Android permission first. */
    private void handlePermissionRequest(PermissionRequest request) {
        List<String> need = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (need.isEmpty()) {
            grantAllowed(request);
        } else {
            if (pendingRequest != null) pendingRequest.deny();
            pendingRequest = request;
            requestPermissions(need.toArray(new String[0]), REQ_CAMERA);
        }
    }

    private void grantAllowed(PermissionRequest request) {
        List<String> ok = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
        }
        if (ok.isEmpty()) request.deny();
        else request.grant(ok.toArray(new String[0]));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LiveController.REQ_PERMISSIONS) { live.onPermissionsResult(); return; }
        if (requestCode == PhoneAudio.REQ_MIC) { phoneAudio.onPermissionsResult(); return; }
        if (requestCode != REQ_CAMERA || pendingRequest == null) return;
        grantAllowed(pendingRequest);
        pendingRequest = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PhoneAudio.REQ_CONSENT) { phoneAudio.onConsent(resultCode, data); return; }
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] picked = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    picked = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) picked[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    picked = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(picked);
            fileCallback = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // the page closes its own panels and tools first (window.onBack); the app only closes from the main menu
        web.evaluateJavascript("(window.onBack && window.onBack()) ? 1 : 0", r -> {
            if (!"1".equals(r)) {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (live != null) live.release();
        if (phoneAudio != null) phoneAudio.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Lets the page save a finished recording into the phone's gallery, in pieces. */
    private class NativeBridge {
        private OutputStream out;
        private Uri uri;
        private File file;

        @JavascriptInterface
        public boolean hasShared() {
            return sharedUri != null;
        }

        @JavascriptInterface
        public void clearShared() {
            sharedUri = null;
        }

        @JavascriptInterface
        public void prepare() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
            }
        }

        @JavascriptInterface
        public synchronized boolean begin(String name, String mime) {
            try {
                closeQuietly();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    boolean image = mime != null && mime.startsWith("image/");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES) + "/PeanutCam");
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = getContentResolver().insert(image
                            ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                    if (uri == null) return false;
                    // a file descriptor (not just a stream) so the end of a recording can patch its header (patch())
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw");
                    if (pfd == null) return false;
                    out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
                } else {
                    boolean image = mime != null && mime.startsWith("image/");
                    File dir = new File(Environment.getExternalStoragePublicDirectory(image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES), "PeanutCam");
                    if (!dir.exists() && !dir.mkdirs()) return false;
                    file = new File(dir, name);
                    out = new FileOutputStream(file);
                }
                return out != null;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean append(String b64) {
            try {
                out.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Overwrites bytes already written (an MP4's box sizes, filled in when the recording ends). */
        @JavascriptInterface
        public synchronized boolean patch(String b64, double position) {
            try {
                if (!(out instanceof FileOutputStream)) return false;
                java.nio.channels.FileChannel ch = ((FileOutputStream) out).getChannel();
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(Base64.decode(b64, Base64.DEFAULT));
                long pos = (long) position;
                while (b.hasRemaining()) pos += ch.write(b, pos);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean finish() {
            try {
                out.close();
                out = null;
                if (uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, v, null, null);
                    uri = null;
                } else if (file != null) {
                    MediaScannerConnection.scanFile(MainActivity.this, new String[]{file.getAbsolutePath()}, null, null);
                    file = null;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private void closeQuietly() {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            out = null;
        }
    }
}
