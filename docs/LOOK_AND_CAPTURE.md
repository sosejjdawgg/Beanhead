# Look, filters and capture

© 2026 James Scott. All rights reserved.

## Where things are

The bottom bar has five buttons:

| Button | What it holds |
|---|---|
| **Studio** | The set, the same for every character: **Scene** (scenes, background, 3D world, light, zoom), **TV**, **Cast** (characters, loops, lock positions) and **Cameras**. |
| **Look** | Shortcut to **Settings › Look**: filters and the 3D style. |
| **Settings** | The character: **Peanut / Puppet / Model** (its own options and tools), **Face**, **Look**, **Motion**, **Capture** and **Camera** (camera choice, mirror, About & legal, reset). |
| **Flip** | Front or back camera. |
| **Hide** | Hides the controls. |

**Home screen:** tap a character to open it. Hold it (or right-click, or tap its **⋯**) for its options: rename, background, **Open**, **Duplicate**, **Move to front** and **Delete** (with **Undo** for a few seconds). Holding the Peanut card opens its background.

**Back:** Android's back button (Esc on a keyboard) closes whatever is on top (a dialog, the settings sheet, a tool), then goes back to the home screen. Only on the home screen does it close the app.

## Filters

**Settings › Look › Filter** puts a finishing pass over the whole picture, background included, so it shows up in recordings, pictures and live streams too. **Strength** sets how strong it is.

| Filter | Look |
|---|---|
| CRT TV | A home TV: curved glass, scanlines that bloom on bright parts, a phosphor mask, a dark vignette and rounded corners. |
| Pro monitor | A flat studio monitor (PVM style): sharp, tight scanlines and an aperture grille. |
| Arcade | A strongly curved arcade cabinet tube: heavy scanlines, strong glow, punchy colour. |
| VHS | A taped-off-the-TV look: smeared colour, line jitter, a rolling tracking band, head-switching noise at the bottom, and a PLAY / date on-screen display. |
| Film | 16 mm film: grain, warm grade, gate weave, flicker, the odd scratch and dust spot. |
| Game Boy | Four greens, ordered dithering and an LCD grid. |
| 8-bit | Big pixels in a 16-colour palette (PICO-8 colours) with dithering. |
| Glitch | RGB split with bursts of torn, shifted blocks and swapped colour. |
| Dreamy | Soft bloom, pastel tint and a glowing edge. |
| Comic | Posterised colour, ink outlines and halftone dots in the shadows. |
| Noir | High-contrast black and white with grain and vignette. |
| Night vision | Green phosphor, noise and a round scope. |
| Thermal | A heat-camera palette. |
| CCTV | Black-and-white fisheye security camera with a CAM 01 / REC / time display. |

The CRT-type filters were chosen because they're the most popular retro looks for streams: OBS plugins such as Retro Effects and obs-shaderfilter offer the same family (CRT, NTSC/VHS, scanlines, curvature, chromatic aberration).

**For maintainers:** `FX` in index.html (section "filters"). After the frame is drawn (and the front TV pass), `copyFramebufferToTexture` copies it into a texture; one filter shader (`BODIES`, compiled on first use) draws it back over the screen, compositing the CSS background underneath (painted into a texture, or a `VideoTexture` for a video background). `glOpaque()` tells recordings, pictures and the live stream that the canvas already holds the background.

## 3D style and the PS1 look

**Settings › Look › 3D style** switches between Modern, PS2 and PS1. In PS1 and PS2 the scene is drawn at a low resolution and snapped like the original hardware:

- **Vertex wobble** snaps each vertex to the low-res pixel grid (1 to 3 pixels), so shapes shimmer as they move but keep their outline.
- **Texture warp** maps textures without perspective correction (affine), like the PlayStation did.
- **Dithering** and **Colour depth** set the 15/12/9-bit ordered dither.

## Capture

**Settings › Capture:**

- **Video size:** Full screen records the picture exactly as drawn, straight from the 3D canvas, which is the lightest on the phone. 1080p, 720p and 540p scale it down for smaller files.
- **Video frame rate:** 30 or 60 fps. Frames are handed to the recorder at exactly that rate.
- **Video quality:** the bitrate follows the size and frame rate (about 0.08, 0.13 or 0.2 bits per pixel per frame, 2 to 24 Mbps).
- **Sound** (used by recordings and live streams), three switches you can mix freely:
  - **Phone sound (music, other apps):** Android app, Android 10 or newer. Everything other apps play (Spotify, YouTube, games…) is captured, even while you listen on headphones. Android shows its "start recording or casting" prompt each time a recording or stream begins; tap **Start**. A notification shows while it runs. A few apps block capture (phone calls, some protected players) and stay silent.
  - **Peanut Cam videos (TV, background):** the app's own video sound. It is never captured twice.
  - **Microphone**, with **Which microphone**: Automatic (a headset or USB mic when plugged in), or a specific one.
  - Tap the mic button on screen to switch the microphone; hold it to open these settings.
- **Picture format** (JPEG or lossless PNG) and **Live stream settings**.
- **Drawing quality:** Auto lowers the drawing resolution when the phone can't keep up and raises it again when it can (it undoes a step that doesn't help, and never changes mid-recording or live). Sharpest, Smooth and Battery saver fix it at 2×, 1.5× and 1×.
- **Frame rate limit:** 60 or 30. On 90/120 Hz screens this stops the app drawing frames nobody records, which keeps the phone cooler and the frame rate steady.

**For maintainers (phone sound):** `android/.../audio/PhoneAudio.java` is the `PeanutAudio` bridge. `start()` asks for the mic permission (playback capture needs it) and the MediaProjection consent; `PhoneAudioService` (foreground, type mediaProjection) must be running before the projection is taken. An `AudioRecord` with an `AudioPlaybackCaptureConfiguration` (media, game, unknown usages, our own UID excluded) fills a one-second ring that the page drains with `read()` every 25 ms into an AudioWorklet (`pc-play`, an adaptive jitter buffer, 100–600 ms) inside the recording's WebAudio mix. A live stream gets the same samples inside the app (`PhoneAudio.setLiveSink` → `MicEncoder.mixPhone`, its own jitter buffer). The capture stops as soon as neither a recording nor a stream needs it.
