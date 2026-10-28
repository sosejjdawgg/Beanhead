/*
 * Peanut Cam for Windows
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 *
 * An Android phone's front or back camera as a webcam over USB, with nothing to install on the phone.
 * It uses adb (Android Platform Tools) and scrcpy-server (Genymobile, Apache 2.0), both bundled in
 * vendor/: the server is pushed to the phone and started with the camera as its video source (Android 12+);
 * it sends H.264, which goes to the page as packets ("phone:packet") and is decoded there with WebCodecs.
 *
 * The phone needs USB debugging turned on and this PC allowed once (the phone asks).
 * Set PEANUT_FAKE_PHONE=<file.h264> to test without a phone: that file is played as if it came from one.
 */
"use strict";
const { spawn, execFile } = require("child_process");
const net = require("net");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const SERVER_VERSION = "3.1";                     // must match vendor/scrcpy-server exactly
const REMOTE = "/data/local/tmp/peanutcam-server.jar";
let V = "", send = () => {};
let cur = null;                                   // {serial, proc, sock, port, stopped}

function init(o) { V = o.vendor; send = o.send; }
const status = (state, extra) => send("phone:status", Object.assign({ state }, extra || {}));

function adbPath() {
  const p = path.join(V, "adb", process.platform === "win32" ? "adb.exe" : "adb");
  return fs.existsSync(p) ? p : "adb";
}
function adb(args, timeout = 15000) {
  return new Promise((resolve, reject) => {
    execFile(adbPath(), args, { timeout, windowsHide: true, maxBuffer: 4 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) { err.stderr = String(stderr || ""); reject(err); } else resolve(String(stdout));
    });
  });
}

/** Phones plugged in: [{serial, state: device|unauthorized|offline|no permissions, model, sdk}] or {error}. */
async function list() {
  if (process.env.PEANUT_FAKE_PHONE) return { devices: [{ serial: "FAKE", state: "device", model: "Test phone", sdk: 34 }] };
  let out;
  try { out = await adb(["devices", "-l"], 20000); }
  catch (e) { return { error: e.code === "ENOENT" ? "The phone tools (adb) are missing from this installation." : "Couldn't look for phones: " + (e.stderr || e.message) }; }
  const devices = [];
  for (const line of out.split(/\r?\n/).slice(1)) {
    const m = line.trim().match(/^(\S+)\s+(device|unauthorized|offline|no permissions|authorizing|recovery|sideload|bootloader)\b(.*)$/);
    if (!m) continue;
    const d = { serial: m[1], state: m[2], model: (m[3].match(/model:(\S+)/) || [])[1] || "", sdk: 0 };
    if (d.state === "device") {
      try {
        const [sdk, brand, model] = (await adb(["-s", d.serial, "shell", "getprop ro.build.version.sdk; getprop ro.product.brand; getprop ro.product.model"])).split(/\r?\n/);
        d.sdk = parseInt(sdk, 10) || 0;
        d.model = [brand, model].filter(Boolean).join(" ").replace(/\b\w/g, c => c.toUpperCase()) || d.model.replace(/_/g, " ");
      } catch (_) {}
    }
    devices.push(d);
  }
  return { devices };
}

/** Starts the camera. opts: {serial, facing: "back"|"front", max: 1280|1920, fps: 30|60}. Resolves {ok} or {error}. */
async function start(opts) {
  await stop();
  const me = cur = { serial: opts.serial, stopped: false, proc: null, sock: null, port: 0 };
  status("starting");
  if (process.env.PEANUT_FAKE_PHONE) return fake(me, opts);
  const facing = opts.facing === "front" ? "front" : "back", max = opts.max | 0 || 1920, fps = opts.fps | 0 || 30;
  try {
    await adb(["-s", me.serial, "push", path.join(V, "scrcpy-server"), REMOTE], 30000);
    const scid = crypto.randomInt(0x7fffffff).toString(16).padStart(8, "0");
    me.port = parseInt(await adb(["-s", me.serial, "forward", "tcp:0", "localabstract:scrcpy_" + scid]), 10);
    if (!me.port) throw new Error("couldn't open a connection to the phone");
    const args = ["-s", me.serial, "shell", "CLASSPATH=" + REMOTE, "app_process", "/", "com.genymobile.scrcpy.Server", SERVER_VERSION,
      "scid=" + scid, "log_level=info", "video_source=camera", "camera_facing=" + facing, "camera_ar=16:9", "max_size=" + max, "camera_fps=" + fps,
      "video_codec=h264", "video_bit_rate=" + (max > 1280 ? 10000000 : 6000000), "audio=false", "control=false", "cleanup=false",
      "tunnel_forward=true", "send_device_meta=false", "send_dummy_byte=true", "send_codec_meta=true", "send_frame_meta=true"];
    const proc = me.proc = spawn(adbPath(), args, { windowsHide: true });
    let lastErr = "";
    const onOut = d => { for (const l of String(d).split(/\r?\n/)) if (/ERROR|Exception|not supported|No camera/i.test(l)) lastErr = l.replace(/^.*?(ERROR:?\s*)/, "").trim(); };
    proc.stdout.on("data", onOut); proc.stderr.on("data", onOut);
    proc.on("exit", () => { if (cur === me && !me.stopped) { status("error", { msg: explain(lastErr) || "The phone stopped sending its camera." }); cleanup(me); } });
    await connect(me);
    return { ok: true };
  } catch (e) {
    cleanup(me);
    const msg = explain(e.stderr || e.message);
    if (cur === me) status("error", { msg });
    return { error: msg };
  }
}

function explain(s) {
  s = String(s || "");
  if (/unauthorized/i.test(s)) return "Allow USB debugging on the phone (tick \"Always allow from this computer\"), then try again.";
  if (/not found|no devices/i.test(s)) return "The phone isn't connected. Plug it in with a data USB cable and turn on USB debugging.";
  if (/Camera mirroring is not supported|API|Android 12/i.test(s)) return "This phone's Android is too old for camera over USB (it needs Android 12 or newer).";
  if (/No camera|camera.*not found/i.test(s)) return "The phone has no camera on that side.";
  if (/fps|frame rate/i.test(s)) return "The phone's camera can't do that frame rate. Try 30 fps.";
  return s ? "Phone camera: " + s.slice(0, 200) : "";
}

// connect to the server through the adb tunnel; it isn't listening for the first moments, so retry
function connect(me) {
  return new Promise((resolve, reject) => {
    let tries = 0;
    const attempt = () => {
      if (me.stopped) return reject(new Error("stopped"));
      const sock = net.connect(me.port, "127.0.0.1");
      let got = false;
      sock.once("data", d => {
        got = true;
        if (d[0] !== 0) { sock.destroy(); return reject(new Error("unexpected answer from the phone")); }
        me.sock = sock;
        stream(me, sock, d.subarray(1));
        resolve();
      });
      const retry = () => { if (got) return; sock.destroy(); if (++tries > 60) reject(new Error("The phone didn't start its camera.")); else setTimeout(attempt, 150); };
      sock.once("close", retry);
      sock.once("error", () => {});
    };
    attempt();
  });
}

// codec meta (codec id, width, height), then per packet: 8-byte pts with flags + 4-byte size + H.264 (Annex B)
function stream(me, sock, first) {
  let buf = first, need = 12, header = null, meta = null;
  const feed = () => {
    while (buf.length >= need) {
      const chunk = buf.subarray(0, need); buf = buf.subarray(need);
      if (!meta) {
        meta = { codec: chunk.readUInt32BE(0), width: chunk.readUInt32BE(4), height: chunk.readUInt32BE(8) };
        status("on", { width: meta.width, height: meta.height, serial: me.serial });
        need = 12; continue;
      }
      if (!header) {
        const hi = chunk.readUInt32BE(0), lo = chunk.readUInt32BE(4);
        header = { config: !!(hi & 0x80000000), key: !!(hi & 0x40000000), pts: (hi & 0x3FFFFFFF) * 4294967296 + lo };
        need = chunk.readUInt32BE(8); continue;
      }
      send("phone:packet", { config: header.config, key: header.key, pts: header.pts, data: Buffer.from(chunk) });
      header = null; need = 12;
    }
  };
  sock.on("data", d => { buf = buf.length ? Buffer.concat([buf, d]) : d; feed(); });
  sock.on("close", () => { if (cur === me && !me.stopped) { status("error", { msg: "The phone's camera connection closed. Is the cable still in?" }); cleanup(me); } });
  feed();
}

// a file of H.264 played as if it came from a phone (tests)
function fake(me, opts) {
  const data = fs.readFileSync(process.env.PEANUT_FAKE_PHONE);
  const nals = []; let s = -1;
  for (let i = 0; i < data.length - 4; i++) {
    const k = data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1 ? 3 : data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 0 && data[i + 3] === 1 ? 4 : 0;
    if (k) { if (s >= 0) nals.push(data.subarray(s, i)); s = i; i += k - 1; }
  }
  nals.push(data.subarray(s));
  const sc = n => (n[2] === 1 ? n[3] : n[4]) & 31;
  const cfg = Buffer.concat(nals.filter(n => sc(n) === 7 || sc(n) === 8).slice(0, 2));
  const frames = nals.filter(n => sc(n) === 1 || sc(n) === 5);
  status("on", { width: 0, height: 0, serial: "FAKE" });
  send("phone:packet", { config: true, key: false, pts: 0, data: cfg });
  let i = 0;
  me.timer = setInterval(() => {
    const f = frames[i % frames.length], key = sc(f) === 5;
    send("phone:packet", { config: false, key, pts: Math.round(i * 1e6 / (opts.fps || 30)), data: Buffer.from(f) });
    i++;
  }, 1000 / (opts.fps || 30));
  return { ok: true };
}

function cleanup(me) {
  me.stopped = true;
  clearInterval(me.timer);
  try { me.sock && me.sock.destroy(); } catch (_) {}
  try { me.proc && me.proc.kill(); } catch (_) {}
  if (me.port) adb(["-s", me.serial, "forward", "--remove", "tcp:" + me.port]).catch(() => {});
  if (cur === me) cur = null;
}

async function stop() {
  if (!cur) return;
  const me = cur; cleanup(me);
  status("off");
}

module.exports = { init, list, start, stop };
