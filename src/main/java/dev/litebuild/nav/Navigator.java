package dev.litebuild.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import dev.litebuild.nav.PathResult.Status;

/**
 * Bounded cardinal A* over loaded, collision-checked standing cells
 * (player box 0.6 x 1.8). Step up/down max 1, no void jumps, no sprint.
 * Ground must have a full sturdy top face. Body cells reject fluids,
 * fire/lava/cactus/powder-snow/magma hazards (with a lava margin) and
 * anything with a collision shape. Scaffold expansion is allowed up to
 * {@value #MAX_SCAFFOLD} temporary cells per plan, each needing a real
 * attach face from an existing solid neighbor.
 */
public class Navigator {
	public static final int MAX_SCAFFOLD = 8;
	private static final int MAX_HORIZONTAL = 24;
	private static final int MAX_VERTICAL = 12;

	private final NavWorld world;
	private final net.minecraft.world.level.BlockGetter getter;
	private final int nodeCap;
	private final boolean allowScaffold;
	/** Blueprinted non-air cells a scaffold must never overwrite. */
	private final Set<BlockPos> reserved;
	/** Cells the placement solver already proved unreachable — never
	 *  plan a support there again, route around instead. */
	private final Set<BlockPos> forbiddenScaffold;
	private BlockPos searchStart;

	public Navigator(NavWorld world, int nodeCap, boolean allowScaffold, Set<BlockPos> reserved) {
		this(world, nodeCap, allowScaffold, reserved, Set.of());
	}

	public Navigator(NavWorld world, int nodeCap, boolean allowScaffold,
					 Set<BlockPos> reserved, Set<BlockPos> forbiddenScaffold) {
		this.world = world;
		this.getter = world instanceof net.minecraft.world.level.BlockGetter g
				? g : EmptyGetter.INSTANCE;
		this.nodeCap = nodeCap;
		this.allowScaffold = allowScaffold;
		this.reserved = reserved == null ? Set.of() : reserved;
		this.forbiddenScaffold = forbiddenScaffold == null
				? Set.of() : forbiddenScaffold;
	}

	private record Node(BlockPos pos, double g, double f, Node parent,
						List<BlockPos> newScaffold) {}

	public PathResult findPath(BlockPos start, Set<BlockPos> goals) {
		this.searchStart = start;
		if (goals.isEmpty()) {
			return new PathResult(List.of(), Map.of(), Status.UNREACHABLE);
		}
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator
				.comparingDouble((Node n) -> n.f)
				.thenComparingInt(n -> n.newScaffold().size())
				.thenComparingInt(n -> n.pos().getY())
				.thenComparingInt(n -> n.pos().getX())
				.thenComparingInt(n -> n.pos().getZ()));
		Map<BlockPos, Double> best = new HashMap<>();
		int expansions = 0;

		open.add(new Node(start, 0, heuristic(start, goals), null, List.of()));
		best.put(start, 0.0);

		while (!open.isEmpty()) {
			Node node = open.poll();
			if (goals.contains(node.pos())) {
				return reconstruct(node);
			}
			if (++expansions > nodeCap) {
				return new PathResult(List.of(), Map.of(), Status.NODE_CAP);
			}
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos next = node.pos().relative(dir).above(dy);
					List<BlockPos> added = new ArrayList<>();
					if (!canEnter(node, next, added)) {
						continue;
					}
					double g = node.g() + 1.0 + Math.max(0, dy) * 0.4 + added.size() * 4.0;
					Double prev = best.get(next);
					if (prev != null && prev <= g) {
						continue;
					}
					best.put(next, g);
					open.add(new Node(next, g, g + heuristic(next, goals), node,
							List.copyOf(added)));
				}
			}
		}
		return new PathResult(List.of(), Map.of(), Status.UNREACHABLE);
	}

	private PathResult reconstruct(Node goal) {
		Deque<BlockPos> cells = new ArrayDeque<>();
		Map<BlockPos, List<BlockPos>> scaffold = new LinkedHashMap<>();
		List<Node> chain = new ArrayList<>();
		for (Node cur = goal; cur.parent() != null; cur = cur.parent()) {
			chain.add(cur);
		}
		for (int i = chain.size() - 1; i >= 0; i--) {
			Node n = chain.get(i);
			cells.addLast(n.pos());
			if (!n.newScaffold().isEmpty()) {
				scaffold.put(n.pos(), n.newScaffold());
			}
		}
		return new PathResult(List.copyOf(cells), scaffold, Status.FOUND);
	}

	private double heuristic(BlockPos pos, Set<BlockPos> goals) {
		double h = Double.MAX_VALUE;
		for (BlockPos goal : goals) {
			double d = Math.abs(pos.getX() - goal.getX())
					+ Math.abs(pos.getZ() - goal.getZ())
					+ 1.5 * Math.abs(pos.getY() - goal.getY());
			if (d < h) {
				h = d;
			}
		}
		return h;
	}

	private boolean inBounds(BlockPos next) {
		return Math.abs(next.getX() - searchStart.getX()) <= MAX_HORIZONTAL
				&& Math.abs(next.getZ() - searchStart.getZ()) <= MAX_HORIZONTAL
				&& Math.abs(next.getY() - searchStart.getY()) <= MAX_VERTICAL;
	}

	private boolean canEnter(Node from, BlockPos dest, List<BlockPos> added) {
		if (!inBounds(dest)) {
			return false;
		}
		if (!world.isLoaded(dest) || !world.isLoaded(dest.above()) || !world.isLoaded(dest.below())) {
			return false;
		}
		Set<BlockPos> planned = collectPlanned(from);
		// Body cells: passable (no collision), no fluid, no hazard — and
		// never inside a support block this path intends to place.
		if (planned.contains(dest) || planned.contains(dest.above())
				|| !isBodyFree(dest) || !isBodyFree(dest.above())) {
			return false;
		}
		if (isHazardAt(dest) || isHazardAt(dest.above())) {
			return false;
		}
		int dy = dest.getY() - from.pos().getY();
		// Step-up always requires headroom above the current stance too.
		if (dy == 1 && !isBodyFree(from.pos().above(2))) {
			return false;
		}

		BlockPos ground = dest.below();
		if (isStandableGround(ground) || planned.contains(ground)) {
			return true;
		}
		if (!allowScaffold) {
			return false;
		}
		int scaffoldBudget = MAX_SCAFFOLD - planned.size();
		if (scaffoldBudget <= 0 || !canScaffoldInto(ground)) {
			return false;
		}
		if (dy <= 0) {
			// Flat or one down: single missing floor cell.
			if (!hasAttachFace(ground, planned)) {
				return false;
			}
			added.add(ground);
			return true;
		}
		// +1 stair: base at dest.y-2 first if needed, then top at dest.y-1.
		BlockPos base = dest.below(2);
		boolean baseOk = isStandableGround(base) || planned.contains(base);
		if (!baseOk) {
			if (scaffoldBudget < 2 || !canScaffoldInto(base) || !hasAttachFace(base, planned)) {
				return false;
			}
			added.add(base);
			planned.add(base);
		}
		if (!hasAttachFace(ground, planned)) {
			return false;
		}
		added.add(ground);
		return true;
	}

	private Set<BlockPos> collectPlanned(Node node) {
		Set<BlockPos> planned = new HashSet<>();
		for (Node cur = node; cur != null; cur = cur.parent()) {
			planned.addAll(cur.newScaffold());
		}
		return planned;
	}

	private boolean isBodyFree(BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (!state.getFluidState().isEmpty()) {
			return false; // reject water/lava body cells incl. flowing
		}
		return state.isAir()
				|| state.getCollisionShape(getter, pos, CollisionContext.empty()).isEmpty();
	}

	public boolean isHazardAt(BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW)
				|| state.is(Blocks.MAGMA_BLOCK) || state.is(BlockTags.CAMPFIRES)) {
			return true;
		}
		for (Direction dir : Direction.values()) {
			if (world.getBlockState(pos.relative(dir)).is(Blocks.LAVA)) {
				return true;
			}
		}
		return false;
	}

	private boolean isStandableGround(BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return !isHazardAt(pos)
				&& state.isFaceSturdy(getter, pos, Direction.UP);
	}

	/** Public revalidation for the controller before each move step. */
	public boolean isSafeStance(BlockPos pos) {
		return world.isLoaded(pos) && world.isLoaded(pos.above()) && world.isLoaded(pos.below())
				&& isBodyFree(pos) && isBodyFree(pos.above())
				&& !isHazardAt(pos) && !isHazardAt(pos.above())
				&& isStandableGround(pos.below());
	}

	private boolean canScaffoldInto(BlockPos pos) {
		if (reserved.contains(pos) || forbiddenScaffold.contains(pos)) {
			return false;
		}
		BlockState state = world.getBlockState(pos);
		if (!state.canBeReplaced() || !state.getFluidState().isEmpty() || isHazardAt(pos)) {
			return false;
		}
		// No scaffold directly adjacent to redstone components or fluids.
		for (Direction dir : Direction.values()) {
			BlockState n = world.getBlockState(pos.relative(dir));
			if (!n.getFluidState().isEmpty()) {
				return false;
			}
			if (n.is(Blocks.REDSTONE_WIRE) || n.is(Blocks.REDSTONE_TORCH)
					|| n.is(Blocks.REDSTONE_BLOCK) || n.is(Blocks.REPEATER)
					|| n.is(Blocks.COMPARATOR) || n.is(Blocks.OBSERVER)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A scaffold cell needs a real clickable neighbor face to place against.
	 * Planned cells count too (the stair base is placed first), but the base
	 * itself was only accepted when a real world face existed, so no
	 * unsupported floating chain can form.
	 */
	private boolean hasAttachFace(BlockPos pos, Set<BlockPos> planned) {
		for (Direction dir : Direction.values()) {
			BlockPos n = pos.relative(dir);
			BlockState state = world.getBlockState(n);
			if (planned.contains(n)) {
				return true;
			}
			if (!state.isAir()
					&& state.isFaceSturdy(getter, n, dir.getOpposite())) {
				return true;
			}
		}
		return false;
	}

	/** Fallback getter for map-based test worlds. */
	private static final class EmptyGetter implements net.minecraft.world.level.BlockGetter {
		static final EmptyGetter INSTANCE = new EmptyGetter();
		@Override public BlockState getBlockState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState();
		}
		@Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
			return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
		}
		@Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}
		@Override public int getHeight() { return 384; }
		@Override public int getMinY() { return -64; }
	}
}
