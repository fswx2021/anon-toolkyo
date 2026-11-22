"""Build and verify an Android release, with optional drafting on GitHub."""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from uuid import uuid4
from xml.etree import ElementTree
import zipfile

from github_release import GitHubRelease, sha256


ROOT = Path(__file__).resolve().parents[1]
STAGE = Path("build/kazumi-integrated")
BUILD_SCRIPT = Path("integration/kazumi/build_android.py")
APK = STAGE / "build/app/outputs/apk/release/app-release.apk"
VERSION = r"[0-9]+\.[0-9]+\.[0-9]+"
OWNER = r"[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*"
REPOSITORY = rf"({OWNER})/([A-Za-z0-9_.-]+)"


def execute(*args, cwd=ROOT, env=None, capture=False):
    return subprocess.run(
        [str(arg) for arg in args], cwd=cwd, env=env, check=True,
        capture_output=capture, text=True, encoding="utf-8" if capture else None,
        errors="replace" if capture else None,
    ).stdout


def config_version():
    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    names = re.findall(rf"(?m)^\s*versionName\s+['\"]({VERSION})['\"]\s*$", gradle)
    codes = re.findall(r"(?m)^\s*versionCode\s+(\d+)\s*$", gradle)
    if len(names) != 1 or len(codes) != 1 or not 0 < int(codes[0]) <= 2_100_000_000:
        raise ValueError("app/build.gradle must define one numeric versionCode and one stable x.y.z versionName.")
    return names[0], int(codes[0])


def build_environment(repository):
    env = os.environ.copy()
    for name in ("DANDANPLAY_APP_ID", "DANDANPLAY_APP_SECRET", "DANDANAPI_APPID",
                 "DANDANAPI_KEY", "KAZUMI_APPID", "KAZUMI_KEY", "ORG_GRADLE_PROJECT_dart-defines",
                 "GH_TOKEN", "GITHUB_TOKEN"):
        env.pop(name, None)
    env["ANON_TOOL_GITHUB_REPOSITORY"] = repository
    env.setdefault("CI", "true")
    env.setdefault("FLUTTER_SUPPRESS_ANALYTICS", "true")
    for variable, cache in (("ANDROID_HOME", "android-sdk"), ("FLUTTER_ROOT", "flutter-sdk"),
                            ("GRADLE_USER_HOME", "gradle-kazumi")):
        local = ROOT / "build" / cache
        if local.is_dir():
            env.setdefault(variable, str(local))
    env.setdefault("PUB_CACHE", str(ROOT / "build/pub-cache"))
    if env.get("JAVA_HOME"):
        env["PATH"] = os.pathsep.join((str(Path(env["JAVA_HOME"]) / "bin"), env["PATH"]))
    return env


def build_tools(explicit, env):
    if explicit is None:
        sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise ValueError("Set ANDROID_HOME or supply --build-tools.")
        versions = Path(sdk) / "build-tools"
        candidates = [entry for entry in versions.iterdir() if re.fullmatch(VERSION, entry.name)
                      and (entry / "lib/apksigner.jar").is_file()]
        explicit = max(candidates, key=lambda value: tuple(map(int, value.name.split("."))), default=None)
    if explicit is None or not (explicit / "lib/apksigner.jar").is_file():
        raise ValueError("Install Android SDK build-tools or supply their directory via --build-tools.")
    return explicit.resolve()


def find_cached_gradle(env):
    if env.get("ANON_GRADLE_EXECUTABLE"):
        launcher = Path(env["ANON_GRADLE_EXECUTABLE"])
        if launcher.is_file():
            return launcher
        raise ValueError("ANON_GRADLE_EXECUTABLE does not point to a Gradle launcher.")
    spec = (ROOT / STAGE / "android/gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    required = re.search(r"gradle-(\d+\.\d+)\.\d+-(?:all|bin)\.zip", spec)
    if not required:
        raise ValueError("Could not detect the cached Gradle version; use --cached-gradle.")
    launcher = "gradle.bat" if os.name == "nt" else "gradle"
    caches = {Path.home() / ".gradle", Path(env.get("GRADLE_USER_HOME", ROOT / "build/gradle-kazumi"))}
    candidates = [path for cache in caches for path in (cache / "wrapper/dists")
                  .glob(f"gradle-{required[1]}.*-*/*/gradle-{required[1]}.*/bin/{launcher}")
                  if re.fullmatch(VERSION, path.parent.parent.name.removeprefix("gradle-"))]
    if not candidates:
        raise ValueError("Compatible Gradle launcher not found in cache; use --cached-gradle.")
    return max(candidates, key=lambda path: tuple(map(int, path.parent.parent.name[7:].split("."))))


@contextmanager
def cached_workspace():
    alias_setting = os.environ.get("ANON_WORKSPACE_ALIAS")
    if alias_setting:
        alias = Path(alias_setting)
        if not alias.samefile(ROOT):
            raise ValueError("ANON_WORKSPACE_ALIAS must point to this project.")
        yield alias
        return
    if os.name != "nt" or ROOT.as_posix().isascii():
        yield ROOT
        return
    for letter in "RQPOZYXWVUTSNMLKJIHGF":
        drive = f"{letter}:"
        alias = Path(f"{drive}/")
        if alias.exists() or subprocess.run(
                ["subst", drive, str(ROOT)], capture_output=True).returncode:
            continue
        try:
            yield alias
        finally:
            if alias.exists() and alias.samefile(ROOT):
                execute("subst", drive, "/D")
        return
    raise ValueError("No free Windows drive letter for the Flutter shader compiler.")


def build(env, cached_gradle, min_tests):
    gradle_tasks = (":app:testReleaseUnitTest", ":app:assembleRelease")
    if cached_gradle is None:
        execute(sys.executable, BUILD_SCRIPT, "--gradle", *gradle_tasks, env=env)
    else:
        if not (ROOT / STAGE / "android/local.properties").is_file() or not (ROOT / "build/kazumi-compare-2.4.5").is_dir():
            raise ValueError("The cached build needs an initialized Gradle stage. Run a connected build first.")
        with cached_workspace() as workspace:
            cached_env = dict(env, ANON_WORKSPACE_ALIAS=str(workspace))
            for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "FLUTTER_ROOT", "GRADLE_USER_HOME", "PUB_CACHE"):
                if cached_env.get(name):
                    path = Path(cached_env[name]).resolve()
                    if path.is_relative_to(ROOT):
                        cached_env[name] = str(workspace / path.relative_to(ROOT))
            sdk = cached_env.get("ANDROID_HOME") or cached_env.get("ANDROID_SDK_ROOT")
            flutter = cached_env.get("FLUTTER_ROOT")
            if not sdk or not flutter:
                raise ValueError("Set ANDROID_HOME and FLUTTER_ROOT for the cached build.")
            properties = workspace / STAGE / "android/local.properties"
            paths = f"flutter.sdk={Path(flutter).as_posix()}\nsdk.dir={Path(sdk).as_posix()}\n"
            properties.write_text(paths.encode("ascii", "backslashreplace").decode("ascii"), encoding="ascii")
            execute(sys.executable, BUILD_SCRIPT, "--prepare-only", cwd=workspace, env=cached_env)
            execute(cached_gradle.resolve(), "--offline", "-Ptarget-platform=android-arm64,android-x64",
                    *gradle_tasks, cwd=workspace / STAGE / "android", env=cached_env)
    reports = ROOT / STAGE / "build/app/test-results/testReleaseUnitTest"
    suites = [ElementTree.parse(report).getroot() for report in reports.glob("TEST-*.xml")]
    tests = sum(int(suite.get("tests", "0")) for suite in suites)
    unsuccessful = sum(int(suite.get(kind, "0")) for suite in suites
                       for kind in ("failures", "errors", "skipped"))
    if tests < min_tests or unsuccessful:
        raise ValueError(f"Android tests: {tests} reported, {unsuccessful} unsuccessful. Expected at least {min_tests} passing.")
    print(f"Android tests: {tests} passed.")
    return ROOT / APK


def verify(apk, previous, tools, env, version, code, repository):
    if not apk.is_file():
        raise ValueError(f"APK does not exist: {apk}")
    previous_arg = ["--previous", previous] if previous else []
    identity = execute(sys.executable, "integration/kazumi/verify_apk.py", apk,
                       "--build-tools", tools, *previous_arg, env=env, capture=True).strip()
    expected = f"com.shengjian.player {version} ({code}), arm64-v8a, x86_64, signer SHA-256 "
    if not identity.startswith(expected) or not re.fullmatch(r"[a-fA-F0-9]{64}", identity[len(expected):]):
        raise ValueError(f"Unexpected APK version, ABI or signer: {identity}")
    zipalign = "zipalign.exe" if os.name == "nt" else "zipalign"
    execute(tools / zipalign, "-c", "-P", "16", "4", apk, env=env)
    with zipfile.ZipFile(apk) as package:
        bad_file = package.testzip()
        if bad_file:
            raise ValueError(f"APK checksum failed for entry {bad_file}.")
        if not any(repository.encode("ascii") in package.read(entry)
                   for entry in package.namelist() if re.fullmatch(r"classes\d*\.dex", entry)):
            raise ValueError("APK does not contain the configured GitHub update repository.")
    print(identity)


def assemble_assets(apk, repository, version, code, notes):
    tag = f"v{version}"
    apk_name = f"anon-toolkyo-{version}.apk"
    parent = ROOT / "build/releases"
    destination = parent / tag
    manifest = {
        "versionCode": code,
        "versionName": version,
        "apkUrl": f"https://github.com/{repository}/releases/download/{tag}/{apk_name}",
        "sha256": sha256(apk),
        "notes": notes,
    }
    payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        folder = parent / f".{tag}-{uuid4().hex}"
        folder.mkdir()
        try:
            shutil.copyfile(apk, folder / apk_name)
            if sha256(folder / apk_name) != manifest["sha256"]:
                raise ValueError("APK changed while assembling release assets.")
            (folder / "update.json").write_text(payload, encoding="utf-8")
            (folder / "SHA256SUMS.txt").write_text(f"{manifest['sha256']}  {apk_name}\n", encoding="utf-8")
            (folder / "notes.md").write_text(notes + "\n", encoding="utf-8")
            folder.rename(destination)
        finally:
            if folder.is_dir():
                for entry in (apk_name, "update.json", "SHA256SUMS.txt", "notes.md"):
                    (folder / entry).unlink(missing_ok=True)
                folder.rmdir()
    elif ((destination / "update.json").read_text(encoding="utf-8") != payload
          or sha256(destination / apk_name) != manifest["sha256"]
          or (destination / "SHA256SUMS.txt").read_text(encoding="utf-8")
          != f"{manifest['sha256']}  {apk_name}\n"
          or (destination / "notes.md").read_text(encoding="utf-8") != notes + "\n"):
        raise ValueError(f"{destination} already contains different release assets.")
    print(f"Release assets: {destination}")
    return tag, destination, apk_name


def check_draft_source(repository, tag):
    if execute("git", "status", "--porcelain", capture=True).strip():
        raise ValueError("Commit the release source before creating a draft.")
    head = execute("git", "rev-parse", "HEAD", capture=True).strip()
    github = GitHubRelease(repository, ROOT)
    target = github.api(f"/commits/{tag}")["sha"]
    if head != target:
        raise ValueError(f"Remote tag {tag} must point to the commit checked out here.")
    return github


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("ANON_TOOL_GITHUB_REPOSITORY"), help="GitHub OWNER/REPO")
    parser.add_argument("--notes-file", type=Path, help="UTF-8 release notes, default tools/releases/<version>.md")
    signing = parser.add_mutually_exclusive_group(required=True)
    signing.add_argument("--previous", type=Path, help="last distributed APK for checking covering upgrades")
    signing.add_argument("--first-release", action="store_true", help="allow a first signed release with no previous APK")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--apk", type=Path, help="verify an already-built APK instead of building it")
    source.add_argument("--cached-gradle", type=Path, help="use this Gradle launcher with the existing offline build cache")
    source.add_argument("--use-cache", action="store_true", help="auto-detect the Gradle launcher for an existing offline build cache")
    parser.add_argument("--build-tools", type=Path, help="Android SDK build-tools directory")
    parser.add_argument("--minimum-tests", type=int, default=1, help="expected Android unit test count when building")
    parser.add_argument("--create-draft", action="store_true", help="also upload the assets to a draft GitHub Release for an existing pushed tag")
    args = parser.parse_args()
    repository = args.repository or ""
    repo = re.fullmatch(REPOSITORY, repository)
    if not repo or len(repo[1]) > 39 or len(repo[2]) > 100 or repo[2] in {".", ".."}:
        parser.error("set --repository OWNER/REPO or ANON_TOOL_GITHUB_REPOSITORY")
    if args.minimum_tests < 1:
        parser.error("--minimum-tests must be greater than zero")
    if args.previous and not args.previous.is_file():
        parser.error("--previous must point to the last distributed APK")
    version, code = config_version()
    path = args.notes_file or ROOT / f"tools/releases/{version}.md"
    notes = path.read_text(encoding="utf-8-sig").strip()
    if not notes or len(notes.encode("utf-16-le")) > 4000:
        parser.error("--notes-file must contain 1 to 2000 UTF-16 code units")
    github = check_draft_source(repository, f"v{version}") if args.create_draft else None
    env = build_environment(repository)
    tools = build_tools(args.build_tools, env)
    cached_gradle = find_cached_gradle(env) if args.use_cache else args.cached_gradle
    apk = args.apk.resolve() if args.apk else build(env, cached_gradle, args.minimum_tests)
    previous = args.previous.resolve() if args.previous else None
    verify(apk, previous, tools, env, version, code, repository)
    tag, directory, name = assemble_assets(apk, repository, version, code, notes)
    if github:
        github.create_draft(tag, f"anon toolkyo {version}", notes,
                            [directory / name, directory / "update.json", directory / "SHA256SUMS.txt"])


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr.strip(), file=sys.stderr)
        sys.exit(str(error))
