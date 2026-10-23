# LiteBuild: path to a stable 1.0

Status: 22 October 2026 (updated after beta.6). Prioritized list of
remaining release work; completed evidence is marked as such.

A 1.0 should reliably finish the supported build tasks, pause
understandably on unsupported tasks, and report the reached state
correctly. More features alone do not make the mod stable.

## Baseline (beta.6)

All eight original gametest classes passed in a single run
(16 min, software GL), plus four new classes in a second run (7 min).
Together twelve registered classes plus the unit suite. Evidence in
`validation/run75-beta6-gate/` and `validation/run77-new-tests/`:

- `LiteBuildAckTest`: delayed and suppressed server acknowledgements
  via a test packet gate; no completion without acknowledgement, bounded
  pause instead of an endless loop, clean completion after release.
- `LiteBuildImportTest`: real `.litematic` file round-trip, rotation
  and mirroring, multiple and negative subregions, vanilla structure
  `.nbt` via `convertStructureToLitematicaSchematic`, overlap conflict
  as an issue. Gap: `.schematic` only has a stub writer upstream;
  `.schem` (Sponge) was not yet file-tested at that point.
- `LiteBuildStallTest`: an unsolvable target ends bounded as MANUAL
  with a coordinate, terminal state PAUSED, other cells finish.
- `LiteBuildScaleTest`: 239-cell build with H-pause and resume to
  COMPLETE (~4600 ticks, ~270 s, +141 MB heap) plus `PlannerScaleTest`
  (7,232 targets, sweep < 2 s) as a unit test.
- `scripts/verify.sh`: documented single command for units, all
  registered gametests and the JAR, including its own test display and
  evidence archive.

The internal packet audit still passes with zero violations. That is not
an external anticheat test.

## Before 1.0

| Priority | Work | State | Proposed evidence |
| --- | --- | --- | --- |
| P0 | Server acknowledgement and timeout behavior | **Proven** (AckTest + RewriteAckTest) | Delayed, suppressed, contradictory (REWRITE: server reports a different state → no completion, bounded PAUSED with coordinate, foreign blocks never mined). Only the run against a real server remains open. |
| P0 | Bound retries and stalls | **Proven** (StallTest) | Unsolvable target → bounded MANUAL + PAUSED with coordinate. |
| P0 | Test standalone servers | **Infra done, run open** | `testserver/`: real 26.2 server + `litebuild-faults` mod (lag/loss/rollback/kick/chunkreload/chaos) + LagProxy. Missing: the actual client run. |
| P0 | Import and block matrix | **Proven** (ImportTest + SchemTest) | `.schem` (Sponge v2) via file incl. door pair, slab, stairs, waterlogging, repeater, chest block entity. Remaining limit: `.schematic` (upstream stub). |
| P0 | Reproducible release check | **Proven** (`scripts/verify.sh`) | One command runs units + all registered gametests + JAR and archives evidence. |
| P0 | Large and long build jobs | **Proven** (PlannerLargeScale 30,510 targets + BigBuild ~1,206 cells with 3× pause/resume) | The real limit remains multi-hour builds; planning and build behavior are checked upward. |
| P0 | Pin down supported scope and installation | **Open** | Test clean install/update of beta.7; document tested version ranges. The in-menu preflight already provides the manual-leftover view. |

### Concrete checkpoint: acknowledgements — mostly proven

`LiteBuildAckTest` suppresses and delays real clientbound updates via a
test mixin gate so the vanilla prediction is honestly rolled back.
Result: no verified completion without acknowledgement, bounded timeouts
with requeue or an understandable pause, correct completion after
release — including the repair mining path. Still open: a run against a
dedicated server.

### Concrete checkpoint: import — proven for the covered formats

`LiteBuildImportTest` loads a real `.litematic` file through the
production `LiteBuildCommands.load` dispatch, checks rotated and
mirrored placements including block states, multiple and negative
subregions and the `.nbt` structure path through Litematica's
conversion. Overlap conflicts are reported as CONFLICT issues.
`LiteBuildSchemTest` covers `.schem` (Sponge v2) via file. Not covered
by file: `.schematic` (the Schematica writer is an upstream stub).

### Concrete checkpoint: size and controls — proven

`PlannerScaleTest` proves bounded sweeps over ~7,200 targets and
time-constant counters/sorting. `LiteBuildScaleTest` builds ~240 cells
with an H emergency stop and resume. The HUD reads only one snapshot per
frame. Window sizes, keyboard controls, invalid settings and failed
saves are covered by `LiteBuildUiTest`.

New coverage: `PlannerLargeScaleTest` (unit, **passed**, 30,510 targets
with exact phase partition, dependent door top halves, double slabs and
waterlogged slabs; sweep 3.4 s, heap +8 MB, time-constant sorting) and
`LiteBuildBigBuildTest` (gametest, ~1,206 cells as a 20×15×4 podium
plus a mechanism row, three H pauses/resumes through the real hotkey
path, [GTBIG] metrics like [GTSCALE]; registered in the manifest,
**passed** in ~26 min).

Review finding from building the large fixture — **fixed**: cells whose
tasks span phases could block each other. `Planner.isEligible` required
for targets at phase MECHANISMS or later that every blueprint neighbor
with phase ≤ ATTACHMENTS is fully live-verified — `cellVerifiedLive`
required ALL tasks of the neighbor cell including its FLUIDS/ACTIVATION
tasks. Two adjacent waterlogged cells therefore waited on each other
mutually (deadlock). Fix: `earlyTasksVerifiedLive` now only requires the
neighbor cell's tasks up to ATTACHMENTS; regression tests
`adjacentWaterloggedCellsDoNotDeadlock` and
`mechanismBesideUnwaterloggedCellIsNotBlocked` in `PlannerTest`.
Correction to the second finding: `StateRules.phaseOf` classifies slabs
entirely as STRUCTURE under 26.2 `isSolid()` semantics (half slabs and
dry shells included) — there is no double-slab mis-sorting; the XL test
constants were corrected accordingly.

Third review finding — **fixed**: auto-verification could bypass phase
authorization. `BuildProgressStore.applyVerified` and the
`cellMatchesFinal` branch of the eligibility sweep marked every task of
a live-matching cell verified — including FLUIDS/ACTIVATION tasks while
those phases were disabled. A stored progress record revalidated against
a preexisting water cell therefore let the hard test's dry stage
complete fluid work that was never authorized. Fix: `Planner.phaseAllowed`
gates every auto-verification path (restore, cell match, pool sweep,
solve shortcut) — disabled phases stay pending until the user enables
them. Regression test `applyVerifiedRespectsPhaseAuthorization`;
proven by `LiteBuildHardTest` keeping `[WATER, LEVER]` pending.

Second review finding — **fixed**: `BuildController.stanceCandidates`
rejected every blueprint-reserved cell as a standing position, including
cells already built. The player could never stand on the structure, so
interior/higher cells of solid builds became unreachable and burned
their two solver attempts into MANUAL. Fixed: only the currently
targeted cell is excluded; geometric standability is checked by
`isStanceFree`. Proven by the BigBuild run (wall at ~262/1206 before,
full 1,206 cells after).

## Especially worthwhile for longer builds

1. ~~**Persist progress across restarts.**~~ ✓ Implemented:
   `BuildProgressStore` writes verified cells plus a real content
   fingerprint (SHA-256 over `x,y,z=block[prop=…]` lines, world ID =
   server IP or save directory, dimension, origin) atomically to
   `config/litebuild-progress/`. Resume only on an exact match and only
   after live revalidation of the world (`applyVerified`); deviating
   records are discarded. Unit tests: `BuildProgressStoreTest`; visible
   in the gametest as "Progress restored: N cells revalidated".
2. ~~**Preflight before the start.**~~ ✓ Implemented: `PreflightReport`
   aggregates missing materials, manual tasks (sign text, container
   contents, command blocks, spawners, entities, unsolvable states),
   risks (dynamic pistons, unknown fluids, unloaded chunks) and required
   toggles (fluids/activation). In the G menu as a "Preflight" action
   with its own page and a hint line in the status card. Unit tests:
   `PreflightReportTest` (8 tests).
3. **Understandable resumption.** On missing material, unreachable
   targets or a changed blueprint, concretely show what the player must
   change. "Build steps done", "fully verified" and "manual leftovers"
   should be clearly distinguishable.
4. **Configuration profiles and translations.** Simple profiles within
   the same safety bounds, complete German/English texts and an optional
   diagnostic report without credentials or private server information
   ease usage and bug reports.

Automatic crafting, chest supply, more freely selectable build patterns
and complex update orders can follow after a clearly scoped 1.0. A
reliable 1.0 does not have to reproduce every imaginable farm
automatically.

## Anticheat compatibility

After approval of the required downloads: test concrete products and
versions on an isolated private server and document the results. The
local packet audit remains an additional control. Neither this audit nor
a successful test on one product justifies a general compatibility or
undetectability guarantee.

## Standalone test server (testserver/)

The roadmap item "test standalone servers" is prepared: `testserver/`
contains a real local server (official Mojang `server.jar` 26.2, sha1
`823e2250…fcd6`; Fabric server launcher with loader 0.19.5; Fabric API
0.161.0+26.2 as a server mod). Offline mode, flat world,
`view-distance=5`, `pause-when-empty-seconds=0`. Boot test passed: the
server starts, the world generates, the instrumentation mod
`litebuild-faults` loads and `/fault` answers.

Fault injection in two layers:

- `/fault` commands of the own server mod (`testserver/instrumentation/`,
  javac-built, no Gradle): send delay and packet loss in both
  directions, targeted suppression/delay of block acks
  (`ClientboundBlockUpdatePacket` among others), TPS stall via tick
  sleep, snapshot/`rollback` for server-side reverted block changes,
  `chunkreload`, `kick`, combined `chaos` mode.
- `testserver/tools/LagProxy.java` (single Java file, runnable without a
  build): real network latency/jitter both ways, retransmit queues as
  loss simulation, hard disconnect via `kick`.

Vanilla additions: `/tick rate`, `/tick freeze`, `/kick`, teleport far
away for real chunk unloading. Start, procedure and expectations
(bounded retries, honest pause, no false completion) are in
`testserver/README.md`. The actual client run with LiteBuild against
this server remains open.

## Platform compatibility

The mod is pure Java/LWJGL code with no platform-specific paths; it runs
on Linux (all distributions), Windows and macOS wherever Fabric Loader
and the dependencies run. Forge/NeoForge are not supported: LiteBuild
depends on Litematica's schematic loading pipeline, and Litematica and
MaLiLib only exist for Fabric. Other Minecraft major versions require
matching Litematica/MaLiLib builds.

## Recommended order

~~HUD/settings~~ ✓ → ~~acknowledgement/timeout gap and stall bounds~~ ✓
(mostly) → own server with lag/rollback + `.schem` file matrix →
large long-running tests → release candidate with documented
support scope → 1.0.

Publication remains a separate decision after this evidence.
