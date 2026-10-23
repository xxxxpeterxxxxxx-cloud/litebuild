package dev.litebuild.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import dev.litebuild.mixin.BlockItemAccessor;
import dev.litebuild.model.ActionKind;
import dev.litebuild.model.BuildTarget;
import dev.litebuild.model.PlacementAction;
import dev.litebuild.state.StateRules;

/**
 * Finds legal placements: stances whose eye raycasts onto a real face of a
 * neighbor (or of the target cell itself for replaceable same-cell stacking
 * like slab->double-slab) such that the item's predicted placement state
 * equals the normalized target. The clip endpoint is pushed slightly inside
 * the face so the raycast lands on the surface, never at a dead point
 * outside collision.
 */
public class PlacementSolver {
	private static final double[] FACE_SAMPLES = {0.25, 0.5, 0.75};
	private static final double EYE_HEIGHT = 1.62;
	private static final double REACH_MARGIN = 0.15;
	private static final double INTO_FACE = 0.002;
	private static final double PLAYER_W = 0.3; // half-width 0.6 box
	private static final double PLAYER_H = 1.8;

	public record SolvedAction(PlacementAction action, BlockPos stance, Vec3 eye,
							   BlockState predicted) {}

	/**
	 * Every outline face of {@code clicked} actually visible from {@code eye}
	 * within reach. Returns the REAL BlockHitResults (surface intersection),
	 * not the synthetic sample point.
	 */
	public static List<BlockHitResult> visibleFaces(Level level, BlockPos clicked,
													Vec3 eye, Player player, double reach) {
		List<BlockHitResult> hits = new ArrayList<>();
		BlockState state = level.getBlockState(clicked);
		VoxelShape shape = state.getShape(level, clicked, CollisionContext.empty());
		if (shape.isEmpty()) {
			return hits;
		}
		for (AABB box : shape.toAabbs()) {
			for (Direction face : Direction.values()) {
				for (double u : FACE_SAMPLES) {
					for (double v : FACE_SAMPLES) {
						Vec3 local = facePoint(box, face, u, v);
						Vec3 point = Vec3.atLowerCornerOf(clicked).add(local);
						if (eye.distanceTo(point) > reach - REACH_MARGIN) {
							continue;
						}
						Vec3 inside = point.subtract(
								face.getStepX() * INTO_FACE,
								face.getStepY() * INTO_FACE,
								face.getStepZ() * INTO_FACE);
						BlockHitResult real = level.clip(new ClipContext(eye, inside,
								ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
						if (real.getType() == HitResult.Type.BLOCK
								&& real.getBlockPos().equals(clicked)
								&& real.getDirection() == face) {
							hits.add(real);
						}
					}
				}
			}
		}
		return hits;
	}

	private static Vec3 facePoint(AABB bounds, Direction face, double u, double v) {
		return switch (face.getAxis()) {
			case Y -> new Vec3(
					lerp(bounds.minX, bounds.maxX, u),
					face == Direction.UP ? bounds.maxY : bounds.minY,
					lerp(bounds.minZ, bounds.maxZ, v));
			case X -> new Vec3(
					face == Direction.EAST ? bounds.maxX : bounds.minX,
					lerp(bounds.minY, bounds.maxY, u),
					lerp(bounds.minZ, bounds.maxZ, v));
			case Z -> new Vec3(
					lerp(bounds.minX, bounds.maxX, u),
					lerp(bounds.minY, bounds.maxY, v),
					face == Direction.SOUTH ? bounds.maxZ : bounds.minZ);
		};
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/**
	 * Solve a placement for {@code target} using {@code item} from one of
	 * {@code stances} (nearest-first; the player's current cell included
	 * when valid).
	 */
	public SolvedAction solve(BuildTarget target, Item item, List<BlockPos> stances,
							  Level level, Player player, double reach) {
		return solve(target, item, stances, level, player, reach, java.util.Set.of());
	}

	/**
	 * As {@link #solve} but skipping hits already attempted this target —
	 * each live-revalidation retry must explore a different face/stance
	 * instead of deterministically repeating the occluded one.
	 */
	public SolvedAction solve(BuildTarget target, Item item, List<BlockPos> stances,
							  Level level, Player player, double reach,
							  java.util.Set<String> excludeHits) {
		BlockPos pos = target.pos();
		if (!placeableInto(target, item, pos, level, player)) {
			return null;
		}
		// Prefer the player's ACTUAL current eye: when it already sees a
		// valid face there is no walking to do at all.
		SolvedAction fromHere = tryFromEye(target, item, player.getEyePosition(),
				player.blockPosition(), level, player, reach, excludeHits);
		if (fromHere != null) {
			return fromHere;
		}
		for (BlockPos stance : stances) {
			if (stance.equals(player.blockPosition())) {
				continue; // actual eye already tried above
			}
			Vec3 eye = Vec3.atBottomCenterOf(stance).add(0, EYE_HEIGHT, 0);
			SolvedAction ok = tryFromEye(target, item, eye, stance,
					level, player, reach, excludeHits);
			if (ok != null) {
				return ok;
			}
		}
		return null;
	}

	private boolean placeableInto(BuildTarget target, Item item, BlockPos pos,
								  Level level, Player player) {
		BlockState wanted = StateRules.initialPlacementState(target.state());
		BlockState current = level.getBlockState(pos);
		return StateRules.constructionEquivalent(wanted, current)
				|| StateRules.canStackSlab(wanted, current)
				|| current.canBeReplaced(new BlockPlaceContext(player,
						net.minecraft.world.InteractionHand.MAIN_HAND,
						new ItemStack(item),
						new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
	}

	/**
	 * Final resolve from the player's REAL eye position after walking and
	 * braking. The stance-centered solve can pick a face the actual ray
	 * cannot see (a few tenths of eye offset occludes thin geometry); this
	 * reruns the identical hit search from the true eye so the click that
	 * follows is exactly the pick vanilla performs.
	 */
	public SolvedAction solveFromEye(BuildTarget target, Item item, Vec3 eye,
									 Level level, Player player, double reach,
									 java.util.Set<String> excludeHits) {
		BlockPos pos = target.pos();
		if (!placeableInto(target, item, pos, level, player)) {
			return null;
		}
		return tryFromEye(target, item, eye, player.blockPosition(),
				level, player, reach, excludeHits);
	}

	private SolvedAction tryFromEye(BuildTarget target, Item item, Vec3 eye,
									BlockPos stance, Level level, Player player,
									double reach, java.util.Set<String> excludeHits) {
		BlockPos pos = target.pos();
		BlockState wanted = StateRules.initialPlacementState(target.state());
		// (a) click a neighbor face that fills the target cell
		for (Direction face : Direction.values()) {
			BlockPos neighbor = pos.relative(face.getOpposite());
			if (!level.isLoaded(neighbor) || level.getBlockState(neighbor).isAir()) {
				continue;
			}
			for (BlockHitResult real : visibleFaces(level, neighbor, eye, player, reach)) {
				if (real.getDirection() != face
						|| excludeHits.contains(neighbor + "/" + face)) {
					continue;
				}
				SolvedAction ok = tryHit(target, wanted, item, real, pos,
						stance, eye, level, player);
				if (ok != null) {
					return ok;
				}
			}
		}
		// (b) click the target cell itself (same-cell stacking: slab->double)
		for (BlockHitResult real : visibleFaces(level, pos, eye, player, reach)) {
			if (!real.getBlockPos().equals(pos)
					|| excludeHits.contains(pos + "/" + real.getDirection())) {
				continue;
			}
			SolvedAction ok = tryHit(target, wanted, item, real, pos,
					stance, eye, level, player);
			if (ok != null) {
				return ok;
			}
		}
		return null;
	}

	private SolvedAction tryHit(BuildTarget target, BlockState wanted, Item item,
								BlockHitResult hit, BlockPos pos, BlockPos stance,
								Vec3 eye, Level level, Player player) {
		BlockState predicted = predictPlacement(item, player, level, hit, pos, eye,
				hit.getLocation());
		if (predicted == null || !StateRules.constructionEquivalent(wanted, predicted)) {
			return null;
		}
		if (!predicted.canSurvive(level, pos)) {
			return null;
		}
		if (bodyIntersects(pos, predicted, level, stance)) {
			return null;
		}
		boolean sneak = isInteractive(level.getBlockState(hit.getBlockPos()));
		return new SolvedAction(new PlacementAction(pos, predicted, hit, item, sneak,
				ActionKind.PLACE), stance, eye, predicted);
	}

	private BlockState predictPlacement(Item item, Player player, Level level,
										BlockHitResult hit, BlockPos pos,
										Vec3 eye, Vec3 point) {
		if (!(item instanceof BlockItem blockItem)) {
			return null;
		}
		ItemStack stack = player.getMainHandItem().getItem() == item
				? player.getMainHandItem() : new ItemStack(item);
		SimulatedContext context = new SimulatedContext(player, level, stack, hit,
				eye, point);
		if (!context.getClickedPos().equals(pos)) {
			return null;
		}
		try {
			return ((BlockItemAccessor) blockItem).litebuild$getPlacementState(context);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Whether placing at {@code pos} would intersect the player standing at
	 * the hypothetical {@code stance} (0.6 x 1.8 box) — not the player's
	 * current box when the stance is elsewhere.
	 */
	public static boolean bodyIntersects(BlockPos pos, BlockState placed,
										 Level level, BlockPos stance) {
		var shape = placed.getCollisionShape(level, pos, CollisionContext.empty());
		if (shape.isEmpty()) {
			return false;
		}
		AABB stanceBox = new AABB(
				stance.getX() + 0.5 - PLAYER_W, stance.getY(), stance.getZ() + 0.5 - PLAYER_W,
				stance.getX() + 0.5 + PLAYER_W, stance.getY() + PLAYER_H, stance.getZ() + 0.5 + PLAYER_W);
		for (AABB box : shape.toAabbs()) {
			if (box.move(pos).intersects(stanceBox)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isInteractive(BlockState state) {
		var block = state.getBlock();
		return block instanceof net.minecraft.world.level.block.BaseEntityBlock
				|| block instanceof net.minecraft.world.level.block.DoorBlock
				|| block instanceof net.minecraft.world.level.block.TrapDoorBlock
				|| block instanceof net.minecraft.world.level.block.FenceGateBlock
				|| block instanceof net.minecraft.world.level.block.ButtonBlock
				|| block instanceof net.minecraft.world.level.block.LeverBlock
				|| block instanceof net.minecraft.world.level.block.NoteBlock
				|| block instanceof net.minecraft.world.level.block.DiodeBlock
				|| block instanceof net.minecraft.world.level.block.CraftingTableBlock
				|| block instanceof net.minecraft.world.level.block.AnvilBlock
				|| block instanceof net.minecraft.world.level.block.CakeBlock
				|| block instanceof net.minecraft.world.level.block.AbstractCauldronBlock
				|| block instanceof net.minecraft.world.level.block.SignBlock
				|| block instanceof net.minecraft.world.level.block.ChiseledBookShelfBlock
				|| block instanceof net.minecraft.world.level.block.JukeboxBlock
				|| block instanceof net.minecraft.world.level.block.BedBlock
				|| block instanceof net.minecraft.world.level.block.RespawnAnchorBlock
				|| block instanceof net.minecraft.world.level.block.BeehiveBlock
				|| block == Blocks.DRAGON_EGG;
	}

	/**
	 * BlockPlaceContext whose looking directions are derived from the
	 * hypothetical eye->hit geometry, so orientation prediction never
	 * rotates the real player.
	 */
	private static final class SimulatedContext extends BlockPlaceContext {
		private final Direction horizontal;
		private final Direction vertical;
		private final Direction[] nearest;
		private final float rotation;

		SimulatedContext(Player player, Level level, ItemStack stack,
						 BlockHitResult hit, Vec3 eye, Vec3 point) {
			super(level, player, net.minecraft.world.InteractionHand.MAIN_HAND, stack, hit);
			Vec3 look = point.subtract(eye);
			double dx = look.x, dy = look.y, dz = look.z;
			this.rotation = (float) Math.toDegrees(Math.atan2(-dx, dz));
			this.horizontal = Direction.fromYRot(this.rotation);
			// Vanilla getNearestLookingVerticalDirection is pitch-sign only:
			// looking down (dy<0) yields UP, looking up yields DOWN.
			this.vertical = dy < 0 ? Direction.UP : Direction.DOWN;
			this.nearest = nearestDirections(look.normalize(),
					this.replaceClicked ? this.getClickedFace() : null);
		}

		private static Direction[] nearestDirections(Vec3 look, Direction clickedFace) {
			Direction[] byLook = java.util.Arrays.stream(Direction.values())
					.sorted((a, b) -> Double.compare(dot(look, b), dot(look, a)))
					.toArray(Direction[]::new);
			if (clickedFace == null) {
				return byLook;
			}
			// Vanilla DirectionUseContext: for replace-clicked contexts the
			// clicked face axis is ranked before all others (face and its
			// opposite in look order, then the remaining directions).
			List<Direction> out = new ArrayList<>(6);
			for (Direction d : byLook) {
				if (d.getAxis() == clickedFace.getAxis()) {
					out.add(d);
				}
			}
			for (Direction d : byLook) {
				if (d.getAxis() != clickedFace.getAxis()) {
					out.add(d);
				}
			}
			return out.toArray(Direction[]::new);
		}

		private static double dot(Vec3 look, Direction dir) {
			return look.x * dir.getStepX() + look.y * dir.getStepY() + look.z * dir.getStepZ();
		}

		@Override public Direction getHorizontalDirection() { return horizontal; }
		@Override public Direction getNearestLookingDirection() { return nearest[0]; }
		@Override public Direction getNearestLookingVerticalDirection() { return vertical; }
		@Override public Direction[] getNearestLookingDirections() { return nearest; }
		@Override public float getRotation() { return rotation; }
	}
}
