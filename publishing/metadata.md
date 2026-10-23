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
| Issues / Source | `<TODO: Repository-URL>` (GPL → Source-Link auf Modrinth fast Pflicht) |
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
- **Gallery** — Vorschläge aus `validation/`:
  1. `run75-beta6-gate/screenshots/0027_ui-overview.png` (Bedienmenü)
  2. `run75-beta6-gate/screenshots/0031_ui-settings-pausen.png` (Settings-Tabs)
  3. `run74-hud/...` HUD im aktiven Bau
  4. `gentlebuild-hard-complete.png` (fertiger Mechanismus-Bau)
  5. Ein In-Game-Screenshot beim Reihenbau (Sprint/Stütze)
  → Vor Upload kurz auf sensible Infos prüfen (Koordinaten/Servername).
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

- [ ] **Projekt-Icon** (beide Plattformen Pflicht): quadratisch, Modrinth
  empfiehlt ≥ 512×512 PNG — Idee: stilisiertes Gerüst/Eimer-Block oder
  „GB“-Monogramm; Datei dann im Repo ablegen
- [ ] Repository-URL in den Beschreibungen ersetzt (`<TODO: Repository-URL>`)
- [ ] Lizenz-Datei (`LICENSE`, GPL-3.0) liegt dem JAR bei bzw. ist verlinkt
- [ ] Galerie-Screenshots ohne sensible Infos
- [ ] Beta-Label klar (kein „stable" im Titel)
- [ ] Dependencies auf beiden Plattformen als Pflicht eingetragen
- [ ] Modrinth: Quellcode-Link gesetzt (GPL + Review-Richtlinie)
- [ ] CurseForge: „Client Only" + Game-Version-Matrix 26.2.x
