package dev.litebuild;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.Test;
import dev.litebuild.model.Phase;
import dev.litebuild.state.StateRules;
import static org.junit.jupiter.api.Assertions.*;

class StateRulesTest extends McTestBase {

	@Test
	void facingMustMatch() {
		BlockState n = Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(StairBlock.FACING, Direction.NORTH);
		BlockState s = n.setValue(StairBlock.FACING, Direction.SOUTH);
		assertFalse(StateRules.placementEquivalent(n, s));
		assertFalse(StateRules.finalEquivalent(n, s));
	}

	@Test
	void repeaterDelayMustMatch() {
		BlockState d1 = Blocks.REPEATER.defaultBlockState()
				.setValue(RepeaterBlock.DELAY, 1);
		BlockState d4 = d1.setValue(RepeaterBlock.DELAY, 4);
		assertFalse(StateRules.placementEquivalent(d1, d4));
		assertFalse(StateRules.finalEquivalent(d1, d4));
	}

	@Test
	void comparatorModeMustMatch() {
		BlockState cmp = Blocks.COMPARATOR.defaultBlockState();
		BlockState sub = cmp.setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT);
		assertFalse(StateRules.placementEquivalent(cmp, sub));
	}

	@Test
	void slabHalfAndAxisMustMatch() {
		BlockState bottom = Blocks.OAK_SLAB.defaultBlockState()
				.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
		BlockState top = bottom.setValue(SlabBlock.TYPE, SlabType.TOP);
		assertFalse(StateRules.placementEquivalent(bottom, top));
		assertFalse(StateRules.finalEquivalent(bottom, top));

		BlockState x = Blocks.OAK_LOG.defaultBlockState()
				.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
		BlockState y = x.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
		assertFalse(StateRules.placementEquivalent(x, y));
	}

	@Test
	void waterloggedMustMatch() {
		BlockState dry = Blocks.OAK_SLAB.defaultBlockState();
		BlockState wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
		assertFalse(StateRules.placementEquivalent(dry, wet));
		assertFalse(StateRules.finalEquivalent(dry, wet));
	}

	@Test
	void runtimePowerIgnored() {
		// Lit is a runtime property (e.g. redstone lamp receiving power).
		BlockState off = Blocks.REDSTONE_LAMP.defaultBlockState()
				.setValue(BlockStateProperties.LIT, false);
		BlockState on = off.setValue(BlockStateProperties.LIT, true);
		assertTrue(StateRules.placementEquivalent(off, on));
		assertTrue(StateRules.finalEquivalent(off, on));
		// Lever powered is user-facing output and must NOT be waived.
		BlockState leverOff = Blocks.LEVER.defaultBlockState()
				.setValue(LeverBlock.POWERED, false);
		BlockState leverOn = leverOff.setValue(LeverBlock.POWERED, true);
		assertFalse(StateRules.placementEquivalent(leverOff, leverOn));
		assertFalse(StateRules.finalEquivalent(leverOff, leverOn));
	}

	@Test
	void stairShapeIgnoredForPlacementRequiredForFinal() {
		BlockState inner = Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(StairBlock.SHAPE,
						net.minecraft.world.level.block.state.properties.StairsShape.INNER_LEFT);
		BlockState straight = inner.setValue(StairBlock.SHAPE,
				net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT);
		assertTrue(StateRules.placementEquivalent(inner, straight));
		assertFalse(StateRules.finalEquivalent(inner, straight));
	}

	@Test
	void differentBlocksNeverEqual() {
		assertFalse(StateRules.placementEquivalent(
				Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState()));
	}

	@Test
	void phaseClassification() {
		assertEquals(Phase.STRUCTURE, StateRules.phaseOf(Blocks.STONE.defaultBlockState()));
		assertEquals(Phase.ATTACHMENTS, StateRules.phaseOf(Blocks.TORCH.defaultBlockState()));
		assertEquals(Phase.ATTACHMENTS, StateRules.phaseOf(Blocks.REDSTONE_WIRE.defaultBlockState()));
		assertEquals(Phase.MECHANISMS, StateRules.phaseOf(Blocks.REPEATER.defaultBlockState()));
		assertEquals(Phase.MECHANISMS, StateRules.phaseOf(Blocks.HOPPER.defaultBlockState()));
		assertEquals(Phase.MECHANISMS, StateRules.phaseOf(Blocks.PISTON.defaultBlockState()));
		assertEquals(Phase.FLUIDS, StateRules.phaseOf(Blocks.WATER.defaultBlockState()));
		assertEquals(Phase.ACTIVATION, StateRules.phaseOf(Blocks.OBSERVER.defaultBlockState()));
		assertEquals(Phase.ACTIVATION, StateRules.phaseOf(Blocks.LEVER.defaultBlockState()));
		assertEquals(Phase.ACTIVATION, StateRules.phaseOf(Blocks.REDSTONE_TORCH.defaultBlockState()));
	}
}
