# Publishing-Metadaten — GentleBuild

Referenz für das Ausfüllen der Projektseiten. Texte liegen je Plattform
in `modrinth/` und `curseforge/`.

## Gemeinsame Felder

| Feld | Wert |
|---|---|
| Name | GentleBuild |
| Slug | `gentlebuild` |
| Lizenz | GPL-3.0-or-later |
| Loader | Fabric |
| Side | Client only (kein Server-Install nötig, funktioniert auf Servern per Client-Interaktion) |
| Minecraft-Version | 26.2 (`~26.2` → alle 26.2.x) |
| Java | 25 |
| Pflicht-Dependencies | Fabric API (beliebig aktuell), Litematica ≥ 0.28.8, MaLiLib ≥ 0.29.6 |
| Optionale Dependencies | — |
| Issues / Source | `https://github.com/xxxxpeterxxxxxx-cloud/gentlebuild` (GPL → Source-Link auf Modrinth fast Pflicht) |
| Donation | `<TODO oder leer>` |

## Modrinth

- **Summary** (≤ 256 Zeichen): `modrinth/summary.txt`
- **Description**: `modrinth/description-en.md` (Hauptseite Englisch;
  `description-de.md` kann als zusätzlicher Absatz/Link eingebettet werden —
  Modrinth hat kein Multi-Language-Feld; ggf. deutsche Fassung unter
  „Deutsch“-Überschrift in derselben Beschreibung anhängen)
- **Categories** (max. 3 empfohlen):
  - `Utility` (primär)
  - `Game Mechanics`
  - `Technology` (alternativ `Management`)
- **Environments**: Client = Required, Server = Unsupported
- **Loaders**: Fabric
- **License**: GPL-3.0-or-later
- **Gallery** — fertige, gebrandete Karten (1600×900, englische
  Headlines, echte GameTest-Screenshots) in `assets/out/`:
  `hero.png` (Titel-Karte), `gallery-verify.png`, `gallery-phases.png`,
  `gallery-scaffold.png`, `gallery-batch.png`, `gallery-sprint.png`,
  `gallery-menu.png`, `gallery-settings.png`, `gallery-cleanup.png`.
  Empfohlene Reihenfolge: hero → verify → phases → batch → sprint →
  scaffold → cleanup → menu → settings.
  Generator: `assets/make_gallery.py` (Quellen: `assets/gallery/`).
- **Featured Version**: `0.1.0-beta.6` (Type: `beta`, Changelog:
  `changelog/beta-6.md`).

## CurseForge

- **Short description**: `curseforge/summary.txt`
- **Description**: `curseforge/description-en.md` (Markdown erlaubt; HTML
  wird auch akzeptiert)
- **Categories**:
  - `Utility` (primär)
  - `Automation`
  - `Map and Information` (Schematic-Bezug)
- **Game Versions**: Minecraft 26.2 (alle 26.2.x auswählen, die CF anbietet)
- **Mod Loaders**: Fabric
- **License**: GPLv3
- **Project type**: Mod, Client-only
- **Required deps** (bei jedem File-Upload als „Required Dependency"
  markieren): Fabric API, Litematica, MaLiLib — jeweils die CF-Projekte
  verlinken, damit der CF-Launcher sie mitzieht.
- **Files**: `gentlebuild-0.1.0-beta.6.jar`, Release Type `Beta`,
  Display name `GentleBuild 0.1.0-beta.6 (MC 26.2)`.

## Version-Changelog-Pflicht

Jeder Upload braucht ein Changelog — Template in `changelog/beta-6.md`.
Regel: nur belegte Aussagen (Teststand in `validation/`), keine
Anticheat-Garantie, Hinweis „Serverside rules apply" beibehalten.

## Checkliste vor Upload

- [x] **Projekt-Icon** fertig: `assets/out/icon.png` / `icon-512.png`
  (512×512), `icon-256.png`, `icon-128.png`, `icon-pixel-64.png` —
  isometrischer Pixel-Art-Voxel-Stack (Stein unten → cyan Blueprint-
  Geister oben), inspiriert von Litematicas Wireframe-Icon.
  Generator: `assets/make_icon_iso.py`.
- [ ] Repository-URL in den Beschreibungen ersetzt (`https://github.com/xxxxpeterxxxxxx-cloud/gentlebuild`)
- [ ] Lizenz-Datei (`LICENSE`, GPL-3.0) liegt dem JAR bei bzw. ist verlinkt
- [ ] Galerie-Screenshots ohne sensible Infos
- [ ] Beta-Label klar (kein „stable" im Titel)
- [ ] Dependencies auf beiden Plattformen als Pflicht eingetragen
- [ ] Modrinth: Quellcode-Link gesetzt (GPL + Review-Richtlinie)
- [ ] CurseForge: „Client Only" + Game-Version-Matrix 26.2.x
