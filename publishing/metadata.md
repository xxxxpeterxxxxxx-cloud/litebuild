# Publishing metadata — GentleBuild

Reference for filling out the project pages. Texts live per platform in
`modrinth/` and `curseforge/`.

## Shared fields

| Field | Value |
|---|---|
| Name | GentleBuild |
| Slug | `gentlebuild` |
| License | GPL-3.0-or-later |
| Loader | Fabric |
| Side | Client only (no server install needed; works on servers via client interaction) |
| Minecraft version | 26.2 (`~26.2` → all 26.2.x) |
| Java | 25 |
| Required dependencies | Fabric API (any current), Litematica ≥ 0.28.8, MaLiLib ≥ 0.29.6 |
| Optional dependencies | — |
| Issues / Source | `https://github.com/xxxxpeterxxxxxx-cloud/gentlebuild` (GPL → source link is near-mandatory on Modrinth) |
| Donation | `<TODO or empty>` |

## Platform notes

- **OS compatibility**: pure Java/LWJGL — runs on Linux (all distros),
  Windows and macOS wherever Fabric + dependencies run. No
  platform-specific code.
- **Forge/NeoForge**: not supported. GentleBuild relies on Litematica's
  loading pipeline; Litematica and MaLiLib exist only for Fabric. Say
  this plainly on both pages.
- **Other MC versions**: each needs matching Litematica/MaLiLib builds;
  not covered.

## Modrinth

- **Summary** (≤ 256 chars): `modrinth/summary.txt`
- **Description**: `modrinth/description-en.md` (main page English;
  `description-de.md` can be embedded as an extra section/link —
  Modrinth has no multi-language field; append the German version under
  a "Deutsch" heading in the same description if wanted)
- **Categories** (max. 3 recommended):
  - `Utility` (primary)
  - `Game Mechanics`
  - `Technology` (alternative: `Management`)
- **Environments**: Client = Required, Server = Unsupported
- **Loaders**: Fabric
- **License**: GPL-3.0-or-later
- **Gallery** — finished branded cards (1600×900, English headlines,
  real gametest screenshots) in `assets/out/`:
  `hero.png` (title card), `gallery-verify.png`, `gallery-phases.png`,
  `gallery-scaffold.png`, `gallery-batch.png`, `gallery-sprint.png`,
  `gallery-menu.png`, `gallery-settings.png`, `gallery-cleanup.png`.
  Recommended order: hero → verify → phases → batch → sprint →
  scaffold → cleanup → menu → settings.
  Generator: `assets/make_gallery.py` (sources: `assets/gallery/`).
- **Featured version**: `0.1.0-beta.7` (type: `beta`, changelog:
  `changelog/beta-7.md`).

## CurseForge

- **Short description**: `curseforge/summary.txt`
- **Description**: `curseforge/description-en.md` (Markdown allowed;
  HTML is also accepted)
- **Categories**:
  - `Utility` (primary)
  - `Automation`
  - `Map and Information` (schematic relation)
- **Game versions**: Minecraft 26.2 (select all 26.2.x CF offers)
- **Mod loaders**: Fabric
- **License**: GPLv3
- **Project type**: Mod, client-only
- **Required deps** (mark as "Required Dependency" on every file
  upload): Fabric API, Litematica, MaLiLib — link the CF projects so the
  CF launcher pulls them in.
- **Files**: `gentlebuild-0.1.0-beta.7.jar`, release type `Beta`,
  display name `GentleBuild 0.1.0-beta.7 (MC 26.2)`.

## Changelog obligation

Every upload needs a changelog — template in `changelog/beta-7.md`.
Rule: only proven statements (test state in `validation/`), no
anticheat guarantee, keep the "serverside rules apply" note.

## Pre-upload checklist

- [x] **Project icon** done: `assets/out/icon.png` / `icon-512.png`
  (512×512), `icon-256.png`, `icon-128.png`, `icon-pixel-64.png` —
  isometric pixel-art voxel stack (solid stone below → cyan blueprint
  ghosts above), inspired by Litematica's wireframe icon.
  Generator: `assets/make_icon_iso.py`, finished in PhotoCraft
  (cyan outer glow + drop shadow).
- [x] Repository URL in descriptions replaced
  (`https://github.com/xxxxpeterxxxxxx-cloud/gentlebuild`)
- [ ] License file (`LICENSE`, GPL-3.0) shipped with the JAR / linked
- [ ] Gallery screenshots contain no sensitive info
- [ ] Beta label clear (no "stable" in the title)
- [ ] Dependencies marked required on both platforms
- [ ] Modrinth: source link set (GPL + review policy)
- [ ] CurseForge: "Client Only" + game version matrix 26.2.x
