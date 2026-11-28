"""Publish a verified Android release to Gitee, using its official v5 API."""

import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, build_opener
from uuid import uuid4

from github_release import NoRedirect, checked_release_notes, sha256


ROOT = Path(__file__).resolve().parents[1]
GITEE = "https://gitee.com"
REPOSITORY = r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"
TAG = r"v[0-9]{1,9}(?:\.[0-9]{1,9}){2}"
USER_AGENT = "anon-toolkyo-release"

READ_DPAPI = r"""
$ErrorActionPreference = 'Stop'
$value = (Get-Content -Raw -LiteralPath $env:ANON_GITEE_TOKEN_FILE).Trim() | ConvertTo-SecureString
[Console]::Write([System.Net.NetworkCredential]::new('', $value).Password)
"""


def credential():
    value = (os.environ.get("GITEE_ACCESS_TOKEN") or os.environ.get("GITEE_TOKEN") or "").strip()
    if not value and os.name == "nt":
        token_file = ROOT / "build/gitee-release-token.dpapi"
        powershell = shutil.which("pwsh") or shutil.which("powershell.exe")
        if powershell and token_file.is_file():
            try:
                result = subprocess.run(
                    [powershell, "-NoProfile", "-NonInteractive", "-Command", READ_DPAPI],
                    env=dict(os.environ, ANON_GITEE_TOKEN_FILE=str(token_file)),
                    capture_output=True, text=True, encoding="utf-8", timeout=15,
                )
            except (OSError, subprocess.TimeoutExpired):
                raise ValueError("Cannot read the Gitee DPAPI credential.") from None
            if result.returncode:
                raise ValueError("Gitee DPAPI credential cannot be opened by this Windows account.")
            value = result.stdout.strip()
    if not re.fullmatch(r"[\x21-\x7e]{1,512}", value):
        raise ValueError("Set GITEE_ACCESS_TOKEN or provide build/gitee-release-token.dpapi.")
    return value


def multipart(fields, path):
    boundary = "anon-release-" + uuid4().hex
    prefix = b"".join(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode("utf-8")
        for name, value in fields.items()
    )
    size = 0
    if path is not None:
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        prefix += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                   f"filename=\"{path.name}\"\r\nContent-Type: {content_type}\r\n\r\n").encode("ascii")
        size = path.stat().st_size
    suffix = (("\r\n" if path is not None else "") + f"--{boundary}--\r\n").encode("ascii")

    def chunks():
        yield prefix
        if path is not None:
            with path.open("rb") as stream:
                yield from iter(lambda: stream.read(1 << 20), b"")
        yield suffix

    return chunks(), {
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(prefix) + size + len(suffix)),
    }


class GiteeRelease:
    def __init__(self, repository, token):
        self.repository = repository
        self.token = token
        self.http = build_opener(NoRedirect())
        self.base = f"{GITEE}/api/v5/repos/{repository}"

    def api(self, route, method="GET", fields=None, path=None, *, missing_ok=False):
        headers = {"Accept": "application/json", "User-Agent": USER_AGENT, "Cache-Control": "no-cache"}
        body = None
        if method != "GET":
            body, multipart_headers = multipart({"access_token": self.token, **(fields or {})}, path)
            headers.update(multipart_headers)
        request = Request(self.base + route, data=body, headers=headers, method=method)
        try:
            with self.http.open(request, timeout=90) as response:
                return json.loads(response.read(1 << 20))
        except HTTPError as error:
            error.close()
            if missing_ok and error.code == 404:
                return None
            raise ValueError(f"Gitee {method} {route} returned HTTP {error.code}.") from None
        except (URLError, TimeoutError, json.JSONDecodeError, UnicodeError) as error:
            raise ValueError(
                f"Gitee {method} {route} did not complete ({type(error).__name__}). Rerun to resume."
            ) from None

    def verify_attachment(self, tag, path, expected):
        size, digest = expected
        initial = f"{GITEE}/{self.repository}/releases/download/{tag}/{path.name}"
        for attempt in range(3):
            try:
                received = self.attachment_hash(initial, path.name, size)
                break
            except (URLError, TimeoutError, OSError) as error:
                if attempt == 2:
                    description = f"HTTP {error.code}" if isinstance(error, HTTPError) else type(error).__name__
                    raise ValueError(f"Cannot read Gitee attachment {path.name} anonymously ({description}).") from None
                time.sleep(2 * (attempt + 1))
        if received != (size, digest):
            raise ValueError(f"Remote attachment differs: {path.name}. Release was not promoted.")
        print(f"Verified anonymous Gitee download: {path.name} ({size} bytes)", flush=True)

    def attachment_hash(self, initial, name, limit):
        address = initial
        for _ in range(3):
            request = Request(address, headers={"User-Agent": USER_AGENT, "Cache-Control": "no-cache"})
            try:
                with self.http.open(request, timeout=45) as response:
                    if response.status != 200:
                        raise ValueError(f"Unexpected download status for {name}: HTTP {response.status}.")
                    size, digest = 0, hashlib.sha256()
                    for block in iter(lambda: response.read(1 << 20), b""):
                        size += len(block)
                        if size > limit:
                            raise ValueError(f"Remote attachment exceeds expected size: {name}.")
                        digest.update(block)
                    return size, digest.hexdigest()
            except HTTPError as error:
                error.close()
                if error.code not in {301, 302, 303, 307, 308}:
                    raise
                location = error.headers.get("Location")
                address = self.redirect(initial, address, location, name)
        raise ValueError(f"Too many redirects for Gitee attachment {name}.")

    def redirect(self, initial, current, location, name):
        target = urlsplit(urljoin(current, location or ""))
        attachment = rf"/{re.escape(self.repository)}/attach_files/\d+/download/{re.escape(name)}"
        if target.scheme == "https" and not target.fragment:
            if (current == initial and target.netloc == "gitee.com"
                    and not target.query and re.fullmatch(attachment, target.path)):
                return target.geturl()
            if ((current == initial or re.fullmatch(attachment, urlsplit(current).path))
                    and target.netloc == "foruda.gitee.com"
                    and re.fullmatch(rf"/attach_file/\d+/{re.escape(name)}", target.path, re.IGNORECASE)):
                return target.geturl()
        raise ValueError(f"Unexpected Gitee attachment redirect: {name}.")

    def publish(self, tag, title, notes, assets):
        release_route = f"/releases/tags/{tag}"
        release = self.api(release_route, missing_ok=True)
        fields = {"tag_name": tag, "name": title, "body": notes}
        if release is None:
            repository = self.api("")
            if repository.get("private") is not False or repository.get("full_name") != self.repository:
                raise ValueError("Gitee target is not the requested public repository.")
            release = self.api("/releases", "POST", {
                **fields, "target_commitish": repository["default_branch"], "prerelease": "true",
            })
            if release.get("prerelease") is not True:
                raise ValueError("Gitee did not create a pre-release. No attachments were uploaded.")
        if (release.get("tag_name") != tag or release.get("name") != title
                or type(release.get("prerelease")) is not bool
                or type(release.get("id")) is not int or release["id"] < 1):
            raise ValueError(f"Gitee release {tag} does not match the expected release.")
        release_id = release["id"]
        attach_route = f"/releases/{release_id}/attach_files"
        existing = self.api(attach_route)
        if not isinstance(existing, list):
            raise ValueError("Gitee did not return an attachment list.")
        names = [item.get("name") for item in existing]
        if any(names.count(path.name) > 1 for path in assets):
            raise ValueError("Gitee release contains duplicate attachment names.")
        missing = [path for path in assets if path.name not in names]
        if missing and not release["prerelease"]:
            raise ValueError("Gitee stable release is missing required attachments.")
        for path in missing:
            print(f"Uploading Gitee attachment: {path.name}", flush=True)
            self.api(attach_route, "POST", path=path)
        for path, expected in assets.items():
            self.verify_attachment(tag, path, expected)
        if release["prerelease"]:
            self.api(f"/releases/{release_id}", "PATCH", {**fields, "prerelease": "false"})
        latest = self.api("/releases/latest")
        if latest.get("tag_name") != tag or latest.get("prerelease") is not False:
            raise ValueError(f"Gitee has not confirmed {tag} as the latest stable release.")
        print(f"Published Gitee release: {GITEE}/{self.repository}/releases/tag/{tag}")


def release_assets(directory, tag):
    version = tag[1:]
    apk = directory / f"anon-toolkyo-{version}.apk"
    manifest_path = directory / "update.json"
    checksum = directory / "SHA256SUMS.txt"
    source = directory / f"anon-toolkyo-{version}-source.zip"
    paths = (apk, manifest_path, checksum, source)
    for path in paths:
        if not path.is_file() or path.is_symlink() or not 0 < path.stat().st_size <= 100 * 1024 * 1024:
            raise ValueError(f"Missing or invalid Gitee attachment: {path.name}.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if (manifest.get("versionName") != version or type(manifest.get("versionCode")) is not int
            or manifest["versionCode"] <= 0):
        raise ValueError("Release manifest does not match the requested version.")
    notes = checked_release_notes(manifest.get("notes", ""))
    expected_url = (rf"https://github\.com/{REPOSITORY}/releases/download/"
                    rf"{re.escape(tag)}/{re.escape(apk.name)}")
    url = manifest.get("apkUrl", "")
    if not isinstance(url, str) or not re.fullmatch(expected_url, url):
        raise ValueError("Release manifest has an invalid canonical APK URL.")
    expected = {path: (path.stat().st_size, sha256(path)) for path in paths}
    digest = expected[apk][1]
    if manifest.get("sha256") != digest or checksum.read_text(encoding="ascii").strip() != f"{digest}  {apk.name}":
        raise ValueError("Release APK differs from its manifest or checksum.")
    website = url.rsplit("/releases/download/", 1)[0] + "/releases/tag/" + tag
    body = f"{notes}\n\n对应源码见附件 `{source.name}`。\n\n[GitHub 正式版]({website})"
    return f"anon toolkyo {version}", body, expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="public Gitee OWNER/REPO")
    parser.add_argument("--tag", required=True, help="stable vMAJOR.MINOR.PATCH tag")
    parser.add_argument("--assets-dir", required=True, type=Path,
                        help="verified APK, release manifest, checksum and source ZIP")
    parser.add_argument("--dry-run", action="store_true", help="validate local attachments without credentials or network")
    args = parser.parse_args()
    if not re.fullmatch(REPOSITORY, args.repository):
        parser.error("--repository must use the owner/repository format")
    if not re.fullmatch(TAG, args.tag):
        parser.error("--tag must be a stable vMAJOR.MINOR.PATCH tag")
    try:
        title, notes, assets = release_assets(args.assets_dir.resolve(), args.tag)
        if args.dry_run:
            for path, (size, digest) in assets.items():
                print(f"{digest}  {path.name} ({size} bytes)")
            print(f"Validated {args.tag} for {args.repository}. No requests were made.")
            return 0
        GiteeRelease(args.repository, credential()).publish(args.tag, title, notes, assets)
    except (KeyError, TypeError, OSError, ValueError) as error:
        print(f"Gitee release error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
