#!/usr/bin/env python3
"""Sync Modrinth project metadata (body, links, icon, gallery) for LiteBuild.

Token is read from ~/.config/litebuild/modrinth.token — never hardcoded.
Usage: python3 publishing/modrinth/sync_project.py [--dry-run]
"""
import json, os, sys, urllib.request, mimetypes, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
API = "https://api.modrinth.com/v2"
PROJECT = "litebuild"
TOKEN = open(os.path.expanduser("~/.config/litebuild/modrinth.token")).read().strip()
DRY = "--dry-run" in sys.argv


def req(method, path, data=None, ctype="application/json"):
    r = urllib.request.Request(API + path, data=data, method=method)
    r.add_header("Authorization", TOKEN)
    r.add_header("User-Agent", "litebuild-publish/1.0 (github.com/xxxxpeterxxxxxx-cloud/litebuild)")
    if data is not None:
        r.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(r) as resp:
            body = resp.read()
            return resp.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:500]


def multipart(fields, files):
    """fields: list of (name, value); files: list of (name, filename, bytes)."""
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
    body = open(os.path.join(ROOT, "publishing/modrinth/description-en.md")).read()
    patch = {
        "body": body,
        "issues_url": "https://github.com/xxxxpeterxxxxxx-cloud/litebuild/issues",
        "wiki_url": "https://github.com/xxxxpeterxxxxxx-cloud/litebuild/blob/master/BETA_READINESS.md",
        "categories": ["management", "utility"],
        "additional_categories": ["game-mechanics"],
    }
    if DRY:
        print("[dry] would PATCH project:", list(patch))
    else:
        code, resp = req("PATCH", f"/project/{PROJECT}", json.dumps(patch).encode())
        print("PATCH project:", code, resp if code >= 300 else "ok")

    # Icon
    icon_path = os.path.join(ROOT, "publishing/assets/out/icon-512.png")
    if os.path.exists(icon_path):
        if DRY:
            print("[dry] would PATCH icon:", icon_path)
        else:
            code, resp = req("PATCH", f"/project/{PROJECT}/icon?ext=png",
                             open(icon_path, "rb").read(), "image/png")
            print("PATCH icon:", code, resp if code >= 300 else "ok")

    # Gallery — upload each card once (dedup by title)
    if not DRY:
        code, proj = req("GET", f"/project/{PROJECT}")
        existing = {g.get("title") for g in (proj.get("gallery") or [])} if isinstance(proj, dict) else set()
    else:
        existing = set()
    cards = [
        ("hero.png", "LiteBuild — builds blueprints the honest way"),
        ("gallery-verify.png", "Two-pass final verification"),
        ("gallery-phases.png", "Dependency-aware build phases"),
        ("gallery-batch.png", "Smart batching on rows"),
        ("gallery-sprint.png", "Real sprint on long legs"),
        ("gallery-scaffold.png", "Honest scaffolding with real mining"),
        ("gallery-cleanup.png", "Cleanup removes only owned supports"),
        ("gallery-menu.png", "Control panel"),
        ("gallery-settings.png", "Settings screen"),
    ]
    for fname, title in cards:
        p = os.path.join(ROOT, "publishing/assets/out", fname)
        if not os.path.exists(p):
            print("missing:", fname)
            continue
        if title in existing:
            print("gallery skip (exists):", title)
            continue
        if DRY:
            print("[dry] would upload gallery:", fname)
        else:
            from urllib.parse import quote
            q = f"ext=png&featured={'true' if fname == 'hero.png' else 'false'}&title={quote(title)}&ordering=0"
            code, resp = req("POST", f"/project/{PROJECT}/gallery?{q}",
                             open(p, "rb").read(), "image/png")
            print("gallery:", fname, code, resp if code >= 300 else "ok")


if __name__ == "__main__":
    main()
