# LiteBuild 0.1.0-beta.6 (Minecraft 26.2, Fabric)

## New

- **Status HUD**: native panel above the chat — blueprint, state, phase,
  progress, current target, pause/wait reasons, active supports.
  Four corners, scale 0.75–1.5, opacity, compact mode; hides on F1 and
  while a screen is open. Replaces the actionbar line.
- **Settings screen** (G → Settings): six tabs — Building, Timings, Look,
  Movement, Pauses, HUD — covering ~30 options. Draft-based: nothing is
  applied until "Save"; invalid values stay visible with inline errors;
  min/max pairs are validated together; "Reset to defaults" only resets
  the draft.
- **New configurable options**: `hotbarSwitchTicks`, `mainRefillTicks`,
  `checkpointStructureBlocks`, `checkpointSensitiveBlocks`, `hudEnabled`,
  `hudCorner`, `hudCompact`, `hudScale`, `hudOpacity`. Older config files
  get defaults automatically.

## Fixes

- Invalid scaffold-block text in settings no longer crashes the input
  responder — it now reports a format error instead.
- Config writes are atomic (temp file + move): a failed save keeps the
  previous `litebuild.json` intact.

## Verified

- All 8 client gametest classes in a single run: build, inventory refill,
  repair, gap-scaffold, row/sprint, fluids→activation→re-verify→4 injected
  faults, checkpoints, UI — `BUILD SUCCESSFUL`, packet audit
  `violations=0`.
- Unit suite green.

## Known boundaries (unchanged)

- Manual tasks (entities, block-entity contents) are reported, not placed.
- Legal client interactions only — slower than paste tools by design.
- **Server rules apply**: where build bots are forbidden, this mod is not
  allowed. Local packet-audit is evidence, not an anti-cheat guarantee.
