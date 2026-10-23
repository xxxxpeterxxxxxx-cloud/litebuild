# LiteBuild

LiteBuild is a pure **client-side mod** for Minecraft **26.2** (Fabric) that
builds Litematica blueprints step by step using **ordinary player
interactions** — no direct block placement, no server commands, no teleport,
no "silent aim", no protocol-level cheats.

License: **GPL-3.0-or-later** (see `LICENSE`).

## What the mod does

- **Import** through Litematica's own file browser: `.litematic`, `.schem`
  (Sponge), `.schematic`, vanilla structure `.nbt` and the remaining
  Litematica formats are converted by Litematica itself.
- **Snapshot** of the selected placement (including reserved air cells)
  with size limits (max. 1M region cells, 100k non-air targets) and a
  stable fingerprint; if the placement is moved or changed, the build
  pauses.
- **Dependency phases**: structure → attachments → mechanisms →
  fluids → activation. Blocks that are already correct are skipped;
  waterlogged full blocks are first built dry, the water arrives as a
  separate task later. With `layerByLayer` (default on), the target pool
  prefers the lowest unfinished layer so the build proceeds bottom-up
  instead of hopping between heights.
- **Normal movement** (A* pathfinding over collision-checked standing
  positions), interpolated speed- and acceleration-limited view movement —
  the server-side rotation is always the real one (no silent aim).
- **Deliberate head movement** (since 0.1.0-beta.5, can be disabled): each
  new look point is executed as a single yaw/pitch rotation — it
  accelerates and decelerates within the configured limits, lands exactly
  on target and stays calm afterwards. Large turns start after a short
  reaction time (1–3 ticks), small ones do not. There is no artificial
  undershoot, no correction pauses and no random glances during pauses.
  Small target shifts only update the target without restarting the
  movement. While walking, the view stays on a fixed direction until the
  direction actually changes. On request, short build pauses (0.4–1.2 s)
  and rare safe AFK pauses (up to 5 s, only on stable ground at the end of
  a task).
- **Main-inventory refill** (since 0.1.0-beta.5): when the material is
  missing from the hotbar, it is fetched from slots 9–35 through a normal
  swap click — preferably into an empty or the currently selected hotbar
  slot. This only works without a foreign container screen and with an
  empty cursor; otherwise the build pauses with a reason instead of
  shifting anything. Armor and the offhand are never touched. After every
  switch LiteBuild waits briefly until the client shows the target item:
  200 ms for a hotbar switch, 400 ms for a main-inventory refill. That is
  a check of the local client state, not a server acknowledgement; the
  placement still verifies the item before every click.
- **Stance selection while building** (since 0.1.0-beta.5): straight rows
  are preferably built from a standing spot that covers as many open,
  same-type blocks in reach as possible. This coverage is only a
  heuristic; every block is re-checked against world and reach before
  placement. Target order keeps a pool of candidates and prefers the view
  direction plus the spot currently being worked on instead of jumping
  across the area. That is a movement style, not a guarantee of being
  "indistinguishable from human".
- **Speed without accuracy loss** (since 0.1.0-beta.4): all targets
  solvable from the current position are built before walking
  (station batch through the same solver, max. 8 probes per target pick);
  the target order follows the current working direction (sweep momentum)
  instead of hopping back and forth; on long straight path segments the
  player genuinely sprints (`sprintOnLongWalks`, default on); consecutive
  pipeline stages run in the same tick. Every placement still requires a
  solver raycast, live revalidation and server acknowledgement — the
  speedup comes from fewer walks, not weaker checks.
- **Placement** only via `useItemOn`/`useItem` with real line of sight,
  reach, face raycast and real `getStateForPlacement` prediction.
  Success counts only after **server acknowledgement** (block update
  packet) and several stable ticks — local client prediction does not
  count.
- **Temporary scaffolds** for unreachable paths (max. 8 per path segment),
  only into previously empty cells, removable via normal mining
  (`/lb cleanup`), with ownership tracking — foreign blocks are never
  touched. The cleanup is conservative: supports are skipped when they
  carry the player, touch the structure, are out of reach or have been
  changed since placement — remaining supports are reported, not silently
  deleted.
- **Fluids** only after the verified dry structure and only when the
  connected fluid area is live-sealed by full stable faces; lava is
  refused next to flammable material; water is not placed in dimensions
  with evaporation.
- **Final verification** checks all cells (including air) in two complete
  passes with a configurable settle time; `COMPLETE` is only reached with
  a fully verified state. Remaining owned scaffolds are shown in the
  status line. Manual leftovers (entities, block entity contents,
  non-reproducible states) are reported separately — the mod never claims
  a finished farm.

## Limits (honest)

- Entities and block entity contents (chests, hopper inventory, sign
  text) are not placed — they are reported as manual tasks.
- Update-sensitive or very dense mechanisms (redstone farms with
  nesting, piston circuits) can require intermediate steps that have to
  be reworked manually.
- Walkways made of slabs/stairs/partial blocks are treated
  conservatively; the navigator refrains from risky edges when in doubt.
- Tested in a real client world (Fabric client gametest): structure
  line, support block, repeater at delay 4, hopper, glass basin with a
  water source, fluid refusal on a missing wall without material, resume
  after repair, final verification.
  Second scenario: a real dirt column across a gap, a double slab from
  two single placements (items only in the main inventory, real hotbar
  switching) and an explicit `/lb cleanup` with server-confirmed removal
  of the support while the surroundings stay intact.
  Speed scenario: a 1×5 row is built from at most two standing
  positions (station batch) and real sprinting is observed on a >8-block
  path.

### Validation of the current source

170 unit tests and all fifteen client gametest classes pass on the same
source. The suite covers a `.schem` v2 file-path import (door pair,
stairs, slab, waterlogging, repeater, chest block entity), contradictory
server acknowledgements (rewrite mode: the build pauses with coordinates
instead of repairing foreign blocks, then completes after release), a
1,206-cell big build with three real pause/resume cycles, progress
persistence with live revalidation across restarts, and a planner sweep
over 30,510 targets. Its own packet audit reports zero violations and
additionally verifies that the real view direction was transmitted before
the placement click. An external anticheat test is still outstanding.

## Controls

- Key **G** (configurable): control screen
- Key **H** (configurable): pause / emergency stop
- Commands `/litebuild` (alias `/lb`):
  `select`, `load <file>`, `start`, `pause`, `stop`, `status`,
  `materials`, `issues`, `fluids`, `activate`, `cleanup`

The build pauses automatically on manual input (WASD/jump/sneak/
mine/use), open GUIs, focus loss, damage, low health, death,
riding/flying, world change and a changed placement.

Configuration in `config/litebuild.json`, including:

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
  "minPlaceIntervalTicks": 3,
  "layerByLayer": true,
  "stallAuditTicks": 400
}
```

Short pauses are taken after `…Actions` confirmed actions for `…Ticks`
ticks; AFK pauses after `afkInterval…Ticks` active build ticks for
`afkDuration…Ticks` (at most 100 ticks = 5 s). Both switches also exist in
the control screen (G). `sprintOnLongWalks` lets the player sprint on
long, clear path segments (normal sprint input, no speed manipulation).
`minPlaceIntervalTicks` (minimum spacing of two placements, clamp ≥ 2)
defaults to **3** since beta.4 instead of 6 — existing
`litebuild.json` files keep their stored value. `layerByLayer` orders
the target pool bottom-up per layer (default on). `stallAuditTicks`
(clamp 60–2400) controls how long the build may go without verified
progress before an audit sweep runs — raise it on very laggy servers.

### HUD and settings (since 0.1.0-beta.6)

A native status display above the chat shows blueprint, state, area,
build-step progress, target, pause and wait reasons plus active supports —
read-only per frame, without world access. Position (four corners),
scaling, opacity and compact mode are configurable; the display hides
automatically when a menu is open and in F1 HUD mode. The previous
actionbar line is gone.

**Settings (G → Settings):** a draft is only applied with "Save" — an
atomic write to `litebuild.json`, no live changes without a click.
Invalid entries stay visible and are flagged with a reason; min/max pairs
are validated together. Fields include `hotbarSwitchTicks`,
`mainRefillTicks`, `checkpointStructureBlocks`,
`checkpointSensitiveBlocks`, `stallAuditTicks`, `layerByLayer`,
`hudEnabled`, `hudCorner`, `hudCompact`, `hudScale`, `hudOpacity`. Older
`litebuild.json` files without these fields get the defaults.

### Final check and repair

After the build, LiteBuild checks every blueprint cell (including
required AIR cells) in two complete passes. Deviations are reported with
types (`/lb status`; "Verify + Fix" or `/lb verify` also starts the check
manually). If `automaticRepairs` is active (default, limit
`maxRepairRounds`, 1–5), only provably safe, owned mistakes are corrected
automatically: re-placing missing blocks, adjusting delay/switch states
via right click and mining *owned* misplaced or support blocks — never
foreign or changed blocks, block entities, fluids, mechanisms or cells
with load/redstone nearby. Everything else is reported as a manual item
with a reason; the build only counts as `COMPLETE` once two clean passes
have succeeded.

- **Intermediate checks** (since 0.1.0-beta.4): after a configurable
  number of finished blocks (`checkpointStructureBlocks`, sooner for
  mechanism or fluid blocks via `checkpointSensitiveBlocks`), the already
  completed build state of those cells is checked, not the full target
  state. A dry state (e.g. a block that will carry water before the fluid
  phase) counts as done; connections (stairs, wire shape) that wait on
  neighbors only count in the final check. Deviations are corrected via
  the same safe repair. A phase change or the end of the blueprint also
  closes small remainders. The full final check with two passes stays
  unchanged; intermediate checks do not swing the camera.

## Installation

1. Fabric Loader **0.19.5+** for Minecraft **26.2**
2. Mods: `fabric-api-0.161.0+26.2`, `litematica-fabric-26.2-0.28.8`,
   `malilib-fabric-26.2-0.29.6`, `litebuild-0.1.0-beta.7.jar`
3. Java **25** is required.

Compatibility: `fabric.mod.json` allows `~26.2` (all 26.2.x patches),
Fabric API any current version, Litematica ≥ 0.28.8, MaLiLib ≥ 0.29.6.
The mod is pure Java/LWJGL code and runs on Linux, Windows and macOS
wherever Fabric and the dependencies run — there is no platform-specific
code. Other Minecraft major versions each need matching
Litematica/MaLiLib builds and are not covered. Forge/NeoForge are not
supported: LiteBuild relies on Litematica's loading pipeline, and
Litematica/MaLiLib only exist for Fabric.

## Building from source

```sh
./gradlew build
```

Produces `build/libs/litebuild-0.1.0-beta.7.jar` and
`litebuild-0.1.0-beta.7-sources.jar`. JDK 25 is required via the
Java toolchain (e.g. `JAVA_HOME` or Gradle auto-detection;
no project-specific paths in `gradle.properties`).

## Verification / release gate

A reproducible full run is documented as a single command:

```sh
./scripts/verify.sh
```

The script checks the environment (Java/Javac, display), sets
`LIBGL_ALWAYS_SOFTWARE=1` as the default for software rendering
(overridable) and respects an existing `DISPLAY`. Without an X display —
or with `--isolated` — it starts its own Xwayland on a free private
display (searching from `:99`; `:0` and `:2` are never touched, only the
self-started Xwayland is stopped again). It runs
`./gradlew --offline clean test`, `./gradlew --offline runClientGameTest`
and `./gradlew --offline build` in sequence. All registered client
gametests are driven by the manifest
`src/gametest/resources/fabric.mod.json` (entrypoint
`fabric-client-gametest`) — the script does not enumerate tests.

All evidence lands in `validation/release-gate-<timestamp>/`:
console logs per step, unit test XMLs, the gametest screenshots,
copies of the used `fabric.mod.json`, `git status`/
`git diff --check` and SHA-256 checksums of the produced JARs. The run
ends with an unambiguous `RELEASE-GATE: PASS` or `FAIL` line; on step
failures, timeout (default 2400 s per step, `--timeout`) or a missing
`BUILD SUCCESSFUL` in the gametest log the exit code is non-zero.

Flags: `--skip-gametest` (unit tests + build only), `--isolated`,
`--display N`, `--timeout SECONDS`.

## Known limits

- The mod only uses legal client interactions; it is slower than
  creative/paste tools and that is intentional.
- Entities and block entity contents (chest contents, sign text, …) are
  reported as **manual tasks**, not reproduced.
- Dynamic piston/flood farms whose behavior depends on update history
  cannot be guaranteed.
- Fluids are only placed in fully sealed areas; leaky blueprints remain
  manual work.
- Unloaded chunks, a changed environment or foreign blocks pause the
  build with concrete coordinates instead of silently continuing.
- **Anticheat behavior:** the packet stream is checked in tests against
  anticheat-typical invariants (max. ~45° rotation/tick, reach ≤ vanilla
  limit, hit vector inside the clicked block, vanilla packet order,
  sprint only while moving forward, no double-use per tick, ≤3 blocks
  position change/tick). That is strong local evidence — a test on a live
  server with a real anticheat remains a separate step; on servers that
  forbid build bots anyway, the mod is not permitted.
