# Peanut Cam: notes for Claude

© 2026 James Scott. All rights reserved (see COPYRIGHT.md, LICENSE). The repo is public only so it can be built and hosted. It is not open source.

Peanut Cam puts the user's real eyes and mouth, tracked live from the front camera, onto a character: a 3D peanut, a 2D cut-out picture on a wooden stick, or an imported 3D model. It runs as a web page and as an Android app.

## Layout

- `index.html` is the whole app (~4,000 lines): HTML, CSS and one `<script type="module">`. There is no bundler or framework.
  - three.js 0.160 and MediaPipe tasks-vision 0.10.14 load via an importmap.
  - three-vrm 2.1 loads lazily.
- `android/` is a thin Java wrapper (`MainActivity.java`).
  - A WebView serves the page from `assets/www` at `https://appassets.androidplatform.net/` through `shouldInterceptRequest`.
  - It also has: a camera/mic permission bridge, a multi-file chooser (ClipData), a share-intent image served at `/__shared`, and a `PeanutNative` JS bridge (begin/append/finish/prepare/hasShared/clearShared) that saves recordings and pictures to MediaStore Pictures/Movies/PeanutCam.
  - `live/` is live streaming, self-contained: the `PeanutLive` bridge (`LiveController`), RTMP (`RtmpPublisher`, `LiveSession`, `Amf0`, pure Java), mic AAC (`MicEncoder`) and a foreground service (`LiveService`). MainActivity only registers it, forwards permission results and calls `release()`. See `docs/LIVE_STREAMING.md`.
- `.github/workflows/android.yml` runs on every push to `main`. It:
  - npm-installs three, tasks-vision and three-vrm;
  - copies `index.html`, the libraries, the loaders and the MediaPipe models into `android/app/src/main/assets/www`, and bundles third-party licence texts into `www/licenses/`;
  - builds a debug APK signed with the fixed `debug.keystore` (so updates install over the top);
  - publishes the APK as GitHub release **`latest`** (`PeanutCam.apk`).
- `peanut-cam.html` is just a redirect to `./` (GitHub Pages hosts the web version).
- `COPYRIGHT.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md` hold the IP policy. The in-app About & legal screen mirrors them (search `openLegal`). If you add a third-party dependency, add it to THIRD_PARTY_NOTICES.md, to the About & legal list, and to the licence step in the workflow.

## How index.html is organised

Sections start with `/* ---------- name ---------- */`; grep for that to navigate. In order: settings, three.js scene, peanut, PSX snapping, imported 3D models, face placement on models (`fitFace`), rigging tool, puppet (2D), face features, stage (+ the `VIEW` camera), background, videos (playlists, mini TV, sound mixer), camera, MediaPipe, arm/hand tracking, per-frame face geometry (`track`, `faceLayout`, `placeFace`), picture upload + scan, outlines/cut-out editor, character gallery, about & legal, settings sheet, layout editor, place-face tool, joint editor, studios, menu/modes/HUD, touch gestures, recording, screenshots, live streaming, PSX 3D world, filters (`FX`), render loop, boot.

Key ideas:

- **Asset paths.** Local mode applies when the host is `appassets.androidplatform.net` or `localhost`: libraries come from `./lib`, models from `./models`. Otherwise libraries come from the jsdelivr CDN and models from storage.googleapis.com.
- **Face features.** The eyes and mouth are ring meshes built from MediaPipe landmarks. Their UVs come from the camera frame; their positions come from frontalised landmarks. `placeFeature(..., rot)` lays them onto a surface function `SURF`:
  - `onSurface` (peanut);
  - `onFlat` (2D puppet);
  - `onModel` (an ellipsoid cap fitted by `fitFace`; per-feature depth comes from `mEyeZ`/`mMouthZ`, blended by height).
- **Settings.** There is one global `S` object. Keys listed in `NUT_KEYS`, `CHAR_KEYS` and `MODEL_KEYS` are scoped per character or the peanut (`loadScope`, `scopeId`). Characters are stored in IndexedDB: the `chars` list plus `char:<id>` records with `png`, `original`, `meta`, `cfg`, `files` and `thumb`.
- **2D characters.**
  - `meta` holds eye/mouth pixel positions, `unit`, `tint`, `crop` (where the cut-out sits in the scaled original), `rot` (per-feature tilt) and `strokes` (outlines, with width as a fraction of height).
  - `outlineCanvas` adds the outline at load time and pads the canvas. `PUP.pad` keeps coordinates stable, so always go through `pxToWorld` / `screenToPx`.
  - `clearPuppet()` must run before building a new puppet (the stacking bug fixed in 4.1.1).
- **3D models.**
  - `loadModelFile` handles file sets and zips through a `LoadingManager` URL modifier keyed by lowercase basename. `applyLooseTextures` matches loose image files to materials by name.
  - `setModelFrom` fits the model by GPU silhouette, not CPU boxes, which are wrong for Mixamo exports. It is ticketed against overlapping loads.
  - `findRig` / `prepareRig` / `aim` handle retargeting.
  - Rigs added by the tool are saved in `cfg.autoRig = {type, joints}` and skinned on load by `skinModel`.
  - `applyModelStyle` must never touch the face overlay meshes when they ride on the head bone.
- **PSX look.** `psxPatch` adds vertex snapping (whole low-res pixels, `uPsxJ` = 1-3 px cells) and affine texturing (`uAff`, `vPsxUv`) to materials through `onBeforeCompile`. Never apply it to `ShaderMaterial`s, which declare `uSnap` themselves (feature meshes use `snapGlsl("0.5")`). The PSX character pass (`post2`) must leave empty pixels at alpha 0.
- **Filters (`FX`).** After the render and `renderTvFront`, `FX.apply` copies the frame (`copyFramebufferToTexture`) and draws it back through a filter shader (`BODIES`), compositing the CSS background (`paintBg` into a texture, `VideoTexture` for video). Scoped keys `fx`, `fxAmt`. `glOpaque()` (world, filter, or a direct recording) means the canvas already has the background: captures must not paint it again.
- **Menus.** One sheet, two tab sets: `TABS[tabKey()]` (Settings: character tab, Face, Look, Motion, Capture, Camera) and `STUDIO_TABS` (Scene, TV, Cast, Cameras); `openSheet(kind, tab)`, `SHEET.kind`, `curTab[tabId()]`. Item type `fn` renders a builder (`studioCastBody`, `studioCamsBody`, `tvSettingsBody`). Builders shared with modals close with `closePanels()`.
- **Back and long-press.** `window.onBack()` closes the top layer (modal, sheet, tool bars by their cancel/done buttons, Hide, then the mode) and returns false only on the home screen; Android's `onBackPressed` calls it, Esc does too. New tools with their own bar must be added to its list. `holdable(tile, btn, tap, hold)` gives home-screen tiles tap/hold/right-click. Duplicating a character deep-clones `cfg`; clips are deleted through `dropMedia`, which keeps files another playlist still uses.
- **Speed.** `loop` caps frames to `S.fpsCap`; `perfStep` adapts the pixel ratio in Auto (`PERF`), never while recording or live.
- **Recording.** `recPlan()`: at full screen size MediaRecorder records the WebGL canvas directly (the FX pass draws the background in); smaller sizes go through a 2D canvas fed with `requestFrame()` at `S.recFps`. Bitrate = pixels × fps × `REC_BPP[S.recQ]`. Recordings and pictures go to the gallery via the native bridge on Android. When videos play sound, the recording takes the WebAudio mixer (`AUD.rec`: videos + mic) instead of the bare mic.
- **Camera (`VIEW`).** `resize()` sets a base framing (models: `S.mFrame` full/upper/head); `VIEW.over()` returns an override the camera glides to each frame (`stepView`): Place face (`placeView`) first, then the active studio camera (`studioView`). Don't set `camera.position` directly.
- **Videos.** Clips live in IndexedDB as `media:<id>`; `Playlist` (`BGP` background, `TVP` mini TV) loops one or plays all in order. Scoped keys `bgList/bgMode/bgVol` and `tv` (an object: always go through `own(k)` / `tvCfg()` so the shared default is never mutated; `tvCfg()` also fills keys added later). The TV (`TV_MODELS`, `buildTvModel`) shows the clip through a `ShaderMaterial` (`tvPicture` sets crop/fit/zoom/sharpness/colour uniforms; `TRIM` samples frames to trim black bars); with `tv.front` it lives in `tvScene` and `renderTvFront` draws it after the main pass (never occluded, full resolution in PSX styles). `tv.amb` paints a blurred copy (`#tvAmb`, `paintAmb`) as the background, which `paintBg` uses first. The TV camera is a studio shot with `rel:"tv"`, framed live by `tvFrame` unless `custom`. Video sound goes through `AUD` (one gain per element into `AUD.bus`, `S.hearVideos` mutes the speaker only); while live, an AudioWorklet tap sends 20 ms of 48 kHz PCM to `PeanutLive.pcm`, which `MicEncoder` mixes in through a jitter buffer.
- **Scenes.** Scoped `sceneId` (`free`, `tvroom`, `car`) + `sceneBak` (what the scene borrowed: background, world, TV placement; restored on leaving). `setScene` applies, `buildScene` rebuilds props into `SCN.g`, `sceneView` is the scene camera (scaled by `S.zoom`) (after Place face and studio shots in `VIEW.over`), `stepScene` runs the TV flicker light (video colour sampled from `TVP`), car shake and passing lights, and sets the stage light levels each frame. `FACE_LIGHT.k` dims the live face with the room. Rigged models sit in scenes with a seat (`syncSeat` / `sitDown` bend thighs and knees in world space, `SIT.dy` lifts the host slot so the head lands at `SEAT_HEAD`). Scenes aren't locked: `sceneLayout` puts back the starting layout (host defaults + `SCENE_TV`) on pick, `loadScope` (reopen/restart) and Reset (`resetPose`); gestures move things freely in between. Extra screens that show the TV's video register in `TV.extra` so `tvPicture` fits them too.
- **Built-in characters.** `BUILTIN.skater.make` builds the skater in code (skeleton with `mixamorig_` names, one `SkinnedMesh` per material, canvas textures, an `Idle` clip). Characters with `builtin` have no files; `loadModelFile` returns the made model and `loadChars` keeps them.
- **Studios.** Scoped key `studio` = `{cast:[{uid, ref, x,y,z,s,r, motion}], host, shots:[{actor, rel, pos, tgt}], shot, smooth, lock}`. Cast members are built by `buildExtra` (models via `buildModel`, pictures via `buildPuppetInto`, peanut via `cloneNut`); `scopeId` is the studio owner and `leadRef` the character wearing the live face. `swapLead` moves the lead's content out to a cast shell and the chosen member's content into the stage rig (`leadSlot` holds `rig`, `puppet`, `mrig`). `storeScope` writes studio keys to the owner and character keys to `leadRef`. Character-editing tools must use `leadRef`, not `scopeId`. Loops (`loop:<ref>`) hold the face buffers + pose per sample (`LOOP_STRIDE` floats) and a 256² face-crop video (WebCodecs H.264/VP8); `stepLoop` decodes it back onto the member's own feature meshes.

## Workflow

- Make your edits, then syntax-check the module:
  ```
  python3 -c "import re;s=open('index.html').read();open('/tmp/t.mjs','w').write(re.findall(r'<script type=\"module\">(.*?)</script>',s,re.S)[0])" && node --check /tmp/t.mjs
  ```
- Bump `versionCode` / `versionName` in `android/app/build.gradle` and `VERSION` in index.html (About screen) for each release.
- Commit and push to `main`; CI builds the APK in about 3 minutes. Run `gh run watch` on the "Build Android APK" run.
- Download the APK with the **REST** API, since GraphQL (`gh release download`) is blocked in Claude sessions:
  ```
  id=$(gh api repos/sosejjdawgg/Beanhead/releases/tags/latest -q '.assets[]|select(.name=="PeanutCam.apk")|.id')
  gh api -H "Accept: application/octet-stream" repos/sosejjdawgg/Beanhead/releases/assets/$id > PeanutCam.apk
  ```
  Verify it with `unzip -p PeanutCam.apk assets/www/index.html | grep <new code>`, then send it to the user.
- The repo has moved; push still works and prints a harmless "moved" notice.

## Testing (headless)

There is no Android device or emulator here. Test the page in Chromium with Playwright.

1. Serve a test copy (not the repo file) from a folder that has `lib/` and `models/`. The easiest way to fill them is to unzip `assets/www/*` from the latest release APK, then replace its `index.html` with yours. Serve it with `python3 -m http.server 8765`, run in the background with a long timeout.
2. Launch Chromium with these flags:
   ```
   --use-angle=swiftshader --enable-unsafe-swiftshader --use-fake-ui-for-media-stream --use-fake-device-for-media-stream --use-file-for-fake-video-capture=<face>.y4m
   ```
   Use a phone viewport (412×880).
3. Expose internals in the **test copy only**: put `window.__M = () => ({MDL, PUP, ...})` after `const BODY = ...`. Never commit hooks like this.
4. Software rendering is slow. Picture uploads take about 10 s while the scan runs, and saving a cut-out takes about 3 s, so wait for state rather than sleeping briefly. Close the touch-controls tips modal (`#mClose`) that appears the first time a character opens.
5. Live pose/hand tracking and VRM can't be exercised offline. Inject fake pose landmarks into `window.__BODY` instead.

## Known rough edges

- Auto-rig skin weights are distance-based with limb gates, so long coats can web or stretch when arms are raised high.
- The face auto-find on models uses the silhouette (neck) for bipeds and the most forward point for pets. The user can always fix it with Place face.
- Characters saved without their original photo can't use Restore in the cut-out editor.
- Face auto-find on models sizes the face from the curvature around the nose; low-poly models with a separate nose blob get a tiny face until Place face is used (it now zooms in, so this is quick to fix).
- Studio loops record the face and head movement, not arm/hand tracking. Shots on a character are stored in that character's frame (`rel`), Wide shots are absolute.
- Swapping the live face onto a model that isn't loaded as a cast member isn't possible; only cast members can be put on camera.

## Live streaming (4.5)

Full write-up: `docs/LIVE_STREAMING.md`. In short:

- **The page encodes; the app publishes.** `liveFrame()` (render loop, after `recFrame()`) composes background + scene into a stream-sized 2D canvas and encodes it with WebCodecs `VideoEncoder`, which is hardware H.264 in Android's WebView. Chunks go to `PeanutLive.video()` as base64. The app adds mic AAC and speaks RTMP/RTMPS itself, with no StreamPack or other dependency. This was chosen over a native capture source because a WebView can't render into an encoder Surface: that route means MediaProjection (consent, captures the HUD) or rendering twice.
- **The stream is clean by construction.** Only the composed canvas is sent, so there's no separate "stream mode". The HUD stays usable while live.
- **Stream keys** are stored only in `localStorage["peanutcam.live"]`, kept apart from `S` and character records. Never log or commit them; `RtmpPublisher.Target.toString()` hides the key.
- **Robustness lives in `LiveSession`.** Reconnect with backoff (2 min), drop-to-keyframe plus lower bitrate when the upload falls behind (the page reconfigures the encoder on `bitrate` events), and a stall watchdog. The page rebuilds the encoder if Android reclaims it.
- **Testing.** The RTMP layer is pure Java: compile it with `javac` and drive it from a harness against MediaMTX (build with `go build` after creating `internal/core/VERSION` and `internal/servers/hls/hls.min.js`) or `ffmpeg -listen 1`. For the page, stub `window.PeanutLive` in Playwright and forward calls to that harness. Headless Chromium has a software H.264 encoder. Software rendering only reaches about 3 fps here, so judge real frame rate on a phone (the stats show it).
- **Not done yet.** No on-device test of the hardware encoder path. SRT isn't supported. The picture freezes while the app is in the background, because Android stops drawing the WebView.

## Commits

Commit as Claude (`git -c user.name=Claude -c user.email=noreply@anthropic.com commit ...`), with a summary line plus bullets, ending with the session attribution lines the environment provides.
