#!/usr/bin/env bash
# Peanut Cam (c) 2026 James Scott. All rights reserved.
# Fills a "www" folder with everything the page needs offline: index.html, three.js, MediaPipe and three-vrm,
# the tracking models and the licence texts. Used by the Android build (assets/www) and the Windows build
# (desktop/www). Usage: scripts/bundle-www.sh <destination folder>
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mkdir -p "$1" && cd "$1" && pwd)"
rm -rf "$W"
mkdir -p "$W/lib/three/examples/jsm" "$W/lib/tasks-vision" "$W/models"
cp "$REPO/index.html" "$W/index.html"

DEPS=$(mktemp -d)
cd "$DEPS"
npm init -y >/dev/null
npm install --no-audit --no-fund three@0.160.0 @mediapipe/tasks-vision@0.10.14 @pixiv/three-vrm@2.1.0 mp4-muxer@5.2.2
cd "$REPO"

cp -r $DEPS/node_modules/three/build "$W/lib/three/build"
cp -r $DEPS/node_modules/three/examples/jsm/environments "$W/lib/three/examples/jsm/environments"
for d in loaders utils curves; do cp -r $DEPS/node_modules/three/examples/jsm/$d "$W/lib/three/examples/jsm/$d"; done
mkdir -p "$W/lib/three/examples/jsm/libs" && cp $DEPS/node_modules/three/examples/jsm/libs/{fflate.module.js,meshopt_decoder.module.js,ktx-parse.module.js,zstddec.module.js} "$W/lib/three/examples/jsm/libs/"
cp -r $DEPS/node_modules/three/examples/jsm/libs/{draco,basis} "$W/lib/three/examples/jsm/libs/"
mkdir -p "$W/lib/three-vrm" && ls $DEPS/node_modules/@pixiv/three-vrm/lib/
cp $DEPS/node_modules/@pixiv/three-vrm/lib/three-vrm.module.js "$W/lib/three-vrm/three-vrm.module.js"
cp $DEPS/node_modules/@mediapipe/tasks-vision/vision_bundle.mjs "$W/lib/tasks-vision/"
cp -r $DEPS/node_modules/@mediapipe/tasks-vision/wasm "$W/lib/tasks-vision/wasm"
mkdir -p "$W/lib/mp4-muxer" && cp $DEPS/node_modules/mp4-muxer/build/mp4-muxer.mjs "$W/lib/mp4-muxer/"

curl -fsSL --retry 3 -o "$W/models/face_landmarker.task" \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
curl -fsSL --retry 3 -o "$W/models/pose_landmarker_lite.task" \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
curl -fsSL --retry 3 -o "$W/models/hand_landmarker.task" \
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
curl -fsSL --retry 3 -o "$W/models/selfie_segmenter.tflite" \
  https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite

# the interface fonts (Bricolage Grotesque, IBM Plex Mono, SIL OFL 1.1), so the apps look right offline
mkdir -p "$W/fonts"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
curl -fsSL --retry 3 -A "$UA" -o "$W/fonts/google.css" \
  "https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,400;12..96,600;12..96,800&family=IBM+Plex+Mono:wght@400;500&display=swap"
n=0
cp "$W/fonts/google.css" "$W/fonts/fonts.css"
for u in $(grep -o 'https://fonts.gstatic.com/[^)]*' "$W/fonts/google.css" | sort -u); do
  n=$((n+1)); curl -fsSL --retry 3 -o "$W/fonts/f$n.woff2" "$u"
  sed -i "s#$u#f$n.woff2#g" "$W/fonts/fonts.css"
done
rm "$W/fonts/google.css"
echo "fonts: $n files"

# licence texts for everything bundled (shown in Menu › About & legal)
L="$W/licenses/THIRD_PARTY_LICENSES.txt"; mkdir -p "$W/licenses"
{
  echo "Peanut Cam (c) 2026 James Scott. All rights reserved."
  echo "Third-party components below remain the property of their owners and are licensed under the terms that follow."
  echo
} > "$L"
add(){  # title, local file or URL
  { printf '\n==================================================================\n%s\n==================================================================\n\n' "$1"
    if [ -f "$2" ]; then cat "$2"; else curl -fsSL --retry 3 "$2" || echo "Licence text: $2"; fi
    echo; } >> "$L"
}
N=$DEPS/node_modules
add "three.js 0.160 - MIT License" "$( [ -f $N/three/LICENSE ] && echo $N/three/LICENSE || echo https://raw.githubusercontent.com/mrdoob/three.js/r160/LICENSE )"
add "@pixiv/three-vrm 2.1 - MIT License" "$( [ -f $N/@pixiv/three-vrm/LICENSE ] && echo $N/@pixiv/three-vrm/LICENSE || echo https://raw.githubusercontent.com/pixiv/three-vrm/v2.1.0/LICENSE )"
add "MediaPipe Tasks Vision 0.10.14 and the Face Landmarker, Pose Landmarker, Hand Landmarker and Selfie Segmenter models (c) Google LLC - Apache License 2.0" https://www.apache.org/licenses/LICENSE-2.0.txt
add "Draco decoder (c) Google LLC - Apache License 2.0" "see the Apache License 2.0 text above"
add "Basis Universal transcoder (c) Binomial LLC - Apache License 2.0" "see the Apache License 2.0 text above"
add "meshoptimizer (c) Arseny Kapoulkine - MIT License" https://raw.githubusercontent.com/zeux/meshoptimizer/master/LICENSE.md
add "mp4-muxer 5.2.2 (c) Vanilagy - MIT License" "$N/mp4-muxer/LICENSE"
add "fflate (c) Arjun Barrett - MIT License" https://raw.githubusercontent.com/101arrowz/fflate/master/LICENSE
add "ktx-parse (c) Don McCurdy - MIT License" https://raw.githubusercontent.com/donmccurdy/KTX-Parse/main/LICENSE
add "zstddec (c) Don McCurdy - MIT License" https://raw.githubusercontent.com/donmccurdy/zstddec/main/LICENSE
add "Zstandard (included in zstddec) (c) Meta Platforms, Inc. - BSD License" https://raw.githubusercontent.com/facebook/zstd/dev/LICENSE
add "Bricolage Grotesque font - SIL Open Font License 1.1" https://raw.githubusercontent.com/google/fonts/main/ofl/bricolagegrotesque/OFL.txt
add "IBM Plex Mono font - SIL Open Font License 1.1" https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexmono/OFL.txt
cp "$REPO/COPYRIGHT.md" "$REPO/THIRD_PARTY_NOTICES.md" "$W/licenses/"
wc -c "$L"

du -sh "$W"
