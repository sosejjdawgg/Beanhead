# Live streaming

© 2026 James Scott. All rights reserved.

The Android app can stream the scene live over RTMP or RTMPS to TikTok, YouTube, Twitch, Kick or any other service that gives you a server URL and a stream key, or into OBS on your own computer. Viewers see only your character and its background. The menus, buttons and camera preview never appear on the stream, so you can keep using the controls while you're live.

## Using it

1. Open the Peanut or a character, then tap the **broadcast button** (above the mic button).
2. Paste the **Server URL** (`rtmp://…` or `rtmps://…`) and the **Stream key**. You can also paste one full URL with the key on the end and leave the key box empty.
3. Pick **Portrait** (TikTok) or **Landscape** (most others), a quality and a frame rate.
4. Tap **Go live**. The chip at the top of the screen shows the time. Tap it to see the bitrate or to end the stream.

| Quality  | Picture | Video bitrate | Upload you need |
|----------|---------|---------------|-----------------|
| Low      | 720p    | 2 Mbps        | about 3 Mbps    |
| Standard | 720p    | 3.5 Mbps      | about 5 Mbps    |
| High     | 1080p   | 6 Mbps        | about 8 Mbps    |

Sound is AAC at 128 kbps, 48 kHz. **Sound delay** (lip sync, default 100 ms) holds the sound back a little, because the picture runs slightly behind your voice after face tracking. Raise it if your mouth moves after you speak; lower it if the mouth moves first.

### TikTok

- If your account can use streaming software, TikTok shows a Server URL and Stream key when you set up a LIVE. Paste both, choose Portrait, tap **Go live** here first, then start the LIVE in TikTok.
- TikTok's keys often work only once. If it won't connect, get a fresh key.
- **TikTok LIVE Studio** on a computer: bring Peanut Cam into OBS (below), turn on OBS's **Virtual Camera**, and add it as a camera in LIVE Studio.

### OBS

OBS plays streams but can't receive one pushed straight from the phone, so a small free server sits in between:

1. On the computer, download and run [MediaMTX](https://github.com/bluenviron/mediamtx). It listens for RTMP on port 1935. Allow it through the firewall if asked.
2. Find the computer's local IP address (Windows: `ipconfig`; macOS: System Settings › Wi-Fi › Details), for example `192.168.1.20`.
3. In Peanut Cam: server URL `rtmp://192.168.1.20/live`, stream key `peanut`, then **Go live**. The phone and computer must be on the same network.
4. In OBS: **Sources › + › Media Source**, untick *Local File*, set *Input* to `rtmp://localhost/live/peanut`, and tick *Use hardware decoding when available*.
5. For a key, use the **Green screen** background in Peanut Cam and add a *Chroma Key* filter in OBS.

## How it works (for maintainers)

```
WebGL canvas ─▶ 2D canvas (background + scene, stream size) ─▶ VideoFrame ─▶ WebCodecs VideoEncoder (hardware H.264)
   index.html "live streaming" section                                             │ AVCC chunks + avcC, base64
                                                                                   ▼
Android  PeanutLive bridge (LiveController) ─▶ LiveSession (queue, reconnect, congestion) ─▶ RtmpPublisher ─▶ server
                         MicEncoder (AudioRecord ─▶ MediaCodec AAC) ──────┘
```

**Why the page encodes, not a native capture source.** A WebView can't draw into an encoder `Surface`. A native "scene source" (for StreamPack or anything similar) would therefore need one of these:

- MediaProjection screen capture, which asks for consent every session and captures the HUD;
- a second WebView rendering the scene a second time, which also needs a second camera;
- copying pixels out of the page every frame.

The page already has the exact frame (recordings use the same composition). WebCodecs `VideoEncoder` in Android's WebView runs on the phone's hardware H.264 encoder (MediaCodec), so only compressed frames, about 10–20 KB each, cross the JS bridge. That costs no extra rendering, needs no consent prompts, and keeps the stream clean by construction. Native code only adds the microphone and speaks RTMP, in plain Java with no new dependencies.

**Files**

- `index.html`, section `live streaming`:
  - `liveFrame()` runs after each render and paces frames to the chosen fps.
  - `liveCompose()` paints the background with `paintBg` and fits the scene. A 3D world fills the frame instead.
  - `liveEncoderConfig()` picks the best supported H.264 setup in this order: hardware before software, Main before Baseline, constant bitrate before variable.
  - Settings live in `localStorage["peanutcam.live"]`, apart from `S` and character data, so the stream key never ends up in a character.
- `android/.../live/`:
  - `LiveController`: the `PeanutLive` JS bridge and permissions. It sends events back to the page as `window.onLive`.
  - `LiveSession`: one stream. It queues frames and reconnects with backoff for up to 2 minutes. When about 2 s of data is waiting, it drops queued video back to a key frame and asks the page for a lower bitrate (−30 %, down to 35 % of the preset). It raises the bitrate again after 15 calm seconds. A watchdog drops the socket if a write blocks for 8 s.
  - `RtmpPublisher`: handshake, connect, publish, chunking, AMF0 and FLV tags.
  - `MicEncoder`: always sends an audio track. When sound is off or the mic is unavailable, it encodes silence on the same clock, because some servers won't start a stream without audio.
  - `LiveService`: a foreground service (microphone, or dataSync without mic permission) with an *End stream* notification and Wi-Fi/CPU locks.
- The bridge protocol: `start(json)`, which returns `""` or an error message; `config(b64 avcC)`; `video(b64, tsMs, isKey)`; `sound(on)`; `soundDelay(ms)`; `stop()`. Events are `{type: state|stats|key|bitrate|mic}`.
- Timestamps share one clock. The page sets `LIVE.t0` just before calling `start`, and the app takes `System.nanoTime()` in that same call. Each connection is rebased to start at 0 on its first key frame.

**Limits**

- Android stops drawing the WebView while the app is hidden or the screen is off, so viewers see the last frame until you come back. Sound and the connection carry on through the foreground service.
- B-frames aren't expected: Chrome's WebCodecs H.264 encoders don't produce them, so the FLV composition time is always 0.
- WebCodecs needs Android System WebView or Chrome 94+. The panel says so if it's missing.

**Testing off the phone.** `RtmpPublisher` and `LiveSession` are pure Java. They can be compiled with `javac` and driven by a small harness against [MediaMTX](https://github.com/bluenviron/mediamtx) or `ffmpeg -listen 1`. The page side can be driven in headless Chromium, whose WebCodecs has a software H.264 encoder, with a stub `window.PeanutLive` that forwards to that harness. Verify the result with `ffprobe` on a pulled copy (`ffmpeg -i rtmp://…/live/peanut -c copy out.flv`).
