package dev.gentlebuild;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.build.AckTracker;
import dev.gentlebuild.build.Transaction;
import dev.gentlebuild.model.ActionKind;
import dev.gentlebuild.model.PlacementAction;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest extends McTestBase {

	private PlacementAction action(BlockPos pos, BlockState expected) {
		return new PlacementAction(pos, expected,
				new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false),
				Items.STONE, false, ActionKind.PLACE);
	}

	@Test
	void noAckNoConfirm() {
		BlockPos pos = p(0, 0, 0);
		Map<BlockPos, BlockState> world = new HashMap<>();
		// Client predicted the block locally, but no server ack arrived.
		world.put(pos, Blocks.STONE.defaultBlockState());
		Transaction tx = new Transaction(action(pos, Blocks.STONE.defaultBlockState()), 60);
		for (int i = 0; i < 10; i++) {
			assertFalse(tx.tick(world::get));
		}
		assertEquals(Transaction.Status.PENDING, tx.status());
	}

	@Test
	void confirmsAfterAckAndSettle() {
		BlockPos pos = p(0, 0, 0);
		Map<BlockPos, BlockState> world = new HashMap<>();
		world.put(pos, Blocks.STONE.defaultBlockState());
		Transaction tx = new Transaction(action(pos, Blocks.STONE.defaultBlockState()), 60);
		assertFalse(tx.tick(world::get));
		AckTracker.submit(tx);
		AckTracker.onServerBlock(pos, Blocks.STONE.defaultBlockState());
		boolean done = false;
		for (int i = 0; i < 10 && !done; i++) {
			done = tx.tick(world::get);
		}
		assertTrue(done);
		assertEquals(Transaction.Status.CONFIRMED, tx.status());
	}

	@Test
	void authoritativeAirRejectsPredictedBlock() {
		// Server rolled the placement back to AIR; the client's local
		// prediction of STONE must never count as success.
		BlockPos pos = p(0, 0, 0);
		Map<BlockPos, BlockState> world = new HashMap<>();
		world.put(pos, Blocks.STONE.defaultBlockState()); // local prediction only
		Transaction tx = new Transaction(action(pos, Blocks.STONE.defaultBlockState()), 60);
		AckTracker.submit(tx);
		// Authoritative AIR arrives and the world settles back to air.
		world.put(pos, Blocks.AIR.defaultBlockState());
		AckTracker.onServerBlock(pos, Blocks.AIR.defaultBlockState());
		boolean done = false;
		for (int i = 0; i < 20 && !done; i++) {
			done = tx.tick(world::get);
		}
		assertTrue(done);
		assertEquals(Transaction.Status.MISMATCHED, tx.status());
	}

	@Test
	void configuredSettleTicksHonored() {
		// With minSettleTicks=6 the ack+match alone must not confirm early.
		BlockPos pos = p(0, 0, 0);
		Map<BlockPos, BlockState> world = new HashMap<>();
		world.put(pos, Blocks.STONE.defaultBlockState());
		Transaction tx = new Transaction(action(pos, Blocks.STONE.defaultBlockState()), 60, 6);
		AckTracker.submit(tx);
		AckTracker.onServerBlock(pos, Blocks.STONE.defaultBlockState());
		int ticks = 0;
		while (!tx.tick(world::get)) {
			ticks++;
		}
		assertEquals(Transaction.Status.CONFIRMED, tx.status());
		assertTrue(ticks >= 6, "confirmed after only " + ticks + " ticks");
	}

	@Test
	void timeoutHalts() {
		BlockPos pos = p(0, 0, 0);
		Map<BlockPos, BlockState> world = new HashMap<>();
		Transaction tx = new Transaction(action(pos, Blocks.STONE.defaultBlockState()), 20);
		boolean done = false;
		for (int i = 0; i < 40 && !done; i++) {
			done = tx.tick(world::get);
		}
		assertTrue(done);
		assertEquals(Transaction.Status.TIMEOUT, tx.status());
	}
}
