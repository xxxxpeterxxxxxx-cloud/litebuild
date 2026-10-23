#!/usr/bin/env python3
"""Upload a LiteBuild release to CurseForge (project 1761829).

Token from ~/.config/litebuild/curseforge.key — a project-scoped
X-Api-Token (works only on minecraft.curseforge.com/api/* endpoints,
not on api.curseforge.com/v1).

Game version IDs (minecraft.curseforge.com/api/game/versions):
  16498 = 26.2 (Java Edition), 7499 = Fabric loader, 9638 = Client.

Usage: python3 publishing/curseforge/upload_version.py [jar-path] [--dry-run]
"""
import json, os, sys, glob, subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PROJECT_ID = 1761829
GAME_VERSIONS = [16498, 7499, 9638]  # 26.2, Fabric, Client
REQUIRED_DEPS = ["fabric-api", "litematica", "malilib"]

KEY = open(os.path.expanduser("~/.config/litebuild/curseforge.key")).read().strip()
args = [a for a in sys.argv[1:] if not a.startswith("--")]
DRY = "--dry-run" in sys.argv

candidates = [c for c in glob.glob(os.path.join(ROOT, "build/libs/litebuild-*.jar"))
              if "-sources" not in c and "-dev" not in c]
jar = args[0] if args else sorted(candidates, key=os.path.getmtime)[-1]
version = os.path.basename(jar).replace("litebuild-", "").replace(".jar", "")
changelog_path = os.path.join(ROOT, "publishing/changelog",
                              version.replace("0.1.0-", "") + ".md")

meta = {
    "changelog": open(changelog_path).read() if os.path.exists(changelog_path) else "",
    "changelogType": "markdown",
    "displayName": f"LiteBuild {version} (MC 26.2)",
    "gameVersions": GAME_VERSIONS,
    "releaseType": "beta",
    "relations": {"projects": [
        {"slug": s, "type": "requiredDependency"} for s in REQUIRED_DEPS]},
}

if DRY:
    print(json.dumps({**meta, "changelog": "..."}, indent=2))
    print("jar:", jar)
    sys.exit(0)

r = subprocess.run([
    "curl", "-s", "-X", "POST", "-H", f"X-Api-Token: {KEY}",
    "--form-string", f"metadata={json.dumps(meta)}",
    "-F", f"file=@{jar}",
    f"https://minecraft.curseforge.com/api/projects/{PROJECT_ID}/upload-file",
], capture_output=True, text=True)
print(r.stdout or r.stderr)
