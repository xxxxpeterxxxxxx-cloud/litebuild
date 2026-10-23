package dev.gentlebuild;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.nav.Navigator;
import dev.gentlebuild.nav.PathResult;
import static org.junit.jupiter.api.Assertions.*;

class NavigatorTest extends McTestBase {

	private MapWorld flat(int radius) {
		MapWorld w = new MapWorld();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				w.set(p(x, -1, z), Blocks.STONE.defaultBlockState());
			}
		}
		return w;
	}

	@Test
	void straightLinePath() {
		MapWorld w = flat(12);
		Navigator nav = new Navigator(w, 4096, false, Set.of());
		PathResult r = nav.findPath(p(0, 0, 0), Set.of(p(5, 0, 0)));
		assertTrue(r.found());
		assertEquals(p(5, 0, 0), r.waypoints().get(r.waypoints().size() - 1));
		assertEquals(5, r.waypoints().size());
	}

	@Test
	void detoursAroundWall() {
		MapWorld w = flat(12);
		for (int z = -8; z <= 8; z++) {
			w.set(p(2, 0, z), Blocks.STONE.defaultBlockState());
			w.set(p(2, 1, z), Blocks.STONE.defaultBlockState());
		}
		// open a gap at z=9
		w.map.remove(p(2, 0, 9));
		w.map.remove(p(2, 1, 9));
		Navigator nav = new Navigator(w, 8192, false, Set.of());
		PathResult r = nav.findPath(p(0, 0, 0), Set.of(p(5, 0, 0)));
		assertTrue(r.found());
		assertTrue(r.waypoints().stream().noneMatch(c -> c.getX() == 2
				&& !w.getBlockState(p(2, c.getY(), c.getZ())).isAir() && c.getY() <= 2));
	}

	@Test
	void avoidsLavaGround() {
		MapWorld w = flat(12);
		w.set(p(5, -1, 0), Blocks.LAVA.defaultBlockState());
		// fully ring the goal with lava margin so it stays hazardous
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				w.set(p(5 + dx, -1, dz), Blocks.LAVA.defaultBlockState());
			}
		}
		Navigator nav = new Navigator(w, 4096, false, Set.of());
		PathResult r = nav.findPath(p(0, 0, 0), Set.of(p(5, 0, 0)));
		assertFalse(r.found());
	}

	@Test
	void stepsUpOne() {
		MapWorld w = flat(12);
		for (int z = -4; z <= 4; z++) {
			w.set(p(3, -1, z), Blocks.AIR.defaultBlockState());
			w.set(p(3, 0, z), Blocks.AIR.defaultBlockState());
		}
		// plateau at y=0 ground for x>=3
		for (int x = 3; x <= 10; x++) {
			for (int z = -4; z <= 4; z++) {
				w.set(p(x, -1, z), Blocks.STONE.defaultBlockState());
				w.set(p(x, 0, z), Blocks.STONE.defaultBlockState());
			}
		}
		Navigator nav = new Navigator(w, 8192, false, Set.of());
		PathResult r = nav.findPath(p(0, 0, 0), Set.of(p(6, 1, 0)));
		assertTrue(r.found());
		assertEquals(p(6, 1, 0), r.waypoints().get(r.waypoints().size() - 1));
	}

	@Test
	void unreachableRespectsCap() {
		MapWorld w = new MapWorld(); // nothing anywhere
		w.set(p(0, -1, 0), Blocks.STONE.defaultBlockState());
		Navigator nav = new Navigator(w, 4096, false, Set.of());
		PathResult r = nav.findPath(p(0, 0, 0), Set.of(p(30, 0, 0)));
		assertFalse(r.found());
		assertTrue(r.status() == PathResult.Status.UNREACHABLE
				|| r.status() == PathResult.Status.NODE_CAP
				|| r.status() == PathResult.Status.OUT_OF_BOUNDS);
	}

	@Test
	void scaffoldFillsGap() {
		MapWorld w = flat(12);
		// one-cell gap at x=2
		for (int z = -8; z <= 8; z++) {
			w.map.remove(p(2, -1, z));
		}
		Navigator nav = new Navigator(w, 8192, true, Set.of());
		PathResult r = nav.findPath(p(0, 0, 0), Set.of(p(4, 0, 0)));
		assertTrue(r.found());
		assertTrue(r.scaffoldPlan().values().stream().flatMap(List::stream)
				.anyMatch(c -> c.getX() == 2));
	}

	@Test
	void plannedGapIsNotSafeStanceUntilBlockExists() {
		MapWorld w = flat(12);
		for (int z = -8; z <= 8; z++) {
			w.map.remove(p(2, -1, z)); // floor gap at x=2, scaffold planned but unbuilt
		}
		Navigator nav = new Navigator(w, 8192, true, Set.of());
		assertFalse(nav.isSafeStance(p(2, 0, 0)), "unbuilt gap must not be a standing cell");
		w.set(p(2, -1, 0), Blocks.STONE.defaultBlockState());
		assertTrue(nav.isSafeStance(p(2, 0, 0)), "the real block makes it a standing cell");
	}

	@Test
	void stairScaffoldNeedsBaseThenTop() {
		MapWorld w = flat(12);
		// destination platform at y=1 floating, no ground anywhere under x>=3
		for (int x = 3; x <= 10; x++) {
			for (int z = -4; z <= 4; z++) {
				w.map.remove(p(x, -1, z));
				w.set(p(x, 0, z), Blocks.STONE.defaultBlockState());
				w.set(p(x, 1, z), Blocks.STONE.defaultBlockState());
				w.map.remove(p(x, 0, z)); // floating slab at y=1? keep block at 1
			}
		}
		// simpler: a +1 ledge with air under the ledge cell
		MapWorld w2 = flat(12);
		w2.set(p(3, 0, 0), Blocks.STONE.defaultBlockState()); // ledge at y+1
		w2.map.remove(p(3, -1, 0));                            // nothing under ledge
		for (int z = -8; z <= 8; z++) {
			w2.map.remove(p(2, -1, z));                        // full-width gap row
		}
		Navigator nav = new Navigator(w2, 8192, true, Set.of());
		PathResult r = nav.findPath(p(0, 0, 0), Set.of(p(3, 1, 0)));
		assertTrue(r.found(), "path across one-gap must be found");
		// The missing ground cell below x=2 must be a planned scaffold cell,
		// attached to the waypoint that crosses it.
		BlockPos gapCell = p(2, -1, 0);
		BlockPos crossWaypoint = null;
		for (var e : r.scaffoldPlan().entrySet()) {
			if (e.getValue().contains(gapCell)) {
				crossWaypoint = e.getKey();
			}
		}
		assertNotNull(crossWaypoint, "gap cell missing from scaffold plan");
		assertEquals(p(2, 0, 0), crossWaypoint,
				"scaffold must attach to the waypoint that crosses the gap");
		assertTrue(r.waypoints().indexOf(crossWaypoint)
				< r.waypoints().indexOf(p(3, 1, 0)),
				"scaffold waypoint must precede the ledge step");
	}
}
