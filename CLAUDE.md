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
- `.github/workflows/android.yml` runs on every push to `main`. It:
  - npm-installs three, tasks-vision and three-vrm;
  - copies `index.html`, the libraries, the loaders and the MediaPipe models into `android/app/src/main/assets/www`, and bundles third-party licence texts into `www/licenses/`;
  - builds a debug APK signed with the fixed `debug.keystore` (so updates install over the top);
  - publishes the APK as GitHub release **`latest`** (`PeanutCam.apk`).
- `peanut-cam.html` is just a redirect to `./` (GitHub Pages hosts the web version).
- `COPYRIGHT.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md` hold the IP policy. The in-app About & legal screen mirrors them (search `openLegal`). If you add a third-party dependency, add it to THIRD_PARTY_NOTICES.md, to the About & legal list, and to the licence step in the workflow.

## How index.html is organised

Sections start with `/* ---------- name ---------- */`; grep for that to navigate. In order: settings, three.js scene, peanut, PSX snapping, imported 3D models, face placement on models (`fitFace`), rigging tool, puppet (2D), face features, stage, background, camera, MediaPipe, arm/hand tracking, per-frame face geometry (`track`), picture upload + scan, outlines/cut-out editor, character gallery, about & legal, settings sheet, layout editor, place-face tool, joint editor, menu/modes/HUD, touch gestures, recording, screenshots, PSX 3D world, render loop, boot.

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
- **PSX look.** `psxPatch` adds vertex snapping to materials through `onBeforeCompile`. Never apply it to `ShaderMaterial`s, which declare `uSnap` themselves.
- **Recording.** MediaRecorder records `canvas.captureStream`, with the background repainted by `paintBg`. Recordings and pictures go to the gallery via the native bridge on Android.

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

## Next up: live streaming (RTMP to TikTok Live Studio / OBS)

The user has a plan to add StreamPack (Kotlin, RTMP) streaming of the rendered scene.

- **Spike the capture path on a device first.** A WebView can't draw directly into an encoder Surface. The options are:
  1. MediaProjection screen capture (StreamPack has a screen source). Robust, but needs user consent each session.
  2. A second WebView in a `Presentation` on a VirtualDisplay backed by the encoder Surface. No consent needed, but the scene renders twice.
  3. Frames pushed from JS (`canvas` → native). Simple but slow.
- **Add a clean "stream mode"** that hides the HUD, menu and camera preview.
- **OBS can't receive RTMP pushed straight from the phone.** It needs a local RTMP server (e.g. MediaMTX) on the PC that OBS pulls from. Document this in the app.
- **Stream keys stay on the device only.** Never put them in the repo.
- **Keep the feature modular** and don't break face tracking. Confirm the current StreamPack version before pinning it.

## Commits

Commit as Claude (`git -c user.name=Claude -c user.email=noreply@anthropic.com commit ...`), with a summary line plus bullets, ending with the session attribution lines the environment provides.
