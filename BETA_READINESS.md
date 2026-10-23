# GentleBuild: Weg zur stabilen 1.0

Stand: 22. Oktober 2026 (aktualisiert nach beta.6). Priorisierte Liste der
verbleibenden Freigabearbeiten; erledigte Nachweise sind als solche markiert.

Eine 1.0 sollte die unterstützten Bauaufgaben verlässlich abschließen,
bei nicht unterstützten Aufgaben verständlich pausieren und den erreichten
Zustand korrekt melden. Mehr Funktionen allein machen die Mod nicht stabil.

## Ausgangslage (beta.6)

Alle acht ursprünglichen Spieltestklassen bestanden in einem einzigen Lauf
(16 min, Software-GL), dazu vier neue Klassen in einem zweiten Lauf (7 min).
Zusammen zwölf registrierte Klassen plus die Unit-Suite. Belege in
`validation/run75-beta6-gate/` und `validation/run77-new-tests/`:

- `GentleBuildAckTest`: verzögerte und unterdrückte Server-Bestätigungen
  über ein Test-Paket-Gate; kein Abschluss ohne Bestätigung, begrenzte
  Pause statt Endlosschleife, sauberer Abschluss nach Freigabe.
- `GentleBuildImportTest`: echter `.litematic`-Datei-Roundtrip, Rotation
  und Spiegelung, Mehrfach- und negative Subregionen, Vanilla-Struktur-
  `.nbt` über `convertStructureToLitematicaSchematic`, Überlappungs-
  Konflikt als Issue. Lücke: `.schematic` hat upstream nur einen
  Stub-Writer; `.schem` (Sponge) ist noch nicht per Datei getestet.
- `GentleBuildStallTest`: unlösbares Ziel endet begrenzt als MANUAL mit
  Koordinate, Terminalzustand PAUSED, andere Zellen werden fertig.
- `GentleBuildScaleTest`: 239-Zellen-Bau mit H-Pause und Resume bis
  COMPLETE (~4600 Ticks, ~270 s, +141 MB Heap) plus `PlannerScaleTest`
  (7232 Ziele, Sweep < 2 s) als Unit-Test.
- `scripts/verify.sh`: dokumentierter Einzelbefehl für Units, alle
  registrierten Spieltests und das JAR, inklusive eigener Testanzeige
  und Evidenzarchiv.

Der eigene Paket-Audit besteht weiterhin mit null Verstößen. Das ist kein
externer Anticheat-Test.

## Vor der 1.0

| Priorität | Arbeit | Stand | Vorgeschlagener Nachweis |
| --- | --- | --- | --- |
| P0 | Serverbestätigung und Timeout-Verhalten | **Teilweise belegt** (AckTest) | Ergänzend: widersprüchliche Bestätigungen und eigener Server mit Lag/Rollback. |
| P0 | Wiederholungen und Stillstände begrenzen | **Belegt** (StallTest) | Unlösbares Ziel → begrenzt MANUAL + PAUSED mit Koordinate. |
| P0 | Eigenständige Server testen | **Offen** | Bau-/Reparaturtests gegen einen lokalen Server mit Verzögerung, TPS-Einbrüchen, Rollbacks, Verbindungsabbruch und Chunk-Neuladen. Braucht Download-Freigabe. |
| P0 | Import- und Blockmatrix | **Teilweise belegt** (ImportTest) | Fehlt: `.schem`-Datei und `.schematic`-Format (upstream-Stub), sowie Blockmatrix mit Türen/Stufen/Waterlogging per Datei. |
| P0 | Reproduzierbare Freigabeprüfung | **Belegt** (`scripts/verify.sh`) | Ein Befehl fährt Units + alle registrierten GameTests + JAR und archiviert Evidenz. |
| P0 | Große und lange Bauaufträge | **Teilweise belegt** (ScaleTest, ~240 Zellen) | Ergänzend: deutlich größere Pläne (5–20k Zellen) und längere Pause-/Resume-Läufe. |
| P0 | Unterstützten Umfang und Installation festlegen | **Offen** | Saubere Installation/Update von beta.6 testen; getestete Versionsbereiche dokumentieren; manuelle Restarbeiten vor Start sichtbar machen (Vorprüfung unten). |

### Konkreter Prüfpunkt: Bestätigungen — größtenteils belegt

`GentleBuildAckTest` unterdrückt und verzögert echte Clientbound-Updates
über ein Test-Mixin-Gate, sodass die Vanilla-Prognose ehrlich
zurückgesetzt wird. Ergebnis: kein verifizierter Abschluss ohne
Bestätigung, begrenzte Timeouts mit Requeue oder verständlicher Pause,
korrekter Abschluss nach Freigabe — auch für den Reparatur-Abbau-Pfad.
Offen bleiben widersprüchliche Bestätigungen (Server meldet anderen
Zustand als erwartet) und Verhalten gegen einen eigenen Server.

### Konkreter Prüfpunkt: Import — teilweise belegt

`GentleBuildImportTest` lädt eine echte `.litematic`-Datei über den
produktiven `GentleBuildCommands.load`-Dispatch, prüft gedrehte und
gespiegelte Placements inklusive Blockzuständen, Mehrfach- und negative
Subregionen sowie den `.nbt`-Strukturpfad über Litematicas Konvertierung.
Überlappungskonflikte werden als CONFLICT-Issue gemeldet. Noch nicht per
Datei abgedeckt: `.schem` (Sponge, Lesepfad vorhanden) und `.schematic`
(Schematica-Writer ist upstream ein Stub).

### Konkreter Prüfpunkt: Größe und Bedienung — teilweise belegt

`PlannerScaleTest` belegt begrenzte Sweeps über ~7.200 Ziele und
zeitkonstante Zähler/Sortierung. `GentleBuildScaleTest` baut ~240 Zellen
mit H-Notstopp und Resume. Die HUD liest pro Bild nur einen Snapshot.
Fenstergrößen, Tastaturbedienung, ungültige Einstellungen und
fehlgeschlagene Speicherung deckt `GentleBuildUiTest` ab. Offen: Bauaufträge
im fünfstelligen Zellbereich und sehr lange Läufe.

## Für längere Bauvorhaben besonders sinnvoll

1. **Fortschritt über Neustarts speichern.** Welt/Server, Dimension,
   Bauplaninhalt und Placement müssen identifizierbar sein. Eigene Blöcke
   dürfen nach dem Laden nur mit passendem Live-Zustand wieder als eigene
   gelten. Anschließend neu prüfen; nicht blind an einer alten Aktion
   fortfahren. Der bisherige Placement-Fingerprint verwendet auch die
   Objektidentität der Schematic und ist dafür nicht ausreichend.
2. **Vorprüfung vor dem Start.** Fehlende Materialien, manuelle NBT-/Entity-
   Aufgaben, nicht unterstützte Zustände und schwierige Zugänge gesammelt
   anzeigen. Eine Zusammenfassung im Menü ist hilfreicher als erst eine
   Pause mitten im Bau.
3. **Verständliche Wiederaufnahme.** Bei Materialmangel, unerreichbarem Ziel
   oder verändertem Bauplan konkret anzeigen, was der Spieler ändern muss.
   „Bauschritte erledigt“, „vollständig geprüft“ und „manuelle Restarbeiten“
   sollen klar unterscheidbar sein.
4. **Konfigurationsprofile und Übersetzungen.** Einfache Profile innerhalb
   derselben Sicherheitsgrenzen, vollständige deutsche/englische Texte
   und ein optionaler Diagnosebericht ohne Zugangsdaten oder private
   Serverinformationen erleichtern die Nutzung und Fehlermeldung.

Automatisches Crafting, Kistenversorgung, weitere frei wählbare Baumuster
und komplexe Update-Reihenfolgen können nach einer klar abgegrenzten 1.0
folgen. Eine verlässliche 1.0 muss nicht jede denkbare Farm automatisch
reproduzieren.

## Anticheat-Kompatibilität

Nach Freigabe der erforderlichen Downloads: konkrete Produkte und Versionen
auf einem isolierten eigenen Server testen und Ergebnisse dokumentieren.
Der lokale Paket-Audit bleibt eine zusätzliche Kontrolle. Weder dieser Audit
noch ein erfolgreicher Test bei einem Produkt rechtfertigt eine allgemeine
Kompatibilitäts- oder Unauffälligkeitsgarantie.

## Empfohlene Reihenfolge

~~HUD/Einstellungen~~ ✓ → ~~Bestätigungs-/Timeout-Lücke und Stillstandsgrenzen~~ ✓
(teils) → eigener Server mit Lag/Rollback + `.schem`-Dateimatrix →
große Langzeittests → Release Candidate mit dokumentiertem
Unterstützungsumfang → 1.0.

Die Veröffentlichung bleibt eine eigene Entscheidung nach diesen Nachweisen.
