/*
 * Peanut Cam for Windows - build step
 * Copyright (c) 2026 James Scott. All rights reserved.
 *
 * Downloads what the phone camera needs into vendor/ (bundled into the installer), plus licence texts:
 *   - adb from Android SDK Platform-Tools for Windows (Google)
 *   - scrcpy-server 3.1 (Genymobile, Apache 2.0), checked against its known SHA-256
 * Also writes build/license.txt (shown by the installer) from LICENSE and COPYRIGHT.md.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const AdmZip = require("adm-zip");

const SCRCPY = { url: "https://github.com/Genymobile/scrcpy/releases/download/v3.1/scrcpy-server-v3.1", sha256: "958f0944a62f23b1f33a16e9eb14844c1a04b882ca175a738c16d23cb22b86c0" };
const PLATFORM_TOOLS = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";
const V = path.join(__dirname, "vendor"), REPO = path.join(__dirname, "..");

async function get(url) {
  const r = await fetch(url, { redirect: "follow" });
  if (!r.ok) throw new Error(url + ": HTTP " + r.status);
  return Buffer.from(await r.arrayBuffer());
}

(async () => {
  fs.mkdirSync(path.join(V, "adb"), { recursive: true });
  const srv = await get(SCRCPY.url);
  const sum = crypto.createHash("sha256").update(srv).digest("hex");
  if (sum !== SCRCPY.sha256) throw new Error("scrcpy-server checksum mismatch: " + sum);
  fs.writeFileSync(path.join(V, "scrcpy-server"), srv);

  const zip = new AdmZip(await get(PLATFORM_TOOLS));
  for (const f of ["adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt", "source.properties"]) {
    const e = zip.getEntry("platform-tools/" + f);
    if (!e) throw new Error("platform-tools is missing " + f);
    fs.writeFileSync(path.join(V, "adb", f), e.getData());
  }
  const rev = (fs.readFileSync(path.join(V, "adb", "source.properties"), "utf8").match(/Pkg.Revision=(\S+)/) || [])[1] || "?";

  // licence texts next to the other third-party licences (shown under About & legal)
  const apache = await get("https://www.apache.org/licenses/LICENSE-2.0.txt").catch(() => Buffer.from("https://www.apache.org/licenses/LICENSE-2.0.txt"));
  const L = path.join(__dirname, "www", "licenses", "THIRD_PARTY_LICENSES.txt");
  if (fs.existsSync(L)) {
    const bar = "\n==================================================================\n";
    fs.appendFileSync(L, bar + "scrcpy-server 3.1 (c) Genymobile, Romain Vimont - Apache License 2.0 (Windows app: phone camera over USB)" + bar + "\n" + apache + "\n" +
      bar + "Android SDK Platform-Tools " + rev + " (adb) (c) Google LLC and the Android Open Source Project (Windows app: phone camera over USB). Notices follow." + bar + "\n" +
      fs.readFileSync(path.join(V, "adb", "NOTICE.txt"), "utf8") + "\n");
  }

  fs.mkdirSync(path.join(__dirname, "build"), { recursive: true });
  fs.writeFileSync(path.join(__dirname, "build", "license.txt"),
    fs.readFileSync(path.join(REPO, "COPYRIGHT.md"), "utf8") + "\n\n" + fs.readFileSync(path.join(REPO, "LICENSE"), "utf8"));
  console.log("vendor ready: scrcpy-server 3.1, platform-tools " + rev);
})().catch(e => { console.error(e); process.exit(1); });
