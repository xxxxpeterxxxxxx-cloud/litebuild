#!/usr/bin/env python3
"""Upload a LiteBuild release version to Modrinth.

Token from ~/.config/litebuild/modrinth.token. JAR is expected at
build/libs/ (default: the newest litebuild-*.jar, or pass a path).
Usage: python3 publishing/modrinth/upload_version.py [jar-path] [--dry-run]
"""
import json, os, sys, urllib.request, mimetypes, uuid, glob

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
API = "https://api.modrinth.com/v2"
PROJECT = "litebuild"
TOKEN = open(os.path.expanduser("~/.config/litebuild/modrinth.token")).read().strip()

args = [a for a in sys.argv[1:] if not a.startswith("--")]
DRY = "--dry-run" in sys.argv

candidates = sorted(glob.glob(os.path.join(ROOT, "build/libs/litebuild-*.jar")),
                    key=os.path.getmtime)
jar = args[0] if args else (candidates[-1] if candidates else
                            os.path.join(ROOT, "build/libs/litebuild-0.1.0-beta.7.jar"))
version = os.path.basename(jar).replace("litebuild-", "").replace(".jar", "")
changelog_path = os.path.join(ROOT, "publishing/changelog",
                              version.replace("0.1.0-", "") + ".md")


def multipart(fields, files):
    boundary = "----lb" + uuid.uuid4().hex
    parts = []
    for name, value in fields:
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    for name, fname, blob in files:
        ct = mimetypes.guess_type(fname)[0] or "application/octet-stream"
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{fname}\"\r\nContent-Type: {ct}\r\n\r\n".encode() + blob + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main():
    meta = {
        "name": f"LiteBuild {version}",
        "version_number": version,
        "changelog": open(changelog_path).read() if os.path.exists(changelog_path) else "",
        "dependencies": [],
        "game_versions": ["26.2"],
        "version_type": "beta",
        "loaders": ["fabric"],
        "featured": True,
        "project_id": None,  # resolved via slug endpoint
        "file_parts": ["file"],
        "primary_file": "file",
    }
    if DRY:
        print(json.dumps({**meta, "changelog": "..."}, indent=2))
        print("jar:", jar, os.path.getsize(jar) if os.path.exists(jar) else "MISSING", "bytes")
        return
    data, ct = multipart(
        [("data", json.dumps(meta))],
        [("file", os.path.basename(jar), open(jar, "rb").read())])
    r = urllib.request.Request(
        f"{API}/version", data=data, method="POST")
    r.add_header("Authorization", TOKEN)
    r.add_header("Content-Type", ct)
    r.add_header("User-Agent", "litebuild-publish/1.0")
    try:
        with urllib.request.urlopen(r) as resp:
            out = json.loads(resp.read())
            print("uploaded:", out.get("version_number"), "id", out.get("id"))
            print("url:", out.get("files", [{}])[0].get("url"))
    except urllib.error.HTTPError as e:
        print("FAILED", e.code, e.read().decode()[:800])


if __name__ == "__main__":
    main()
