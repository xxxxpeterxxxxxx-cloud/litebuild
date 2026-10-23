package dev.gentlebuild.state;

import java.util.Set;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.Property;
import dev.gentlebuild.model.Phase;

/**
 * State comparison rules. Intrinsic user-controlled properties (facing, axis,
 * slab half, stair half, repeater delay, comparator mode, door hinge/open,
 * waterlogging, counts/layers, fluid level, leaves persistence, ...) must
 * match. A small set of genuinely runtime-driven properties is ignored for
 * matching but is listed for the final report.
 */
public final class StateRules {

	/** Always runtime: power level, triggers, occupancy, growth, lit. */
	private static final Set<String> RUNTIME_PROPS = Set.of(
			"power", "triggered", "occupied", "age", "lit");

	/**
	 * Properties recomputed by neighbor-shape updates. Ignored during
	 * placement matching; required again during final verification.
	 */
	private static final Set<String> CONNECTION_PROPS = Set.of(
			"shape", "north", "east", "south", "west");

	private StateRules() {}

	/** Loose comparison for deciding whether a placement click succeeded. */
	public static boolean placementEquivalent(BlockState expected, BlockState actual) {
		return compare(expected, actual, true);
	}

	/**
	 * Construction progress: intrinsic properties must match, neighbor
	 * connection shapes (stair shape, wire/fence sides) may still be
	 * incomplete until neighbors exist — they are rechecked by the final
	 * verifier.
	 */
	public static boolean constructionEquivalent(BlockState expected, BlockState actual) {
		return compare(expected, actual, true);
	}

	/** Strict comparison for the final verifier after neighbors settled. */
	public static boolean finalEquivalent(BlockState expected, BlockState actual) {
		return compare(expected, actual, false);
	}

	private static boolean compare(BlockState expected, BlockState actual,
								   boolean ignoreConnections) {
		if (expected == actual) {
			return true;
		}
		if (expected.getBlock() != actual.getBlock()) {
			return false;
		}
		for (Property.Value<?> value : expected.getValues().toList()) {
			Property<?> prop = value.property();
			String name = prop.getName();
			if (isRuntimeWaived(expected.getBlock(), prop)) {
				continue;
			}
			if (ignoreConnections && CONNECTION_PROPS.contains(name)
					&& isConnectionProperty(expected, prop)) {
				continue;
			}
			if (!value.value().equals(actual.getValue((Property) prop))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Runtime waiver: "powered" is ignored EXCEPT on a lever (a lever's
	 * powered state is a user setting we must reproduce); "enabled" is waived
	 * only for hoppers (power-driven), ditto "conditional"/"extended" for
	 * pistons and "unstable" for TNT-adjacent quirks.
	 */
	private static boolean isRuntimeWaived(Block block, Property<?> prop) {
		String name = prop.getName();
		if (RUNTIME_PROPS.contains(name)) {
			return true;
		}
		if (name.equals("powered")) {
			return !(block instanceof LeverBlock);
		}
		if (name.equals("enabled")) {
			return block instanceof HopperBlock;
		}
		if (name.equals("conditional")) {
			return block instanceof CommandBlock;
		}
		if (name.equals("extended")) {
			return block instanceof PistonBaseBlock;
		}
		return false;
	}

	/**
	 * Narrows the connection waiver to blocks that really recompute those
	 * properties on neighbor updates (mushroom-cap sides and vine/multiface
	 * faces are placement data, not recomputed connections).
	 */
	private static boolean isConnectionProperty(BlockState state, Property<?> prop) {
		String name = prop.getName();
		var block = state.getBlock();
		if (name.equals("shape")) {
			return block instanceof StairBlock || block instanceof BaseRailBlock;
		}
		if (name.equals("north") || name.equals("east") || name.equals("south")
				|| name.equals("west")) {
			return block instanceof RedStoneWireBlock
					|| block instanceof FenceBlock
					|| block instanceof WallBlock
					|| block instanceof IronBarsBlock
					|| block instanceof ChorusPlantBlock
					|| block instanceof TripWireBlock;
		}
		return false;
	}

	/**
	 * The state a placement click can realistically produce: interaction
	 * settings are normalized to their freshly-placed defaults so the solver
	 * can verify a getStateForPlacement prediction. Orientation is kept.
	 */
	public static BlockState initialPlacementState(BlockState wanted) {
		BlockState s = wanted;
		if (s.hasProperty(RepeaterBlock.DELAY)) {
			s = s.setValue(RepeaterBlock.DELAY, 1);
		}
		if (s.hasProperty(ComparatorBlock.MODE)) {
			s = s.setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE);
		}
		if (s.hasProperty(LeverBlock.POWERED)) {
			s = s.setValue(LeverBlock.POWERED, false);
		}
		if (s.hasProperty(BlockStateProperties.OPEN)) {
			s = s.setValue(BlockStateProperties.OPEN, false);
		}
		if (s.hasProperty(BlockStateProperties.WATERLOGGED)) {
			s = s.setValue(BlockStateProperties.WATERLOGGED, false);
		}
		return s;
	}

	/**
	 * True when the placed shell differs from the wanted state only in
	 * properties we can fix with ordinary right-clicks (repeater delay,
	 * comparator mode, lever/trapdoor/door state).
	 */
	public static boolean needsInteractionAfterPlacement(BlockState wanted, BlockState placed) {
		if (wanted.getBlock() != placed.getBlock()) {
			return false;
		}
		for (Property.Value<?> value : wanted.getValues().toList()) {
			Property<?> prop = value.property();
			String name = prop.getName();
			if (isRuntimeWaived(wanted.getBlock(), prop)) {
				continue;
			}
			if (name.equals("delay") || name.equals("mode") || name.equals("powered")
					|| name.equals("open") || name.equals("waterlogged")) {
				if (!value.value().equals(placed.getValue((Property) prop))) {
					return true;
				}
			}
		}
		return false;
	}

	/** True when {@code actual} is a single half slab of the same block and
	 * {@code wanted} is the double slab — a second ordinary placement stacks. */
	public static boolean canStackSlab(BlockState wanted, BlockState actual) {
		return wanted.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
				&& actual.getBlock() == wanted.getBlock()
				&& wanted.getValue(net.minecraft.world.level.block.state.properties
						.BlockStateProperties.SLAB_TYPE)
						== net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
				&& actual.getValue(net.minecraft.world.level.block.state.properties
						.BlockStateProperties.SLAB_TYPE)
						!= net.minecraft.world.level.block.state.properties.SlabType.DOUBLE;
	}

	/** Intermediate half-slab stage satisfied by ANY existing single half
	 * of the same slab block (bottom or top). */
	public static boolean slabStageEquivalent(BlockState stage, BlockState actual) {
		return stage.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
				&& actual.getBlock() == stage.getBlock()
				&& stage.getValue(net.minecraft.world.level.block.state.properties
						.BlockStateProperties.SLAB_TYPE)
						!= net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
				&& actual.getValue(net.minecraft.world.level.block.state.properties
						.BlockStateProperties.SLAB_TYPE)
						!= net.minecraft.world.level.block.state.properties.SlabType.DOUBLE;
	}

	/**
	 * The single next interaction step towards the wanted state (repeater
	 * delay +1 wrap, comparator mode toggle, lever/trapdoor/door toggle).
	 * Returns null when nothing interactable remains.
	 */
	public static BlockState nextInteractionStep(BlockState wanted, BlockState actual) {
		if (wanted.getBlock() != actual.getBlock()) {
			return null;
		}
		// Only interact once every non-fixable property (facing, half,
		// waterlogged, ...) already matches; never tune a wrongly oriented block.
		for (Property.Value<?> value : wanted.getValues().toList()) {
			Property<?> prop = value.property();
			String name = prop.getName();
			if (isRuntimeWaived(wanted.getBlock(), prop)
					|| CONNECTION_PROPS.contains(name)) {
				continue;
			}
			boolean fixable = name.equals("delay") || name.equals("mode")
					|| (name.equals("powered") && wanted.getBlock() instanceof LeverBlock)
					|| (name.equals("open") && (wanted.getBlock() instanceof TrapDoorBlock
						|| wanted.getBlock() instanceof DoorBlock
						|| wanted.getBlock() instanceof FenceGateBlock));
			if (!fixable && !value.value().equals(actual.getValue((Property) prop))) {
				return null;
			}
		}
		if (wanted.hasProperty(RepeaterBlock.DELAY)
				&& !wanted.getValue(RepeaterBlock.DELAY).equals(actual.getValue(RepeaterBlock.DELAY))) {
			int next = actual.getValue(RepeaterBlock.DELAY) % 4 + 1;
			return actual.setValue(RepeaterBlock.DELAY, next);
		}
		if (wanted.hasProperty(ComparatorBlock.MODE)
				&& !wanted.getValue(ComparatorBlock.MODE).equals(actual.getValue(ComparatorBlock.MODE))) {
			return actual.setValue(ComparatorBlock.MODE,
					actual.getValue(ComparatorBlock.MODE) == ComparatorMode.COMPARE
							? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE);
		}
		if (wanted.getBlock() instanceof LeverBlock
				&& !wanted.getValue(LeverBlock.POWERED).equals(actual.getValue(LeverBlock.POWERED))) {
			return actual.setValue(LeverBlock.POWERED,
					!actual.getValue(LeverBlock.POWERED));
		}
		if (wanted.hasProperty(BlockStateProperties.OPEN)
				&& (wanted.getBlock() instanceof TrapDoorBlock
				|| wanted.getBlock() instanceof DoorBlock
				|| wanted.getBlock() instanceof FenceGateBlock)
				&& !wanted.getValue(BlockStateProperties.OPEN)
				.equals(actual.getValue(BlockStateProperties.OPEN))) {
			return actual.setValue(BlockStateProperties.OPEN,
					!actual.getValue(BlockStateProperties.OPEN));
		}
		return null;
	}

	/** Phase classification of a target state (dry side; fluids split upstream). */
	public static Phase phaseOf(BlockState state) {
		var block = state.getBlock();
		if (block instanceof LiquidBlock) {
			return Phase.FLUIDS;
		}
		if (block instanceof ObserverBlock
				|| block == Blocks.REDSTONE_BLOCK
				|| block instanceof RedstoneTorchBlock
				|| block instanceof LeverBlock
				|| block instanceof ButtonBlock
				|| block instanceof BasePressurePlateBlock
				|| block instanceof TripWireHookBlock
				|| block instanceof DaylightDetectorBlock
				|| block instanceof TargetBlock
				|| block instanceof SculkSensorBlock
				|| block instanceof TrappedChestBlock) {
			return Phase.ACTIVATION;
		}
		if (block instanceof BaseEntityBlock
				|| block instanceof PistonBaseBlock
				|| block instanceof HopperBlock
				|| block instanceof DiodeBlock
				|| block instanceof DispenserBlock
				|| block instanceof RedstoneLampBlock
				|| block instanceof TntBlock
				|| block instanceof BellBlock
				|| block instanceof DoorBlock
				|| block instanceof TrapDoorBlock
				|| block instanceof FenceGateBlock
				|| block instanceof NoteBlock) {
			return Phase.MECHANISMS;
		}
		if (block instanceof BaseRailBlock
				|| block instanceof RedStoneWireBlock
				|| block instanceof TorchBlock
				|| block instanceof LadderBlock
				|| isAttachableFallback(state)) {
			return Phase.ATTACHMENTS;
		}
		return Phase.STRUCTURE;
	}

	/**
	 * Non-solid blocks that need a real support to survive: carpets, crops,
	 * signs, flowers, saplings, layers, ...
	 */
	private static boolean isAttachableFallback(BlockState state) {
		var block = state.getBlock();
		return block instanceof CarpetBlock
				|| block instanceof CropBlock
				|| block instanceof SaplingBlock
				|| block instanceof BushBlock
				|| block instanceof SignBlock
				|| block instanceof SnowLayerBlock
				|| block instanceof LanternBlock
				|| block instanceof CandleBlock
				|| block instanceof RodBlock
				|| block instanceof AmethystClusterBlock
				|| block instanceof PointedDripstoneBlock
				|| block instanceof TripWireBlock
				|| block instanceof VineBlock
				|| block instanceof CocoaBlock
				|| block instanceof BedBlock
				|| block instanceof DoublePlantBlock
				|| block instanceof TallSeagrassBlock
				|| block instanceof SeaPickleBlock
				|| block instanceof TurtleEggBlock
				|| block instanceof FrogspawnBlock
				|| (!(block instanceof LeverBlock)
						&& !(block instanceof LiquidBlockContainer)
						&& !state.isSolid());
	}
}
