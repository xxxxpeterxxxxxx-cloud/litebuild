package dev.gentlebuild.plan;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.VerificationMismatch;
import dev.gentlebuild.nav.LevelNavWorld;
import dev.gentlebuild.nav.NavWorld;
import dev.gentlebuild.state.StateRules;

/**
 * Conservative repair policy — pure decisions over the audit record, the
 * live world and the exact ownership map. Anything not provably safe is a
 * MANUAL decision with a concrete reason; unsafe cells are never broken and
 * failed repairs never mark the build complete.
 */
public final class RepairPolicy {

	private RepairPolicy() {}

	public enum Kind { FILL, ADJUST, REPLACE, REMOVE, MANUAL }

	public record Decision(VerificationMismatch mismatch, Kind kind,
						   String reason) {
		public static Decision of(VerificationMismatch m, Kind k, String r) {
			return new Decision(m, k, r);
		}
		public static Decision manual(VerificationMismatch m, String r) {
			return new Decision(m, Kind.MANUAL, r);
		}
	}

	/**
	 * Classify one typed mismatch. The live cell must still equal the audited
	 * snapshot — a changed cell reschedules a full audit instead of acting on
	 * stale data.
	 */
	public static Decision classify(VerificationMismatch m, Blueprint blueprint,
									NavWorld world,
									Map<BlockPos, BlockState> owned,
									BlockPos playerFeet,
									boolean fluidsEnabled,
									boolean activationEnabled) {
		if (m.kind() == VerificationMismatch.Kind.UNLOADED) {
			return Decision.manual(m, "Chunk nicht geladen");
		}
		BlockPos pos = m.pos();
		if (!world.isLoaded(pos)) {
			return Decision.manual(m, "Chunk nicht geladen");
		}
		BlockState live = world.getBlockState(pos);
		if (m.actual() == null || !live.equals(m.actual())) {
			return Decision.manual(m, "Zelle veraendert — Neuabgleich noetig");
		}
		BlockState expected = m.expected();

		switch (m.kind()) {
			case MISSING -> {
				if (expected.getBlock() instanceof LiquidBlock) {
					if (expected.getValue(BlockStateProperties.LEVEL) != 0) {
						return Decision.manual(m, "Quelle/Fluss pruefen");
					}
					return fluidsEnabled
							? Decision.of(m, Kind.FILL, "Quellblock setzen")
							: Decision.manual(m, "Fluide freigeben");
				}
				if (isDependentHalf(expected)) {
					BlockPos counterpart = counterpartPos(expected, pos);
					BlockState liveCounter = world.isLoaded(counterpart)
							? world.getBlockState(counterpart) : null;
					boolean counterMissing = liveCounter == null
							|| liveCounter.isAir() || liveCounter.canBeReplaced();
					return counterMissing
							? Decision.of(m, Kind.FILL,
									"Primaerteil fehlt ebenfalls — gemeinsam setzen")
							: Decision.manual(m,
									"Abhaengige Haelfte einzeln nicht platzierbar");
				}
				return Decision.of(m, Kind.FILL, "Fehlenden Block setzen");
			}
			case WRONG_STATE -> {
				BlockState step = StateRules.nextInteractionStep(expected, live);
				if (step != null) {
					if (!activationEnabled && isLiveControl(expected)) {
						return Decision.manual(m, "Aktivierung freigeben");
					}
					return Decision.of(m, Kind.ADJUST,
							"Interaktionsschritt (Verzoegerung/Schalter)");
				}
				if (StateRules.canStackSlab(expected, live)) {
					return Decision.of(m, Kind.FILL, "Zweite Stufe setzen");
				}
				if (expected.hasProperty(BlockStateProperties.WATERLOGGED)
						&& expected.getValue(BlockStateProperties.WATERLOGGED)
						&& StateRules.constructionEquivalent(expected, live)) {
					return fluidsEnabled
							? Decision.of(m, Kind.FILL, "Trockene Schale waessern")
							: Decision.manual(m, "Fluide freigeben");
				}
				if (StateRules.constructionEquivalent(expected, live)) {
					// Only neighbor-derived connection shapes differ; other
					// repairs may settle this — never re-break wire/stairs for
					// a transient shape.
					return Decision.manual(m,
							"Nachbarzustand/Verbindung pruefen");
				}
				return breakOrManual(m, live, blueprint, world, owned,
						playerFeet, true);
			}
			case WRONG_BLOCK -> {
				return breakOrManual(m, live, blueprint, world, owned,
						playerFeet, true);
			}
			case EXTRA_BLOCK, TEMP_SUPPORT -> {
				return breakOrManual(m, live, blueprint, world, owned,
						playerFeet, false);
			}
			default -> {
				return Decision.manual(m, "Unbekannter Befund");
			}
		}
	}

	private static Decision breakOrManual(VerificationMismatch m,
										  BlockState live, Blueprint blueprint,
										  NavWorld world,
										  Map<BlockPos, BlockState> owned,
										  BlockPos playerFeet,
										  boolean replace) {
		String reason = breakBlockReason(m.pos(), live, blueprint, world,
				owned, playerFeet);
		if (reason != null) {
			return Decision.manual(m, reason);
		}
		return Decision.of(m, replace ? Kind.REPLACE : Kind.REMOVE,
				replace ? "Eigenen Fehlblock ersetzen" : "Eigenen Block entfernen");
	}

	/**
	 * Conservative break safety, re-evaluated immediately before every damage
	 * tick. {@code null} means the break is authorized; otherwise the exact
	 * manual reason.
	 */
	public static String breakBlockReason(BlockPos pos, BlockState actual,
										  Blueprint blueprint, NavWorld world,
										  Map<BlockPos, BlockState> owned,
										  BlockPos playerFeet) {
		if (!world.isLoaded(pos)) {
			return "Chunk nicht geladen";
		}
		BlockState live = world.getBlockState(pos);
		if (live.isAir()) {
			return "Bereits Luft";
		}
		if (!live.equals(actual)) {
			return "Zustand veraendert — Neuabgleich noetig";
		}
		if (blueprint == null || !blueprint.blocks().containsKey(pos)) {
			return "Ausserhalb des Bauplans";
		}
		BlockState rec = owned.get(pos);
		if (rec == null || !rec.equals(live)) {
			return "Kein eigener Block (unbekannt/veraendert)";
		}
		if (live.hasBlockEntity() || blockEntityAt(world, pos)) {
			return "BlockEntity/Inventar vorhanden";
		}
		if (!live.getFluidState().isEmpty()
				|| (live.hasProperty(BlockStateProperties.WATERLOGGED)
				&& live.getValue(BlockStateProperties.WATERLOGGED))) {
			return "Fluid/waterlogged";
		}
		if (!isInertBreakable(live)) {
			return "Kein inerter Block";
		}
		if (playerFeet != null
				&& (pos.equals(playerFeet.below()) || pos.equals(playerFeet)
				|| pos.equals(playerFeet.above()))) {
			return "Spieler steht darauf/darin";
		}
		// Any non-air block directly above would drop/collapse onto the cell.
		if (!world.isLoaded(pos.above())
				|| !world.getBlockState(pos.above()).isAir()) {
			return "Block darueber";
		}
		// Chebyshev-1 shell: everything must be loaded and dry, and nothing
		// may actually depend on this cell. A masking reader answers AIR at
		// pos so each block's own canSurvive rule decides — merely nearby
		// independent blocks (a door on the platform) do not block removal.
		LevelReader masked = world instanceof LevelReader lr
				? new MaskedLevel(lr, pos) : null;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dy == 0 && dz == 0
							|| (dx == 0 && dy == 1 && dz == 0)) {
						continue; // self and the cell above handled above
					}
					BlockPos n = pos.offset(dx, dy, dz);
					if (!world.isLoaded(n)) {
						return "Nachbar nicht geladen";
					}
					BlockState ns = world.getBlockState(n);
					if (ns.isAir()) {
						continue;
					}
					if (!ns.getFluidState().isEmpty()) {
						return "Fluid nebenan";
					}
					if (ns.hasBlockEntity() || blockEntityAt(world, n)) {
						return "BlockEntity nebenan";
					}
					if (masked != null) {
						if (!ns.canSurvive(masked, n)) {
							return "Abhaengiger Block nebenan";
						}
					} else if (!isInertNeighbor(ns)) {
						return "Mechanismus/Anbauteil nebenan";
					}
				}
			}
		}
		if (world instanceof BlockGetter bg) {
			float hardness = live.getDestroySpeed(bg, pos);
			if (hardness < 0 || hardness > 5.0f) {
				return "Zu hart/unzerstoerbar";
			}
		}
		return null;
	}

	/** Level view that reports one cell as air so canSurvive checks run the
	 *  real dependency rule for a hypothetical removal. */
	private static final class MaskedLevel extends LevelNavWorld {
		private final BlockPos masked;

		MaskedLevel(LevelReader level, BlockPos masked) {
			super(level);
			this.masked = masked;
		}

		@Override
		public BlockState getBlockState(BlockPos p) {
			return p.equals(masked)
					? Blocks.AIR.defaultBlockState() : super.getBlockState(p);
		}
	}

	/** Inert blocks we may break: full cube, slab, stair or log — nothing else. */
	private static boolean isInertBreakable(BlockState s) {
		var b = s.getBlock();
		if (b instanceof SlabBlock || b instanceof StairBlock
				|| b instanceof RotatedPillarBlock) {
			return true;
		}
		return isInertShape(s);
	}

	/** Air-free neighbors must be plain full-cube inert shapes (glass ok). */
	private static boolean isInertNeighbor(BlockState s) {
		return isInertShape(s);
	}

	private static boolean isInertShape(BlockState s) {
		var b = s.getBlock();
		if (isHazardous(s)) {
			return false;
		}
		return s.isCollisionShapeFullBlock(EMPTY_GETTER, BlockPos.ZERO)
				|| s.isSolid();
	}

	private static boolean isHazardous(BlockState s) {
		var b = s.getBlock();
		return b instanceof FallingBlock || b instanceof TntBlock
				|| b instanceof LeavesBlock || b instanceof PistonBaseBlock
				|| b instanceof PistonHeadBlock || b instanceof ObserverBlock
				|| b instanceof DiodeBlock || b instanceof RedStoneWireBlock
				|| b instanceof DoorBlock || b instanceof BedBlock
				|| b instanceof DoublePlantBlock || b instanceof BaseEntityBlock
				|| b instanceof net.minecraft.world.level.block.piston.MovingPistonBlock
				|| s.isSignalSource();
	}

	private static boolean isRedstoneSensitive(BlockState s) {
		var b = s.getBlock();
		return b instanceof DiodeBlock || b instanceof RedStoneWireBlock
				|| b instanceof ObserverBlock || b instanceof PistonBaseBlock
				|| b instanceof RedstoneTorchBlock || b instanceof TripWireHookBlock
				|| b instanceof TripWireBlock || s.isSignalSource();
	}

	private static boolean blockEntityAt(NavWorld world, BlockPos pos) {
		return world instanceof BlockGetter bg && bg.getBlockEntity(pos) != null;
	}

	private static boolean isLiveControl(BlockState wanted) {
		var b = wanted.getBlock();
		return b instanceof LeverBlock || b instanceof DoorBlock
				|| b instanceof TrapDoorBlock || b instanceof FenceGateBlock;
	}

	private static boolean isDependentHalf(BlockState expected) {
		var b = expected.getBlock();
		if (b instanceof DoorBlock || b instanceof DoublePlantBlock) {
			return expected.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
					&& expected.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
							== DoubleBlockHalf.UPPER;
		}
		if (b instanceof BedBlock) {
			return expected.hasProperty(BlockStateProperties.BED_PART)
					&& expected.getValue(BlockStateProperties.BED_PART)
							== BedPart.HEAD;
		}
		return false;
	}

	private static BlockPos counterpartPos(BlockState expected, BlockPos pos) {
		var b = expected.getBlock();
		if (b instanceof DoorBlock || b instanceof DoublePlantBlock) {
			return pos.below();
		}
		if (b instanceof BedBlock) {
			return pos.relative(expected.getValue(
					BlockStateProperties.HORIZONTAL_FACING).getOpposite());
		}
		return pos;
	}

	/** Minimal BlockGetter for shape tests (MapWorld/LevelNavWorld used
	 * elsewhere; shape emptiness is state-derived, position-insensitive). */
	private static final BlockGetter EMPTY_GETTER = new BlockGetter() {
		@Override public BlockState getBlockState(BlockPos p) {
			return Blocks.AIR.defaultBlockState();
		}
		@Override public net.minecraft.world.level.material.FluidState getFluidState(
				BlockPos p) {
			return net.minecraft.world.level.material.Fluids.EMPTY
					.defaultFluidState();
		}
		@Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(
				BlockPos p) {
			return null;
		}
		@Override public int getHeight() { return 384; }
		@Override public int getMinY() { return -64; }
	};
}
