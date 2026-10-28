/*
 * Peanut Cam for Windows
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 *
 * Live streaming over RTMP/RTMPS: the same protocol code and behaviour as the Android app
 * (android/.../live/RtmpPublisher.java and LiveSession.java), in Node. The page hands over H.264 (AVCC)
 * and AAC that it encoded itself; this queues them, publishes, reconnects with backoff for up to two
 * minutes, drops video to the next key frame and asks for a lower bitrate when the upload can't keep up,
 * and raises it again after 15 calm seconds. Stream keys only pass through memory.
 */
"use strict";
const net = require("net");
const tls = require("tls");
const crypto = require("crypto");

// ---------------------------------------------------------------- AMF0
const amf = {
  write(vals) {
    const parts = [];
    const str = s => { const b = Buffer.from(s, "utf8"); const h = Buffer.alloc(2); h.writeUInt16BE(b.length); parts.push(h, b); };
    const any = v => {
      if (v === null || v === undefined) parts.push(Buffer.from([0x05]));
      else if (typeof v === "string") { parts.push(Buffer.from([0x02])); str(v); }
      else if (typeof v === "boolean") parts.push(Buffer.from([0x01, v ? 1 : 0]));
      else if (typeof v === "number") { const b = Buffer.alloc(9); b[0] = 0; b.writeDoubleBE(v, 1); parts.push(b); }
      else if (v && v.__ecma) { const b = Buffer.alloc(5); b[0] = 0x08; b.writeUInt32BE(Object.keys(v.obj).length, 1); parts.push(b); props(v.obj); }
      else if (typeof v === "object") { parts.push(Buffer.from([0x03])); props(v); }
    };
    const props = o => { for (const [k, v] of Object.entries(o)) { str(k); any(v); } parts.push(Buffer.from([0, 0, 0x09])); };
    for (const v of vals) any(v);
    return Buffer.concat(parts);
  },
  readAll(b) {
    const out = []; let p = 0;
    const u16 = () => { const v = b.readUInt16BE(p); p += 2; return v; };
    const u32 = () => { const v = b.readUInt32BE(p); p += 4; return v; };
    const rs = n => { const s = b.toString("utf8", p, p + n); p += n; return s; };
    const props = () => { const m = {}; for (;;) { const n = u16(); if (n === 0 && b[p] === 0x09) { p++; return m; } const k = rs(n); m[k] = read(); } };
    const read = () => {
      const t = b[p++];
      switch (t) {
        case 0x00: { const v = b.readDoubleBE(p); p += 8; return v; }
        case 0x01: return b[p++] !== 0;
        case 0x02: return rs(u16());
        case 0x0C: return rs(u32());
        case 0x03: return props();
        case 0x08: p += 4; return props();
        case 0x05: case 0x06: return null;
        case 0x0A: { const n = u32(), l = []; for (let i = 0; i < n; i++) l.push(read()); return l; }
        case 0x0B: p += 10; return null;
        default: throw new Error("AMF0 type " + t);
      }
    };
    try { while (p < b.length) out.push(read()); } catch (e) { /* a type we don't model: keep what was read */ }
    return out;
  }
};

// ---------------------------------------------------------------- target
class UserError extends Error { constructor(msg) { super(msg); this.userMessage = msg; } }

/** "Server URL" + "stream key" as TikTok, YouTube, Twitch and OBS show them; an empty key takes the URL's last segment. */
function parseTarget(url, key) {
  url = String(url || "").trim(); key = String(key || "").trim();
  const lower = url.toLowerCase();
  let secure;
  if (lower.startsWith("rtmps://")) secure = true;
  else if (lower.startsWith("rtmp://")) secure = false;
  else throw new UserError("The server URL should start with rtmp:// or rtmps://");
  const rest = url.slice(secure ? 8 : 7), slash = rest.indexOf("/");
  const hostPort = slash < 0 ? rest : rest.slice(0, slash);
  let p = slash < 0 ? "" : rest.slice(slash + 1);
  while (p.endsWith("/")) p = p.slice(0, -1);
  if (!key) { const ls = p.lastIndexOf("/"); if (ls < 0) throw new UserError("Enter the stream key."); key = p.slice(ls + 1); p = p.slice(0, ls); }
  let host = hostPort, port = secure ? 443 : 1935;
  const colon = hostPort.lastIndexOf(":");
  if (colon > 0 && hostPort.indexOf("]") < colon) {
    host = hostPort.slice(0, colon); port = parseInt(hostPort.slice(colon + 1), 10);
    if (!(port > 0)) throw new UserError("The port in the server URL isn't a number.");
  }
  if (host.startsWith("[") && host.endsWith("]")) host = host.slice(1, -1);
  if (!host) throw new UserError("The server URL has no host name.");
  if (!p) throw new UserError("The server URL needs an app name after the host, like rtmp://host/live");
  if (!key) throw new UserError("Enter the stream key.");
  const tcUrl = (secure ? "rtmps://" : "rtmp://") + hostPort + "/" + p;
  return { tls: secure, host, port, app: p, tcUrl, key, toString: () => tcUrl + "/<key>" };
}

// ---------------------------------------------------------------- publisher (one connection)
const TYPE_SET_CHUNK = 1, TYPE_ABORT = 2, TYPE_ACK = 3, TYPE_USER = 4, TYPE_WINDOW = 5, TYPE_PEER_BW = 6, TYPE_AUDIO = 8, TYPE_VIDEO = 9, TYPE_DATA = 18, TYPE_CMD = 20;
const CS_CONTROL = 2, CS_CMD = 3, CS_AUDIO = 4, CS_DATA = 5, CS_VIDEO = 6, OUT_CHUNK = 4096;

class Rejected extends UserError {}

function friendly(code, desc) {
  const d = desc == null ? "" : String(desc).trim();
  const base = {
    "NetStream.Publish.BadName": "The server says this stream key is already in use or not allowed.",
    "NetConnection.Connect.Rejected": "The server rejected the connection. Check the server URL and stream key.",
    "NetStream.Publish.Denied": "The server denied publishing. Check the stream key, or get a new one.",
    "NetStream.Publish.Unauthorized": "The server didn't accept the stream key. Copy a fresh one and try again.",
    "NetConnection.Connect.Unauthorized": "The server didn't accept the stream key. Copy a fresh one and try again.",
    "NetStream.Unpublish.Success": "The server ended the stream."
  }[code] || "The server said: " + code;
  return !d || base.includes(d) ? base : base + " (" + d + ")";
}
const infoOf = v => { for (let i = v.length - 1; i >= 2; i--) if (v[i] && typeof v[i] === "object" && !Array.isArray(v[i])) return v[i]; return null; };
function statusError(v) {
  if (!v.length || (v[0] !== "onStatus" && v[0] !== "_error")) return null;
  const info = infoOf(v);
  if (!info) return v[0] === "_error" ? "The server reported an error." : null;
  const code = String(info.code);
  const bad = info.level === "error" || code.endsWith(".Failed") || code.endsWith(".Rejected") || code === "NetStream.Unpublish.Success" || code === "NetConnection.Connect.Closed";
  return bad ? friendly(code, info.description) : null;
}

class RtmpPublisher {
  constructor(target, sock) {
    this.t = target; this.sock = sock;
    this.bufs = []; this.len = 0; this.waiter = null; this.err = null;
    this.inChunk = 128; this.outChunk = 128; this.windowAck = 2500000; this.bytesIn = 0; this.lastAck = 0;
    this.txn = 0; this.streamId = 0; this.closed = false; this.inStreams = new Map(); this.onClose = null;
    sock.on("data", d => { this.bufs.push(d); this.len += d.length; this.bytesIn += d.length; this.wake(); });
    sock.on("error", e => { this.err = this.err || e; this.wake(); });
    sock.on("close", () => { this.err = this.err || Object.assign(new Error("The server closed the connection."), { eof: true }); this.closed = true; this.wake(); });
  }

  static open(t, timeoutMs) {
    return new Promise((resolve, reject) => {
      let done = false, p = null;
      const fail = e => { if (done) return; done = true; clearTimeout(timer); try { sock.destroy(); } catch (_) {} reject(e); };
      const opts = { host: t.host, port: t.port, servername: net.isIP(t.host) ? undefined : t.host };
      const sock = t.tls ? tls.connect(opts) : net.connect(opts);
      const timer = setTimeout(() => fail(Object.assign(new Error("timeout"), { code: "ETIMEDOUT" })), timeoutMs);
      sock.setNoDelay(true);
      sock.once(t.tls ? "secureConnect" : "connect", async () => {
        try {
          p = new RtmpPublisher(t, sock);
          await p.handshake(); await p.connectApp(); await p.publish();
          if (done) return;
          done = true; clearTimeout(timer); resolve(p);
        } catch (e) { fail(e); }
      });
      sock.once("error", fail);
    });
  }

  wake() { const w = this.waiter; this.waiter = null; if (w) w(); }
  async read(n) {
    while (this.len < n) { if (this.err) throw this.err; await new Promise(r => { this.waiter = r; }); }
    const all = this.bufs.length === 1 ? this.bufs[0] : Buffer.concat(this.bufs, this.len);
    this.bufs = all.length > n ? [all.subarray(n)] : []; this.len -= n;
    return all.subarray(0, n);
  }

  async handshake() {
    const c1 = crypto.randomBytes(1536); c1.writeUInt32BE((Date.now() / 1000) >>> 0, 0); c1.writeUInt32BE(0, 4);
    this.sock.write(Buffer.concat([Buffer.from([3]), c1]));
    const v = (await this.read(1))[0];
    if (v !== 3) throw new Error("Not an RTMP server (version " + v + ")");
    const s1 = Buffer.from(await this.read(1536));
    this.sock.write(s1);                 // C2 echoes S1
    await this.read(1536);               // S2
  }

  command(cs, sid, name, ...args) {
    const d = amf.write([name, ++this.txn, ...args]);
    this.writeMessage(cs, TYPE_CMD, sid, 0, d);
    return this.txn;
  }

  async awaitResult(id, what) {
    for (;;) {
      const m = await this.readMessage();
      if (m.type !== TYPE_CMD) continue;
      const v = amf.readAll(m.data);
      if (v.length < 2 || v[1] !== id) continue;
      if (v[0] === "_result") return v;
      if (v[0] === "_error") { const i = infoOf(v); throw new Rejected(i ? friendly(String(i.code), i.description) : "The server refused " + what + "."); }
    }
  }

  async connectApp() {
    const id = this.command(CS_CMD, 0, "connect", { app: this.t.app, type: "nonprivate", flashVer: "FMLE/3.0 (compatible; FMSc/1.0)", swfUrl: this.t.tcUrl, tcUrl: this.t.tcUrl });
    const r = await this.awaitResult(id, "connect");
    const err = statusError(r); if (err) throw new Rejected(err);
    const cs = Buffer.alloc(4); cs.writeUInt32BE(OUT_CHUNK);
    this.writeMessage(CS_CONTROL, TYPE_SET_CHUNK, 0, 0, cs);
    this.outChunk = OUT_CHUNK;
  }

  async publish() {
    this.command(CS_CMD, 0, "releaseStream", null, this.t.key);
    this.command(CS_CMD, 0, "FCPublish", null, this.t.key);
    const id = this.command(CS_CMD, 0, "createStream", null);
    const r = await this.awaitResult(id, "createStream");
    if (typeof r[3] !== "number") throw new Rejected("The server didn't create a stream.");
    this.streamId = r[3] | 0;
    this.command(CS_CMD, this.streamId, "publish", null, this.t.key, "live");
    for (;;) {
      const m = await this.readMessage();
      if (m.type !== TYPE_CMD) continue;
      const v = amf.readAll(m.data);
      if (!v.length) continue;
      if (v[0] === "_error") throw new Rejected("The server refused to publish.");
      if (v[0] !== "onStatus") continue;
      const info = infoOf(v), code = info ? String(info.code) : "";
      if (code === "NetStream.Publish.Start") return;
      if (info && info.level === "error") throw new Rejected(friendly(code, info.description));
    }
  }

  /** Answers pings and notices when the server ends the stream. */
  startReader(onClose) {
    this.onClose = onClose;
    (async () => {
      let why = "The server closed the connection.";
      try {
        while (!this.closed) {
          const m = await this.readMessage();
          if (m.type === TYPE_CMD) { const err = statusError(amf.readAll(m.data)); if (err) { why = err; break; } }
        }
      } catch (e) { if (!e.eof) why = "Connection lost (" + (e.message || e.code) + ")"; }
      if (!this.aborted) { this.abort(); if (this.onClose) this.onClose(why); }
    })();
  }

  sendMetadata(c) {
    const m = { duration: 0, width: c.width, height: c.height, videodatarate: c.videoKbps, framerate: c.fps, videocodecid: 7,
      audiodatarate: c.audioKbps, audiosamplerate: c.sampleRate, audiosamplesize: 16, stereo: c.channels === 2, audiocodecid: 10, encoder: "Peanut Cam", filesize: 0 };
    this.writeMessage(CS_DATA, TYPE_DATA, this.streamId, 0, amf.write(["@setDataFrame", "onMetaData", { __ecma: true, obj: m }]));
  }
  sendTag(type, ts, body) { this.writeMessage(type === TYPE_VIDEO ? CS_VIDEO : CS_AUDIO, type, this.streamId, ts, body); }
  get buffered() { return this.sock.writableLength; }
  get bytesOut() { return this.sock.bytesWritten; }

  /** Ends the stream politely, then closes. */
  close() {
    if (this.aborted) return;
    try { this.command(CS_CMD, 0, "FCUnpublish", null, this.t.key); this.command(CS_CMD, 0, "deleteStream", null, this.streamId); } catch (_) {}
    this.aborted = true; this.closed = true;
    try { this.sock.end(); } catch (_) {}
    setTimeout(() => { try { this.sock.destroy(); } catch (_) {} }, 1000);
  }
  abort() { this.aborted = true; this.closed = true; try { this.sock.destroy(); } catch (_) {} }

  writeMessage(cs, type, sid, ts, d) {
    if (this.closed && this.aborted) throw new Error("closed");
    const ext = ts >= 0xFFFFFF, len = d.length, chunks = Math.max(1, Math.ceil(len / this.outChunk));
    const b = Buffer.allocUnsafe(12 + (ext ? 4 : 0) + len + (chunks - 1) * (1 + (ext ? 4 : 0)));
    let p = 0;
    const t24 = ext ? 0xFFFFFF : ts;
    b[p++] = cs & 0x3F;
    b.writeUIntBE(t24, p, 3); p += 3;
    b.writeUIntBE(len, p, 3); p += 3;
    b[p++] = type;
    b.writeUInt32LE(sid >>> 0, p); p += 4;
    if (ext) { b.writeUInt32BE(ts >>> 0, p); p += 4; }
    let sent = 0;
    for (;;) {
      const n = Math.min(this.outChunk, len - sent);
      d.copy(b, p, sent, sent + n); p += n; sent += n;
      if (sent >= len) break;
      b[p++] = 0xC0 | (cs & 0x3F);
      if (ext) { b.writeUInt32BE(ts >>> 0, p); p += 4; }
    }
    this.sock.write(b.subarray(0, p));
  }

  async readMessage() {
    for (;;) {
      const b0 = (await this.read(1))[0];
      const fmt = b0 >>> 6; let cs = b0 & 0x3F;
      if (cs === 0) cs = 64 + (await this.read(1))[0];
      else if (cs === 1) { const x = await this.read(2); cs = 64 + x[0] + x[1] * 256; }
      let st = this.inStreams.get(cs);
      if (!st) { st = { ts: 0, delta: 0, len: 0, type: 0, sid: 0, filled: 0, ext: false, data: null }; this.inStreams.set(cs, st); }
      let ts = 0;
      if (fmt <= 2) ts = (await this.read(3)).readUIntBE(0, 3);
      if (fmt <= 1) { const h = await this.read(4); st.len = h.readUIntBE(0, 3); st.type = h[3]; }
      if (fmt === 0) st.sid = (await this.read(4)).readUInt32LE(0);
      if (fmt <= 2) { st.ext = ts === 0xFFFFFF; if (st.ext) ts = (await this.read(4)).readUInt32BE(0); }
      else if (st.ext) await this.read(4);
      if (!st.data) {
        if (fmt === 0) st.ts = ts; else if (fmt <= 2) { st.delta = ts; st.ts += ts; } else st.ts += st.delta;
        if (st.len > 16 * 1024 * 1024) throw new Error("Oversized RTMP message");
        st.data = Buffer.alloc(st.len); st.filled = 0;
      }
      const n = Math.min(this.inChunk, st.len - st.filled);
      (await this.read(n)).copy(st.data, st.filled); st.filled += n;
      this.maybeAck();
      if (st.filled < st.len) continue;
      const d = st.data; st.data = null;
      if (this.handleControl(st.type, d)) continue;
      return { type: st.type, data: d };
    }
  }

  handleControl(type, d) {
    switch (type) {
      case TYPE_SET_CHUNK: if (d.length >= 4) this.inChunk = Math.max(1, d.readUInt32BE(0) & 0x7FFFFFFF); return true;
      case TYPE_ABORT: if (d.length >= 4) { const st = this.inStreams.get(d.readUInt32BE(0)); if (st) st.data = null; } return true;
      case TYPE_ACK: return true;
      case TYPE_WINDOW: if (d.length >= 4) this.windowAck = d.readUInt32BE(0); return true;
      case TYPE_PEER_BW: if (d.length >= 4) this.writeMessage(CS_CONTROL, TYPE_WINDOW, 0, 0, d.subarray(0, 4)); return true;
      case TYPE_USER:
        if (d.length >= 6 && d[0] === 0 && d[1] === 6) this.writeMessage(CS_CONTROL, TYPE_USER, 0, 0, Buffer.from([0, 7, d[2], d[3], d[4], d[5]]));
        return true;
      default: return false;
    }
  }

  maybeAck() {
    if (this.windowAck > 0 && this.bytesIn - this.lastAck >= this.windowAck / 2) {
      this.lastAck = this.bytesIn;
      const b = Buffer.alloc(4); b.writeUInt32BE(this.bytesIn >>> 0);
      this.writeMessage(CS_CONTROL, TYPE_ACK, 0, 0, b);
    }
  }
}

// ---------------------------------------------------------------- session (one stream, many connections)
const CONNECT_TIMEOUT_MS = 10000, GIVE_UP_MS = 120000, STALL_MS = 8000, SOCKET_HIGH = 128 * 1024;

class LiveSession {
  /** emit({type: "state"|"stats"|"key"|"bitrate", ...}) goes to the page's window.onLive. */
  constructor(cfg, emit) {
    this.cfg = cfg; this.emit = emit;
    this.queue = []; this.queuedBytes = 0;
    this.stopping = false; this.accepting = false; this.dropToKey = true; this.finished = false;
    this.pub = null; this.closedReason = null; this.avcSeq = null; this.aacSeq = null;
    this.curKbps = cfg.videoKbps; this.liveSince = 0; this.lastVideoTs = 0;
    this.dropped = 0; this.videoSent = 0; this.lastCongestion = 0; this.lastBytes = 0; this.lastFrames = 0; this.calmSecs = 0;
    this.stall = { bytes: 0, since: 0 };
    this.wakeFn = null;
  }
  minKbps() { return Math.max(400, Math.floor(this.cfg.videoKbps * 35 / 100)); }
  state(s, msg) { this.emit({ type: "state", state: s, msg: msg || null }); }

  start() {
    this.ticker = setInterval(() => this.tick(), 1000);
    this.run().catch(e => { console.error(e); this.finish("error", "The stream stopped: " + (e.message || e)); });
  }

  stop() {
    if (this.stopping) return;
    this.stopping = true; this.accepting = false;
    this.wake();
    setTimeout(() => { if (this.pub && !this.finished) this.pub.abort(); this.wake(); }, 1500);
  }

  // ---- input from the page
  videoConfig(avcC) {
    const body = Buffer.concat([Buffer.from([0x17, 0, 0, 0, 0]), avcC]);
    const old = this.avcSeq;
    if (old && old.equals(body)) return;
    this.avcSeq = body;
    if (old && this.accepting) { this.enqueue({ type: TYPE_VIDEO, ts: this.lastVideoTs, body, key: false, config: true }); this.dropToKey = true; }
  }
  video(avcc, ts, key) {
    if (!this.accepting) return;
    if (this.dropToKey) { if (!key) { this.dropped++; return; } this.dropToKey = false; }
    if (this.queuedBytes + (this.pub ? this.pub.buffered : 0) > this.budgetBytes()) {
      this.congestion();
      if (!key) { this.dropped++; return; }
      this.dropToKey = false;
    }
    const body = Buffer.concat([Buffer.from([key ? 0x17 : 0x27, 1, 0, 0, 0]), avcc]);
    this.lastVideoTs = ts;
    this.enqueue({ type: TYPE_VIDEO, ts, body, key, config: false });
  }
  audioConfig(asc) { this.aacSeq = Buffer.concat([Buffer.from([0xAF, 0]), asc]); }
  audio(aac, ts) {
    if (!this.accepting) return;
    this.enqueue({ type: TYPE_AUDIO, ts, body: Buffer.concat([Buffer.from([0xAF, 1]), aac]), key: false, config: false });
  }

  enqueue(k) { this.queue.push(k); this.queuedBytes += k.body.length; this.pump(); }
  budgetBytes() { return Math.max(256 * 1024, (this.curKbps + this.cfg.audioKbps) * 125 * 2); }

  congestion() {
    const now = Date.now();
    if (now - this.lastCongestion < 2000) return;
    this.lastCongestion = now;
    this.queue = this.queue.filter(k => { if (k.type === TYPE_VIDEO && !k.config) { this.queuedBytes -= k.body.length; this.dropped++; return false; } return true; });
    this.dropToKey = true;
    this.emit({ type: "key" });
    const nk = Math.max(this.minKbps(), Math.floor(this.curKbps * 7 / 10));
    if (nk < this.curKbps) { this.curKbps = nk; this.emit({ type: "bitrate", kbps: nk }); }
    this.calmSecs = 0;
  }

  // ---- sending: written to the socket while it has room, the rest waits here (and can be dropped)
  pump() {
    const p = this.pub;
    if (!p || p.closed || !this.conn) return;
    while (this.queue.length && p.buffered < SOCKET_HIGH) {
      const k = this.queue.shift(); this.queuedBytes -= k.body.length;
      try { this.send(p, k); } catch (e) { this.closedReason = this.closedReason || "Connection lost."; p.abort(); this.wake(); return; }
    }
  }
  send(p, k) {
    const c = this.conn;
    if (k.type === TYPE_VIDEO) {
      if (k.config) { if (c.started) p.sendTag(TYPE_VIDEO, c.lastV, k.body); return; }
      if (!c.started) {
        if (!k.key || !this.avcSeq) return;
        c.base = k.ts; p.sendTag(TYPE_VIDEO, 0, this.avcSeq); c.started = true;
      }
      const ts = Math.max(c.lastV, k.ts - c.base);
      p.sendTag(TYPE_VIDEO, ts, k.body); c.lastV = ts; this.videoSent++;
    } else {
      if (!c.started) return;                        // sound starts with the first picture
      let ts = k.ts - c.base; if (ts < 0) return;
      if (!c.aacSent) { if (!this.aacSeq) return; p.sendTag(TYPE_AUDIO, ts, this.aacSeq); c.aacSent = true; }
      ts = Math.max(c.lastA, ts);
      p.sendTag(TYPE_AUDIO, ts, k.body); c.lastA = ts;
    }
  }

  wake() { const w = this.wakeFn; this.wakeFn = null; if (w) w(); }
  sleep(ms) { return new Promise(r => { const t = setTimeout(() => { this.wakeFn = null; r(); }, ms); this.wakeFn = () => { clearTimeout(t); r(); }; }); }

  async run() {
    let attempt = 0, failingSince = 0, wasLive = false, why = null;
    while (!this.stopping) {
      this.state(wasLive ? "reconnecting" : "connecting", why);
      let p;
      try { p = await RtmpPublisher.open(this.cfg.target, CONNECT_TIMEOUT_MS); }
      catch (e) {
        if (e instanceof Rejected) { this.finish("error", e.userMessage); return; }
        if (this.stopping) break;
        const now = Date.now(); if (!failingSince) failingSince = now;
        const certBad = /certificate|CERT_|self.signed|altname/i.test((e.code || "") + " " + (e.message || ""));
        if ((!wasLive && (attempt >= 2 || certBad)) || now - failingSince > GIVE_UP_MS) {
          this.finish("error", wasLive ? "Lost the connection to the server and couldn't get it back." : connectError(e)); return;
        }
        await this.sleep(Math.min(10000, 1000 * 2 ** Math.min(attempt, 4))); attempt++;
        continue;
      }
      if (this.stopping) { p.close(); break; }
      this.pub = p; this.closedReason = null;
      const closed = new Promise(r => { p.startReader(reason => { this.closedReason = reason; r(); this.wake(); }); });
      p.sock.on("drain", () => this.pump());
      p.sendMetadata(this.cfg);
      attempt = 0; failingSince = 0; wasLive = true;
      if (!this.liveSince) this.liveSince = Date.now();
      this.queue = []; this.queuedBytes = 0; this.dropToKey = true;
      this.conn = { started: false, aacSent: false, base: 0, lastV: 0, lastA: 0 };
      this.stall = { bytes: p.bytesOut, since: Date.now() };
      this.accepting = true;
      this.emit({ type: "key" });
      this.state("live");
      await Promise.race([closed, new Promise(r => { const chk = () => { if (this.stopping) r(); else this.wakeFn = chk; }; this.wakeFn = chk; })]);
      this.accepting = false; this.conn = null;
      if (this.stopping) break;
      p.abort();
      why = this.closedReason || "Connection lost.";
      failingSince = Date.now();
      await this.sleep(1000);
    }
    if (this.pub) this.pub.close();
    this.finish("stopped");
  }

  finish(state, msg) {
    if (this.finished) return;
    this.finished = true; this.stopping = true; this.accepting = false;
    clearInterval(this.ticker); this.queue = []; this.queuedBytes = 0;
    this.state(state, msg);
  }

  tick() {
    const p = this.pub;
    if (p && !p.closed) {                              // a write that hasn't moved for 8 s: the network is gone
      if (p.buffered > 0 && p.bytesOut === this.stall.bytes) { if (Date.now() - this.stall.since > STALL_MS) { this.closedReason = "The network stalled."; p.abort(); this.wake(); } }
      else this.stall = { bytes: p.bytesOut, since: Date.now() };
    }
    const bytes = p ? p.bytesOut : this.lastBytes;
    if (bytes < this.lastBytes) this.lastBytes = 0;
    const kbps = Math.round((bytes - this.lastBytes) * 8 / 1000); this.lastBytes = bytes;
    const fps = this.videoSent - this.lastFrames; this.lastFrames = this.videoSent;
    const queueMs = Math.round((this.queuedBytes + (p ? p.buffered : 0)) * 8 / Math.max(1, this.curKbps + this.cfg.audioKbps));
    if (this.accepting) {
      this.calmSecs = queueMs < 300 ? this.calmSecs + 1 : 0;
      if (this.calmSecs >= 15 && this.curKbps < this.cfg.videoKbps) {
        this.curKbps = Math.min(this.cfg.videoKbps, Math.floor(this.curKbps * 115 / 100) + 50); this.calmSecs = 0;
        this.emit({ type: "bitrate", kbps: this.curKbps });
      }
    }
    const secs = this.liveSince ? Math.floor((Date.now() - this.liveSince) / 1000) : 0;
    this.emit({ type: "stats", kbps, target: this.curKbps, fps, queueMs, dropped: this.dropped, secs });
  }
}

function connectError(e) {
  const c = e.code || "", m = e.message || c || String(e);
  if (c === "ENOTFOUND" || c === "EAI_AGAIN") return "Couldn't find the server. Check the server URL and your internet connection.";
  if (c === "ETIMEDOUT") return "The server didn't answer. Check the server URL, and that the server is running.";
  if (c === "ECONNREFUSED") return "The server refused the connection. Check the server URL and port, and that the server is running.";
  if (/certificate|CERT_|altname/i.test(c + " " + m)) return "The server's security certificate isn't valid for this address. Check the server URL.";
  return "Couldn't connect: " + m;
}

module.exports = { LiveSession, RtmpPublisher, parseTarget, amf, TYPE_AUDIO, TYPE_VIDEO };
