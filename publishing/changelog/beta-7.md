# LiteBuild 0.1.0-beta.7

## Highlights

- **All-English interface** — HUD, settings, status lines, commands and
  reports are fully in English (German strings removed).
- **Layer-by-layer building** — new `layerByLayer` option (default on)
  builds bottom-up instead of hopping between heights.
- **Progress persistence** — verified cells persist across restarts in
  `config/litebuild-progress/` with a content fingerprint; resume only
  after live revalidation against the world.
- **Preflight report** — G → Preflight lists missing materials, manual
  tasks (sign text, container contents, entities), risks and required
  toggles before the build starts.
- **Configurable stall audit** — `stallAuditTicks` (60–2400) in
  Settings → Timings, for laggy servers.

## Fixes

- Fixed a planner deadlock where adjacent waterlogged cells waited on
  each other mutually.
- Fixed stance selection so built blueprint cells are valid footing —
  large solid builds no longer stall mid-structure.

## Validation

- 170 unit tests green.
- 15 client gametests including `.schem` file import, contradictory
  server acknowledgements and a 1,206-cell build with three
  pause/resume cycles.
- Internal packet audit: zero violations.

See BETA_READINESS.md for the full evidence list. Still beta — an
external anticheat test is outstanding and no server-side guarantee is
claimed.
