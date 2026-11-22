"""Check the installable Android package without starting a device."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def output(args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          encoding="utf-8", errors="replace").stdout


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def classes(dex):
    # Read only definitions, so a reference to a missing activity cannot pass.
    (strings,) = struct.unpack_from("<I", dex, 60)
    (types,) = struct.unpack_from("<I", dex, 68)
    count, start = struct.unpack_from("<II", dex, 96)
    for index in range(count):
        (type_id,) = struct.unpack_from("<I", dex, start + 32 * index)
        (string_id,) = struct.unpack_from("<I", dex, types + 4 * type_id)
        (offset,) = struct.unpack_from("<I", dex, strings + 4 * string_id)
        while dex[offset] & 128:
            offset += 1
        offset += 1
        yield dex[offset:dex.index(0, offset)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--previous", type=Path, help="previously distributed APK to check upgrade compatibility")
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    aapt = str((args.build_tools / ("aapt.exe" if os.name == "nt" else "aapt")).resolve())
    signer = ["java", "-jar", str(args.build_tools / "lib/apksigner.jar"), "verify", "--print-certs"]
    def certificates(apk):
        return re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", output([*signer, str(apk)]))

    def identity(apk):
        badging = output([aapt, "dump", "badging", apk.name], cwd=apk.parent)
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        require(match, "Could not read package/version")
        return match.group(1), int(match.group(2)), match.group(3), badging

    package, code, version, badging = identity(args.apk)
    require(package == "com.shengjian.player", "Incorrect Android application ID")
    require(re.findall(r"launchable-activity: name='([^']+)'", badging) == [package + ".MainActivity"],
            "Unexpected launcher")
    certs = certificates(args.apk)
    require(certs, "Missing APK signature")
    if args.previous:
        previous_package, previous_code, _, _ = identity(args.previous)
        require(previous_package == package and previous_code < code, "Upgrade package/version mismatch")
        require(certs == certificates(args.previous), "Previous signing certificate differs")

    components = ["com.shengjian.player.MainActivity", "com.shengjian.player.AnimePlaybackActivity",
                  "com.shengjian.player.PlaybackService", "com.shengjian.player.AnimeFeatureActivity",
                  "com.ryanheise.audioservice.AudioService",
                  "com.pravera.flutter_foreground_task.service.ForegroundService"]
    manifest = output([aapt, "dump", "xmltree", args.apk.name, "AndroidManifest.xml"], cwd=args.apk.parent)
    require('"com.example.kazumi.MainActivity"' not in manifest, "Unexpected standalone anime application activity")
    require(sum('"android.intent.category.LAUNCHER"' in line for line in manifest.splitlines()) == 1,
            "Expected a single launcher category in the merged manifest")
    activities = re.split(r"(?m)^      E: ", manifest)
    feature = next((entry for entry in activities if entry.startswith("activity (")
                    and '"com.shengjian.player.AnimeFeatureActivity"' in entry), "")
    require(re.search(r"android:exported\([^)]*\)=\(type 0x12\)0x0\b", feature),
            "ANON anime feature must be private")
    for component in components:
        require(f'"{component}"' in manifest, f"Manifest missing {component}")
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries")
        require(not any(name.startswith("assets/flutter_assets/assets/images/logo/") for name in names),
                "Standalone Kazumi launcher artwork was bundled")
        assets = {"assets/flutter_assets/AssetManifest.bin", "assets/flutter_assets/NOTICES.Z",
                  "assets/flutter_assets/assets/images/noface.jpeg", "assets/reader-samples/novel-source.json",
                  "assets/licenses/kazumi-GPL-3.0.txt"}
        require(assets <= set(names), f"Missing assets: {assets.difference(names)}")
        snapshot = "assets/flutter_assets/assets/anon/anime-popular-2026-11-21.json"
        require(snapshot in names, "Missing first-launch anime catalogue")
        subjects = json.loads(archive.read(snapshot))["data"]
        require(bool(subjects), "Empty first-launch catalogue")
        for item in subjects:
            poster = f"assets/flutter_assets/assets/anon/posters/{item['id']}.webp"
            require(poster in names and archive.read(poster)[8:12] == b"WEBP", f"Missing poster for {item['id']}")
        libraries = {"libflutter.so", "libapp.so", "libmpv.so", "libech_http.so",
                     "libmediakitandroidhelper.so", "libmedia_kit_event_loop.so"}
        abis = sorted({name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")})
        require("arm64-v8a" in abis, "Missing ARM64 payload")
        for abi in abis:
            for lib in libraries:
                require(f"lib/{abi}/{lib}" in names, f"Missing {abi}/{lib}")
            snapshot_code = archive.read(f"lib/{abi}/libapp.so")
            require(b"com.shengjian.player/anime_features" in snapshot_code,
                    f"Missing ANON feature router in {abi} Dart release snapshot")
        needed = {("L" + name.replace(".", "/") + ";").encode() for name in components}
        for name in names:
            if re.fullmatch(r"classes\d*\.dex", name):
                definitions = set(classes(archive.read(name)))
                require(b"Lcom/example/kazumi/MainActivity;" not in definitions,
                        "Unexpected standalone anime activity in DEX")
                needed.difference_update(definitions)
        require(not needed, f"Missing DEX definitions: {needed}")
    print(f"{package} {version} ({code}), {', '.join(abis)}, signer SHA-256 {certs[0]}")


if __name__ == "__main__":
    main()
