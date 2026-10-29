/*
 * Peanut Cam for Windows
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 *
 * The desktop app is the same page as the Android app (index.html), served from app://peanut/ out of the
 * bundled www folder, plus what a phone app does natively:
 *   - PeanutNative: saving recordings and pictures (to Videos\PeanutCam and Pictures\PeanutCam)
 *   - PeanutLive: publishing a live stream over RTMP/RTMPS (rtmp.js; the page encodes picture and sound)
 *   - phone camera: an Android phone's front or back camera over USB (phonecam.js, adb + scrcpy-server)
 *   - PC sound: the system's sound for recordings and streams (display-media loopback)
 */
"use strict";
const { app, BrowserWindow, protocol, session, ipcMain, shell, desktopCapturer, nativeTheme, Menu } = require("electron");
const path = require("path");
const fs = require("fs");
const { LiveSession, parseTarget } = require("./rtmp");
const phoneCam = require("./phonecam");

const ROOT = app.isPackaged ? process.resourcesPath : __dirname;
const WWW = path.join(ROOT, "www");
const VENDOR = path.join(ROOT, "vendor");

// keep drawing (and streaming) when the window is covered by another one
app.commandLine.appendSwitch("disable-features", "CalculateNativeWinOcclusion");
app.commandLine.appendSwitch("disable-background-timer-throttling");
app.commandLine.appendSwitch("disable-renderer-backgrounding");

protocol.registerSchemesAsPrivileged([{ scheme: "app", privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true, codeCache: true } }]);

const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".json": "application/json", ".wasm": "application/wasm",
  ".css": "text/css", ".png": "image/png", ".jpg": "image/jpeg", ".svg": "image/svg+xml", ".txt": "text/plain; charset=utf-8", ".md": "text/plain; charset=utf-8",
  ".woff2": "font/woff2", ".task": "application/octet-stream", ".tflite": "application/octet-stream", ".bin": "application/octet-stream"
};

let win = null;
const send = (ch, data) => { if (win && !win.isDestroyed()) win.webContents.send(ch, data); };

function serveApp() {
  protocol.handle("app", async req => {
    const u = new URL(req.url);
    let rel = decodeURIComponent(u.pathname);
    if (rel === "/" || rel === "") rel = "/index.html";
    const file = path.normalize(path.join(WWW, rel));
    if (!file.startsWith(WWW + path.sep)) return new Response("Not found", { status: 404 });
    try {
      const data = await fs.promises.readFile(file);
      return new Response(data, { headers: { "content-type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream", "cache-control": "no-cache" } });
    } catch (e) {
      return new Response("Not found", { status: 404 });
    }
  });
}

function permissions() {
  const ok = new Set(["media", "display-capture", "fullscreen", "clipboard-sanitized-write", "notifications"]);
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(ok.has(perm)));
  session.defaultSession.setPermissionCheckHandler((wc, perm) => ok.has(perm));
  // PC sound: the page asks for display media; answer with the whole screen and the system's sound (loopback).
  // The picture is thrown away by the page; only the sound is used.
  session.defaultSession.setDisplayMediaRequestHandler((req, cb) => {
    desktopCapturer.getSources({ types: ["screen"] })
      .then(src => cb(src.length ? { video: src[0], audio: "loopback" } : {}))
      .catch(() => cb({}));
  });
}

function createWindow() {
  nativeTheme.themeSource = "dark";
  win = new BrowserWindow({
    width: 1280, height: 820, minWidth: 420, minHeight: 600,
    backgroundColor: "#0e0906", title: "Peanut Cam", show: false, autoHideMenuBar: true,
    icon: path.join(__dirname, "build", "icon.png"),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, sandbox: false, backgroundThrottling: false, spellcheck: false }
  });
  win.once("ready-to-show", () => win.show());
  // links open in the browser, never inside the app
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/i.test(url)) shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => { if (!url.startsWith("app://")) { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); } });
  win.webContents.on("before-input-event", (e, input) => {
    if (input.type !== "keyDown") return;
    if (input.key === "F11") { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (input.key === "F12" || (input.control && input.shift && input.key.toLowerCase() === "i")) { win.webContents.toggleDevTools(); e.preventDefault(); }
  });
  win.on("closed", () => { win = null; });
  win.loadURL("app://peanut/index.html");
}

// ---------------------------------------------------------------- saving (the PeanutNative bridge)
const SAVE = { fd: null, file: null };
const saveDir = mime => path.join(app.getPath(mime.startsWith("image/") ? "pictures" : "videos"), "PeanutCam");
ipcMain.handle("save:begin", async (e, name, mime) => {
  try {
    if (SAVE.fd) { try { fs.closeSync(SAVE.fd); } catch (_) {} }
    const dir = saveDir(mime || "");
    fs.mkdirSync(dir, { recursive: true });
    SAVE.file = path.join(dir, path.basename(String(name)));
    SAVE.fd = fs.openSync(SAVE.file, "w");
    return true;
  } catch (err) { SAVE.fd = null; return false; }
});
ipcMain.handle("save:append", async (e, b64) => {
  try { fs.writeSync(SAVE.fd, Buffer.from(b64, "base64")); return true; } catch (err) { return false; }
});
ipcMain.handle("save:patch", async (e, b64, pos) => {
  try { const b = Buffer.from(b64, "base64"); fs.writeSync(SAVE.fd, b, 0, b.length, Math.max(0, pos | 0)); return true; } catch (err) { return false; }
});
ipcMain.handle("save:finish", async () => {
  try { fs.closeSync(SAVE.fd); SAVE.fd = null; return SAVE.file; } catch (err) { return false; }
});
ipcMain.handle("save:show", (e, file) => { if (file && fs.existsSync(file)) shell.showItemInFolder(file); });
ipcMain.handle("app:info", () => ({ version: app.getVersion(), platform: process.platform, videos: saveDir("video/"), pictures: saveDir("image/") }));
ipcMain.handle("app:open", (e, url) => { if (/^https?:/i.test(url)) shell.openExternal(url); });

// ---------------------------------------------------------------- live streaming (the PeanutLive bridge)
let live = null;
ipcMain.on("live:start", (e, json) => {
  try {
    if (live && !live.finished) { e.returnValue = "Already live."; return; }
    const o = JSON.parse(json);
    const cfg = {
      target: parseTarget(o.url, o.key),
      width: clampEven(o.width || 720, 160, 3840), height: clampEven(o.height || 1280, 160, 3840),
      fps: Math.max(10, Math.min(60, o.fps || 30)), videoKbps: Math.max(300, Math.min(12000, o.kbps || 3500)),
      audioKbps: 128, sampleRate: 48000, channels: 2
    };
    live = new LiveSession(cfg, ev => send("live", ev));
    live.start();
    e.returnValue = "";
  } catch (err) {
    e.returnValue = err && err.userMessage ? err.userMessage : "Couldn't start: " + (err && err.message || err);
  }
});
ipcMain.on("live:config", (e, b64) => live && live.videoConfig(Buffer.from(b64, "base64")));
ipcMain.on("live:video", (e, b64, ts, key) => live && live.video(Buffer.from(b64, "base64"), Math.max(0, Math.round(ts)), !!key));
ipcMain.on("live:audioConfig", (e, b64) => live && live.audioConfig(Buffer.from(b64, "base64")));
ipcMain.on("live:audio", (e, b64, ts) => live && live.audio(Buffer.from(b64, "base64"), Math.max(0, Math.round(ts))));
ipcMain.on("live:stop", () => { if (live) live.stop(); });
const clampEven = (v, lo, hi) => Math.max(lo, Math.min(hi, v | 0)) & ~1;

// ---------------------------------------------------------------- phone camera over USB
phoneCam.init({ vendor: VENDOR, send });
ipcMain.handle("phone:list", () => phoneCam.list());
ipcMain.handle("phone:start", (e, opts) => phoneCam.start(opts));
ipcMain.handle("phone:stop", () => phoneCam.stop());

// ---------------------------------------------------------------- app lifecycle
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on("second-instance", () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    serveApp();
    permissions();
    createWindow();
  });
  app.on("window-all-closed", () => { if (live) live.stop(); phoneCam.stop(); app.quit(); });
  process.on("uncaughtException", err => console.error(err));
}
