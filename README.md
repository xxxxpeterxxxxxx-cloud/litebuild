# GentleBuild

GentleBuild ist ein reiner **Client-Mod** für Minecraft **26.2** (Fabric), der
Litematica-Baupläne schrittweise mit **ganz normalen Spielerinteraktionen**
aufbaut – kein Direktsetzen von Blöcken, keine Serverbefehle, kein Teleport,
kein „Silent Aim“, kein protokollseitiger Cheat.

Lizenz: **GPL-3.0-or-later** (siehe `LICENSE`).

## Was der Mod tut

- **Import** über Litematicas eigenen Datei-Browser: `.litematic`, `.schem`
  (Sponge), `.schematic`, Vanilla-Struktur-`.nbt` und die übrigen
  Litematica-Formate werden von Litematica selbst konvertiert.
- **Snapshot** der ausgewählten Placement (inkl. reservierter Luft-Zellen)
  mit Größenlimits (max. 1 Mio. Regionzellen, 100k Nicht-Luft-Ziele) und
  stabilem Fingerabdruck; wird die Placement verschoben/verändert, pausiert
  der Bau.
- **Abhängigkeits-Phasen**: Struktur → Anbauteile → Mechanismen →
  Flüssigkeiten → Aktivierung. Bereits korrekte Blöcke werden übersprungen;
  wassergeloggte Vollblöcke werden zuerst trocken gebaut, das Wasser kommt
  als separate Aufgabe später.
- **Normale Bewegung** (A*-Wegsuche über kollisionsgeprüfte Standpunkte),
  interpolierte, geschwindigkeits-/beschleunigungsbegrenzte Blickbewegung —
  die Server-Rotation bleibt immer die echte (kein Silent Aim).
- **Gezielte Kopfbewegung** (ab 0.1.0-beta.5, abschaltbar): Jeder neue
  Blickpunkt wird als eine einzige Dreh-Bewegung für Gieren und Nicken
  ausgeführt — sie beschleunigt und bremst innerhalb der konfigurierten
  Grenzen, landet genau am Ziel und bleibt danach ruhig. Große Wendungen
  starten nach einer kurzen Reaktionszeit (1–3 Ticks), kleine nicht. Es gibt
  kein künstliches Untersteuern, keine Korrekturpausen und keine zufälligen
  Blicke während Pausen. Kleine Zielverschiebungen aktualisieren nur das Ziel,
  ohne die Bewegung neu zu starten. Beim Gehen bleibt der Blick auf einer
  festen Richtung, bis sich die Richtung wirklich ändert.
  Auf Wunsch kurze Baupausen (0,4–1,2 s) und seltene sichere AFK-Pausen
  (bis 5 s, nur auf stabilem Untergrund am Aufgabenende).
- **Hauptinventar-Nachfüllung** (ab 0.1.0-beta.5): Fehlt das Material in der
  Hotbar, wird es aus Slots 9–35 über einen normalen Tausch-Klick geholt —
  bevorzugt in einen leeren oder den gerade gewählten Hotbar-Platz. Das geht
  nur ohne fremdes Container-Menü und mit leerem Cursor; sonst pausiert der Bau mit
  einem Grund, ohne etwas zu verschieben. Rüstung und Schildhand werden nie
  angefasst. Nach jedem Wechsel wartet GentleBuild kurz, bis der Client das
  Zielitem zeigt: 200 ms beim Hotbar-Wechsel, 400 ms beim Nachfüllen aus dem
  Hauptinventar. Das ist eine Prüfung des eigenen Clientzustands, keine
  Server-Bestätigung; die Platzierung prüft das Item weiterhin vor jedem Klick.
- **Standortwahl beim Bauen** (ab 0.1.0-beta.5): Gerade Reihen werden bevorzugt
  von einem Standpunkt aus gebaut, der möglichst viele noch offene, gleichartige
  Blöcke in Reichweite hat. Diese Abdeckung ist nur eine Heuristik; jeder Block
  wird vor dem Setzen erneut gegen Welt und Reichweite geprüft. Zielreihenfolge
  merkt sich einen Pool an Kandidaten und bevorzugt Blickrichtung plus die gerade
  bearbeitete Stelle statt quer über die Fläche zu springen. Das ist ein Bewegungsstil, keine
  Garantie für „menschlich-ununterscheidbar".
- **Tempo ohne Genauigkeitsverlust** (ab 0.1.0-beta.4): alle von der
  aktuellen Position aus lösbaren Ziele werden gebaut, bevor gelaufen wird
  (Stations-Batch über denselben Solver, max. 8 Probes pro Zielwahl);
  die Zielwahl folgt der aktuellen Arbeitsrichtung (Sweep-Momentum) statt
  hin- und herzuspringen; auf langen geraden Wegstrecken wird echt gesprintet
  (`sprintOnLongWalks`, Standard an); aufeinanderfolgende Pipeline-Stufen
  laufen im selben Tick. Jede Platzierung braucht weiterhin Solver-Raycast,
  Live-Revalidierung und Server-Bestätigung — schneller kommt nur aus
  weniger Wegen, nicht aus schwächeren Prüfungen.
- **Platzieren** nur per `useItemOn`/`useItem` mit echter Sichtlinie,
  Reichweite, Flächen-Raycast und echter `getStateForPlacement`-Vorhersage.
  Erfolg zählt erst nach **Server-Bestätigung** (Block-Update-Paket) und
  mehreren stabilen Ticks – lokale Client-Vorhersage gilt nicht.
- **Temporäre Gerüste** für unerreichbare Wege (max. 8 pro Wegsegment),
  nur aus vorher leeren Zellen, per normalem Abbau entfernbar
  (`/gb cleanup`), mit Besitzverfolgung – fremde Blöcke werden nie
  angerührt. Der Abbau ist konservativ: Stützen werden übersprungen, wenn
  sie den Spieler tragen, das Bauwerk berühren, außer Reichweite liegen
  oder seit der Platzierung verändert wurden – verbliebene Stützen werden
  gemeldet, nicht still gelöscht.
- **Flüssigkeiten** nur nach verifizierter Trockenstruktur und nur wenn der
  verbundene Fluidbereich live durch volle stabile Flächen abgedichtet ist;
  Lava wird neben brennbarem Material verweigert; Wasser wird in
  Dimensionen mit Verdunstung nicht gesetzt.
- **Endverifikation** prüft alle Zellen (inkl. Luft) in zwei vollständigen
  Durchgängen mit konfigurierbarer Beruhigungszeit; `COMPLETE` gibt es nur
  bei vollständig verifiziertem Zustand. Verbleibende eigene Gerüste werden
  in der Statuszeile ausgewiesen. Manuelle Restarbeiten (Entities,
  BlockEntity-Inhalte, nicht reproduzierbare Zustände) werden separat
  gemeldet – der Mod behauptet niemals eine vollständige Farm.

## Grenzen (ehrlich)

- Entities und BlockEntity-Inhalte (Truhen, Hopper-Inventar, Schildtexte)
  werden nicht gesetzt – sie werden als manuelle Aufgabe gemeldet.
- Update-empfindliche oder sehr dichte Mechanismen (Redstone-Farmen mit
  Verschachtelung, Kolben-Schaltungen) können Zwischenschritte erfordern,
  die manuell nachgearbeitet werden müssen.
- Laufflächen aus Stufen/Treppen/Teilblöcken werden konservativ behandelt;
  der Navigator verzichtet im Zweifel auf riskante Kanten.
- Getestet in einer echten Client-Welt (Fabric-Client-Gametest):
  Struktur-Linie, Support-Block, Repeater mit Verzögerung 4, Hopper,
  Glas-Becken mit Wasserquelle, Fluid-Verweigerung bei fehlender Wand ohne
  Material, Resume nach Reparatur, Endverifikation. Screenshots und Logs
  unter `validation/run13/`.
  Zweites Szenario (`validation/run18/`): echte Erdklotz-Stuetze ueber eine
  Luecke, Doppelstufe aus zwei Einzelsetzungen (Items nur im Hauptinventar,
  echte Hotbar-Umschaltung) und explizites `/gb cleanup` mit
  serverbestaetigtem Abbau der Stuetze bei intakter Bauumgebung.
  Tempo-Szenario (`validation/run32/`): eine 1x5-Reihe wird aus maximal
  zwei Standpositionen gebaut (Stations-Batch) und auf einem >8-Block-Weg
  wird echtes Sprinten beobachtet.

### Validierung von beta.5

138 Unit-Tests und alle sieben Client-Spieltestklassen bestehen auf demselben
Quellstand, verteilt auf mehrere Läufe: HardTest in `validation/run71-repair-stance/`,
Bau- und Inventartest sowie Unit-Tests in `validation/run72-final-gate/`,
Reparatur, Lückenüberquerung, Tempo und Zwischenprüfung in
`validation/run73-software-remaining/`. Der gemeinsame Lauf wurde am
Laufzeitlimit abgebrochen; ein erfolgreicher Gesamtlauf in einem Prozess
ist damit noch nicht nachgewiesen. Die vier verbleibenden Tests liefen mit
Software-Rendering in 4m27s durch.

Die 18er-Reihe wird von vier Standpunkten gebaut, mit bis zu acht bestätigten
Platzierungen hintereinander vom selben Standpunkt. Die Lückenüberquerung
bleibt auf Y=64. Der HardTest besteht inklusive Flüssigkeiten, Aktivierung,
Nachprüfung und vier absichtlich eingebauten Fehlern. Sein eigener
Paket-Audit meldet null Verstöße und prüft zusätzlich, dass die echte
Blickrichtung vor dem Platzierklick übertragen wurde. Ein externer
Anticheat-Test steht weiterhin aus.

## Bedienung

- Taste **G** (konfigurierbar): Bedienfenster
- Taste **H** (konfigurierbar): Pausieren / Notstopp
- Befehle `/gentlebuild` (Alias `/gb`):
  `select`, `load <datei>`, `start`, `pause`, `stop`, `status`,
  `materials`, `issues`, `fluids`, `activate`, `cleanup`

Der Bau pausiert automatisch bei manueller Eingabe (WASD/Sprung/Schleichen/
Abbau/Benutzen), geöffneten GUIs, Fokusverlust, Schaden, niedriger
Gesundheit, Tod, Reiten/Fliegen, Weltenwechsel und verändertem Placement.

Konfiguration in `config/gentlebuild.json`, u. a.:

```json
{
  "naturalMovement": true,
  "microCorrectionStrength": 0.6,
  "automaticPauses": true,
  "shortPauseMinActions": 4,
  "shortPauseMaxActions": 10,
  "shortPauseMinTicks": 8,
  "shortPauseMaxTicks": 24,
  "afkIntervalMinTicks": 2400,
  "afkIntervalMaxTicks": 6000,
  "afkDurationMinTicks": 40,
  "afkDurationMaxTicks": 100,
  "sprintOnLongWalks": true,
  "minPlaceIntervalTicks": 3
}
```

Kurze Pausen werden nach `…Actions` bestätigten Aktionen für
`…Ticks` Ticks gelegt; AFK-Pausen nach `afkInterval…Ticks` aktiven
Bauticks für `afkDuration…Ticks` (höchstens 100 Ticks = 5 s). Die beiden Schalter gibt es auch im
Bedienfenster (G). `sprintOnLongWalks` lässt den Spieler auf langen,
freien Wegabschnitten sprinten (normales Sprint-Input, kein
Geschwindigkeitseingriff). `minPlaceIntervalTicks` (Mindestabstand zweier
Platzierungen, Clamp ≥ 2) hat seit beta.4 den Standard **3** statt 6 —
bestehende `gentlebuild.json`-Dateien behalten ihren gespeicherten Wert.

### HUD und Einstellungen (ab 0.1.0-beta.6)

Eine native Statusanzeige über dem Chat zeigt Bauplan, Zustand, Bereich,
Bauschritt-Fortschritt, Ziel, Pause- und Wartegründe sowie aktive Stützen —
lesend pro Frame, ohne Weltzugriffe. Position (vier Ecken), Skalierung,
Deckkraft und Kompaktmodus sind einstellbar; die Anzeige blendet sich bei
offenem Menü und F1-HUD-Modus automatisch aus. Die bisherige
Actionbar-Zeile entfällt dadurch.

**Einstellungen (G → Einstellungen):** Ein Entwurf wird erst mit
„Speichern" übernommen — atomarer Schreibvorgang auf `gentlebuild.json`,
keine laufenden Änderungen ohne Klick. Ungültige Eingaben bleiben sichtbar
und werden mit Grund angemahnt; Min/Max-Paare werden gemeinsam geprüft.
Neue Felder: `hotbarSwitchTicks`, `mainRefillTicks`,
`checkpointStructureBlocks`, `checkpointSensitiveBlocks`, `hudEnabled`,
`hudCorner`, `hudCompact`, `hudScale`, `hudOpacity`. Ältere
`gentlebuild.json`-Dateien ohne diese Felder bekommen die Standardwerte.

### Endprüfung und Reparatur

Nach dem Bau prüft GentleBuild jede Bauplan-Zelle (inkl. geforderter
AIR-Zellen) in zwei kompletten Durchläufen. Abweichungen werden typisiert
gemeldet (`/gb status`, „Pruefen + Fixen" bzw. `/gb verify` startet die
Prüfung auch manuell). Ist `automaticRepairs` aktiv (Standard, Grenze
`maxRepairRounds`, 1–5), werden nur nachweislich sichere, eigene Fehler
automatisch korrigiert: fehlende Blöcke nachsetzen, Verzögerungs-/
Schalterzustände per Rechtsklick anpassen und *eigene* Fehl- oder
Stützblöcke abbauen — niemals fremde oder veränderte Blöcke, BlockEntities,
Fluide, Mechanismen oder Zellen mit Last/Redstone in der Nähe. Alles andere
wird als manueller Punkt mit Grund gemeldet; der Build gilt erst als
`COMPLETE`, wenn zwei saubere Durchläufe bestanden sind.

- **Zwischenprüfungen** (ab 0.1.0-beta.4): Nach 10 fertig gebauten Blöcken
  (bei Mechanik- oder Flüssigkeitsblöcken schon nach 5) wird der bereits
  abgeschlossene Bauzustand dieser Zellen geprüft, nicht der volle Zielzustand.
  Ein Trockenstand (z. B. wasserführender Block vor der Flüssigkeit) gilt dafür
  als fertig; Verbindungen (Treppen-, Drahtform), die auf Nachbarn warten, zählen
  erst in der Endprüfung. Abweichungen werden über dieselbe sichere Reparatur
  korrigiert. Ein Phasenwechsel oder das Bauplanende schließt auch kleine Reste
  ab. Die vollständige Endprüfung mit zwei Durchläufen bleibt unverändert;
  Zwischenprüfungen schwenken die Kamera nicht.

## Installation

1. Fabric Loader **0.19.5+** für Minecraft **26.2**
2. Mods: `fabric-api-0.161.0+26.2`, `litematica-fabric-26.2-0.28.8`,
   `malilib-fabric-26.2-0.29.6`, `gentlebuild-0.1.0-beta.6.jar`
3. Java **25** wird benötigt.

Kompatibilität: `fabric.mod.json` erlaubt `~26.2` (alle 26.2.x-Patches),
Fabric API beliebig aktuell, Litematica ≥ 0.28.8, MaLiLib ≥ 0.29.6.
Andere Minecraft-Hauptversionen brauchen jeweils passende
Litematica-/MaLiLib-Builds und sind nicht abgedeckt.

## Bauen aus Quellen

```sh
./gradlew build
```

Erzeugt `build/libs/gentlebuild-0.1.0-beta.6.jar` und
`gentlebuild-0.1.0-beta.6-sources.jar`. JDK 25 wird über die
Java-Toolchain benötigt (z. B. `JAVA_HOME` oder Gradle-Auto-Erkennung;
keine projektspezifischen Pfade in `gradle.properties`).

## Verifizierung / Release-Gate

Ein reproduzierbarer Gesamtlauf ist über ein einziges Kommando dokumentiert:

```sh
./scripts/verify.sh
```

Das Skript prüft die Umgebung (Java/Javac, Display), setzt
`LIBGL_ALWAYS_SOFTWARE=1` als Standard für Software-Rendering
(überschreibbar) und respektiert ein vorhandenes `DISPLAY`. Ohne
X-Display — oder mit `--isolated` — startet es ein eigenes Xwayland auf
einem freien privaten Display (Suche ab `:99`; `:0` und `:2` werden nie
angefasst, nur das selbst gestartete Xwayland wird wieder beendet). Es
führt nacheinander `./gradlew --offline clean test`,
`./gradlew --offline runClientGameTest` und `./gradlew --offline build`
aus. Alle registrierten Client-GameTests werden dabei vom Manifest
`src/gametest/resources/fabric.mod.json` (Entrypoint
`fabric-client-gametest`) gesteuert — das Skript zählt keine Tests
einzeln auf.

Alle Belege landen in `validation/release-gate-<zeitstempel>/`:
Konsolen-Logs je Schritt, Unit-Test-XMLs, die GameTest-Screenshots,
Kopien der verwendeten `fabric.mod.json`, `git status`/
`git diff --check` und SHA-256-Prüfsummen der erzeugten JARs. Am Ende
steht eine eindeutige `RELEASE-GATE: PASS`- bzw. `FAIL`-Zeile; bei
Schrittfehlern, Timeout (Standard 2400 s je Schritt, `--timeout`) oder
fehlendem `BUILD SUCCESSFUL` im GameTest-Log ist der Exit-Code ungleich
null.

Flags: `--skip-gametest` (nur Unit-Tests + Build), `--isolated`,
`--display N`, `--timeout SEKUNDEN`.

## Bekannte Grenzen

- Der Mod nutzt nur legale Client-Interaktionen; er ist langsamer als
  Creative-/Paste-Werkzeuge und das ist Absicht.
- Entities und BlockEntity-Inhalte (Truheninhalte, Schildertexte, …) werden
  als **manuelle Aufgaben** gemeldet, nicht reproduziert.
- Dynamische Kolben-/Flut-Farmen, deren Verhalten von Update-Historie
  abhängt, können nicht garantiert werden.
- Flüssigkeiten werden nur bei vollständig abgedichteten Bereichen
  platziert; undichte Baupläne bleiben manuelle Arbeit.
- Ungeladene Chunks, veränderte Umgebung oder fremde Blöcke pausieren den
  Bau mit konkreten Koordinaten statt heimlich fortzufahren.
- **Anticheat-Verhalten:** Der Paketstrom wird im Test gegen
  Anticheat-typische Invarianten geprüft (max. ~45° Drehung/Tick,
  Reichweite ≤ Vanilla-Limit, Hit-Vektor im geklickten Block,
  Vanilla-Paketreihenfolge, Sprint nur bei Vorwärtslauf, keine
  Doppel-Use pro Tick, ≤3 Blöcke Positionsänderung/Tick). Das ist
  starke lokale Evidenz — ein Test auf einem Live-Server mit echtem
  Anticheat bleibt ein separater Schritt; auf Servern, die Bau-Bots
  ohnehin verbieten, ist der Mod nicht zulässig.
