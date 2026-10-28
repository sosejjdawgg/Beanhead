# Peanut Cam for Windows

© 2026 James Scott. All rights reserved.

The Windows app is the same Peanut Cam as on Android: characters, studios, scenes, the TV, filters, the PS1/PS2 looks, recording, pictures and live streaming. It adds what a computer needs: mouse and keyboard controls, a side panel layout, PC sound, and cameras of every kind, including an Android phone over USB.

## Installing

- **PeanutCam-Setup.exe** installs Peanut Cam for your Windows user (Start menu and desktop shortcuts, uninstall from Settings › Apps).
- **PeanutCam-Portable.exe** runs without installing (it unpacks itself each time, so it starts a little slower).
- The app isn't code-signed yet, so Windows SmartScreen may say it's unrecognised: choose **More info › Run anyway**.
- Windows 10 or 11, 64-bit. The latest build is on the GitHub release **latest-windows**.

Recordings go to **Videos › PeanutCam** and pictures to **Pictures › PeanutCam**; the message after saving has a **Show** button. Your characters and settings are stored in the app on this PC (they don't come over from the phone).

## Mouse and keyboard

| Do | To |
|---|---|
| Drag | Move a character; spin and tilt the Peanut |
| Scroll / Shift + scroll | Resize / turn |
| Double-click | Put it back where it started |
| ← → / ↑ ↓ | Previous or next background / character |
| R, P | Start or stop recording, take a picture |
| S, L, T | Settings, Look, Studio |
| H, F11, Esc | Hide the controls, full screen, close the top panel (then back to the menu) |

Drop a picture onto the window to make a character, or a 3D model (its files, or a zip) to import it.

## Cameras

**Settings › Camera** lists every camera Windows can see, plus **Phone over USB**. **Camera quality** asks webcams for 720p, 1080p or 4K.

### An Android phone over USB (front or back)

Nothing is installed on the phone; the app uses Android's debugging connection (adb) and starts the camera itself with scrcpy's server.

1. Android 12 or newer.
2. On the phone: **Settings › About phone**, tap **Build number** seven times (Developer options appear).
3. **Settings › System › Developer options**: turn on **USB debugging**.
4. Connect with a USB *data* cable, unlock the phone, tap **Allow** (tick *Always allow from this computer*).
5. In Peanut Cam pick **Phone over USB**. Choose **Back** or **Front** (or press **Flip**), 720p or 1080p, 30 or 60 fps, and **Turn picture** if it's sideways.

If the phone isn't found, install its maker's USB driver (Samsung Android USB Driver; Google USB Driver for Pixels). Android 14 and newer can also act as a plain webcam without debugging: plug in, open the USB notification on the phone, choose **Webcam**; it then appears in the list (switch cameras on the phone).

iPhone: use a webcam app such as Camo or iVCam (phone + PC); it appears in the list.

### Mirrorless, DSLR and other cameras

- Webcams, USB cameras, HDMI capture cards and OBS Virtual Camera work as they are: plug in, pick.
- **Canon** (EOS Webcam Utility), **Sony** (Imaging Edge Webcam; newer models stream over USB directly), **Nikon** (Webcam Utility), **Fujifilm** (X Webcam), **Panasonic** (LUMIX Webcam Software; newer models stream over USB), **OM System** (OM Capture), **GoPro** (GoPro Webcam): install the maker's free webcam software, connect by USB, and pick the camera it adds. The in-app guide has a **Find it** button for each.
- Any camera with a clean HDMI output works through a USB capture card (Elgato Cam Link or a generic HDMI-to-USB stick).

## Sound

**Settings › Capture › Sound**: **PC sound** (everything playing on the PC), **Peanut Cam videos** and **Microphone** (with a choice of microphone). The same switches apply to recordings and live streams. While *Hear video sound* is on, Peanut Cam's videos are part of the PC sound, so they're counted once. Use headphones with the microphone on.

## Live streaming

Same panel as on Android (the broadcast button). The PC encodes the picture (H.264) and sound (AAC) with its own hardware where it can, and the app publishes over RTMP/RTMPS with the same reconnect and quality control as the phone. To stream into OBS on the same PC: run MediaMTX and use `rtmp://localhost/live`, or add a **Window Capture** of Peanut Cam in OBS and press **H** to hide the controls.

## For maintainers

- `desktop/` is an Electron app. `main.js` serves the bundled page from `app://peanut/` (`desktop/www`, filled by `scripts/bundle-www.sh`), answers permission and display-media (PC sound) requests, saves files (the `PeanutNative` bridge, awaited by the page), runs live streams (`rtmp.js`: the Java RTMP publisher and session ported to Node; the page sends AAC as well as H.264 because `PeanutLive.encodesAudio`) and the phone camera (`phonecam.js`).
- **Phone camera:** `phonecam.js` pushes `vendor/scrcpy-server` (v3.1, checksum-pinned in `fetch-tools.js`) with the bundled adb, forwards a port, starts the server with `video_source=camera`, reads its stream (codec meta, then 12-byte packet headers) and sends packets to the page (`phone:packet`). The page (`PCAM`, `phonePacket`) decodes them with WebCodecs onto a canvas whose `captureStream` is the camera. `PEANUT_FAKE_PHONE=<file.h264>` plays a file instead, for tests.
- **Build:** `cd desktop && npm ci && bash ../scripts/bundle-www.sh www && node fetch-tools.js && npx electron-builder --win --x64`. On Linux this needs wine (64- and 32-bit). CI: `.github/workflows/windows.yml` builds on `windows-latest` for every push to `main` and publishes the release **latest-windows**.
- **Testing:** run the Linux Electron (`desktop/node_modules/electron/dist/electron`) under `xvfb-run` with the usual Chromium fake-camera flags and drive it with Playwright's `_electron`. Linux has no AAC encoder, so live streams there are silent; Windows has one.
