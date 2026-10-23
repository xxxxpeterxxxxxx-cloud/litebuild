package dev.gentlebuild.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import dev.gentlebuild.blueprint.BlueprintImporter;
import dev.gentlebuild.config.BuildConfig;
import dev.gentlebuild.mixin.BlockItemAccessor;
import dev.gentlebuild.model.ActionKind;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.BuildIssue;
import dev.gentlebuild.model.BuildTarget;
import dev.gentlebuild.model.Phase;
import dev.gentlebuild.model.PlacementAction;
import dev.gentlebuild.model.RunState;
import dev.gentlebuild.motion.AngleMath;
import dev.gentlebuild.motion.InputBroker;
import dev.gentlebuild.motion.MotionController;
import dev.gentlebuild.nav.LevelNavWorld;
import dev.gentlebuild.nav.NavWorld;
import dev.gentlebuild.nav.Navigator;
import dev.gentlebuild.nav.PathResult;
import dev.gentlebuild.plan.BuildCheckpoint;
import dev.gentlebuild.plan.FinalVerifier;
import dev.gentlebuild.plan.FluidContainment;
import dev.gentlebuild.plan.ItemResolver;
import dev.gentlebuild.plan.PlacementSolver;
import dev.gentlebuild.plan.Planner;
import dev.gentlebuild.state.StateRules;

/**
 * Per-session orchestration: snapshot -> plan -> navigate -> aim -> act ->
 * confirm -> verify. Strict per-tick stage flow — walking never falls
 * through into aiming/placing, and an in-flight transaction owns the tick.
 * All interactions are ordinary gameMode calls; all movement is vanilla
 * input routed through KeyboardInput.
 */
public class BuildController {
	private static final BuildController INSTANCE = new BuildController();
	private static final double WAYPOINT_DIST_SQ = 0.18 * 0.18;
	private static final double HEADING_TOLERANCE_DEG = 25.0;
	private static final int STALL_TICKS = 80;
	private static final double EYE_HEIGHT = 1.62;
	private static final double REACH_MARGIN = 0.15;
	private static final int MAX_REVALIDATE_FAILURES = 2;
	/** Scaffold eye-resolves get more tries: every failure excludes the
	 *  stance, so each retry genuinely stands somewhere else. */
	private static final int MAX_SCAFFOLD_RESOLVE_FAILURES = 4;
	private static final int STANCE_PROBE_LIMIT = 8;
	/** Ticks to wait for a late inventory sync before declaring a material missing. */
	private static final int MATERIAL_SYNC_TICKS = 30;
	private static final int MAX_UNSAFE_WAYPOINT_RETRIES = 3;
	private int unsafeWaypointRetries;
	private static final String INVENTORY_BLOCKED_REASON =
			"Inventarwechsel blockiert (Container/Cursor/Slotwechsel)";
	private static final int LAVA_SETTLE_TICKS = 100;

	public static BuildController get() {
		return INSTANCE;
	}

	/** Suspended build work while a waypoint scaffold is being placed. */
	private record WorkPlan(BuildTarget target, PlacementAction action,
							BlockPos stance, PathResult path, int pathIndex) {}

	private BuildConfig config = BuildConfig.load();
	private final MotionController motion = new MotionController();
	private final PlacementSolver solver = new PlacementSolver();
	private final SupportManager supports = new SupportManager();

	private RunState state = RunState.EMPTY;
	private Blueprint blueprint;
	private Planner planner;
	private BlueprintImporter.SnapshotTask snapshot;
	private String fingerprint;
	private Object placementIdentity;
	private net.minecraft.resources.Identifier dimension;
	private boolean startAfterSnapshot;

	private BuildTarget current;
	private PathResult path;
	private int pathIndex;
	private List<BlockPos> stanceFallback = List.of();
	private int stanceIndex;
	private Set<BlockPos> reservedCells = Set.of();
	private PlacementAction pendingAction;
	private BlockHitResult validatedHit;
	private String revalidateReason = "";
	private final java.util.Set<String> triedFaces = new java.util.HashSet<>();
	private BlockPos triedFacesFor;
	private BlockPos pendingStance;
	private BuildTarget pendingScaffoldTarget;
	private WorkPlan suspendedWork;
	private Transaction transaction;
	private Transaction cleanupTx;
	private int intervalCooldown;
	private int aimSettledTicks;
	private int sneakTicks;
	private int stallTicks;
	private int revalidateFailures;
	private boolean eyeResolved;
	private Vec3 lastProgressPos;
	private float lastHealth = -1;
	private boolean cleanupMode;
	private final Deque<BlockPos> cleanupQueue = new ArrayDeque<>();
	private int fluidSettle;
	private int materialWait;
	private final Map<BlockPos, Integer> breakSightFailures = new HashMap<>();
	private dev.gentlebuild.plan.FinalVerifier verifier;
	private final List<String> statusLines = new ArrayList<>();
	private final LinkedHashSet<BuildTarget> targetPool = new LinkedHashSet<>();
	private BlockPos lastWorkPos;
	private BlockPos prevWorkPos;
	private final dev.gentlebuild.motion.BuildRhythm rhythm =
			new dev.gentlebuild.motion.BuildRhythm();
	private final java.util.random.RandomGenerator glanceRandom =
			java.util.random.RandomGenerator.getDefault();
	private int aimSettleNeeded = 2;
	/** Stances whose eye already failed to resolve the pending target —
	 *  retries must move, not repeat the identical solve. */
	private final java.util.Set<BlockPos> failedStances = new java.util.HashSet<>();
	private final java.util.Map<BlockPos, Integer> scaffoldSolveFailures =
			new java.util.HashMap<>();
	private final java.util.Map<BlockPos, Integer> breakTimeouts =
			new java.util.HashMap<>();
	private final java.util.Map<BlockPos, Integer> softRetries =
			new java.util.HashMap<>();
	private final java.util.Set<BlockPos> bannedSupports =
			new java.util.HashSet<>();
	private boolean repairBreakSeenAir;
	private long lastConfirmTick = Long.MIN_VALUE;
	private BlockPos failedStancesFor;

	// --- final audit / repair orchestration ---
	private static final int MAX_REPAIR_TARGETS_PER_ROUND = 128;
	private static final int MAX_ATTEMPTS_PER_POS = 2;
	private final PlacementJournal journal = new PlacementJournal();
	private Planner repairPlanner;
	private boolean repairMode;
	private int repairRound;
	private int fullAuditRuns;
	private final BuildCheckpoint checkpoint = new BuildCheckpoint();
	private Blueprint checkpointScope;
	private Planner savedCheckpointPlanner;
	private int checkpointRepairRound;
	private int checkpointRuns;
	private int repairSettle;
	private final Map<BlockPos, Integer> repairAttempts = new HashMap<>();
	private final Deque<dev.gentlebuild.plan.RepairPolicy.Decision> breakQueue =
			new ArrayDeque<>();
	private final java.util.Set<BlockPos> repairApprovedAll = new java.util.HashSet<>();
	private final java.util.Set<BlockPos> roundApproved = new java.util.HashSet<>();
	private final java.util.Set<BlockPos> repairedPositions = new java.util.HashSet<>();
	private BlockState actionBefore;
	private String repairSummary = "";

	public BuildConfig config() { return config; }

	/**
	 * Apply a validated settings draft: persist first, then swap the live
	 * config. Session-only toggles (fluids/activation) are preserved. A running
	 * build pauses so the new values take effect cleanly.
	 */
	public boolean saveConfig(BuildConfig draft) {
		if (!draft.validationErrors().isEmpty()) {
			return false;
		}
		BuildConfig next = draft.copy();
		next.fluidsEnabled = config.fluidsEnabled;
		next.activationEnabled = config.activationEnabled;
		if (!next.save()) {
			return false;
		}
		if (isRunning()) {
			pause("Einstellungen geändert");
		}
		config = next;
		return true;
	}

	/** One-line snapshot for the HUD overlay; cheap to call per frame. */
	public record HudInfo(String name, RunState state, String phase, int done, int total,
						  BlockPos target, String detail, int supports, int checkpoints) {}

	public HudInfo hudInfo() {
		Planner shown = checkpointScope != null ? savedCheckpointPlanner : (repairMode ? null : planner);
		String phase = cleanupMode ? "Stützenabbau" : checkpointScope != null ? "Zwischenprüfung"
				: repairMode ? "Reparatur " + repairRound + "/" + config.maxRepairRounds
				: current != null ? switch (current.phase()) {
					case STRUCTURE -> "Struktur"; case ATTACHMENTS -> "Anbauteile";
					case MECHANISMS -> "Mechanik"; case FLUIDS -> "Flüssigkeiten";
					case ACTIVATION -> "Aktivierung";
				} : "";
		String detail = state == RunState.RESTING
				? "Pause: " + (int) Math.ceil(rhythm.remainingTicks() / 20.0) + " s"
				: state == RunState.WAITING && transaction != null ? "Warte auf Serverbestätigung"
				: statusLines.isEmpty() ? "" : statusLines.get(0);
		return new HudInfo(blueprint == null ? "Kein Bauplan gewählt" : blueprint.name(), state, phase,
				shown == null ? 0 : shown.verifiedCount(), shown == null ? 0 : shown.totalTargets(),
				pendingAction != null ? pendingAction.target() : current == null ? null : current.pos(),
				detail, supports.ownedCount(), checkpointRuns);
	}
	public RunState state() { return state; }
	public Blueprint blueprint() { return blueprint; }
	public Planner planner() { return planner; }
	public List<String> statusLines() { return statusLines; }
	public int supportCount() { return supports.ownedCount(); }

	public java.util.Set<BlockPos> supportPositions() {
		return supports.ownedPositions();
	}

	public dev.gentlebuild.motion.BuildRhythm.PauseKind automaticPauseKind() {
		return rhythm.kind();
	}

	public int automaticPauseRemainingTicks() {
		return rhythm.remainingTicks();
	}

	public int repairRound() { return repairRound; }
	public int fullAuditRuns() { return fullAuditRuns; }
	public int checkpointRuns() { return checkpointRuns; }

	public int repairedCount() { return repairedPositions.size(); }
	public String repairSummary() { return repairSummary; }

	/** Exact ownership map: our confirmed placements + owned supports. */
	private Map<BlockPos, BlockState> ownedBlocks() {
		Map<BlockPos, BlockState> m = new HashMap<>(journal.owned());
		m.putAll(supports.ownedStates());
		return m;
	}

	/** True while the controller owns the view angles (aiming/looking). */
	public boolean isAimingView() {
		return isRunning() && motion.isActive();
	}

	public boolean isRunning() {
		return state == RunState.WALKING || state == RunState.AIMING
				|| state == RunState.WAITING || state == RunState.VERIFYING
				|| state == RunState.RESTING;
	}

	public List<BuildIssue> issues() {
		List<BuildIssue> all = new ArrayList<>();
		if (planner != null) {
			all.addAll(planner.issues());
		}
		if (snapshot != null) {
			all.addAll(snapshot.issues());
		}
		return all;
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	/** Snapshot the currently selected placement (incremental, 4096/tick). */
	public void importSelected() {
		stop();
		resetCheckpoints();
		lastWorkPos = null;
		prevWorkPos = null;
		journal.clear();
		repairAttempts.clear();
		repairRound = 0;
		fullAuditRuns = 0;
		repairedPositions.clear();
		repairApprovedAll.clear();
		blueprint = null;
		planner = null;
		reservedCells = Set.of();
		config.fluidsEnabled = false;   // per-build permissions reset
		config.activationEnabled = false;
		startAfterSnapshot = false;

		List<BuildIssue> errors = new ArrayList<>();
		List<BlueprintImporter.RegionWork> regions = BlueprintImporter.collectRegions(errors);
		statusLines.clear();
		if (regions == null) {
			for (BuildIssue e : errors) {
				statusLines.add(e.message());
			}
			state = RunState.EMPTY;
			return;
		}
		SchematicPlacement placement = DataManager.getSchematicPlacementManager()
				.getSelectedSchematicPlacement();
		fingerprint = BlueprintImporter.fingerprint(placement, regions);
		placementIdentity = placement;
		dimension = Minecraft.getInstance().level != null
				? Minecraft.getInstance().level.dimension().identifier() : null;
		snapshot = new BlueprintImporter.SnapshotTask(placement, regions);
		state = RunState.PLANNING;
		statusLines.add("Snapshot laeuft...");
	}

	public void start() {
		if (state == RunState.PLANNING) {
			startAfterSnapshot = true;
			return;
		}
		if (blueprint == null || planner == null) {
			importSelected();
			if (state == RunState.PLANNING) {
				startAfterSnapshot = true;
			}
			return;
		}
		cleanupMode = false;
		state = RunState.READY;
		resume();
	}

	/**
	 * Resume builds a FRESH planner against the actual world — stale
	 * verification/conflict/attempt state never survives user corrections.
	 * Confirmed support ownership survives within the same world.
	 */
	public void resume() {
		if (state != RunState.PAUSED && state != RunState.READY
				&& state != RunState.COMPLETE) {
			return;
		}
		if (blueprint == null) {
			return;
		}
		abandonCheckpointScope();
		planner = new Planner(blueprint);
		for (BlockPos pos : blueprint.blockEntityData()) {
			planner.noteManualRequirement(pos,
					"BlockEntity-Inhalt (Inventar/Text) manuell setzen");
		}
		statusLines.clear();
		statusLines.add("Baue...");
		state = RunState.WALKING;
		current = null;
		path = null;
		targetPool.clear();
		rhythm.reset(config);
		clearPending();
		suspendedWork = null;
		clearRepairRun();
		verifyReset();
		stallTicks = 0;
		aimSettledTicks = 0;
		revalidateFailures = 0;
	}

	public void pause(String reason) {
		releaseInputs();
		abandonCheckpointScope();
		if (transaction != null) {
			AckTracker.remove(transaction);
			transaction = null;
		}
		if (cleanupTx != null) {
			AckTracker.remove(cleanupTx);
			cleanupTx = null;
		}
		rhythm.cancelPause();
		targetPool.clear();
		clearPending();
		current = null;
		path = null;
		if (state != RunState.EMPTY && state != RunState.COMPLETE) {
			state = RunState.PAUSED;
		}
		if (statusLines.isEmpty() || !statusLines.get(0).equals("Pausiert: " + reason)) {
			statusLines.add(0, "Pausiert: " + reason);
		}
	}

	public void stop() {
		releaseInputs();
		abandonCheckpointScope();
		AckTracker.cancelAll();
		transaction = null;
		cleanupTx = null;
		rhythm.reset(config);
		targetPool.clear();
		state = blueprint != null ? RunState.READY : RunState.EMPTY;
		current = null;
		path = null;
		clearPending();
		cleanupMode = false;
		suspendedWork = null;
		clearRepairRun();
		if (blueprint != null) {
			statusLines.add(0, "Gestoppt");
		}
	}

	public void unload() {
		releaseInputs();
		AckTracker.cancelAll();
		transaction = null;
		cleanupTx = null;
		motion.reset();
		config.fluidsEnabled = false;
		config.activationEnabled = false;
		state = RunState.EMPTY;
		blueprint = null;
		planner = null;
		snapshot = null;
		supports.clear();
		reservedCells = Set.of();
		cleanupQueue.clear();
		cleanupTx = null;
		suspendedWork = null;
		targetPool.clear();
		lastWorkPos = null;
		prevWorkPos = null;
		journal.clear();
		repairAttempts.clear();
		repairRound = 0;
		fullAuditRuns = 0;
		repairedPositions.clear();
		repairApprovedAll.clear();
		softRetries.clear();
		bannedSupports.clear();
		scaffoldSolveFailures.clear();
		clearRepairRun();
		rhythm.reset(config);
		current = null;
		path = null;
		clearPending();
		cleanupMode = false;
		startAfterSnapshot = false;
		lastHealth = -1;
		fluidSettle = 0;
		resetCheckpoints();
		verifyReset();
		statusLines.clear();
	}

	private void verifyReset() {
		verifier = null;
	}

	/** Explicit "verify + repair" (/gb verify, GUI): fresh audit, new budget. */
	public void verify() {
		if (blueprint == null || state == RunState.EMPTY
				|| state == RunState.PLANNING) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		// Explicit verify is always a FULL blueprint audit, never a scoped one.
		abandonCheckpointScope();
		// Explicit user audit resets the bounded repair budget.
		repairAttempts.clear();
		repairRound = 0;
		repairedPositions.clear();
		repairApprovedAll.clear();
		roundApproved.clear();
		if (planner == null) {
			planner = new Planner(blueprint);
		}
		beginFinalAudit();
	}

	public void onScreenOpened(Screen screen) {
		if (isRunning() || cleanupMode) {
			pause("GUI geoeffnet");
		}
	}

	// ------------------------------------------------------------------
	// Per-tick
	// ------------------------------------------------------------------

	/** Developer diagnostics for game tests and /gb status. */
	public String diagnosticState() {
		StringBuilder b = new StringBuilder("state=").append(state)
				.append(" target=").append(current != null ? current.pos() : "-")
				.append(" pending=").append(pendingAction != null
						? pendingAction.kind() + "@" + pendingAction.target() : "-")
				.append(" stance=").append(pendingStance)
				.append(" path=").append(path != null
						? pathIndex + "/" + path.waypoints().size() : "-")
				.append(" eyeResolved=").append(eyeResolved)
				.append(" aim=").append(aimSettledTicks)
				.append(" rest=").append(rhythm.kind())
				.append("/").append(rhythm.remainingTicks())
				.append(" sneak=").append(sneakTicks)
				.append(" tx=").append(transaction != null ? transaction.status() : "-")
				.append(" fluidSettle=").append(fluidSettle)
				.append(" revReason=").append(revalidateReason)
				.append(" status=").append(statusLines);
		Minecraft mc = null;
		try {
			mc = Minecraft.getInstance();
		} catch (IllegalStateException offClientThread) {
			// gametest diagnostics may run off the client thread
		}
		if (mc != null && mc.player != null) {
			b.append(" pos=").append(mc.player.position());
		}
		return b.toString();
	}

	public void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			if (state != RunState.EMPTY) {
				unload();
			}
			return;
		}
		// World/dimension change: drop everything, never resume blindly.
		if (blueprint != null
				&& !mc.level.dimension().identifier().equals(dimension)) {
			unload();
			return;
		}
		if (intervalCooldown > 0) {
			intervalCooldown--;
		}
		if (isRunning() || cleanupMode) {
			if (guardHalt(mc, player)) {
				return;
			}
		}
		switch (state) {
			case PLANNING -> tickSnapshot(mc);
			case READY, PAUSED, EMPTY, COMPLETE -> { }
			case WALKING, AIMING, WAITING, VERIFYING, RESTING -> tickBuild(mc, player);
		}
	}

	private boolean guardHalt(Minecraft mc, LocalPlayer player) {
		// Raw physical input wins even when the broker wasn't controlling.
		if (InputBroker.manualInputRecently()
				|| (mc.options.keyAttack.isDown()
						&& !InputBroker.attackHeldByMod())
				|| mc.options.keyUse.isDown()
				|| mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
				|| mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()
				|| mc.options.keyJump.isDown() || mc.options.keyShift.isDown()) {
			pause("Manuelle Eingabe");
			return true;
		}
		if (!mc.isWindowActive() || mc.isPaused() || mc.gui.screen() != null) {
			pause("Fenster/GUI");
			return true;
		}
		if (!player.isAlive()) {
			pause("Spieler gestorben");
			return true;
		}
		float health = player.getHealth();
		if (lastHealth >= 0 && health < lastHealth - 0.01f) {
			lastHealth = health;
			pause("Schaden erhalten");
			return true;
		}
		lastHealth = health;
		if (health <= 6.0f) {
			pause("Niedrige Gesundheit");
			return true;
		}
		if (player.isPassenger() || player.isFallFlying() || player.getAbilities().flying) {
			pause("Reiten/Fliegen/Elytra");
			return true;
		}
		return false;
	}

	private void tickSnapshot(Minecraft mc) {
		if (snapshot == null) {
			state = RunState.EMPTY;
			return;
		}
		// Abort the incremental snapshot if the placement changed mid-scan.
		if (!fingerprintStillValid(mc)) {
			snapshot = null;
			startAfterSnapshot = false;
			state = RunState.EMPTY;
			statusLines.add(0, "Placement waehrend Snapshot veraendert");
			return;
		}
		if (snapshot.tick()) {
			if (snapshot.isFailed()) {
				statusLines.clear();
				for (BuildIssue e : snapshot.issues()) {
					statusLines.add(e.message());
				}
				state = RunState.EMPTY;
				startAfterSnapshot = false;
				return;
			}
			blueprint = snapshot.result();
			planner = new Planner(blueprint);
			reservedCells = new LinkedHashSet<>();
			for (Map.Entry<BlockPos, BlockState> e : blueprint.blocks().entrySet()) {
				if (!e.getValue().isAir()) {
					reservedCells.add(e.getKey());
				}
			}
			for (BlockPos pos : blueprint.blockEntityData()) {
				planner.noteManualRequirement(pos,
						"BlockEntity-Inhalt (Inventar/Text) manuell setzen");
			}
			statusLines.clear();
			statusLines.add("Bauplan: " + blueprint.name() + " | "
					+ blueprint.nonAirCount() + " Bloecke, "
					+ blueprint.entities() + " Entities(man.), "
					+ blueprint.blockEntityData().size() + " BlockEntities(man.)");
			state = RunState.READY;
			if (startAfterSnapshot) {
				startAfterSnapshot = false;
				start();
			}
		}
	}

	private boolean fingerprintStillValid(Minecraft mc) {
		SchematicPlacement placement = DataManager.getSchematicPlacementManager()
				.getSelectedSchematicPlacement();
		if (placement == null || placement != placementIdentity || mc.level == null
				|| !mc.level.dimension().identifier().equals(dimension)) {
			return false;
		}
		List<BuildIssue> errors = new ArrayList<>();
		List<BlueprintImporter.RegionWork> regions = BlueprintImporter.collectRegions(errors);
		return regions != null
				&& BlueprintImporter.fingerprint(placement, regions).equals(fingerprint);
	}

	// ------------------------------------------------------------------
	// Build loop — strict per-tick stage flow, one stage per tick
	// ------------------------------------------------------------------

	private void tickBuild(Minecraft mc, LocalPlayer player) {
		// Placement must never be handed to a stale selection.
		if (!fingerprintStillValid(mc)) {
			pause("Placement veraendert");
			return;
		}
		// Fail closed: only a walking decision later this tick re-arms forward.
		releaseMoveOnly();
		// Cleanup uses its own transaction path (destroy packets continue
		// while the air-confirmation transaction is pending).
		if (cleanupMode) {
			tickCleanup(mc, player);
			return;
		}
		// 1. pending transaction owns the tick
		if (transaction != null) {
			releaseMoveOnly();
			state = RunState.WAITING;
			// Repair breaking needs a destroy packet every tick — the pending
			// AIR-confirmation must not block damage, and the safety policy
			// is re-evaluated live each tick.
			if (transaction.action().kind() == ActionKind.BREAK_REPAIR
					&& !driveRepairBreak(mc, player)) {
				BlockPos bp = transaction.action().target();
				AckTracker.remove(transaction);
				transaction = null;
				InputBroker.holdAttack(false);
				mc.gameMode.stopDestroyBlock();
				if (breakSightFailures.merge(bp, 1, Integer::sum) >= 3) {
					// Bounded: never spin on an unsolvable angle — the issue
					// stays unverified and is reported as manual work.
					if (!breakQueue.isEmpty() && breakQueue.peek()
							.mismatch().pos().equals(bp)) {
						breakQueue.poll();
					}
					statusLines.add(0, "Reparatur manuell "
							+ bp.toShortString() + ": " + revalidateReason);
				}
				clearPending();
				current = null;
				return;
			}
			if (transaction.tick(pos -> mc.level.getBlockState(pos))) {
				AckTracker.remove(transaction);
				finishTransaction(mc);
			}
			return;
		}
		if (fluidSettle > 0) {
			fluidSettle--;
			releaseMoveOnly();
			state = RunState.WAITING;
			return;
		}
		// Full audit owns the tick while VERIFYING — no cadence/acquire.
		if (state == RunState.VERIFYING) {
			releaseMoveOnly();
			tickAudit(mc, player);
			return;
		}
		// Repair breaks drain their queue at clean task boundaries.
		if (repairMode && current == null && pendingAction == null
				&& suspendedWork == null && !breakQueue.isEmpty()) {
			scheduleNextBreak(mc, player);
			return;
		}
		// A completed batch is re-checked at a clean boundary before the next task.
		if (current == null && pendingAction == null && suspendedWork == null
				&& !repairMode && checkpoint.due(config.checkpointStructureBlocks,
						config.checkpointSensitiveBlocks)) {
			startCheckpointAudit();
			return;
		}
		// Active work time only counts while actually working.
		rhythm.activeTick(config);
		if (state == RunState.RESTING) {
			if (!config.automaticPauses) {
				rhythm.cancelPause();
			}
			rhythm.tickPause();
			releaseMoveOnly();
			if (rhythm.kind() == dev.gentlebuild.motion.BuildRhythm.PauseKind.NONE) {
				state = RunState.WALKING;
			} else {
				return;
			}
		}
		// Scheduled pauses only start at a clean task boundary on safe ground.
		if (current == null && pendingAction == null && suspendedWork == null
				&& !planner.isComplete()) {
			var kind = rhythm.tryStart(safeRestSpot(mc, player), config);
			if (kind != dev.gentlebuild.motion.BuildRhythm.PauseKind.NONE) {
				enterRest();
				return;
			}
		}
		// 2. acquire/keep target — fall through into solving in the same
		// tick; stages that completed without side effects cost no extra tick.
		if (current == null) {
			acquireTarget(mc, player);
			if (current == null || !isRunning()
					|| state == RunState.VERIFYING || state == RunState.RESTING) {
				return;
			}
		}
		// 3. solve action — when the stance is already reached, aiming may
		// continue in the same tick.
		if (pendingAction == null) {
			solvePending(mc, player);
			if (pendingAction == null || current == null || !isRunning()
					|| state == RunState.VERIFYING || state == RunState.RESTING) {
				return;
			}
		}
		// 4. walk to stance (never falls through to placement)
		if (pendingStance != null && !atStance(player, pendingStance)) {
			state = RunState.WALKING;
			walkTick(mc, player);
			return;
		}
		releaseMoveOnly();
		// Scaffolding may have changed the held item while work was suspended.
		if (pendingAction != null && pendingAction.item() != null) {
			InventoryHelper.EquipResult equip = InventoryHelper.equip(player, pendingAction.item(), config);
			if (equip != InventoryHelper.EquipResult.READY) {
				equipFailed(equip, "Material fehlt: "
						+ new ItemStack(pendingAction.item()).getHoverName().getString());
				return;
			}
			materialWait = 0;
		}
		// Hold sneak throughout resolving and aiming: releasing it would change eye height.
		if (pendingAction.sneak()) {
			InputBroker.setDesired(new Input(false, false, false, false,
					false, true, false));
			if (!player.isShiftKeyDown() || sneakTicks < 1) {
				sneakTicks++;
				eyeResolved = false;
				aimSettledTicks = 0;
				return;
			}
		}
		if (player.getDeltaMovement().horizontalDistanceSqr() > 0.0025
				|| !player.onGround()) {
			return; // braking before acting
		}
		// 4.5 final resolve from the ACTUAL eye: the stance-centered solve
		// assumes the cell-center eye, but walk tolerance leaves the real
		// eye slightly off — enough for a thin wall to occlude the planned
		// face. Resolve once now, then hold this exact hit fixed.
		if (!eyeResolved) {
			PlacementAction resolved = resolveFromActualEye(mc, player);
			if (resolved == null) {
				// Nothing reachable from the real eye — retry with another
				// face/stance, bounded; never drop suspended scaffold work.
				if (pendingScaffoldTarget != null) {
					noteFailedStance(pendingScaffoldTarget.pos());
					bannedSupports.add(pendingScaffoldTarget.pos());
					supports.unplan(pendingScaffoldTarget.pos());
					pendingScaffoldTarget = null;
					pendingAction = null;
					pendingStance = null;
					restoreSuspended();
					if (++revalidateFailures >= MAX_SCAFFOLD_RESOLVE_FAILURES) {
						pause("Stuetze nicht erreichbar aus aktueller Position");
						revalidateFailures = 0;
						suspendedWork = null;
						clearPending();
						current = null;
					}
					return;
				}
				if (pendingAction != null) {
					noteFailedStance(pendingAction.target());
				}
				if (++revalidateFailures >= MAX_REVALIDATE_FAILURES) {
					revalidateFailures = 0;
					clearPending();
					if (current != null) {
						failOrRequeue(current,
								"Keine Sicht auf Ziel aus aktueller Position");
					}
					return;
				}
				clearPending();
				return;
			}
			revalidateFailures = 0;
			pendingAction = resolved;
			pendingStance = null;
			eyeResolved = true;
			aimSettledTicks = 0;
			aimSettleNeeded = config.naturalMovement
					? 2 + glanceRandom.nextInt(2) : 2;
			// Small turn (typical inside a same-stance batch): one unchanged
			// on-target tick is enough before live revalidation.
			Vec3 toHit = pendingAction.hit().getLocation()
					.subtract(player.getEyePosition());
			if (toHit.lengthSqr() > 1e-6) {
				double cos = player.getViewVector(1.0f).normalize()
						.dot(toHit.normalize());
				double turnDeg = Math.toDegrees(
						Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
				if (turnDeg < 30.0) {
					aimSettleNeeded = 1;
				}
			}
		}
		// 5. aim + settle: let vanilla transmit the real rotation first
		state = RunState.AIMING;
		motion.aimAt(player, pendingAction.hit().getLocation());
		float beforeYaw = player.getYRot();
		float beforePitch = player.getXRot();
		motion.tick(player, config);
		// A tick that still turns, or lands off target, is not settled. An unchanged on-target
		// tick means vanilla's own rotation send ran before this tick's use-item click.
		if (!motion.isOnTarget(player)
				|| player.getYRot() != beforeYaw || player.getXRot() != beforePitch) {
			aimSettledTicks = 0;
			return;
		}
		if (++aimSettledTicks < aimSettleNeeded) {
			return;
		}
		// 6. live revalidation of world/view/pose/stack
		PlacementAction attempted = pendingAction;
		if (!revalidate(mc, player)) {
			if (!isRunning() || pendingAction == null && state != RunState.WAITING) {
				return; // pause() may have cleared the action (e.g. fluid leak)
			}
			if (attempted != null) {
				// getBlockPos() can be a MutableBlockPos — its toString
				// would never match the solver's immutable exclusion keys.
				triedFaces.add(attempted.hit().getBlockPos().immutable() + "/"
						+ attempted.hit().getDirection());
				triedFacesFor = attempted.target();
			}
			if (++revalidateFailures >= MAX_REVALIDATE_FAILURES) {
				revalidateFailures = 0;
				statusLines.add(0, "Revalidierung fehlgeschlagen bei "
						+ attempted.target().toShortString()
						+ " [" + revalidateReason + "]");
				clearPending();
				if (current != null) {
					failOrRequeue(current, revalidateReason);
				}
				return;
			}
			clearPending();
			return;
		}
		revalidateFailures = 0;
		// 7. sneak must survive whole ticks before the click
		if (pendingAction.sneak()) {
			if (!player.isShiftKeyDown() || sneakTicks < 1) {
				InputBroker.setDesired(new Input(false, false, false, false,
						false, true, false));
				sneakTicks++;
				eyeResolved = false;
				aimSettledTicks = 0;
				return;
			}
		}
		if (intervalCooldown > 0) {
			return;
		}
		// 8. arm the transaction first, then perform
		Transaction tx = new Transaction(pendingAction,
				pendingAction.kind() == ActionKind.BREAK_REPAIR
						? Math.max(config.confirmationTimeoutTicks, 200)
						: config.confirmationTimeoutTicks,
				minSettle(pendingAction));
		if (pendingAction.kind() == ActionKind.BREAK_REPAIR) {
			repairBreakSeenAir = false;
		}
		AckTracker.submit(tx);
		if (perform(mc, player, pendingAction)) {
			transaction = tx;
			intervalCooldown = config.minPlaceIntervalTicks;
			aimSettledTicks = 0;
			sneakTicks = 0;
		} else {
			AckTracker.remove(tx);
			if (current != null) {
				if (!planner.placeAttemptAllowed(current.pos())) {
					planner.markManual(current.pos(),
							"Platzierung wiederholt vom Server nicht angenommen");
				} else {
					planner.recordPlaceAttempt(current.pos());
				}
			}
			clearPending();
			current = null;
		}
	}

	private int minSettle(PlacementAction action) {
		if (action.kind() == ActionKind.FLUID) {
			return action.expected().getFluidState().getType() == Fluids.LAVA
					? LAVA_SETTLE_TICKS : Math.max(config.settleTicks, 20);
		}
		return config.settleTicks;
	}

	private void acquireTarget(Minecraft mc, LocalPlayer player) {
		NavWorld world = new LevelNavWorld(mc.level);
		// Drop settled/stale candidates; reconcile cells that reached their
		// construction state while the pool was waiting.
		for (var it = targetPool.iterator(); it.hasNext();) {
			BuildTarget t = it.next();
			if (planner.isVerified(t) || planner.isConflicted(t.pos())
					|| !world.isLoaded(t.pos())) {
				it.remove();
				continue;
			}
			BlockState actual = world.getBlockState(t.pos());
			if (StateRules.constructionEquivalent(t.state(), actual)
					|| planner.acceptsHalfSlabStage(t, actual)) {
				markVerified(t);
				it.remove();
				continue;
			}
			if (!planner.isEligible(t, world)) {
				it.remove();
			}
		}
		// Top up the persistent pool (bounded; one scan call per tick).
		if (targetPool.size() < 16 && !planner.isComplete()) {
			for (BuildTarget t : planner.nextEligible(world,
					config.fluidsEnabled, config.activationEnabled, player.getEyePosition())) {
				if (targetPool.size() < 24) {
					targetPool.add(t);
				}
			}
		}
		if (targetPool.isEmpty()) {
			if (!repairMode && !checkpoint.isEmpty()) {
				startCheckpointAudit(); // tail batch, however short, before pausing or finishing
				return;
			}
			if (planner.isComplete()) {
				if (repairMode && !breakQueue.isEmpty()) {
					return; // breaks drain before the next audit
				}
				if (repairMode && repairSettle++ < config.settleTicks) {
					return; // let blocks settle before re-auditing
				}
				repairSettle = 0;
				beginFinalAudit();
				return;
			}
			if (planner.scanIncomplete()) {
				String disabled = planner.onlyDisabledWorkRemaining(
						config.fluidsEnabled, config.activationEnabled);
				if (disabled != null) {
					pause(disabled + " nicht aktiviert");
					return;
				}
				if (++stallTicks > 400) {
					// Dependency stall: let the audit decide what is missing.
					stallTicks = 0;
					beginFinalAudit();
				}
				return; // sweep not finished, not a stall
			}
			// Full scan, no eligible targets: conflicts block normal progress;
			// hand the situation to the final audit instead of pausing blind.
			for (BuildTarget t : planner.targets()) {
				if (!planner.isVerified(t) && !planner.isConflicted(t.pos())
						&& !world.isLoaded(t.pos())) {
					pause("Relevante Chunks nicht geladen bei "
							+ t.pos().toShortString());
					return;
				}
			}
			beginFinalAudit();
			return;
		}
		// Same-stance batching: probe the best-ranked pool candidates with
		// the real solver from the CURRENT eye. The first one solvable from
		// here wins — solvePending then resolves pendingStance=here and the
		// whole walk stage is skipped. Fall back to chooseNext otherwise.
		current = probeFromCurrentStance(mc, player);
		if (current == null) {
			current = Planner.chooseNext(List.copyOf(targetPool),
					player.getEyePosition(), player.getYRot(), player.getXRot(),
					lastWorkPos, prevWorkPos);
		}
		if (!repairMode && checkpoint.shouldFlush(current.phase())) {
			current = null;
			startCheckpointAudit();
			return;
		}
		targetPool.remove(current);
		path = null;
		stallTicks = 0;
		state = RunState.WALKING;
	}

	/**
	 * Same-stance batching probe: rank the pool by the shared candidate
	 * order and cheap-test up to {@link #STANCE_PROBE_LIMIT} ordinary
	 * placements for solvability from the player's real eye position.
	 * Only plain PLACE work qualifies — FLUIDS and same-block interact /
	 * conflict cells keep the normal solvePending flow. Probing uses a
	 * fresh empty exclusion set so it never poisons triedFaces retries.
	 */
	private BuildTarget probeFromCurrentStance(Minecraft mc, LocalPlayer player) {
		List<BuildTarget> ranked = new ArrayList<>(targetPool);
		ItemStack held = player.getMainHandItem();
		ranked.sort(Planner.candidateOrder(player.getEyePosition(),
				player.getYRot(), player.getXRot(), lastWorkPos, prevWorkPos,
				held.isEmpty() ? null : held.getItem()));
		BuildTarget first = ranked.isEmpty() ? null : ranked.get(0);
		int probes = 0;
		for (BuildTarget t : ranked) {
			if (probes >= STANCE_PROBE_LIMIT) {
				break;
			}
			// Normal ordering stays authoritative: probe only the first entry's phase and layer.
			if (first != null && (t.phase() != first.phase()
					|| t.pos().getY() != first.pos().getY())) {
				continue;
			}
			if (t.phase() == Phase.FLUIDS) {
				continue;
			}
			BlockState actual = mc.level.getBlockState(t.pos());
			boolean placePath = actual.isAir() || actual.canBeReplaced()
					|| StateRules.canStackSlab(t.state(), actual);
			if (!placePath) {
				continue; // interact/conflict handling stays in solvePending
			}
			if (planner.isManual(t.pos())) {
				continue;
			}
			Item item = ItemResolver.itemFor(t.state());
			if (item == null) {
				continue;
			}
			probes++;
			java.util.Set<String> excl = t.pos().equals(triedFacesFor)
					? triedFaces : Set.of();
			if (solver.solveFromEye(t, item, player.getEyePosition(), mc.level,
					player, player.blockInteractionRange(), excl) != null) {
				return t;
			}
		}
		return null;
	}

	private void solvePending(Minecraft mc, LocalPlayer player) {
		BuildTarget target = current;
		// Exclusions are per-target; a different cell starts fresh.
		if (triedFacesFor == null || !triedFacesFor.equals(target.pos())) {
			triedFaces.clear();
			triedFacesFor = target.pos();
		}
		NavWorld world = new LevelNavWorld(mc.level);
		BlockState actual = mc.level.getBlockState(target.pos());

		// Fluid tasks first: a dry waterlogged shell is not a conflict.
		if (target.phase() == Phase.FLUIDS) {
			if (!config.fluidsEnabled) {
				pause("Fluessigkeiten nicht aktiviert");
				return;
			}
			if (StateRules.finalEquivalent(target.state(), actual)) {
				markVerified(target);
				current = null;
				return;
			}
			if (!solveFluid(mc, player, target, world)) {
				if (isRunning()) {
					resetExclusions(target.pos());
					failOrRequeue(target, "Fluidplatzierung nicht loesbar");
				}
			}
			return;
		}

		// Construction match? task done (final verifier checks connections).
		if (StateRules.constructionEquivalent(target.state(), actual)
				|| planner.acceptsHalfSlabStage(target, actual)) {
			markVerified(target);
			current = null;
			return;
		}
		if (!actual.isAir() && actual.getBlock() == target.state().getBlock()
				&& !StateRules.canStackSlab(target.state(), actual)) {
			BlockState next = StateRules.nextInteractionStep(target.state(), actual);
			if (next != null) {
				if (!config.activationEnabled && isLiveControl(target.state())) {
					pause("Aktivierung nicht aktiviert fuer " + target.pos().toShortString());
					return;
				}
				if (!planner.interactAttemptAllowed(target.pos())) {
					planner.markManual(target.pos(),
							"Zielzustand nicht erreichbar nach Interaktionen");
					current = null;
					return;
				}
				if (!solveInteract(mc, player, target, next)) {
					resetExclusions(target.pos());
					failOrRequeue(target, "Keine Interaktionsposition gefunden");
				}
				return;
			}
			planner.markConflict(target.pos(),
					"Gleicher Block, nicht korrigierbare Eigenschaft weicht ab");
			current = null;
			return;
		}
		if (!actual.isAir() && !actual.canBeReplaced()
				&& !StateRules.canStackSlab(target.state(), actual)) {
			planner.markConflict(target.pos(),
					"Falscher Block vorhanden: " + actual.getBlock());
			current = null;
			return;
		}

		// Ordinary placement.
		Item item = ItemResolver.itemFor(target.state());
		if (item == null) {
			planner.noteManualRequirement(target.pos(),
					"Kein Item fuer " + target.state().getBlock());
			planner.markManual(target.pos(),
					"Manuell platzieren: " + target.state().getBlock());
			current = null;
			return;
		}
		InventoryHelper.EquipResult equip = InventoryHelper.equip(player, item, config);
		if (equip != InventoryHelper.EquipResult.READY) {
			equipFailed(equip, "Material fehlt: " + new ItemStack(item).getHoverName().getString());
			return;
		}
		materialWait = 0;
		List<BlockPos> stances = filterFailedStances(
				stanceCandidates(player, target.pos(), world), target.pos());
		if (target.phase() == Phase.STRUCTURE) {
			// Standing cell first (the solver also tries the real eye first), then coverage.
			stances.sort(Comparator.comparing((BlockPos s) -> !s.equals(player.blockPosition()))
					.thenComparing(Planner.batchStanceOrder(List.copyOf(targetPool), target,
							player.position(), player.blockInteractionRange())));
		}
		PlacementSolver.SolvedAction solved = solver.solve(target, item, stances,
				mc.level, player, player.blockInteractionRange(), triedFaces);
		if (solved == null) {
			resetExclusions(target.pos());
			failOrRequeue(target, "Keine gueltige Platzierungsloesung");
			return;
		}
		stanceFallback = stances;
		stanceIndex = stances.indexOf(solved.stance());
		pendingScaffoldTarget = null;
		// Solved from the cell we already stand on: no walk or recentering needed.
		pendingStance = solved.stance().equals(player.blockPosition()) ? null : solved.stance();
		// The transaction expects the state a fresh placement produces.
		pendingAction = new PlacementAction(target.pos(), solved.predicted(),
				solved.action().hit(), item, solved.action().sneak(), ActionKind.PLACE);
	}

	private void failOrRequeue(BuildTarget target, String reason) {
		if (!planner.placeAttemptAllowed(target.pos())) {
			planner.markManual(target.pos(), reason);
		} else {
			planner.recordPlaceAttempt(target.pos());
		}
		current = null; // try another target instead of stalling on this one
	}

	/**
	 * Requeue after a pure TIMING failure (late/no ack): nothing was proven
	 * wrong with the cell, so the bounded place-attempt counter stays
	 * untouched. Soft retries are still capped — a target that keeps
	 * timing out eventually falls back to {@link #failOrRequeue}.
	 */
	private void softRequeue(BuildTarget target, String reason) {
		if (softRetries.merge(target.pos(), 1, Integer::sum) > 6) {
			softRetries.remove(target.pos());
			failOrRequeue(target, reason);
			return;
		}
		current = null;
	}

	private boolean isLiveControl(BlockState wanted) {
		var b = wanted.getBlock();
		return b instanceof net.minecraft.world.level.block.LeverBlock
				|| b instanceof net.minecraft.world.level.block.DoorBlock
				|| b instanceof net.minecraft.world.level.block.TrapDoorBlock;
	}

	/**
	 * Solve a legitimate right-click on an existing block: real outline faces
	 * from reachable stances (a repeater's top is y.125, not a full cube).
	 */
	private boolean solveInteract(Minecraft mc, LocalPlayer player,
								  BuildTarget target, BlockState nextStep) {
		NavWorld world = new LevelNavWorld(mc.level);
		List<BlockPos> stances = filterFailedStances(
				stanceCandidates(player, target.pos(), world), target.pos());
		// Player's own eye position first.
		stances.add(0, player.blockPosition());
		double reach = player.blockInteractionRange();
		for (BlockPos stance : stances) {
			Vec3 eye = Vec3.atBottomCenterOf(stance).add(0, EYE_HEIGHT, 0);
			for (BlockHitResult real : PlacementSolver.visibleFaces(
					mc.level, target.pos(), eye, player, reach)) {
				pendingStance = stance;
				pendingScaffoldTarget = null;
				pendingAction = new PlacementAction(target.pos(), nextStep, real,
						null, false, ActionKind.INTERACT);
				stanceFallback = stances;
				stanceIndex = stances.indexOf(stance);
				return true;
			}
		}
		return false;
	}

	private boolean solveFluid(Minecraft mc, LocalPlayer player,
							   BuildTarget target, NavWorld world) {
		BlockPos pos = target.pos();
		Fluid fluid;
		boolean waterlog = Planner.isWaterlogTask(target);
		if (waterlog) {
			fluid = Fluids.WATER;
		} else {
			fluid = target.state().getFluidState().getType();
			if (fluid != Fluids.WATER && fluid != Fluids.LAVA) {
				planner.markManual(pos, "Nicht unterstuetztes Fluid");
				return true;
			}
		}
		if (fluid == Fluids.WATER && mc.level.environmentAttributes().getValue(
				net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, pos)) {
			planner.markManual(pos, "Wasser verdampft in dieser Dimension");
			return true;
		}
		Item bucket = fluid == Fluids.WATER ? Items.WATER_BUCKET : Items.LAVA_BUCKET;
		InventoryHelper.EquipResult equip = InventoryHelper.equip(player, bucket, config);
		if (equip != InventoryHelper.EquipResult.READY) {
			if (equip == InventoryHelper.EquipResult.WAITING) {
				return true;
			}
			equipFailed(equip, "Material fehlt: " + new ItemStack(bucket).getHoverName().getString());
			return true;
		}
		materialWait = 0;
		double reach = player.blockInteractionRange();
		List<BlockPos> stances = filterFailedStances(
				stanceCandidates(player, pos, world), pos);
		for (BlockPos stance : stances) {
			Vec3 eye = Vec3.atBottomCenterOf(stance).add(0, EYE_HEIGHT, 0);
			BlockHitResult real = fluidHit(mc, target, waterlog, pos, eye,
					player, reach);
			if (real != null) {
				pendingStance = stance;
				pendingScaffoldTarget = null;
				pendingAction = new PlacementAction(pos, target.state(), real,
						bucket, false, ActionKind.FLUID);
				stanceFallback = stances;
				stanceIndex = stances.indexOf(stance);
				return true;
			}
		}
		return false;
	}

	/** A real pick from {@code eye} that pours into {@code pos} (or fills the
	 * shell for waterlogging). Shared by stance solving and the final
	 * actual-eye resolve. */
	private BlockHitResult fluidHit(Minecraft mc, BuildTarget target,
									boolean waterlog, BlockPos pos, Vec3 eye,
									LocalPlayer player, double reach) {
		if (waterlog) {
			// Click the existing shell's real outline.
			BlockState dry = target.state().setValue(
					net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,
					false);
			BlockState live = mc.level.getBlockState(pos);
			if (!StateRules.constructionEquivalent(dry, live)
					&& !StateRules.constructionEquivalent(target.state(), live)) {
				return null;
			}
			for (BlockHitResult real : PlacementSolver.visibleFaces(
					mc.level, pos, eye, player, reach)) {
				return real;
			}
			return null;
		}
		// Click an adjacent face so the pour lands in the target cell.
		for (Direction dir : Direction.values()) {
			BlockPos neighbor = pos.relative(dir);
			if (!mc.level.isLoaded(neighbor)
					|| mc.level.getBlockState(neighbor).isAir()) {
				continue;
			}
			for (BlockHitResult real : PlacementSolver.visibleFaces(
					mc.level, neighbor, eye, player, reach)) {
				if (real.getBlockPos().relative(real.getDirection()).equals(pos)) {
					return real;
				}
			}
		}
		return null;
	}

	/**
	 * Final action resolution from the player's REAL eye position after
	 * walking and braking. The stance-centered solve assumes the cell-center
	 * eye; the few tenths of residual offset are enough for a thin wall to
	 * occlude the planned face. Rerun the identical hit search from the true
	 * eye so the click that follows matches the pick vanilla performs.
	 */
	private PlacementAction resolveFromActualEye(Minecraft mc, LocalPlayer player) {
		PlacementAction a = pendingAction;
		Vec3 eye = player.getEyePosition();
		double reach = player.blockInteractionRange();
		switch (a.kind()) {
			case PLACE -> {
				BuildTarget eff = pendingScaffoldTarget != null
						? pendingScaffoldTarget : current;
				if (eff == null) {
					return a;
				}
				PlacementSolver.SolvedAction re = solver.solveFromEye(eff,
						a.item(), eye, mc.level, player, reach, triedFaces);
				if (re == null) {
					return null;
				}
				return new PlacementAction(eff.pos(), re.predicted(),
						re.action().hit(), a.item(), re.action().sneak(),
						ActionKind.PLACE);
			}
			case INTERACT -> {
				for (BlockHitResult real : PlacementSolver.visibleFaces(
						mc.level, a.target(), eye, player, reach)) {
					return new PlacementAction(a.target(), a.expected(), real,
							a.item(), a.sneak(), ActionKind.INTERACT);
				}
				return null;
			}
			case FLUID -> {
				if (current == null) {
					return a;
				}
				boolean waterlog = Planner.isWaterlogTask(current);
				BlockHitResult real = fluidHit(mc, current, waterlog, a.target(),
						eye, player, reach);
				if (real == null) {
					return null;
				}
				return new PlacementAction(a.target(), a.expected(), real,
						a.item(), false, ActionKind.FLUID);
			}
			case BREAK_REPAIR -> {
				for (BlockHitResult real : PlacementSolver.visibleFaces(
						mc.level, a.target(), eye, player, reach)) {
					return new PlacementAction(a.target(), a.expected(), real,
							null, false, ActionKind.BREAK_REPAIR);
				}
				return null;
			}
			default -> {
				return a;
			}
		}
	}

	/**
	 * Live revalidation immediately before the click: real pick from the
	 * actual view, held stack, real BlockPlaceContext prediction.
	 */
private boolean revalidate(Minecraft mc, LocalPlayer player) {
		PlacementAction a = pendingAction;
		BlockPos pos = a.target();
		if (!mc.level.isLoaded(pos) || !mc.level.isLoaded(a.hit().getBlockPos())) {
			revalidateReason = "world unloaded";
			return false;
		}
		if (a.item() != null && player.getMainHandItem().getItem() != a.item()) {
			revalidateReason = "held item " + player.getMainHandItem().getItem()
					+ " != " + a.item();
			return false;
		}
		// The current view ray must hit SOME block within reach. For
		// placements the clicked face need not equal the planned one — the
		// BlockPlaceContext checks below (clickedPos == target and the
		// predicted state equivalent to the blueprint state) are the real
		// correctness proof. Exact hit equality is only required for
		// INTERACT/FLUID where the destination is defined by the hit itself.
		if (!(player.pick(player.blockInteractionRange(), 1.0f,
				a.kind() == ActionKind.FLUID) instanceof BlockHitResult real)
				|| real.getType() != HitResult.Type.BLOCK) {
			revalidateReason = "pick miss";
			return false;
		}
		switch (a.kind()) {
			case PLACE -> {
				BlockState live = mc.level.getBlockState(pos);
				var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
						player.getMainHandItem(), real);
				if (!context.getClickedPos().equals(pos)) {
					revalidateReason = "clickedPos " + context.getClickedPos()
							+ " != " + pos + " hit=" + real.getBlockPos()
							+ "/" + real.getDirection();
					return false;
				}
				if (!(a.item() instanceof BlockItem blockItem)) {
					revalidateReason = "not a BlockItem";
					return false;
				}
				BlockState predicted = ((BlockItemAccessor) blockItem)
						.gentlebuild$getPlacementState(context);
				if (predicted == null) {
					revalidateReason = "predicted null";
					return false;
				}
				if (!StateRules.placementEquivalent(a.expected(), predicted)) {
					revalidateReason = "predicted " + predicted + " != expected "
							+ a.expected();
					return false;
				}
				if (!predicted.canSurvive(mc.level, pos)) {
					revalidateReason = "cannot survive";
					return false;
				}
				// Reconcile a nonreplaceable target that meanwhile reached a
				// different state instead of placing beside it.
				if (!live.canBeReplaced(context)
						&& !StateRules.canStackSlab(a.expected(), live)
						&& current != null
						&& !StateRules.constructionEquivalent(current.state(), live)) {
					revalidateReason = "target occupied by " + live.getBlock();
					return false;
				}
				if (PlacementSolver.bodyIntersects(pos, predicted, mc.level,
						player.blockPosition())) {
					revalidateReason = "body intersects";
					return false;
				}
				validatedHit = real;
				return true;
			}
			case INTERACT -> {
				if (real.getBlockPos().equals(pos)
						&& real.getDirection() == a.hit().getDirection()) {
					validatedHit = real;
					return true;
				}
				revalidateReason = "interact hit " + real.getBlockPos()
						+ "/" + real.getDirection() + " != " + pos
						+ "/" + a.hit().getDirection();
				return false;
			}
			case FLUID -> {
				if (current == null) {
					revalidateReason = "no current";
					return false;
				}
				Fluid fluid = Planner.isWaterlogTask(current)
						? Fluids.WATER : current.state().getFluidState().getType();
				FluidContainment.Check check = FluidContainment.verify(
						new LevelNavWorld(mc.level), blueprint.blocks()::get, pos, fluid);
				if (!check.sealed()) {
					pause("Fluid-Abdichtung: " + check.reason()
							+ (check.leakAt() != null ? " @" + check.leakAt().toShortString() : ""));
					return false;
				}
				if (real.getBlockPos().equals(pos)
						|| real.getBlockPos().relative(real.getDirection()).equals(pos)) {
					validatedHit = real;
					return true;
				}
				revalidateReason = "fluid hit " + real.getBlockPos()
						+ "/" + real.getDirection() + " != " + pos;
				return false;
			}
			case BREAK_TEMPORARY -> {
				if (real.getBlockPos().equals(pos)) {
					validatedHit = real;
					return true;
				}
				revalidateReason = "break hit mismatch";
				return false;
			}
			case BREAK_REPAIR -> {
				String reason = dev.gentlebuild.plan.RepairPolicy.breakBlockReason(
						pos, mc.level.getBlockState(pos), blueprint,
						new LevelNavWorld(mc.level), ownedBlocks(),
						player.blockPosition());
				if (reason != null || !real.getBlockPos().equals(pos)) {
					revalidateReason = reason != null ? reason
							: "break hit mismatch";
					return false;
				}
				validatedHit = real;
				return true;
			}
		}
		revalidateReason = "unknown kind";
		return false;
	}

	private boolean perform(Minecraft mc, LocalPlayer player, PlacementAction action) {
		if (player.isUsingItem()) {
			return false;
		}
		switch (action.kind()) {
			case PLACE -> {
				// Authoritative pre-click cell for the ownership journal —
				// captured before the client-side prediction applies.
				actionBefore = mc.level.getBlockState(action.target());
				InteractionResult result = mc.gameMode.useItemOn(player,
						InteractionHand.MAIN_HAND, validatedHit);
				if (result.consumesAction()) {
					player.swing(InteractionHand.MAIN_HAND);
					return true;
				}
				return false;
			}
			case INTERACT -> {
				InteractionResult result = mc.gameMode.useItemOn(player,
						InteractionHand.MAIN_HAND, validatedHit);
				if (result.consumesAction()) {
					player.swing(InteractionHand.MAIN_HAND);
					return true;
				}
				return false;
			}
			case FLUID -> {
				// Bucket pour goes through useItem; the real pick must point
				// at the intended cell or a face adjacent to it.
				if (!(player.pick(player.blockInteractionRange(), 1.0f, true)
						instanceof BlockHitResult pick)
						|| pick.getType() == HitResult.Type.MISS) {
					return false;
				}
				boolean hits = pick.getBlockPos().equals(action.target())
						|| (pick.getType() == HitResult.Type.BLOCK
						&& pick.getBlockPos().relative(pick.getDirection())
								.equals(action.target()));
				if (!hits) {
					return false;
				}
				InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
				if (result.consumesAction()) {
					player.swing(InteractionHand.MAIN_HAND);
					return true;
				}
				return false;
			}
			case BREAK_TEMPORARY, BREAK_REPAIR -> {
				InputBroker.holdAttack(true);
				return true;
			}
		}
		return false;
	}

	private void finishTransaction(Minecraft mc) {
		Transaction tx = transaction;
		transaction = null;
		PlacementAction a = tx.action();
		switch (tx.status()) {
			case CONFIRMED -> {
				lastConfirmTick = mc.level.getGameTime();
				softRetries.remove(a.target());
				if (a.kind() == ActionKind.BREAK_REPAIR) {
					InputBroker.holdAttack(false);
					breakSightFailures.remove(a.target());
					breakTimeouts.remove(a.target());
					journal.remove(a.target());
					supports.removed(a.target());
					if (!breakQueue.isEmpty()
							&& breakQueue.peek().mismatch().pos()
									.equals(a.target())) {
						breakQueue.poll();
					}
					rhythm.actionConfirmed(config);
					prevWorkPos = lastWorkPos;
					lastWorkPos = a.target();
					current = null;
					clearPending();
					return;
				}
				if (a.kind() == ActionKind.PLACE) {
					journal.recordConfirmed(a.target(), actionBefore,
							tx.acknowledgedState() != null
									? tx.acknowledgedState()
									: mc.level.getBlockState(a.target()));
				} else if (a.kind() == ActionKind.INTERACT
						&& tx.acknowledgedState() != null) {
					journal.touchOwned(a.target(), tx.acknowledgedState());
				}
				if (pendingScaffoldTarget != null) {
					supports.confirmOwnership(a.target(),
							mc.level.getBlockState(a.target()));
					pendingScaffoldTarget = null;
					pendingAction = null;
					pendingStance = null;
					aimSettledTicks = 0;
					sneakTicks = 0;
					restoreSuspended();
					return;
				} else if (a.kind() == ActionKind.INTERACT && current != null) {
					planner.recordInteractAttempt(a.target());
					BlockState live = mc.level.getBlockState(a.target());
					if (StateRules.finalEquivalent(current.state(), live)) {
						markVerified(current);
						current = null;
					}
				} else if (a.kind() == ActionKind.PLACE && current != null) {
					BlockState live = mc.level.getBlockState(a.target());
					if (StateRules.constructionEquivalent(current.state(), live)) {
						markVerified(current);
						current = null;
					} else if (!StateRules.placementEquivalent(a.expected(), live)) {
						planner.markConflict(a.target(),
								"Server bestaetigte anderen Zustand");
						current = null;
					}
					// else: shell placed; interaction steps follow
				}
				// Only non-auxiliary acknowledged actions count for rhythm.
				rhythm.actionConfirmed(config);
				prevWorkPos = lastWorkPos;
				lastWorkPos = a.target();
				fluidSettle = 0;
				clearPending();
			}
			case MISMATCHED -> {
				if (a.kind() == ActionKind.BREAK_REPAIR
						|| a.kind() == ActionKind.BREAK_TEMPORARY) {
					InputBroker.holdAttack(false);
				}
				// Our own confirmed wrong placement is still ours — record
				// it only when the ack shows the held BlockItem's block and
				// the pre-click cell was air.
				if (a.kind() == ActionKind.PLACE
						&& tx.acknowledgedState() != null
						&& actionBefore != null && actionBefore.isAir()
						&& a.item() instanceof BlockItem bi
						&& tx.acknowledgedState().getBlock() == bi.getBlock()) {
					journal.recordConfirmed(a.target(), actionBefore,
							tx.acknowledgedState());
				}
				if (planner != null) {
					planner.markConflict(a.target(), "Server-Zustand weicht ab");
				}
				current = null;
				clearPending();
			}
			case TIMEOUT -> {
				// A late ack under server lag is not a fault: if the live
				// world already shows the wanted state the action landed —
				// the final audit re-verifies authoritatively. Otherwise
				// re-queue; per-position attempt bounds keep it finite.
				// Only a genuinely silent server (no acks at all for a
				// while) pauses the build.
				if (a.kind() != ActionKind.BREAK_REPAIR) {
					BlockState liveNow = mc.level.getBlockState(a.target());
					boolean landed = StateRules.placementEquivalent(
							a.expected(), liveNow);
					boolean acksFlowing = lastConfirmTick > Long.MIN_VALUE
							&& mc.level.getGameTime() - lastConfirmTick < 400;
					if (landed) {
						// World already matches — no attempt spent; the
						// re-pick sees the satisfied cell and verifies it.
						statusLines.add(0, "Spaete Bestaetigung bei "
								+ a.target().toShortString()
								+ " — weiter");
						current = null;
						clearPending();
						return;
					}
					if (acksFlowing) {
						statusLines.add(0, "Bestaetigung spaet bei "
								+ a.target().toShortString()
								+ " — erneuter Versuch");
						if (current != null) {
							softRequeue(current, "Spaete Bestaetigung");
						}
						current = null;
						clearPending();
						return;
					}
				}
				// Breaks are idempotent: under server lag the world update
				// can arrive after the timeout — if the cell already reads
				// air the work is done; otherwise retry the decision a
				// bounded number of times before flagging it manual.
				if (a.kind() == ActionKind.BREAK_REPAIR) {
					InputBroker.holdAttack(false);
					if (mc.level.getBlockState(a.target()).isAir()
							|| repairBreakSeenAir) {
						breakSightFailures.remove(a.target());
						breakTimeouts.remove(a.target());
						journal.remove(a.target());
						supports.removed(a.target());
						if (!breakQueue.isEmpty() && breakQueue.peek()
								.mismatch().pos().equals(a.target())) {
							breakQueue.poll();
						}
					} else if (breakTimeouts.merge(a.target(), 1,
							Integer::sum) <= 2) {
						// Decision stays at the queue head; the next
						// schedule pass re-solves and retries the break.
					} else {
						breakTimeouts.remove(a.target());
						if (!breakQueue.isEmpty() && breakQueue.peek()
								.mismatch().pos().equals(a.target())) {
							breakQueue.poll();
						}
						statusLines.add(0, "Reparatur manuell "
								+ a.target().toShortString()
								+ ": Server-Bestaetigung ausgeblieben");
					}
					current = null;
					clearPending();
					return;
				}
				pause("Server-Bestaetigung ausgeblieben bei "
						+ a.target().toShortString());
			}
			default -> { }
		}
	}

	private void clearPending() {
		unsafeWaypointRetries = 0;
		if (current == null) {
			triedFaces.clear();
			triedFacesFor = null;
		}
		validatedHit = null;
		pendingAction = null;
		pendingStance = null;
		if (suspendedWork == null) {
			pendingScaffoldTarget = null;
		}
		aimSettledTicks = 0;
		sneakTicks = 0;
		eyeResolved = false;
	}

	// ------------------------------------------------------------------
	// Walking / scaffolding
	// ------------------------------------------------------------------

	private List<BlockPos> stanceCandidates(LocalPlayer player, BlockPos target,
											NavWorld world) {
		List<BlockPos> stances = new ArrayList<>();
		double reach = player.blockInteractionRange();
		int r = (int) Math.ceil(reach) + 1;
		for (int dy = -3; dy <= 3; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos feet = target.offset(dx, dy, dz);
					if (dist2(feet, target) > (reach + 0.5) * (reach + 0.5)) {
						continue;
					}
					// Never stand on a cell that is itself a build target.
					if (reservedCells.contains(feet)) {
						continue;
					}
					if (!isStanceFree(feet, world)) {
						continue;
					}
					stances.add(feet);
				}
			}
		}
		// Current position first when usable, then nearest to the
		// PLAYER — the shortest possible relocation wins, which both
		// builds faster and avoids detours across unreachable cells.
		BlockPos here = player.blockPosition();
		stances.sort(Comparator
				.comparing((BlockPos s) -> !s.equals(here))
				.thenComparingDouble(s -> dist2(s, here)));
		return stances;
	}

	private static double dist2(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dy = a.getY() - b.getY();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dy * dy + dz * dz;
	}

	private boolean stanceBodyFree(BlockPos feet, NavWorld world) {
		net.minecraft.world.level.BlockGetter g =
				world instanceof net.minecraft.world.level.BlockGetter x ? x : null;
		if (g == null) {
			return false;
		}
		return world.getBlockState(feet).getCollisionShape(g, feet).isEmpty()
				&& world.getBlockState(feet).getFluidState().isEmpty()
				&& world.getBlockState(feet.above()).getCollisionShape(g, feet.above()).isEmpty()
				&& world.getBlockState(feet.above()).getFluidState().isEmpty();
	}

	private boolean isStanceFree(BlockPos feet, NavWorld world) {
		net.minecraft.world.level.BlockGetter g =
				world instanceof net.minecraft.world.level.BlockGetter x ? x : null;
		if (g == null || !world.isLoaded(feet) || !world.isLoaded(feet.above())
				|| !world.isLoaded(feet.below())) {
			return false;
		}
		boolean ground = world.getBlockState(feet.below())
				.isFaceSturdy(g, feet.below(), Direction.UP);
		boolean body = world.getBlockState(feet).getCollisionShape(g, feet).isEmpty()
				&& world.getBlockState(feet).getFluidState().isEmpty();
		boolean head = world.getBlockState(feet.above()).getCollisionShape(g, feet.above()).isEmpty()
				&& world.getBlockState(feet.above()).getFluidState().isEmpty();
		return ground && body && head;
	}

	/** Record the stance the eye-resolve just failed from, keyed to the
	 *  cell being solved so a different target starts fresh. */
	private void noteFailedStance(BlockPos key) {
		if (failedStancesFor == null || !failedStancesFor.equals(key)) {
			failedStances.clear();
			failedStancesFor = key;
		}
		if (pendingStance != null) {
			failedStances.add(pendingStance);
		}
	}

	/** Drop stances that already failed an eye-resolve for this key so
	 *  a retry walks to a genuinely different position. */
	private List<BlockPos> filterFailedStances(List<BlockPos> stances,
												BlockPos key) {
		if (failedStancesFor == null || !failedStancesFor.equals(key)) {
			failedStances.clear();
			failedStancesFor = key;
			return stances;
		}
		if (failedStances.isEmpty()) {
			return stances;
		}
		List<BlockPos> out = new ArrayList<>(stances);
		out.removeAll(failedStances);
		return out;
	}

	/**
	 * A single missed ray can poison the per-target exclusion sets — for
	 * facing-locked blocks (e.g. a downward hopper) the ONLY legal face
	 * would stay burned forever. Clear them when a solve comes back empty;
	 * genuinely unsolvable targets still terminate via the bounded
	 * per-position attempt counter in {@link #failOrRequeue}.
	 */
	private void resetExclusions(BlockPos key) {
		if (key.equals(triedFacesFor)) {
			triedFaces.clear();
			triedFacesFor = null;
		}
		if (key.equals(failedStancesFor)) {
			failedStances.clear();
			failedStancesFor = null;
		}
	}

	private boolean atStance(LocalPlayer player, BlockPos stance) {
		return player.blockPosition().equals(stance)
				&& player.position().distanceToSqr(Vec3.atBottomCenterOf(stance)) < 0.05;
	}

	private void walkTick(Minecraft mc, LocalPlayer player) {
		// A scaffold action takes over the normal aim/revalidate/transaction
		// path; the original work is suspended in suspendedWork.
		if (path == null || !path.found()) {
			planPath(mc, player);
			if (path == null || !path.found()) {
				// Try the next stance candidate before giving up.
				if (stanceIndex >= 0 && stanceIndex + 1 < stanceFallback.size()) {
					stanceIndex++;
					pendingStance = stanceFallback.get(stanceIndex);
					path = null;
					return;
				}
				failOrRequeue(current, "Kein Weg zur Position");
				return;
			}
		}
		// The stance was solved against possibly stale block updates: check
		// it is still a usable standing cell before driving into it. A stance
		// whose ground is a PLANNED scaffold cell is fine too — the cell is
		// meant to arrive via the scaffold plan; only body and head must be
		// free. Otherwise take the next candidate (or re-solve).
		boolean stanceOk = pendingStance == null
				|| pendingStance.equals(player.blockPosition())
				|| isStanceFree(pendingStance, new LevelNavWorld(mc.level))
				|| (supports.isPlanned(pendingStance.below())
						&& stanceBodyFree(pendingStance, new LevelNavWorld(mc.level)));
		if (!stanceOk) {
			noteFailedStance(pendingScaffoldTarget != null
					? pendingScaffoldTarget.pos()
					: pendingAction != null ? pendingAction.target()
							: pendingStance);
			if (stanceIndex >= 0 && stanceIndex + 1 < stanceFallback.size()) {
				stanceIndex++;
				pendingStance = stanceFallback.get(stanceIndex);
				path = null;
				return;
			}
			pendingStance = null;
			path = null;
			if (pendingScaffoldTarget != null) {
				supports.unplan(pendingScaffoldTarget.pos());
				pendingScaffoldTarget = null;
				pendingAction = null;
				restoreSuspended();
			} else {
				failOrRequeue(current, "Position nicht mehr begehbar");
			}
			return;
		}
		List<BlockPos> waypoints = path.waypoints();
		if (pathIndex >= waypoints.size()) {
			// Path exhausted but not centered: drive toward the stance center
			// until inside the waypoint threshold.
			Vec3 goal = Vec3.atBottomCenterOf(pendingStance);
			Vec3 pos = player.position();
			if (pos.distanceToSqr(goal) < WAYPOINT_DIST_SQ) {
				releaseMoveOnly();
				return;
			}
			driveToward(player, goal, goal, true);
			return;
		}
		BlockPos next = waypoints.get(pathIndex);
		// Waypoint scaffold cells: suspend the build work, place the first
		// missing cell via the shared action path.
		List<BlockPos> scaffold = path.scaffoldPlan().get(next);
		if (scaffold != null) {
			List<BlockPos> missing = scaffold.stream()
					.filter(c -> mc.level.getBlockState(c).isAir())
					.toList();
			if (!missing.isEmpty() && pendingScaffoldTarget == null) {
				for (BlockPos cell : missing) {
					supports.planCell(cell);
				}
				suspendForScaffold(mc, player, missing.get(0));
				return;
			}
		}
		// Revalidate the upcoming waypoint against the live world.
		Navigator nav = new Navigator(new LevelNavWorld(mc.level), config.maxPlanNodes,
				config.allowTemporarySupports, reservedCells, bannedSupports);
		if (!nav.isSafeStance(next)) {
			releaseMoveOnly();
			path = null; // replan from the live world
			if (++unsafeWaypointRetries > MAX_UNSAFE_WAYPOINT_RETRIES) {
				unsafeWaypointRetries = 0;
				pause("Wegpunkt nicht mehr sicher");
			}
			return;
		}
		Vec3 goal = Vec3.atBottomCenterOf(next);
		Vec3 pos = player.position();
		if (pos.distanceToSqr(goal) < WAYPOINT_DIST_SQ) {
			// Arrived. On a straight run keep the forward input and drive
			// directly toward the next waypoint instead of braking to a
			// full stop in every cell.
			pathIndex++;
			stallTicks = 0;
			unsafeWaypointRetries = 0;
			if (pathIndex < waypoints.size() && collinearRun(waypoints, pathIndex)
					&& nav.isSafeStance(waypoints.get(pathIndex))) {
				next = waypoints.get(pathIndex);
				goal = Vec3.atBottomCenterOf(next);
				boolean brake = pathIndex == waypoints.size() - 1
						|| !nav.isSafeStance(waypoints.get(pathIndex + 1));
				driveToward(player, goal, gazeAhead(waypoints, pathIndex), brake);
				return;
			}
			releaseMoveOnly();
			return;
		}
		// Brake on approach to the final waypoint.
		boolean brake = pathIndex == waypoints.size() - 1
				|| !nav.isSafeStance(waypoints.get(pathIndex + 1));
		driveToward(player, goal, gazeAhead(waypoints, pathIndex), brake);
	}

	/** Remaining physical path distance: current goal plus the rest of
	 * the queued waypoints; falls back to direct distance when no path. */
	private double remainingPath(Vec3 pos, Vec3 goal) {
		double d = pos.distanceTo(goal);
		if (path == null || !path.found() || pathIndex >= path.waypoints().size()) {
			return d;
		}
		List<BlockPos> wps = path.waypoints();
		Vec3 prev = Vec3.atBottomCenterOf(wps.get(pathIndex));
		for (int i = pathIndex + 1; i < wps.size(); i++) {
			Vec3 c = Vec3.atBottomCenterOf(wps.get(i));
			d += prev.distanceTo(c);
			prev = c;
		}
		return d;
	}

	/** Gaze lookahead along SAME-Y, collinear cardinal segments only —
	 * stops at the first corner or step, max 2 cells ahead. Pure look
	 * target; it can never change the physical path. */
	private static Vec3 gazeAhead(List<BlockPos> waypoints, int i) {
		int end = i;
		for (int j = i + 1; j < waypoints.size() && j <= i + 2; j++) {
			BlockPos a = waypoints.get(j - 1);
			BlockPos b = waypoints.get(j);
			if (b.getY() != a.getY()) {
				break;
			}
			int dx = Integer.signum(b.getX() - a.getX());
			int dz = Integer.signum(b.getZ() - a.getZ());
			if (dx == 0 && dz == 0) {
				break;
			}
			if (j > i + 1) {
				BlockPos pa = waypoints.get(j - 2);
				BlockPos pb = waypoints.get(j - 1);
				if (Integer.signum(pb.getX() - pa.getX()) != dx
						|| Integer.signum(pb.getZ() - pa.getZ()) != dz) {
					break;
				}
			}
			end = j;
		}
		return Vec3.atCenterOf(waypoints.get(end).above());
	}

	/** Waypoint i lies on a straight, same-level continuation of the path. */
	private static boolean collinearRun(List<BlockPos> waypoints, int i) {
		if (i < 1) {
			return false;
		}
		BlockPos prev = waypoints.get(i - 1);
		BlockPos cur = waypoints.get(i);
		BlockPos nxt = i + 1 < waypoints.size() ? waypoints.get(i + 1) : null;
		if (nxt == null || cur.getY() != prev.getY() || cur.getY() != nxt.getY()) {
			return false;
		}
		int dx1 = Integer.signum(cur.getX() - prev.getX());
		int dz1 = Integer.signum(cur.getZ() - prev.getZ());
		int dx2 = Integer.signum(nxt.getX() - cur.getX());
		int dz2 = Integer.signum(nxt.getZ() - cur.getZ());
		return dx1 == dx2 && dz1 == dz2 && (dx1 != 0 || dz1 != 0);
	}

	private void driveToward(LocalPlayer player, Vec3 goal, Vec3 gaze,
							 boolean brake) {
		Vec3 pos = player.position();
		double goalYaw = Math.toDegrees(Math.atan2(-(goal.x - pos.x), goal.z - pos.z));
		double gazeYaw = Math.toDegrees(Math.atan2(-(gaze.x - pos.x), gaze.z - pos.z));
		// The lookahead is pure gaze and must never steer the body off the
		// waypoint line: if it points away from the actual move direction
		// (off-line start, turns) fall back to the immediate goal.
		if (Math.abs(AngleMath.shortestDelta(goalYaw, gazeYaw)) > 12.0) {
			gaze = goal;
			gazeYaw = goalYaw;
		}
		double remaining = remainingPath(pos, goal);
		motion.walkToward(player, gaze, remaining);
		motion.tick(player, config);
		double headingError = Math.abs(AngleMath.shortestDelta(player.getYRot(), gazeYaw));
		double goalError = Math.abs(AngleMath.shortestDelta(player.getYRot(), goalYaw));
		boolean stepUp = goal.y > player.blockPosition().getY() + 0.5;
		boolean slow = brake && pos.distanceToSqr(goal) < 0.8 * 0.8
				&& player.getDeltaMovement().horizontalDistanceSqr() > 0.0025;
		// Vanilla-legit sprint on long flat legs: only while aligned, on the
		// ground, outside the 2-block brake window and with no step-up ahead.
		boolean sprint = config.sprintOnLongWalks && player.onGround()
				&& !stepUp && remaining > 4.5
				&& !(brake && pos.distanceToSqr(goal) < 2.0 * 2.0);
		if (headingError < HEADING_TOLERANCE_DEG
				&& goalError < HEADING_TOLERANCE_DEG && !slow) {
			InputBroker.setDesired(new Input(true, false, false, false,
					stepUp && player.onGround(), false, sprint));
		} else {
			InputBroker.setDesired(Input.EMPTY);
		}
		if (lastProgressPos == null || pos.distanceToSqr(lastProgressPos) > 0.01) {
			lastProgressPos = pos;
			stallTicks = 0;
		} else if (++stallTicks > STALL_TICKS) {
			path = null;
			pause("Bewegung steckt fest: pos=" + player.position()
					+ " goal=" + goal + " yaw=" + player.getYRot()
					+ " err=" + headingError + " onGround=" + player.onGround()
					+ " desired=" + InputBroker.desired()
					+ " controlling=" + InputBroker.isControlling()
					+ " input=" + player.input.keyPresses
					+ " moveVec=" + player.input.getMoveVector());
		}
	}

	/** Suspend the current placement and solve a scaffold cell placement. */
	private void suspendForScaffold(Minecraft mc, LocalPlayer player, BlockPos cell) {
		Item item = scaffoldItem();
		if (item == null) {
			materialWait = 0;
			pause("Kein Geruestblock im Inventar");
			return;
		}
		InventoryHelper.EquipResult equip = InventoryHelper.equip(player, item, config);
		if (equip != InventoryHelper.EquipResult.READY) {
			equipFailed(equip, "Kein Geruestblock im Inventar");
			return;
		}
		materialWait = 0;
		suspendedWork = new WorkPlan(current, pendingAction, pendingStance,
				path, pathIndex);
		BlockState scaffoldState = scaffoldState(item);
		pendingScaffoldTarget = new BuildTarget(cell, scaffoldState, Phase.STRUCTURE);
		List<BlockPos> stances = filterFailedStances(
				stanceCandidates(player, cell, new LevelNavWorld(mc.level)), cell);
		PlacementSolver.SolvedAction solved = solver.solve(pendingScaffoldTarget,
				item, stances, mc.level, player, player.blockInteractionRange());
		if (solved == null) {
			// The cell may be occluded by blocks placed since the path was
			// computed — ban it for scaffolding so the navigator routes
			// around it instead of demanding the same impossible cell.
			bannedSupports.add(cell);
			supports.unplan(cell);
			pendingScaffoldTarget = null;
			restoreSuspended();
			path = null;
			if (scaffoldSolveFailures.merge(cell, 1, Integer::sum) < 3) {
				return;
			}
			scaffoldSolveFailures.remove(cell);
			pause("Manuelle Stuetze noetig bei " + cell.toShortString());
			return;
		}
		stanceFallback = stances;
		stanceIndex = stances.indexOf(solved.stance());
		pendingStance = solved.stance();
		pendingAction = new PlacementAction(cell, solved.predicted(),
				solved.action().hit(), item, solved.action().sneak(),
				ActionKind.PLACE);
		path = null; // walking resumes via the shared walk/aim path
	}

	/** Restore suspended build work after a scaffold cell confirmed. */
	private void restoreSuspended() {
		if (suspendedWork != null) {
			current = suspendedWork.target();
			pendingAction = suspendedWork.action();
			pendingStance = suspendedWork.stance();
			path = suspendedWork.path();
			pathIndex = suspendedWork.pathIndex();
			// The scaffold's own eye resolve must not satisfy the original work's final resolve.
			eyeResolved = false;
			aimSettledTicks = 0;
			sneakTicks = 0;
			suspendedWork = null;
		}
	}

	private void planPath(Minecraft mc, LocalPlayer player) {
		Navigator nav = new Navigator(new LevelNavWorld(mc.level), config.maxPlanNodes,
				config.allowTemporarySupports && (pendingAction == null
						|| pendingAction.kind() != ActionKind.BREAK_REPAIR),
				reservedCells, bannedSupports);
		path = nav.findPath(player.blockPosition(), Set.of(pendingStance));
		pathIndex = 0;
	}

	private Item scaffoldItem() {
		var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
				.getValue(net.minecraft.resources.Identifier.parse(config.scaffoldBlock));
		// Only full-cube, non-falling, inert scaffold blocks are acceptable:
		// no entity blocks, no redstone sources, no TNT/observers/pistons.
		if (block != null && block != Blocks.AIR && block.asItem() != Items.AIR
				&& !(block instanceof net.minecraft.world.level.block.FallingBlock)
				&& !(block instanceof net.minecraft.world.level.block.BaseEntityBlock)
				&& !(block instanceof net.minecraft.world.level.block.TntBlock)
				&& !block.defaultBlockState().isSignalSource()
				&& block.defaultBlockState().isCollisionShapeFullBlock(
						new LevelNavWorld(Minecraft.getInstance().level),
						BlockPos.ZERO)) {
			return block.asItem();
		}
		return Items.DIRT;
	}

	private BlockState scaffoldState(Item item) {
		if (item instanceof net.minecraft.world.item.BlockItem bi) {
			return bi.getBlock().defaultBlockState();
		}
		return Blocks.DIRT.defaultBlockState();
	}

	// ------------------------------------------------------------------
	// Completion
	// ------------------------------------------------------------------

	/** Enter a scheduled pause: release movement and aim; the view stays where it is. */
	private void enterRest() {
		InputBroker.releaseAll();
		motion.reset();
		state = RunState.RESTING;
	}

	/**
	 * A rest spot must be genuinely safe: on ground, nearly still, not
	 * swimming/fall-flying, and standing on sturdy non-hazardous floor —
	 * feet cell plus all four horizontal neighbors checked so a one-block
	 * bridge or ledge edge never counts as a rest spot.
	 */
	private boolean safeRestSpot(Minecraft mc, LocalPlayer player) {
		if (!player.onGround() || player.isFallFlying() || player.isPassenger()
				|| player.getDeltaMovement().horizontalDistanceSqr() > 0.0004) {
			return false;
		}
		NavWorld world = new LevelNavWorld(mc.level);
		BlockPos feet = player.blockPosition();
		if (!world.getBlockState(feet).getFluidState().isEmpty()
				|| !world.getBlockState(feet.above()).getFluidState().isEmpty()) {
			return false;
		}
		Navigator nav = new Navigator(world, config.maxPlanNodes,
				config.allowTemporarySupports, reservedCells);
		for (BlockPos p : List.of(feet, feet.north(), feet.south(),
				feet.east(), feet.west())) {
			if (!nav.isSafeStance(p)) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Final audit + bounded safe repairs
	// ------------------------------------------------------------------

	/** Start a fresh full audit of the global blueprint (never resumes a
	 * half-swept verifier or a stale repair planner). */
	private void beginFinalAudit() {
		if (checkpointScope != null) {
			enterAudit(new FinalVerifier(checkpointScope, FinalVerifier.Mode.CONSTRUCTION));
			return;
		}
		enterAudit(new FinalVerifier(blueprint));
		fullAuditRuns++;
	}

	private void enterAudit(FinalVerifier audit) {
		Minecraft mc = Minecraft.getInstance();
		InputBroker.releaseAll();
		motion.reset();
		if (mc.gameMode != null && mc.gameMode.isDestroying()) {
			mc.gameMode.stopDestroyBlock();
		}
		current = null;
		clearPending();
		path = null;
		targetPool.clear();
		suspendedWork = null;
		verifier = audit;
		state = RunState.VERIFYING;
	}

	/**
	 * Record a verified task for the construction checkpoint. Repairs and
	 * active checkpoints never feed the batch; a cell with an unverified
	 * same-phase sibling (double-slab half) waits for its final task.
	 */
	private void markVerified(BuildTarget target) {
		planner.markVerified(target);
		if (repairMode || checkpointScope != null) {
			return;
		}
		BlockPos pos = target.pos();
		for (BuildTarget t : planner.targetsAt(pos)) {
			if (t.phase() == target.phase() && !planner.isVerified(t)) {
				return;
			}
		}
		BlockState completed = planner.isVerified(pos)
				? blueprint.blocks().getOrDefault(pos, target.state())
				: target.state();
		checkpoint.record(new BuildTarget(pos, completed, target.phase()));
	}

	private void startCheckpointAudit() {
		Map<BlockPos, BlockState> cells = checkpoint.snapshot();
		checkpointScope = new Blueprint(blueprint.name() + "/checkpoint", cells,
				Set.of(), 0, fingerprint);
		savedCheckpointPlanner = planner;
		checkpointRepairRound = 0;
		checkpointRuns++;
		checkpoint.clear();
		statusLines.clear();
		statusLines.add("Zwischenpruefung: " + cells.size() + " Bloecke");
		enterAudit(new FinalVerifier(checkpointScope, FinalVerifier.Mode.CONSTRUCTION));
	}

	/** A clean construction re-check: resume the saved main planner, never COMPLETE. */
	private void finishCheckpoint() {
		verifyReset();
		clearRepairRun();
		planner = savedCheckpointPlanner;
		savedCheckpointPlanner = null;
		checkpointScope = null;
		checkpointRepairRound = 0;
		motion.reset();
		state = RunState.WALKING;
		statusLines.clear();
		statusLines.add("Baue...");
	}

	/**
	 * Pause, stop, resume and explicit verify discard the pending batch. An
	 * active checkpoint also gives back the main planner and its scoped repair
	 * queues; the discarded cells are simply rediscovered by the next audit.
	 */
	private void abandonCheckpointScope() {
		checkpoint.clear();
		if (checkpointScope == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode != null && mc.gameMode.isDestroying()) {
			mc.gameMode.stopDestroyBlock();
		}
		verifyReset();
		clearRepairRun();
		planner = savedCheckpointPlanner;
		savedCheckpointPlanner = null;
		checkpointScope = null;
		checkpointRepairRound = 0;
	}

	private void resetCheckpoints() {
		checkpoint.clear();
		checkpointScope = null;
		savedCheckpointPlanner = null;
		checkpointRepairRound = 0;
		checkpointRuns = 0;
	}

	private void clearRepairRun() {
		repairMode = false;
		repairPlanner = null;
		breakQueue.clear();
		roundApproved.clear();
		repairSettle = 0;
	}

	private void tickAudit(Minecraft mc, LocalPlayer player) {
		NavWorld world = new LevelNavWorld(mc.level);
		var status = verifier.tick(world,
				p -> supports.owns(p) && supports.stillExpected(world, p),
				config.settleTicks);
		switch (status) {
			case SWEEPING, SETTLING -> { }
			case UNLOADED -> {
				String at = verifier.differences().stream()
						.filter(m -> m.kind()
								== dev.gentlebuild.model.VerificationMismatch.Kind.UNLOADED)
						.map(m -> m.pos().toShortString()).findFirst().orElse("?");
				verifyReset();
				pause("Verifikation: Chunk nicht geladen bei " + at);
			}
			case MISMATCH -> onAuditMismatch(mc, player);
			case COMPLETE -> {
				if (checkpointScope != null) {
					finishCheckpoint();
					return;
				}
				for (BlockPos pos : repairApprovedAll) {
					BlockState exp = blueprint.blocks().get(pos);
					if (exp != null && StateRules.finalEquivalent(exp,
							world.getBlockState(pos))) {
						repairedPositions.add(pos);
					}
				}
				List<String> notes = verifier.runtimeNotes();
				verifyReset();
				state = RunState.COMPLETE;
				releaseInputs();
				repairSummary = repairedPositions.isEmpty()
						? "Bauplan geprueft"
						: "Bauplan geprueft, " + repairedPositions.size()
								+ " Positionen korrigiert";
				statusLines.add(0, repairSummary
						+ (supports.ownedCount() > 0
								? "; " + supports.ownedCount()
										+ " temporaere Stuetzen verbleiben"
								: "")
						+ (blueprint.entities() > 0 || !blueprint.blockEntityData().isEmpty()
						? "; manuelle Entities/NBT verbleiben" : ""));
				for (String n : notes) {
					statusLines.add(0, "Info: " + n);
				}
			}
		}
	}

	/**
	 * Classify the audit differences, partition into safe automatic repairs
	 * and manual issues, then either run one bounded repair round or pause
	 * with the full accounting. Never completes on mismatches.
	 */
	private void onAuditMismatch(Minecraft mc, LocalPlayer player) {
		List<dev.gentlebuild.model.VerificationMismatch> diffs =
				verifier.differences();
		int total = verifier.mismatchCount();
		verifyReset();
		NavWorld world = new LevelNavWorld(mc.level);
		Map<BlockPos, BlockState> owned = ownedBlocks();
		List<String> manual = new ArrayList<>();
		List<dev.gentlebuild.plan.RepairPolicy.Decision> approved =
				new ArrayList<>();

		for (dev.gentlebuild.model.VerificationMismatch m : diffs) {
			var d = dev.gentlebuild.plan.RepairPolicy.classify(m, blueprint,
					world, owned, player.blockPosition(),
					config.fluidsEnabled, config.activationEnabled);
			if (d.kind() == dev.gentlebuild.plan.RepairPolicy.Kind.MANUAL) {
				manual.add(m.pos().toShortString() + " [" + m.kind() + "] "
						+ d.reason());
				continue;
			}
			if (approved.size() >= MAX_REPAIR_TARGETS_PER_ROUND) {
				manual.add(m.pos().toShortString()
						+ ": Rundelimit ueberschritten");
				continue;
			}
			if (repairAttempts.getOrDefault(m.pos(), 0) >= MAX_ATTEMPTS_PER_POS) {
				manual.add(m.pos().toShortString()
						+ ": Reparaturversuche erschoepft");
				continue;
			}
			// Material preflight BEFORE any break: a REPLACE/FILL whose
			// replacement item is missing must not destroy anything.
			if ((d.kind() == dev.gentlebuild.plan.RepairPolicy.Kind.FILL
					|| d.kind() == dev.gentlebuild.plan.RepairPolicy.Kind.REPLACE)
					&& !m.expected().isAir()
					&& !(m.expected().getBlock() instanceof LiquidBlock)) {
				Item item = ItemResolver.itemFor(m.expected());
				if (item == null) {
					manual.add(m.pos().toShortString()
							+ ": Kein Item fuer " + m.expected().getBlock());
					continue;
				}
				if (InventoryHelper.countItem(player, item) == 0) {
					manual.add(m.pos().toShortString() + ": Material fehlt: "
							+ new ItemStack(item).getHoverName().getString());
					continue;
				}
			}
			repairAttempts.merge(m.pos(), 1, Integer::sum);
			approved.add(d);
		}

		// Progress check: after a repair round, at least one approved cell
		// must have reached its final state — otherwise stop instead of
		// burning the remaining rounds on an identical outcome.
		boolean scoped = checkpointScope != null;
		boolean progressed = false;
		for (BlockPos pos : roundApproved) {
			BlockState exp = scoped
					? checkpointScope.blocks().get(pos) : blueprint.blocks().get(pos);
			if (exp != null && world.isLoaded(pos) && (scoped
					? StateRules.constructionEquivalent(exp, world.getBlockState(pos))
					: StateRules.finalEquivalent(exp, world.getBlockState(pos)))) {
				progressed = true;
				repairedPositions.add(pos);
			}
		}
		roundApproved.clear();

		reportManual(total, manual);
		if (!config.automaticRepairs) {
			pause("Verifikation: " + total + " Abweichungen (automatische "
					+ "Reparatur aus)");
			return;
		}
		int round = scoped ? checkpointRepairRound : repairRound;
		if (approved.isEmpty() || (round > 0 && !progressed)) {
			pause("Verifikation: " + total + " Abweichungen, keine weitere "
					+ "automatische Reparatur moeglich");
			return;
		}
		if (round >= config.maxRepairRounds) {
			pause("Reparatur-Limit erreicht (" + total + " Abweichungen)");
			return;
		}
		if (scoped) {
			checkpointRepairRound++;
		} else {
			repairRound++;
		}
		startRepairRound(approved);
		statusLines.add(0, "Reparatur " + (round + 1) + "/"
				+ config.maxRepairRounds + ": " + approved.size()
				+ " Positionen automatisch, " + manual.size() + " manuell");
	}

	private void reportManual(int total, List<String> manual) {
		for (int i = 0; i < manual.size() && i < 8; i++) {
			statusLines.add(0, "Verifikation: " + manual.get(i));
		}
		if (manual.size() > 8 || total > manual.size()) {
			statusLines.add(0, "Verifikation: " + total
					+ " Abweichungen gesamt (" + manual.size() + " manuell)");
		}
		repairSummary = "Abweichungen: " + total
				+ ", manuell: " + manual.size();
	}

	/**
	 * One bounded repair round: approved breaks are queued first, then a
	 * repair-subset planner fills/adjusts through the ordinary action path.
	 * The global blueprint stays authoritative for fluid bounds and the
	 * re-audit.
	 */
	private void startRepairRound(
			List<dev.gentlebuild.plan.RepairPolicy.Decision> approved) {
		// Deterministic order: earlier phases and lower layers first.
		approved.sort(Comparator
				.comparingInt((dev.gentlebuild.plan.RepairPolicy.Decision d) ->
						StateRules.phaseOf(d.mismatch().expected()).ordinal())
				.thenComparingInt(d -> d.mismatch().pos().getY())
				.thenComparingInt(d -> d.mismatch().pos().getX())
				.thenComparingInt(d -> d.mismatch().pos().getZ()));
		Map<BlockPos, BlockState> subset = new java.util.LinkedHashMap<>();
		breakQueue.clear();
		roundApproved.clear();
		for (var d : approved) {
			var m = d.mismatch();
			roundApproved.add(m.pos());
			repairApprovedAll.add(m.pos());
			if (!m.expected().isAir()) {
				subset.put(m.pos(), m.expected());
			}
			if (d.kind() == dev.gentlebuild.plan.RepairPolicy.Kind.REMOVE
					|| d.kind() == dev.gentlebuild.plan.RepairPolicy.Kind.REPLACE) {
				breakQueue.addLast(d);
			}
		}
		repairPlanner = new Planner(new Blueprint(blueprint.name() + "/repair",
				Map.copyOf(subset), Set.of(), 0, fingerprint));
		planner = repairPlanner;
		repairMode = true;
		repairSettle = 0;
		state = RunState.WALKING;
	}

	/** Serve the next queued repair break at a clean task boundary. */
	private void scheduleNextBreak(Minecraft mc, LocalPlayer player) {
		while (!breakQueue.isEmpty()) {
			var d = breakQueue.peek();
			if (solveRepairBreak(mc, player, d)) {
				return;
			}
			statusLines.add(0, "Reparatur uebersprungen "
					+ d.mismatch().pos().toShortString() + ": "
					+ revalidateReason);
			breakQueue.poll();
		}
	}

	/**
	 * Solve a repair break: policy re-check, correct-tool preflight, real
	 * visible face from a safe stance (never standing on the block itself).
	 */
	private boolean solveRepairBreak(Minecraft mc, LocalPlayer player,
									 dev.gentlebuild.plan.RepairPolicy.Decision d) {
		BlockPos pos = d.mismatch().pos();
		NavWorld world = new LevelNavWorld(mc.level);
		BlockState live = mc.level.getBlockState(pos);
		String reason = dev.gentlebuild.plan.RepairPolicy.breakBlockReason(
				pos, live, blueprint, world, ownedBlocks(),
				player.blockPosition());
		if (reason != null) {
			revalidateReason = reason;
			return false;
		}
		if (live.requiresCorrectToolForDrops()) {
			var inv = player.getInventory();
			Item tool = null;
			for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) {
				ItemStack st = inv.getItem(i);
				if (!st.isEmpty() && st.isCorrectToolForDrops(live)) {
					tool = st.getItem();
					break;
				}
			}
			if (tool == null) {
				revalidateReason = "Werkzeug fehlt";
				return false;
			}
			InventoryHelper.EquipResult equip = InventoryHelper.equip(player, tool, config);
			if (equip == InventoryHelper.EquipResult.WAITING) {
				return true; // same queue head is retried next tick
			}
			if (equip == InventoryHelper.EquipResult.BLOCKED) {
				pause(INVENTORY_BLOCKED_REASON);
				return true;
			}
			if (equip == InventoryHelper.EquipResult.MISSING) {
				revalidateReason = "Werkzeug fehlt";
				return false;
			}
		}
		List<BlockPos> stances = stanceCandidates(player, pos, world);
		Set<BlockPos> removals = new LinkedHashSet<>();
		for (var queued : breakQueue) {
			removals.add(queued.mismatch().pos());
		}
		// Never use a block awaiting removal as our repair footing/body space.
		stances.removeIf(s -> removals.contains(s.below())
				|| removals.contains(s) || removals.contains(s.above()));
		Navigator nav = new Navigator(world, config.maxPlanNodes, false,
				reservedCells, bannedSupports);
		double reach = player.blockInteractionRange();
		for (BlockPos stance : stances) {
			if (!nav.isSafeStance(stance)) {
				continue;
			}
			Vec3 eye = Vec3.atBottomCenterOf(stance).add(0, EYE_HEIGHT, 0);
			for (BlockHitResult real : PlacementSolver.visibleFaces(
					mc.level, pos, eye, player, reach)) {
				PathResult route = nav.findPath(player.blockPosition(),
						Set.of(stance));
				if (!route.found()) {
					break; // Another face does not make this stance reachable.
				}
				pendingScaffoldTarget = null;
				pendingStance = stance;
				BlockState exp = blueprint.blocks().getOrDefault(pos,
						Blocks.AIR.defaultBlockState());
				current = new BuildTarget(pos, exp, Phase.STRUCTURE);
				pendingAction = new PlacementAction(pos,
						Blocks.AIR.defaultBlockState(), real, null, false,
						ActionKind.BREAK_REPAIR);
				stanceFallback = List.of(stance);
				stanceIndex = 0;
				path = route;
				pathIndex = 0;
				return true;
			}
		}
		revalidateReason = "Keine sichere erreichbare Abbauposition";
		return false;
	}

	/**
	 * While a BREAK_REPAIR transaction is pending: re-run the full safety
	 * policy, aim, verify the real pick and keep sending damage packets.
	 * Returns false when the environment changed — the audit decides next.
	 */
	private boolean driveRepairBreak(Minecraft mc, LocalPlayer player) {
		BlockPos pos = transaction.action().target();
		BlockState live = mc.level.getBlockState(pos);
		if (live.isAir()) {
			repairBreakSeenAir = true;
			return true; // authoritative AIR settles via the transaction
		}
		String reason = dev.gentlebuild.plan.RepairPolicy.breakBlockReason(
				pos, live, blueprint, new LevelNavWorld(mc.level),
				ownedBlocks(), player.blockPosition());
		if (reason != null) {
			revalidateReason = reason;
			return false;
		}
		// Aim at the solved face hit — visible from this stance — rather
		// than the block center, which can sit behind a neighbor edge.
		Vec3 center = transaction.action().hit() != null
				? transaction.action().hit().getLocation()
				: Vec3.atCenterOf(pos);
		motion.aimAt(player, center);
		motion.tick(player, config);
		if (!motion.isOnTarget(player)) {
			InputBroker.holdAttack(false);
			return true;
		}
		if (!(player.pick(player.blockInteractionRange(), 1.0f, false)
				instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK
				|| !hit.getBlockPos().equals(pos)) {
			revalidateReason = "Sicht auf Reparaturziel verloren";
			return false;
		}
		InputBroker.holdAttack(true);
		return true;
	}

	// ------------------------------------------------------------------
	// Cleanup (explicit command, reverse order, normal breaking)
	// ------------------------------------------------------------------

	public void startCleanup() {
		if (supports.ownedCount() == 0) {
			statusLines.add(0, "Keine temporaeren Stuetzen");
			return;
		}
		if (isRunning()) {
			stop();
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		cleanupQueue.clear();
		cleanupQueue.addAll(supports.cleanupOrder(new LevelNavWorld(mc.level),
				pos -> blueprint != null && blueprint.blocks().containsKey(pos)
						&& !blueprint.blocks().get(pos).isAir(),
				mc.player.blockPosition()));
		cleanupMode = true;
		cleanupTx = null;
		state = RunState.WALKING;
	}

	private void tickCleanup(Minecraft mc, LocalPlayer player) {
		// A pending air-confirmation must not block continueDestroyBlock —
		// damage stages need packets every tick.
		if (cleanupTx != null) {
			if (cleanupTx.tick(pos -> mc.level.getBlockState(pos))) {
				AckTracker.remove(cleanupTx);
				InputBroker.holdAttack(false);
				if (cleanupTx.status() == Transaction.Status.CONFIRMED) {
					supports.removed(cleanupTx.action().target());
					cleanupQueue.poll();
				} else if (cleanupTx.status() == Transaction.Status.TIMEOUT) {
					pause("Cleanup-Timeout bei "
							+ cleanupTx.action().target().toShortString());
					AckTracker.remove(cleanupTx);
					cleanupTx = null;
					return;
				} else {
					cleanupQueue.poll();
					statusLines.add(0, "Stuetze: Server-Zustand weicht ab");
				}
				cleanupTx = null;
			}
		}
		while (!cleanupQueue.isEmpty()) {
			BlockPos pos = cleanupQueue.peek();
			NavWorld world = new LevelNavWorld(mc.level);
			BlockState live = world.getBlockState(pos);
			if (live.isAir()) {
				if (cleanupTx == null) {
					// Require authoritative air: wait for the block-update ack.
					cleanupTx = new Transaction(
							new PlacementAction(pos, Blocks.AIR.defaultBlockState(),
									new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP,
											pos, false),
									null, false, ActionKind.BREAK_TEMPORARY),
							config.confirmationTimeoutTicks);
					AckTracker.submit(cleanupTx);
				}
				return;
			}
			// Live protection each tick: never break a cell that is not our
			// exact recorded support, supports the player right now, or a
			// neighboring final component depends on it.
			if (!supports.owns(pos) || !supports.stillExpected(world, pos)) {
				cancelCleanupTx(pos);
				statusLines.add(0, "Stuetze veraendert, bleibt: " + pos.toShortString());
				supports.removed(pos);
				cleanupQueue.poll();
				continue;
			}
			if (pos.equals(player.blockPosition().below())) {
				cancelCleanupTx(pos);
				cleanupQueue.poll();
				statusLines.add(0, "Stuetze unter Spieler uebersprungen: "
						+ pos.toShortString());
				continue;
			}
			boolean protectedNeighbor = false;
			for (Direction dir : Direction.values()) {
				BlockPos n = pos.relative(dir);
				if (blueprint != null) {
					BlockState exp = blueprint.blocks().get(n);
					if (exp != null && !exp.isAir()) {
						protectedNeighbor = true; // conservative: keep it
						break;
					}
				}
			}
			if (protectedNeighbor) {
				cancelCleanupTx(pos);
				cleanupQueue.poll();
				statusLines.add(0, "Stuetze stuetzt Bauwerk, bleibt: "
						+ pos.toShortString());
				continue;
			}
			if (!world.isLoaded(pos)) {
				cancelCleanupTx(pos);
				statusLines.add(0, "Stuetze in ungeladenem Chunk: " + pos.toShortString());
				cleanupQueue.poll();
				continue;
			}
			Vec3 eye = player.getEyePosition();
			Vec3 center = Vec3.atCenterOf(pos);
			if (eye.distanceTo(center) > player.blockInteractionRange() - REACH_MARGIN) {
				cancelCleanupTx(pos);
				cleanupQueue.poll();
				statusLines.add(0, "Stuetze ausser Reichweite: " + pos.toShortString());
				continue;
			}
			motion.aimAt(player, center);
			motion.tick(player, config);
			if (!motion.isOnTarget(player)) {
				return;
			}
			if (!(player.pick(player.blockInteractionRange(), 1.0f, false)
					instanceof BlockHitResult hit)
					|| hit.getType() != HitResult.Type.BLOCK
					|| !hit.getBlockPos().equals(pos)) {
				cancelCleanupTx(pos);
				cleanupQueue.poll();
				statusLines.add(0, "Keine Sicht auf Stuetze: " + pos.toShortString());
				continue;
			}
			if (cleanupTx == null) {
				cleanupTx = new Transaction(
						new PlacementAction(pos, Blocks.AIR.defaultBlockState(), hit,
								null, false, ActionKind.BREAK_TEMPORARY),
						Math.max(config.confirmationTimeoutTicks, 200));
				AckTracker.submit(cleanupTx);
			}
			InputBroker.holdAttack(true);
			return;
		}
		cleanupMode = false;
		cleanupTx = null;
		InputBroker.holdAttack(false);
		releaseInputs();
		state = RunState.PAUSED;
		statusLines.add(0, "Cleanup fertig; verbliebene: " + supports.ownedCount());
	}

	private void cancelCleanupTx(BlockPos pos) {
		if (cleanupTx != null && cleanupTx.action().target().equals(pos)) {
			AckTracker.remove(cleanupTx);
			cleanupTx = null;
			InputBroker.holdAttack(false);
			Minecraft.getInstance().gameMode.stopDestroyBlock();
		}
	}

	// ------------------------------------------------------------------
	// Misc
	// ------------------------------------------------------------------

	public void releaseInputs() {
		InputBroker.holdAttack(false);
		InputBroker.releaseAll();
		motion.reset();
		InventoryHelper.cancelPending();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			InventoryHelper.restoreHotbar(player);
		}
		if (Minecraft.getInstance().gameMode != null
				&& Minecraft.getInstance().gameMode.isDestroying()) {
			Minecraft.getInstance().gameMode.stopDestroyBlock();
		}
	}

	private void releaseMoveOnly() {
		InputBroker.setDesired(Input.EMPTY);
	}

	/** Handles a failed equip: waiting is silent, blocked pauses, missing uses the sync grace. */
	private void equipFailed(InventoryHelper.EquipResult result, String missingReason) {
		switch (result) {
			case READY, WAITING -> { }
			case BLOCKED -> pause(INVENTORY_BLOCKED_REASON);
			case MISSING -> {
				if (materialWait++ < MATERIAL_SYNC_TICKS) {
					return;
				}
				materialWait = 0;
				pause(missingReason);
			}
		}
	}

	public List<String> materialsReport() {
		List<String> lines = new ArrayList<>();
		if (planner == null || blueprint == null) {
			return lines;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return lines;
		}
		// Keyed by registry id so distinct items never merge; dependent
		// counterparts (door top, bed head, ...) come with the primary item.
		Map<String, int[]> needed = new java.util.TreeMap<>();
		Map<String, Item> items = new HashMap<>();
		for (BuildTarget t : planner.targets()) {
			if (planner.isVerified(t) || planner.isConflicted(t.pos())
					|| planner.isDependent(t)) {
				continue;
			}
			Item item;
			if (t.phase() == Phase.FLUIDS) {
				Fluid f = Planner.isWaterlogTask(t) ? Fluids.WATER
						: t.state().getFluidState().getType();
				item = f == Fluids.LAVA ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
			} else {
				item = ItemResolver.itemFor(t.state());
			}
			if (item == null) {
				continue;
			}
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getKey(item).toString();
			items.put(id, item);
			needed.computeIfAbsent(id, k -> new int[1])[0]++;
		}
		for (Map.Entry<String, int[]> e : needed.entrySet()) {
			Item item = items.get(e.getKey());
			int have = InventoryHelper.countItem(player, item);
			String name = new ItemStack(item).getHoverName().getString();
			lines.add(name + ": " + have + "/" + e.getValue()[0]
					+ (have < e.getValue()[0]
					? " (fehlen " + (e.getValue()[0] - have) + ")" : ""));
		}
		return lines;
	}
}
