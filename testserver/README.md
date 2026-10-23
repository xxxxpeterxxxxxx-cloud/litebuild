# GentleBuild standalone test server (Minecraft 26.2)

Local dedicated-server harness for the release-roadmap item *"Eigenständige
Server testen"*: GentleBuild is exercised against a **real** server with
injected faults (latency, packet loss, suppressed block acks, TPS stalls,
rollbacks, disconnects, chunk reloads) instead of a test-mixin gate.

Everything in this directory is gitignored and safe to delete/rebuild.

## Contents

| Path | What it is |
| --- | --- |
| `server.jar` | Official Mojang 26.2 server (sha1 `823e2250d24b3ddac457a60c92a6a941943fcd6a`, from `version_manifest_v2.json` -> `26.2.json` -> `downloads.server.url`) |
| `fabric-server-mc.26.2-loader.0.19.5-launcher.1.1.2.jar` | Official Fabric server launcher (meta.fabricmc.net), loader 0.19.5 = the version pinned in `gradle.properties` |
| `mods/fabric-api-0.161.0+26.2.jar` | Fabric API, same version as `gradle.properties` (server-side dep of the instrumentation mod) |
| `mods/gentlebuild-faults.jar` | The fault-injection mod, built by `instrumentation/build.sh` |
| `instrumentation/` | javac-built mini mod (no Gradle): `/fault` commands + a `Connection` mixin that drops/delays packets |
| `tools/LagProxy.java` | Single-file TCP proxy for true network latency/jitter/loss + hard disconnects |
| `eula.txt` | EULA accepted for this local harness |
| `server.properties` | Offline mode, flat world, `view-distance=5`, `simulation-distance=4`, peaceful, creative, no spawn protection, `pause-when-empty-seconds=0` |
| `start.sh` | `java -jar fabric-server-...launcher.jar nogui` (uses `$JAVA_HOME/bin/java`) |
| `world/`, `logs/`, `libraries/`, `versions/`, `.fabric/` | Created on first boot |

Prerequisite: **Java 25** (e.g. `JAVA_HOME=/var/home/julius/.jdks-local/jdk-25.0.4.1+1`).

## Start the server

```bash
cd testserver
JAVA_HOME=/var/home/julius/.jdks-local/jdk-25.0.4.1+1 ./start.sh
```

First start unpacks `versions/` + `libraries/`, generates the flat world and
prints `Done`. Stop with `stop` in the console.

## Join with a client

- Multiplayer -> Direct Connection -> `localhost:25565` (or add server).
- `online-mode=false`, so any 26.2 client with GentleBuild + Litematica
  works; the account name does not matter.
- Players join in creative mode (flat world, no mobs). `/fault` is usable
  by everyone (and from the server console) — this is a dedicated test
  harness, not a public server.
- Optional: run the LagProxy and connect to `localhost:25566` instead
  (see below).

## Fault triggers

Two layers:

1. **`/fault ...`** (in-game or server console) — protocol-level faults
   injected by `gentlebuild-faults.jar` via a mixin on
   `net.minecraft.network.Connection` plus a per-tick scheduler.
2. **`tools/LagProxy.java`** — wire-level faults a server-side mod cannot
   reproduce faithfully (propagation delay, retransmit stalls).

### `/fault` commands (gentlebuild-faults mod)

| Command | Effect | Simulates |
| --- | --- | --- |
| `/fault` / `/fault status` | Show active faults + packet counters (ok / delayed / dropped / in-dropped) | — |
| `/fault delay <ms>` | Hold every clientbound packet `<ms>` in a send queue | Server-side send latency (affects acks too) |
| `/fault ackdelay <ms>` | Delay only ack packets (`ClientboundBlockUpdatePacket`, `ClientboundSectionBlocksUpdatePacket`, `ClientboundContainerSetSlotPacket`) | Late confirmations |
| `/fault ackdrop <pct>` | Drop `<pct>`% of those ack packets | Suppressed/lost confirmations — the GentleBuildAckTest scenario against a real server |
| `/fault drop <pct>` | Drop `<pct>`% of **all** clientbound play packets | Outbound packet loss |
| `/fault dropin <pct>` | Drop `<pct>`% of **all** serverbound play packets | Client->server loss: block-use packets never arrive, no ack is ever sent |
| `/fault stall <ms> [ticks]` | `Thread.sleep(ms)` inside every server tick for `[ticks]` (default 100) | Overloaded server / TPS drop while the game loop still "runs" |
| `/fault snapshot [radius]` | Record all block states in a +/-`radius` cube around every player (default 24, max 128) | Baseline for rollback |
| `/fault rollback` | Restore every recorded position whose live state changed; block updates go to clients | Server-side reverted block changes (blocks placed by the mod vanish, broken blocks reappear) |
| `/fault chunkreload` | `ClientboundForgetLevelChunkPacket` + full `ClientboundLevelChunkWithLightPacket` resend for every chunk in each player's view distance | Chunk unload/reload on the client |
| `/fault kick` | `disconnect()` every connected player | Hard disconnect |
| `/fault chaos [on\|off]` | Every ~10-30 s fires a random burst: 3 s total ack suppression, TPS stall burst, rollback (if a snapshot exists), or 2 s 25% outbound loss | Long-build soak test against mixed faults |
| `/fault clear` | Reset every fault and counter | — |

Vanilla commands that complement the harness (also in-game/console):

- `/tick rate <tps>` / `/tick rate 20` — run the server at reduced tick
  rate (official TPS-drop knob), `/tick freeze` + `/tick unfreeze` — a
  total server stall that still keeps the connection alive; acks time
  out but the socket stays up.
- `/kick <player>` — single-player disconnect.
- `/tp <player> <far coords>` and back — real chunk unload/reload.
- `stop` mid-build — server vanishes; tests reconnect/resume behaviour.

### LagProxy (wire-level faults)

```bash
cd testserver/tools
java LagProxy.java --delay 150 --jitter 50 --loss 5
# then connect the client to localhost:25566
```

Live stdin commands while it runs: `delay <ms>`, `c2sdelay <ms>`,
`s2cdelay <ms>`, `jitter <ms>`, `loss <pct>`, `kick` (close all
connections = sudden network loss), `status`, `quit`.

Note: `loss` on TCP cannot drop bytes (it would corrupt the stream), so
the proxy simulates it as retransmit stalls (random extra 300-900 ms
holds) — the observable effect is identical: delayed acks.

## Suggested test sequence

1. `./start.sh`, join `localhost:25565`.
2. `/fault snapshot` before letting GentleBuild build; start a small
   Litematica build.
3. Baseline: no faults — build must COMPLETE normally.
4. `/fault ackdelay 400` — expect slower but still-completing build;
   retries bounded.
5. `/fault ackdrop 50` — expect bounded retries, requeues or honest
   MANUAL/PAUSED, **never** COMPLETE without verified placements.
6. `/fault snapshot` -> build -> `/fault rollback` — placed blocks are
   reverted server-side; GentleBuild must re-place or mark MANUAL, not
   trust stale predictions.
7. `/fault stall 150 200` or `/tick rate 5` — degraded TPS; pacing must
   degrade gracefully, no placement spam.
8. `/fault dropin 30` — client->server loss; block-use packets vanish,
   so acks never come — same honest-timeout expectation as (5).
9. `/fault kick` mid-build, rejoin — state must resync; no false
   completion, resume only after live re-verification.
10. `/fault chunkreload` mid-build — pending cells re-verified after the
    resend; no phantom progress.
11. `/fault chaos on` for a long build — combined soak test.
12. Repeat key scenarios through LagProxy (`--delay 300 --jitter 150`)
    for true network latency, plus `kick` on the proxy for connection
    loss that is not a clean server-side disconnect.

## Expected GentleBuild behaviour (pass criteria)

- **Bounded retries**: a missing or contradictory ack triggers a finite
  number of retries/requeues, then the cell escalates to MANUAL.
- **Honest pause**: an unsolvable target ends PAUSED with a coordinate and
  reason — never an infinite retry loop.
- **No false completion**: COMPLETE only after server-confirmed
  verification of all cells; rollbacks and stale predictions must not be
  counted as done.
- **Disconnect/chunk reload**: state is re-verified against the live
  world on rejoin/resend; the job resumes correctly or pauses honestly.
- **TPS drops**: throughput degrades, correctness does not.

## Rebuilding the instrumentation mod

```bash
cd instrumentation
JAVA_HOME=/var/home/julius/.jdks-local/jdk-25.0.4.1+1 ./build.sh
# compiles src/ with javac against lib/server-26.2.jar (Mojang-shipped
# names, no mappings needed) + fabric-loader + fabric-api modules +
# sponge-mixin, and copies the jar to ../mods/
```

`instrumentation/lib/` holds compile-only jars extracted from the
official `server.jar` (inner `server-26.2.jar` + bundled libraries), the
gradle cache copies of `fabric-loader-0.19.5` / `sponge-mixin`, and the
Fabric API module jars needed for `CommandRegistrationCallback` /
`ServerTickEvents`.

## Known limits / what stays manual

- The actual client join + GentleBuild build runs are **manual** — this
  harness only provides the server side and the fault controls.
- `/fault rollback` restores block states, not block entities or
  entities; snapshots are per-dimension cubes around players at snapshot
  time.
- `/fault dropin` dropping 100% also eats keep-alives — the client will
  time out; that is realistic but use < ~60% for "lossy but alive" tests.
- Faults apply only during the play phase (login/handshake is left
  alone), so joining always works.
