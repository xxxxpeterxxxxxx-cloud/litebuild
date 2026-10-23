package dev.litebuild;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import dev.litebuild.model.BuildTarget;
import dev.litebuild.model.Phase;
import dev.litebuild.plan.Planner;
import static org.junit.jupiter.api.Assertions.*;

class PlannerBatchTest extends McTestBase {

	private static BuildTarget stone(BlockPos pos) {
		return new BuildTarget(pos, Blocks.STONE.defaultBlockState(), Phase.STRUCTURE);
	}

	private static BuildTarget dirt(BlockPos pos) {
		return new BuildTarget(pos, Blocks.DIRT.defaultBlockState(), Phase.STRUCTURE);
	}

	@Test
	void heldItemPenaltyPrefersSameMaterialButOnlyWithinBoundedCost() {
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		BuildTarget nearDirt = dirt(p(0, 64, 1));
		BuildTarget farStone = stone(p(0, 64, 2));
		List<BuildTarget> pool = new ArrayList<>(List.of(nearDirt, farStone));

		var noHeld = pool.stream().sorted(Planner.candidateOrder(eye, 0f, 0f, null, null, null))
				.toList();
		assertEquals(nearDirt, noHeld.get(0), "without a held item the closest wins");

		var heldStone = pool.stream().sorted(Planner.candidateOrder(eye, 0f, 0f, null, null,
				Items.STONE)).toList();
		assertEquals(farStone, heldStone.get(0), "held stone continues within the bounded penalty");

		BuildTarget veryFarStone = stone(p(0, 64, -40));
		var bounded = List.of(nearDirt, veryFarStone).stream()
				.sorted(Planner.candidateOrder(eye, 0f, 0f, null, null, Items.STONE)).toList();
		assertEquals(nearDirt, bounded.get(0), "a far same-material cell never outweighs the penalty");
	}

	@Test
	void stanceCoveringMoreCellsOutranksNearerEmptyStance() {
		BlockPos start = p(0, 64, 0);
		List<BuildTarget> candidates = new ArrayList<>();
		for (int x = 0; x <= 3; x++) {
			candidates.add(stone(p(x, 64, 5)));
		}
		BuildTarget current = candidates.get(0);
		Vec3 feet = new Vec3(0.5, 64.0, 0.5);
		var order = Planner.batchStanceOrder(candidates, current, feet, 4.5);
		BlockPos covering = p(0, 64, 4);
		BlockPos empty = p(20, 64, 0);
		assertTrue(order.compare(covering, empty) < 0, "covering stance ranks first");
		assertTrue(order.compare(covering, start) < 0, "covering stance beats the cell next to the player");
	}

	@Test
	void batchOrderIsDeterministicForTies() {
		List<BuildTarget> candidates = List.of(stone(p(0, 64, 5)));
		var order = Planner.batchStanceOrder(candidates, candidates.get(0),
				new Vec3(0.5, 64.0, 0.5), 4.5);
		BlockPos a = p(1, 64, 4);
		BlockPos b = p(2, 64, 4);
		assertEquals(Integer.signum(order.compare(a, b)), -Integer.signum(order.compare(b, a)));
	}

	private static MapWorld groundRow(int xmax) {
		MapWorld world = new MapWorld();
		for (int x = -4; x <= xmax; x++) {
			for (int z = 0; z <= 2; z++) {
				world.set(p(x, 63, z), Blocks.STONE.defaultBlockState());
			}
		}
		return world;
	}

	private static dev.litebuild.plan.Planner rowPlanner(java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks) {
		return new dev.litebuild.plan.Planner(new dev.litebuild.model.Blueprint("row",
				blocks, java.util.Set.of(), 0, "fp"));
	}

	@Test
	void oddRowInitialScanReachesCellsNextToThePlayer() {
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> row = new java.util.LinkedHashMap<>();
		for (int x = 0; x <= 17; x++) {
			row.put(p(x, 64, 1), Blocks.STONE.defaultBlockState());
		}
		var planner = rowPlanner(row);
		MapWorld world = groundRow(17);
		Vec3 eye = new Vec3(0.23, 65.62, 0.23);
		var found = planner.nextEligible(world, true, true, eye);
		assertTrue(found.stream().anyMatch(t -> t.pos().equals(p(0, 64, 1))),
				"the cell beside the player must be visible: " + found);
		assertTrue(found.stream().allMatch(t -> t.pos().getX() < 8),
				"nearest eight only, no far cells: " + found);
		assertTrue(found.size() <= 8);
	}

	@Test
	void lowerLayerWinsEvenWhenUpperCellIsCloser() {
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new java.util.LinkedHashMap<>();
		blocks.put(p(0, 65, 1), Blocks.STONE.defaultBlockState());
		blocks.put(p(6, 64, 1), Blocks.STONE.defaultBlockState());
		var planner = rowPlanner(blocks);
		MapWorld world = groundRow(8);
		world.set(p(0, 64, 1), Blocks.STONE.defaultBlockState());
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		var found = planner.nextEligible(world, true, true, eye);
		assertFalse(found.isEmpty());
		assertEquals(p(6, 64, 1), found.get(0).pos(), "lowest layer first: " + found);
	}

	@Test
	void phasePrecedesHeldItemPreference() {
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		// The later-phase candidate is the one the held repeater favours; phase must still win.
		BuildTarget nearMechanism = new BuildTarget(p(0, 64, 1),
				Blocks.REPEATER.defaultBlockState(), Phase.MECHANISMS);
		BuildTarget farStructure = stone(p(0, 64, 9));
		var order = Planner.candidateOrder(eye, 0f, 0f, null, null, Items.REPEATER);
		var sorted = java.util.stream.Stream.of(nearMechanism, farStructure).sorted(order).toList();
		assertEquals(farStructure, sorted.get(0), "phase outranks any held-item preference");
	}

	@Test
	void stanceCoverageIgnoresOtherPhaseLayerAndMaterial() {
		BuildTarget current = stone(p(0, 64, 5));
		List<BuildTarget> plain = new ArrayList<>(List.of(current, stone(p(1, 64, 5)), stone(p(2, 64, 5))));
		Vec3 feet = new Vec3(0.5, 64.0, 0.5);
		BlockPos covering = p(0, 64, 4);
		BlockPos edge = p(6, 64, 4);
		int before = Integer.signum(Planner.batchStanceOrder(plain, current, feet, 4.5)
				.compare(covering, edge));
		List<BuildTarget> noisy = new ArrayList<>(plain);
		noisy.add(new BuildTarget(p(1, 64, 5), Blocks.REPEATER.defaultBlockState(), Phase.MECHANISMS));
		noisy.add(new BuildTarget(p(2, 65, 5), Blocks.STONE.defaultBlockState(), Phase.STRUCTURE));
		noisy.add(new BuildTarget(p(3, 64, 5), Blocks.DIRT.defaultBlockState(), Phase.STRUCTURE));
		int after = Integer.signum(Planner.batchStanceOrder(noisy, current, feet, 4.5)
				.compare(covering, edge));
		assertEquals(before, after, "unrelated phase, layer or material must not change coverage");
	}
}
