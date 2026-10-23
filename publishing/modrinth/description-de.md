# GentleBuild

**GentleBuild baut deine Litematica-Baupläne langsam und ehrlich — mit denselben Eingaben und Paketen wie ein menschlicher Spieler.**

Keine Welt-Edits. Kein Silent Aim. Kein Teleport. Keine Paket-Tricks. GentleBuild läuft zu jedem Block, dreht den Kopf, wählt das richtige Item und klickt — dann wartet es auf die Server-Bestätigung. Es ist ein Baubot, kein Cheat, und sein Netzwerkverkehr bleibt innerhalb der Vanilla-Regeln.

## Was der Mod tut

- **Importiert alles, was Litematica lesen kann** — `.litematic`, `.schem` (Sponge), `.schematic`, Vanilla-Struktur-`.nbt`. Laden und Konvertierung laufen über Litematicas eigene Pipeline.
- **Baut in Abhängigkeits-Phasen**: Struktur → Anbauteile → Mechanismen → Flüssigkeiten → Aktivierung. Wassergeloggte Vollblöcke werden zuerst trocken gebaut; Wasser kommt als eigene Aufgabe, sobald die Abdichtung verifiziert ist.
- **Bewegt sich wie ein Spieler**: A*-Wegsuche über kollisionsgeprüfte Standpunkte, gedeckelte Drehgeschwindigkeiten, echtes Sprinten auf langen Strecken, eine gezielte Blickdrehung pro Ziel und danach Stillstand — kein Radar-Scannen, keine Jitter-Formeln.
- **Arbeitet in sinnvollen Abschnitten**: Gerade Reihen und erreichbare Flächen werden von einem guten Standpunkt aus gebaut (bis zu 8 bestätigte Platzierungen vor dem Weitergehen) statt Schritt-für-Schritt zu kriechen.
- **Nutzt dein echtes Inventar**: Hotbar-Wechsel plus Nachfüllen aus dem Hauptinventar über normale Tausch-Klicks — mit kurzen, menschlichen Wechselpausen und ohne Rüstung oder Schildhand anzufassen.
- **Gerüstet ehrlich**: bis zu 8 temporäre Stützblöcke pro Wegsegment, wenn eine Zelle unerreichbar ist — danach mit echtem Abbau entfernt; fremde oder veränderte Blöcke werden nie angefasst.
- **Respektiert Flüssigkeiten**: Wasser wird nur in vollständig abgedichtete, live geprüfte Bereiche gegossen; Lava neben brennbarem Material wird verweigert; Verdunstungs-Dimensionen werden respektiert.
- **Verifiziert die eigene Arbeit**: Zwischenprüfungen alle 5–10 Blöcke (phasenabhängig), dann eine vollständige Endprüfung in zwei Durchläufen inklusive geforderter Luft-Zellen. „Fertig" gilt erst, wenn der Server es zweimal sagt.
- **Repariert sicher**: eigene, nachweisbar sichere Fehler werden automatisch korrigiert (begrenzte Runden) — alles andere wird als klare manuelle Aufgabe mit Koordinate und Grund gemeldet.

## Bedienung

- **G** — Bedienfenster (Bauplan, Start/Pause/Stopp, Prüfen+Fixen, Stützenabbau, Fluid-/Aktivierungs-Freigaben, Einstellungen)
- **H** — Pausieren / Notstopp
- `/gb` (`/gentlebuild`) — `select`, `load <datei>`, `start`, `pause`, `stop`, `status`, `materials`, `issues`, `fluids`, `activate`, `cleanup`

Der Bau pausiert automatisch bei manueller Eingabe, geöffneten GUIs, Fokusverlust, Schaden, niedriger Gesundheit, Reiten/Fliegen, Weltenwechsel und verändertem Placement.

## HUD & Einstellungen

Eine native Statusanzeige (vier Ecken, Skalierung, Deckkraft, Kompaktmodus — alles einstellbar) zeigt Bauplan, Zustand, Phase, Fortschritt, Ziel, Pausengründe und aktive Stützen. Das Einstellungsmenü (G → Einstellungen) hat sechs Tabs — Bauen, Timings, Blick, Bewegung, Pausen, HUD — mit Validierung und atomarem Speichern.

## Installation

1. **Fabric Loader 0.19.5+** für Minecraft **26.2**
2. Benötigte Mods: **Fabric API**, **Litematica ≥ 0.28.8**, **MaLiLib ≥ 0.29.6**
3. `gentlebuild-*.jar` nach `mods/`
4. **Java 25** wird benötigt

## Ehrliche Grenzen

- Entities und BlockEntity-Inhalte (Truheninhalte, Schildtexte, …) werden als **manuelle Aufgaben** gemeldet, nicht reproduziert.
- Dichte, update-abhängige Mechanismen (verschachtelter Redstone, Kolben-Timing) können manuelle Zwischenschritte brauchen.
- Der Mod nutzt nur legale Client-Interaktionen — er ist absichtlich langsamer als Creative-/Paste-Werkzeuge.
- Ein lokaler Paket-Audit in der Testsuite erzwingt Vanilla-Invarianten (Rotation ≤ 45°/Tick, Vanilla-Reichweite, korrekte Paketreihenfolge, kein Doppel-Use, keine unmögliche Bewegung). Das ist starke Evidenz, keine Garantie: **wo ein Server Bau-Bots verbietet, ist der Mod dort nicht erlaubt.** Externer Anticheat-Test ist offen im Repository dokumentiert.

## Quellcode & Lizenz

- Quellcode, Issues und Validierungsbelege: **https://github.com/xxxxpeterxxxxxx-cloud/gentlebuild**
- Lizenz: **GPL-3.0-or-later**
