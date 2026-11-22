"""Create and complete a GitHub Release draft with an existing login."""

import hashlib
import json
import mimetypes
import os
import shutil
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


POWERSHELL_UPLOAD = r"""
$ErrorActionPreference = 'Stop'
$headers = @{
    Authorization = ('Bearer ' + $env:ANON_RELEASE_UPLOAD_TOKEN)
    Accept = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}
$upload = @{
    Uri = $env:ANON_RELEASE_UPLOAD_URL
    Method = 'Post'
    Headers = $headers
    InFile = $env:ANON_RELEASE_UPLOAD_FILE
    ContentType = $env:ANON_RELEASE_UPLOAD_TYPE
    MaximumRedirection = 0
    TimeoutSec = 240
}
$result = Invoke-RestMethod @upload
$result | ConvertTo-Json -Depth 8 -Compress
"""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Keep the credential on the specific GitHub endpoint selected by the caller.
        return None


def credential(repository, cwd):
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        return token
    cli = shutil.which("gh")
    if cli:
        result = subprocess.run([cli, "auth", "token", "--hostname", "github.com"], cwd=cwd,
                                check=False, capture_output=True, text=True, encoding="utf-8")
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    result = subprocess.run(
        ["git", "credential", "fill"], cwd=cwd, check=False, capture_output=True,
        input=f"protocol=https\nhost=github.com\npath={repository}.git\n\n",
        text=True, encoding="utf-8",
        env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="Never"),
    )
    if result.returncode == 0:
        for line in result.stdout.splitlines():
            if line.startswith("password=") and line[9:]:
                return line[9:]
    raise ValueError("No GitHub login found. Sign in with Git Credential Manager or GitHub CLI.")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


class GitHubRelease:
    def __init__(self, repository, cwd):
        self.token = credential(repository, cwd)
        self.repository = f"/repos/{repository}"
        self.http = build_opener(NoRedirect())

    def request(self, host, route, *, method="GET", data=None, headers=None):
        request = Request(f"https://{host}{self.repository}{route}", data=data, method=method, headers={
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "anon-toolkyo-release",
            **(headers or {}),
        })
        try:
            with self.http.open(request, timeout=90) as response:
                return json.load(response)
        except HTTPError as error:
            raise ValueError(f"GitHub {method} {route} returned HTTP {error.code}.") from None

    def api(self, route, form=None):
        if form is None:
            return self.request("api.github.com", route)
        return self.request("api.github.com", route, method="POST", data=json.dumps(form).encode("utf-8"),
                            headers={"Content-Type": "application/json"})

    def find_release(self, tag):
        page = 1
        while True:
            releases = self.api(f"/releases?per_page=100&page={page}")
            release = next((value for value in releases if value["tag_name"] == tag), None)
            if release is not None or len(releases) < 100:
                return release
            page += 1

    def upload(self, release_id, path):
        query = urlencode({"name": path.name})
        route = f"/releases/{release_id}/assets?{query}"
        content_type = mimetypes.guess_type(path)[0] or "application/octet-stream"
        powershell = shutil.which("pwsh") if os.name == "nt" else None
        if powershell:
            # PowerShell uses the system HTTPS client and proxy for large APK uploads.
            environment = dict(os.environ, ANON_RELEASE_UPLOAD_TOKEN=self.token,
                               ANON_RELEASE_UPLOAD_URL=f"https://uploads.github.com{self.repository}{route}",
                               ANON_RELEASE_UPLOAD_FILE=str(path.resolve()), ANON_RELEASE_UPLOAD_TYPE=content_type)
            response = subprocess.run(
                [powershell, "-NoProfile", "-NonInteractive", "-Command", POWERSHELL_UPLOAD],
                env=environment, capture_output=True, text=True, encoding="utf-8", errors="replace",
            )
            if response.returncode:
                raise ValueError(f"GitHub asset upload failed: {path.name}. Rerun to resume the draft.")
            return json.loads(response.stdout)
        with path.open("rb") as stream:
            return self.request("uploads.github.com", route, method="POST",
                                data=stream, headers={
                                    "Content-Type": content_type,
                                    "Content-Length": str(path.stat().st_size),
                                })

    def create_draft(self, tag, title, notes, paths):
        draft = self.find_release(tag)
        if draft is None:
            draft = self.api("/releases", {"tag_name": tag, "name": title, "body": notes, "draft": True})
        if not draft["draft"] or draft["name"] != title or draft["body"] != notes:
            raise ValueError(f"GitHub already has a different release for {tag}.")
        assets = {asset["name"]: asset for asset in draft["assets"]}
        print(f"GitHub release draft: {draft['html_url']}", flush=True)
        for path in paths:
            asset = assets.get(path.name) or self.upload(draft["id"], path)
            expected = "sha256:" + sha256(path)
            if asset.get("state") != "uploaded" or asset.get("digest") != expected:
                raise ValueError(f"GitHub draft asset differs or is incomplete: {path.name}")
