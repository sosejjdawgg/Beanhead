/*
 * Peanut Cam for Windows
 * Copyright (c) 2026 James Scott. All rights reserved.
 *
 * The bridges the page expects from the Android app, backed by the desktop main process:
 * PeanutNative (saving), PeanutLive (streaming) and PeanutDesktop (phone camera, folders, events).
 */
"use strict";
const { contextBridge, ipcRenderer } = require("electron");

// saving recordings and pictures: same calls as Android, but asynchronous (the page awaits them)
contextBridge.exposeInMainWorld("PeanutNative", {
  prepare: () => true,
  begin: (name, mime) => ipcRenderer.invoke("save:begin", String(name), String(mime)),
  append: b64 => ipcRenderer.invoke("save:append", String(b64)),
  finish: () => ipcRenderer.invoke("save:finish"),
  hasShared: () => false,
  clearShared: () => {}
});

// live streaming: the page encodes picture and sound (desktop: sound too, as AAC); this publishes over RTMP
contextBridge.exposeInMainWorld("PeanutLive", {
  encodesAudio: true,
  start: json => ipcRenderer.sendSync("live:start", String(json)),
  config: b64 => ipcRenderer.send("live:config", String(b64)),
  video: (b64, ts, key) => ipcRenderer.send("live:video", String(b64), +ts, !!key),
  audioConfig: b64 => ipcRenderer.send("live:audioConfig", String(b64)),
  audio: (b64, ts) => ipcRenderer.send("live:audio", String(b64), +ts),
  sound: () => {},            // the page mutes its own microphone on the desktop
  soundDelay: () => {},
  stop: () => ipcRenderer.send("live:stop")
});

const listeners = {};
for (const ch of ["live", "phone:status", "phone:packet"]) {
  ipcRenderer.on(ch, (e, data) => { for (const f of listeners[ch] || []) { try { f(data); } catch (err) { console.error(err); } } });
}
contextBridge.exposeInMainWorld("PeanutDesktop", {
  platform: process.platform,
  on: (ch, f) => { (listeners[ch] ||= []).push(f); },
  info: () => ipcRenderer.invoke("app:info"),
  showFile: file => ipcRenderer.invoke("save:show", String(file)),
  openLink: url => ipcRenderer.invoke("app:open", String(url)),
  phone: {
    list: () => ipcRenderer.invoke("phone:list"),
    start: opts => ipcRenderer.invoke("phone:start", opts),
    stop: () => ipcRenderer.invoke("phone:stop")
  }
});
