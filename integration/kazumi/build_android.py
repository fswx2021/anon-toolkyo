"""Build ANON with its anime feature runtime and pinned upstream feature code."""
import argparse
import base64
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

SOURCE_ROOT = Path(__file__).resolve().parents[2]
ROOT = Path(os.environ.get("ANON_WORKSPACE_ALIAS", SOURCE_ROOT))
if not ROOT.samefile(SOURCE_ROOT):
    raise RuntimeError("ANON_WORKSPACE_ALIAS must map to this project")
HERE = ROOT / "integration/kazumi"
CACHE = ROOT / "build"
STAGE = CACHE / "kazumi-integrated"
UPSTREAM = CACHE / "kazumi-compare-2.4.5"
REVISION = "dedb7136318a509d7f412794b2531cf725034f00"


def run(*args, cwd=ROOT, **kwargs):
    return subprocess.run(list(map(str, args)), cwd=cwd, check=True, **kwargs)


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError(f"Expected exactly one occurrence of {old!r} in build input")
    return text.replace(old, new, 1)


def prepare():
    CACHE.mkdir(exist_ok=True)
    if not UPSTREAM.is_dir():
        run("git", "clone", "--depth", "1", "--branch", "2.4.5",
            "https://github.com/Predidit/Kazumi.git", UPSTREAM)
    actual = run("git", "rev-parse", "HEAD", cwd=UPSTREAM, capture_output=True,
                 text=True).stdout.strip()
    if actual != REVISION:
        raise RuntimeError(f"Unexpected Kazumi revision {actual}")
    STAGE.mkdir(exist_ok=True)
    archive = run("git", "archive", "--format=zip", REVISION, "lib", "test", "android",
                  "licenses", "assets", "pubspec.yaml", "pubspec.lock", "LICENSE",
                  ".metadata", "analysis_options.yaml", cwd=UPSTREAM, capture_output=True)
    with zipfile.ZipFile(io.BytesIO(archive.stdout)) as files:
        files.extractall(STAGE)
    # git archive omits files introduced by our patches. Remove only those generated
    # files so repeated builds can apply the same checked patches to pristine inputs.
    for name in ("embed-2.4.5.patch", "data-2.4.5.patch", "feature-pages-2.4.5.patch",
                 "android-media-2.4.5.patch", "band-loading-2.4.5.patch"):
        patch = HERE / name
        for added in re.findall(r"^--- /dev/null\n\+\+\+ b/(.+)$",
                                patch.read_text(encoding="utf-8"), re.MULTILINE):
            target = (STAGE / added).resolve()
            target.relative_to(STAGE.resolve())
            target.unlink(missing_ok=True)
        env = dict(os.environ, GIT_CEILING_DIRECTORIES=str(STAGE.parent))
        run("git", "apply", "--whitespace=error", patch, cwd=STAGE, env=env)

    android = STAGE / "android"
    app = android / "app"
    # Pub's Git mirrors store canonical locations. Keep those stable while
    # handing Android's CMake/Ninja the short path of the very same plugin.
    if os.name == "nt" and ROOT != SOURCE_ROOT:
        original = SOURCE_ROOT.as_posix().replace("'", "\\'")
        alias = ROOT.as_posix().replace("'", "\\'")
        with (android / "settings.gradle").open("a", encoding="utf-8") as settings:
            settings.write(f"""
def anonOriginalPath = new File('{original}').toPath()
def anonBuildPath = new File('{alias}').toPath()
rootProject.children.each {{ child ->
    def path = child.projectDir.toPath().normalize()
    if (path.startsWith(anonOriginalPath)) {{
        child.projectDir = anonBuildPath.resolve(anonOriginalPath.relativize(path)).toFile()
    }}
}}
""")
    runtime = HERE / "runtime"
    if not (runtime / "lib/anon_anime.dart").is_file():
        raise RuntimeError("ANON anime feature entrypoint is missing")
    shutil.copytree(runtime / "lib", STAGE / "lib", dirs_exist_ok=True)
    shutil.copytree(runtime / "assets", STAGE / "assets", dirs_exist_ok=True)
    if (runtime / "test").is_dir():
        shutil.copytree(runtime / "test", STAGE / "test", dirs_exist_ok=True)
    # AudioService may create its own engine, which invokes the default main.
    # Keep that entrypoint and all Gradle/Flutter build modes on the same runtime.
    (STAGE / "lib/main.dart").write_text(
        "import 'anon_anime.dart' as anon;\n\nFuture<void> main() => anon.main();\n",
        encoding="utf-8")
    android_code = app / "src/main/kotlin"
    native = android_code / "com/example/kazumi/MainActivity.kt"
    media_support = replace_once(native.read_text(encoding="utf-8"),
                                 "package com.example.kazumi", "package com.shengjian.player")
    media_support = replace_once(media_support, "class MainActivity: AudioServiceActivity()",
                                 "abstract class AnimeMediaActivity : AudioServiceActivity()")
    native.unlink()
    feature_code = android_code / "com/shengjian/player"
    feature_code.mkdir(parents=True, exist_ok=True)
    (feature_code / "AnimeMediaActivity.kt").write_text(media_support, encoding="utf-8")
    directory = android_code / "com/example/kazumi/DownloadDirectoryHandler.kt"
    (feature_code / directory.name).write_text(replace_once(directory.read_text(encoding="utf-8"),
        "package com.example.kazumi", "package com.shengjian.player"), encoding="utf-8")
    directory.unlink()
    shutil.copy2(HERE / "android/AnimeFeatureActivity.kt", feature_code)
    shutil.copyfile(HERE / "AndroidManifest.xml", app / "src/main/AndroidManifest.xml")
    # The Android feature uses ANON's launcher artwork. Retain the conventional
    # mipmap name for plugins that look up a notification icon by resource name.
    resources = app / "src/main/res"
    for filename in ("ic_launcher.png", "ic_launcher.xml", "ic_launcher_foreground.png"):
        for icon in resources.glob("*/" + filename):
            icon.unlink()
    (resources / "values/anon_launcher.xml").unlink(missing_ok=True)
    (resources / "mipmap").mkdir(exist_ok=True)
    shutil.copy2(ROOT / "app/src/main/res/drawable/app_icon.xml", resources / "mipmap/ic_launcher.xml")
    pubspec_file = STAGE / "pubspec.yaml"
    pubspec = pubspec_file.read_text(encoding="utf-8")
    for standalone_asset in ("assets/images/logo/", "assets/statements/"):
        pubspec = replace_once(pubspec, "    - " + standalone_asset + "\n", "")
    pubspec_file.write_text(pubspec, encoding="utf-8")
    (STAGE / "assets/images/loading.png").unlink(missing_ok=True)
    # Lint 9.3.1's comment detector crashes while parsing dependency JavaDocs
    # (image_picker_android and url_launcher_android). Retain the other checks.
    with (android / "build.gradle").open("a", encoding="utf-8") as project_build:
        project_build.write("""
subprojects { library ->
    library.plugins.withId('com.android.library') {
        library.android.lint.disable.addAll(['EasterEgg', 'StopShip'])
    }
}
""")
    host = ROOT / "app"
    posters = STAGE / "assets/anon"
    shutil.copytree(host / "src/main/assets/anime-popular-posters", posters / "posters",
                    dirs_exist_ok=True)
    shutil.copy2(host / "src/main/assets/anime-popular-2026-11-21.json", posters)
    app_path = host.as_posix().replace("'", "\\'")
    host_gradle = (host / "build.gradle").read_text(encoding="utf-8")
    # Use the same BuildConfig, package, dependencies and version as the host.
    plugins = """plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.4.20'
    id 'dev.flutter.flutter-gradle-plugin'
}"""
    host_gradle = re.sub(r"^plugins\s*\{[^}]+\}", lambda _: plugins, host_gradle, count=1)
    host_gradle = re.sub(r"compileSdk\s+\d+", "compileSdk flutter.compileSdkVersion",
                         host_gradle, count=1)
    host_gradle = host_gradle.replace("files('libs/", f"files('{app_path}/libs/")
    host_gradle += f"""
flutter {{ source '../..' }}
android {{
    ndkVersion flutter.ndkVersion
    defaultConfig.ndk.abiFilters.addAll(['arm64-v8a', 'x86_64'])
    packaging.jniLibs.excludes.addAll(['**/armeabi-v7a/**', '**/x86/**'])
    kotlinOptions {{ jvmTarget = '17' }}
    sourceSets.main {{
        java.srcDir '{app_path}/src/main/java'
        java.exclude 'com/alexmercerind/mediakitandroidhelper/MediaKitAndroidHelper.java'
        kotlin.srcDir '{app_path}/src/main/kotlin'
        res.srcDir '{app_path}/src/main/res'
        assets.srcDir '{app_path}/src/main/assets'
    }}
    sourceSets.test.java.srcDir '{app_path}/src/test/java'
    buildTypes.release {{
        minifyEnabled false
        shrinkResources false
    }}
}}
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {{ variant ->
    variant.enableUnitTest = true
}}
"""
    (app / "build.gradle").write_text(host_gradle, encoding="utf-8")
    with (android / "gradle.properties").open("a", encoding="utf-8") as output:
        # Flutter otherwise replaces the explicit ABI set with its default three.
        output.write("\nandroid.overridePathCheck=true\ndisable-abi-filtering=true\n")


def sdk_bin():
    name = "flutter.bat" if os.name == "nt" else "flutter"
    candidates = []
    if os.environ.get("FLUTTER_ROOT"):
        candidates.append(Path(os.environ["FLUTTER_ROOT"]) / "bin" / name)
    candidates.append(CACHE / "flutter-sdk/bin" / name)
    if shutil.which("flutter"):
        candidates.append(Path(shutil.which("flutter")))
    return next((path for path in candidates if path.is_file()), None)


def property_path(path):
    return Path(path).as_posix().encode("ascii", "backslashreplace").decode("ascii")


def launch():
    # impellerc on Windows cannot open shader includes under some non-ASCII paths.
    if (os.name != "nt" or ROOT.as_posix().isascii() or
            "--prepare-only" in sys.argv or os.environ.get("ANON_WORKSPACE_ALIAS")):
        main()
        return
    for letter in "RQPOZYXWVUTSNMLKJIHGF":
        drive = letter + ":"
        alias = Path(drive + "/")
        if alias.exists():
            continue
        mapped = subprocess.run(["subst", drive, str(SOURCE_ROOT)], capture_output=True)
        if mapped.returncode:
            continue
        try:
            env = dict(os.environ, ANON_WORKSPACE_ALIAS=str(alias))
            run(sys.executable, alias / "integration/kazumi/build_android.py", *sys.argv[1:],
                cwd=alias, env=env)
        finally:
            if alias.exists() and alias.samefile(SOURCE_ROOT):
                subprocess.run(["subst", drive, "/D"], check=True)
        return
    sys.exit("No drive letter available for the Windows Flutter shader compiler.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--gradle", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    prepare()
    if args.prepare_only:
        return
    flutter = sdk_bin()
    if not flutter:
        sys.exit("Install Flutter 3.50.1 and set FLUTTER_ROOT or PATH.")
    env = dict(os.environ)
    if not env.get("ANDROID_HOME") and (CACHE / "android-sdk/platforms").is_dir():
        env["ANDROID_HOME"] = str(CACHE / "android-sdk")
    env.setdefault("PUB_CACHE", str(SOURCE_ROOT / "build/pub-cache"))
    env.setdefault("CI", "true")
    defines = {key: env[key] for key in
               ("DANDANAPI_APPID", "DANDANAPI_KEY", "KAZUMI_APPID", "KAZUMI_KEY")
               if env.get(key)}
    pub_options = ["--offline"] if env.get("ANON_PUB_OFFLINE") == "1" else []
    run(flutter, "pub", "get", "--enforce-lockfile", *pub_options, cwd=STAGE, env=env)
    wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
    wrapper_root = flutter.parent / "cache/artifacts/gradle_wrapper"
    for part in (wrapper, "gradle/wrapper/gradle-wrapper.jar"):
        shutil.copy2(wrapper_root / part, STAGE / "android" / part)
    launcher = os.environ.get("ANON_GRADLE_EXECUTABLE")
    if launcher:
        if not Path(launcher).is_file():
            sys.exit("ANON_GRADLE_EXECUTABLE must point to an installed Gradle launcher.")
        if os.name == "nt":
            (STAGE / "android" / wrapper).write_bytes(
                b'@echo off\r\n@call "%ANON_GRADLE_EXECUTABLE%" %*\r\n@exit /b %errorlevel%\r\n')
        else:
            (STAGE / "android" / wrapper).write_text(
                '#!/bin/sh\nexec "$ANON_GRADLE_EXECUTABLE" "$@"\n', encoding="utf-8")
    if args.gradle is not None:
        # Gradle excludes dev-only plugins from release. Ask Flutter to generate
        # the Android plugin registrant for the same mode before calling Gradle.
        mode = "--debug" if any("debug" in task.lower() for task in args.gradle) else "--release"
        run(flutter, "build", "apk", "--config-only", mode,
            "--target-platform=android-arm64,android-x64", cwd=STAGE, env=env)
        sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
        if not sdk:
            sys.exit("Set ANDROID_HOME before invoking Gradle directly.")
        (STAGE / "android/local.properties").write_text(
            f"flutter.sdk={property_path(flutter.parent.parent)}\nsdk.dir={property_path(sdk)}\n",
            encoding="utf-8")
        if defines:
            env["ORG_GRADLE_PROJECT_dart-defines"] = ",".join(
                base64.b64encode(f"{k}={v}".encode()).decode("ascii") for k, v in defines.items())
        run(STAGE / "android" / wrapper, "-Ptarget-platform=android-arm64,android-x64",
            *args.gradle, cwd=STAGE / "android", env=env)
    else:
        with tempfile.TemporaryDirectory(prefix="anon-flutter-") as temporary:
            values = Path(temporary) / "defines.json"
            values.write_text(json.dumps(defines), encoding="utf-8")
            # Flutter builds with --no-pub skip the release-mode plugin registrant;
            # a plain pub get previously registered dev-only Android plugins.
            run(flutter, "build", "apk", "--debug" if args.debug else "--release",
                "--target-platform=android-arm64,android-x64",
                f"--dart-define-from-file={values}", cwd=STAGE, env=env)
    outputs = STAGE / "build/app/outputs/apk"
    if outputs.is_dir():
        shutil.copytree(outputs, ROOT / "app/build/outputs/apk", dirs_exist_ok=True)


if __name__ == "__main__":
    launch()
