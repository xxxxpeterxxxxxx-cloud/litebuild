# GentleBuild

**GentleBuild builds your Litematica schematics the slow, honest way — with the same mouse, keyboard and packets a human player would use.**

No world edits. No silent aim. No teleporting. No packet tricks. GentleBuild walks to each block, turns its head, picks the right item, and clicks — then waits for the server to confirm. It's a builder, not a cheat engine, and it's designed so its wire traffic stays inside vanilla rules.

## What it does

- **Imports everything Litematica can read** — `.litematic`, `.schem` (Sponge), `.schematic`, vanilla structure `.nbt`. Loading and conversion run through Litematica's own pipeline; GentleBuild never reimplements a format.
- **Builds in dependency-aware phases**: structure → attachments → mechanisms → fluids → activation. Water-logged solids go in dry first; water is poured as a separate task once containment is verified.
- **Moves like a player**: A* pathfinding over collision-checked stances, smooth capped rotations, real sprint on long legs, one coherent saccade per look target then a still head — no radar scanning, no jitter formulas.
- **Works in smart batches**: straight rows and reachable patches get placed from a single good stance (up to 8 confirmed placements before relocating) instead of the walk-one-place-one crawl.
- **Uses your real inventory**: hotbar switching plus refills from the main inventory via normal swap clicks — with short human-level settle delays and no touchy-feely with your armor or offhand.
- **Scaffolds honestly**: up to 8 temporary support blocks per path segment when a cell can't be reached, removed afterwards with real mining — and never touches foreign or changed blocks.
- **Respects fluids**: water is only poured into fully enclosed, live-verified regions; lava is refused next to flammable material; evaporating dimensions are respected.
- **Verifies its own work**: incremental checkpoints every 5–10 blocks (phase-dependent), then a full two-pass final audit including required-air cells. Nothing is "done" until the server says so twice.
- **Repairs safely**: safe, owned mistakes get fixed automatically (bounded rounds) — everything else becomes a clear manual task with coordinates and a reason.

## Controls

- **G** — control panel (blueprint, start/pause/stop, verify+fix, cleanup, fluids/activation gates, settings)
- **H** — pause / emergency stop
- `/gb` (`/gentlebuild`) — `select`, `load <file>`, `start`, `pause`, `stop`, `status`, `materials`, `issues`, `fluids`, `activate`, `cleanup`

The build also pauses automatically on manual input, opened GUIs, focus loss, damage, low health, riding/flying, world changes or a moved placement.

## HUD & settings

A native status panel (four corners, scale, opacity, compact mode — all configurable) shows blueprint, state, phase, progress, current target, pause reasons and active supports. The settings screen (G → Settings) covers six tabs: building, timings, look, movement, pauses and HUD — validated, saved atomically, sane defaults for everything.

> **Note:** the mod's in-game texts are currently German (the original target audience). English localization is on the 1.0 roadmap.

## Installation

1. **Fabric Loader 0.19.5+** for Minecraft **26.2**
2. Required mods: **Fabric API**, **Litematica ≥ 0.28.8**, **MaLiLib ≥ 0.29.6**
3. Drop `gentlebuild-*.jar` into `mods/`
4. **Java 25** required (same as the game itself)

## Honest limitations

- Entities and block-entity contents (chest inventories, sign text, …) are reported as **manual tasks**, not reproduced.
- Dense, update-order-sensitive mechanisms (nested redstone, piston timing tricks) may need manual intermediate steps.
- The mod only uses legal client interactions — it is intentionally slower than creative paste tools.
- A local packet-audit in the test suite enforces vanilla invariants (rotation ≤ 45°/tick, vanilla reach, correct packet order, no double-use, no impossible motion). That is strong evidence, not a guarantee: **if a server forbids build bots, this mod is not allowed there either.** External anti-cheat testing is tracked openly in the repository.

## Support & source

- Source, issues and validation evidence: **<TODO: repository URL>**
- License: **GPL-3.0-or-later**
