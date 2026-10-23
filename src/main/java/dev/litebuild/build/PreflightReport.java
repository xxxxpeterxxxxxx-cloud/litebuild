package dev.litebuild.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import dev.litebuild.model.Blueprint;
import dev.litebuild.model.BuildTarget;
import dev.litebuild.model.Phase;
import dev.litebuild.nav.NavWorld;
import dev.litebuild.plan.ItemResolver;
import dev.litebuild.plan.Planner;
import dev.litebuild.state.StateRules;

/**
 * Pre-build audit ("Preflight"): everything worth knowing BEFORE a build
 * starts — missing materials against the live inventory, manual work the mod
 * cannot perform (block-entity NBT such as sign text or chest contents,
 * entities, states with no usable item), risky/unsupported states (dynamic
 * pistons, unsupported fluids, unloaded chunks) and the session toggles
 * (fluids/activation) the plan needs while they are still off.
 *
 * Pure read-only aggregation over a Planner, the blueprint and an inventory
 * counter — no world mutation — so it is unit-testable and safe to compute
 * from any screen. Material keying mirrors {@code materialsReport()}: one
 * entry per registry id, dependent counterparts ride along with the primary.
 */
public final class PreflightReport {
	private static final int MAX_EXAMPLES = 3;

	/** Line severity hints; the UI maps them to colors. */
	public enum Tone { HEADER, INFO, OK, WARN, BAD }

	/** One summary line for screens/chat. */
	public record Line(String text, Tone tone) {}

	/** Per-item demand vs. inventory content. */
	public record Material(String itemId, Item item, int need, int have) {
		public int missing() {
			return Math.max(0, need - have);
		}

		public String displayName() {
			return new ItemStack(item).getHoverName().getString();
		}
	}

	/** Grouped manual/risk findings with up to {@link #MAX_EXAMPLES} positions. */
	public record TaskGroup(String key, String label, int count, List<BlockPos> examples) {}

	/** A session toggle the plan needs but that is currently disabled. */
	public record ToggleNeed(String label, int tasks) {}

	private final String blueprintName;
	private final int totalTargets;
	private final List<Material> materials;
	private final List<TaskGroup> manualTasks;
	private final List<TaskGroup> risks;
	private final List<ToggleNeed> requiredToggles;
	/** Distinct plan cells in unloaded chunks, or -1 when no world was given. */
	private final int unloadedCells;

	private PreflightReport(String blueprintName, int totalTargets,
							List<Material> materials, List<TaskGroup> manualTasks,
							List<TaskGroup> risks, List<ToggleNeed> requiredToggles,
							int unloadedCells) {
		this.blueprintName = blueprintName;
		this.totalTargets = totalTargets;
		this.materials = List.copyOf(materials);
		this.manualTasks = List.copyOf(manualTasks);
		this.risks = List.copyOf(risks);
		this.requiredToggles = List.copyOf(requiredToggles);
		this.unloadedCells = unloadedCells;
	}

	public String blueprintName() { return blueprintName; }
	public int totalTargets() { return totalTargets; }
	public List<Material> materials() { return materials; }
	public List<TaskGroup> manualTasks() { return manualTasks; }
	public List<TaskGroup> risks() { return risks; }
	public List<ToggleNeed> requiredToggles() { return requiredToggles; }
	public int unloadedCells() { return unloadedCells; }

	/** Required items the inventory cannot fully cover. */
	public List<Material> missing() {
		return materials.stream().filter(m -> m.missing() > 0).toList();
	}

	public int manualTotal() {
		return manualTasks.stream().mapToInt(TaskGroup::count).sum();
	}

	public int riskTotal() {
		return risks.stream().mapToInt(TaskGroup::count).sum();
	}

	/** True when nothing blocks or warns: materials complete, no toggles due. */
	public boolean clear() {
		return missing().isEmpty() && requiredToggles.isEmpty() && risks.isEmpty();
	}

	/** Compact one-liner for the menu status card. */
	public String headline() {
		List<String> parts = new ArrayList<>();
		int missingKinds = missing().size();
		if (missingKinds > 0) {
			parts.add(missingKinds + (missingKinds == 1
					? " material missing" : " materials missing"));
		}
		int manual = manualTotal();
		if (manual > 0) {
			parts.add(manual + (manual == 1
					? " manual task" : " manual tasks"));
		}
		int risk = riskTotal();
		if (risk > 0) {
			parts.add(risk + (risk == 1 ? " risk" : " risks"));
		}
		if (!requiredToggles.isEmpty()) {
			StringBuilder sb = new StringBuilder("Toggles: ");
			for (int i = 0; i < requiredToggles.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(requiredToggles.get(i).label());
			}
			parts.add(sb.toString());
		}
		return parts.isEmpty()
				? "Preflight: ready"
				: "Preflight: " + String.join(" · ", parts);
	}

	/** German report lines, grouped like the sections in the detail screen. */
	public List<Line> summaryLines() {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line("Preflight: " + blueprintName
				+ " (" + totalTargets + " tasks)", Tone.HEADER));
		List<Material> miss = missing();
		if (miss.isEmpty()) {
			lines.add(new Line("Materials: complete", Tone.OK));
		} else {
			lines.add(new Line("Missing materials:", Tone.WARN));
			for (Material m : miss) {
				lines.add(new Line("  " + m.displayName() + ": " + m.have()
						+ "/" + m.need() + " (missing " + m.missing() + ")", Tone.BAD));
			}
		}
		if (manualTasks.isEmpty()) {
			lines.add(new Line("Manual tasks: none", Tone.OK));
		} else {
			lines.add(new Line("Manual tasks:", Tone.WARN));
			for (TaskGroup g : manualTasks) {
				lines.add(new Line("  " + g.count() + "x " + g.label()
						+ examplesSuffix(g), Tone.INFO));
			}
		}
		if (risks.isEmpty()) {
			lines.add(new Line("Risks: none", Tone.OK));
		} else {
			lines.add(new Line("Risks:", Tone.WARN));
			for (TaskGroup g : risks) {
				lines.add(new Line("  " + g.count() + "x " + g.label()
						+ examplesSuffix(g), Tone.WARN));
			}
		}
		if (requiredToggles.isEmpty()) {
			lines.add(new Line("Toggles: none needed", Tone.OK));
		} else {
			StringBuilder sb = new StringBuilder("Toggles needed: ");
			for (int i = 0; i < requiredToggles.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				ToggleNeed t = requiredToggles.get(i);
				sb.append(t.label()).append(" (").append(t.tasks()).append(')');
			}
			lines.add(new Line(sb.toString(), Tone.WARN));
		}
		return lines;
	}

	private static String examplesSuffix(TaskGroup g) {
		if (g.examples().isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(" (z.B. ");
		for (int i = 0; i < g.examples().size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(g.examples().get(i).toShortString());
		}
		return sb.append(')').toString();
	}

	/**
	 * Compute the audit. {@code planner} may be null — a fresh plan over the
	 * blueprint is built then; pass the live planner to skip already verified
	 * or conflicted cells. {@code world} may be null — chunk-loaded cells are
	 * then reported as -1 via {@link #unloadedCells()}.
	 */
	public static PreflightReport analyze(Blueprint blueprint, Planner planner,
										ToIntFunction<Item> inventory, NavWorld world,
										boolean fluidsEnabled, boolean activationEnabled) {
		Planner plan = planner != null ? planner : new Planner(blueprint);
		Map<String, int[]> needed = new TreeMap<>();
		Map<String, Item> items = new HashMap<>();
		Map<String, Acc> manual = new LinkedHashMap<>();
		Map<String, Acc> risks = new LinkedHashMap<>();
		Set<BlockPos> unloaded = new LinkedHashSet<>();
		Set<BlockPos> manualCounted = new HashSet<>();
		Set<BlockPos> interactPositions = new HashSet<>();
		int fluidTasks = 0;
		int activationTasks = 0;

		// Block-entity NBT the schematic stores but the mod never writes.
		for (BlockPos pos : blueprint.blockEntityData()) {
			String key = beKey(blueprint.blocks().get(pos));
			acc(manual, key, beLabel(key), pos);
			manualCounted.add(pos);
		}
		if (blueprint.entities() > 0) {
			manual.computeIfAbsent("entities",
					k -> new Acc("Entities platzieren")).extra += blueprint.entities();
		}

		for (BuildTarget t : plan.targets()) {
			if (world != null && !world.isLoaded(t.pos())) {
				unloaded.add(t.pos());
			}
			if (plan.isVerified(t) || plan.isConflicted(t.pos())
					|| plan.isDependent(t)) {
				continue;
			}
			BlockState st = t.state();
			Block block = st.getBlock();

			if (isDynamicPiston(st)) {
				acc(risks, "piston", "Dynamischer Kolbenzustand", t.pos());
				continue; // no usable item exists either way
			}
			if (t.phase() == Phase.FLUIDS) {
				fluidTasks++;
				Fluid f = Planner.isWaterlogTask(t) ? Fluids.WATER
						: st.getFluidState().getType();
				if (f == Fluids.LAVA) {
					count(needed, items, Items.LAVA_BUCKET);
				} else if (f == Fluids.WATER) {
					count(needed, items, Items.WATER_BUCKET);
				} else {
					acc(risks, "fluid", "Unsupported fluid", t.pos());
				}
			} else {
				Item item = ItemResolver.itemFor(st);
				if (item != null) {
					count(needed, items, item);
				} else {
					acc(manual, "noitem", "Place manually (no item)", t.pos());
				}
			}
			if (t.phase() == Phase.ACTIVATION) {
				activationTasks++;
			}
			// Live-control states a fresh placement cannot reach need a
			// right-click fix — and those are gated by the activation toggle.
			if (isLiveControl(st)
					&& StateRules.nextInteractionStep(st,
							StateRules.initialPlacementState(st)) != null) {
				interactPositions.add(t.pos());
			}
			// Command blocks and spawners are manual even without stored NBT.
			if (!manualCounted.contains(t.pos())
					&& (block instanceof CommandBlock || block instanceof SpawnerBlock)) {
				String key = block instanceof CommandBlock ? "command" : "spawner";
				acc(manual, key, beLabel(key), t.pos());
				manualCounted.add(t.pos());
			}
		}

		List<Material> materials = new ArrayList<>();
		for (Map.Entry<String, int[]> e : needed.entrySet()) {
			Item item = items.get(e.getKey());
			materials.add(new Material(e.getKey(), item, e.getValue()[0],
					Math.max(0, inventory.applyAsInt(item))));
		}

		int activationNeed = activationTasks + interactPositions.size();
		if (fluidTasks > 0 && !fluidsEnabled) {
			risks.computeIfAbsent("fluids_off",
					k -> new Acc("Fluids disabled")).extra += fluidTasks;
		}
		if (activationNeed > 0 && !activationEnabled) {
			risks.computeIfAbsent("activation_off",
					k -> new Acc("Activation disabled")).extra += activationNeed;
		}
		if (!unloaded.isEmpty()) {
			risks.computeIfAbsent("unloaded",
					k -> new Acc("Cells in unloaded chunks")).extra += unloaded.size();
		}

		List<ToggleNeed> toggles = new ArrayList<>();
		if (fluidTasks > 0 && !fluidsEnabled) {
			toggles.add(new ToggleNeed("Fluids", fluidTasks));
		}
		if (activationNeed > 0 && !activationEnabled) {
			toggles.add(new ToggleNeed("Activation", activationNeed));
		}

		return new PreflightReport(blueprint.name(), plan.targets().size(), materials,
				groups(manual), groups(risks), toggles,
				world == null ? -1 : unloaded.size());
	}

	private static void count(Map<String, int[]> needed, Map<String, Item> items,
							  Item item) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		items.put(id, item);
		needed.computeIfAbsent(id, k -> new int[1])[0]++;
	}

	private static void acc(Map<String, Acc> map, String key, String label,
							BlockPos pos) {
		map.computeIfAbsent(key, k -> new Acc(label)).add(pos);
	}

	private static List<TaskGroup> groups(Map<String, Acc> map) {
		List<TaskGroup> out = new ArrayList<>();
		for (Map.Entry<String, Acc> e : map.entrySet()) {
			Acc a = e.getValue();
			List<BlockPos> examples = a.positions.stream()
					.limit(MAX_EXAMPLES).toList();
			out.add(new TaskGroup(e.getKey(), a.label,
					a.positions.size() + a.extra, examples));
		}
		return out;
	}

	private static final class Acc {
		final String label;
		final Set<BlockPos> positions = new LinkedHashSet<>();
		/** Positionless counts (entities, per-task risks). */
		int extra;

		Acc(String label) {
			this.label = label;
		}

		void add(BlockPos pos) {
			positions.add(pos.immutable());
		}
	}

	private static String beKey(BlockState st) {
		if (st == null) {
			return "blockentity";
		}
		Block b = st.getBlock();
		if (b instanceof SignBlock) {
			return "sign";
		}
		if (b instanceof CommandBlock) {
			return "command";
		}
		if (b instanceof SpawnerBlock) {
			return "spawner";
		}
		if (isContainer(b)) {
			return "container";
		}
		return "blockentity";
	}

	private static String beLabel(String key) {
		return switch (key) {
			case "sign" -> "Sign text";
			case "command" -> "Command block";
			case "spawner" -> "Spawner data";
			case "container" -> "Container contents";
			default -> "Block entity data";
		};
	}

	private static boolean isContainer(Block b) {
		return b instanceof AbstractChestBlock || b instanceof BarrelBlock
				|| b instanceof ShulkerBoxBlock || b instanceof AbstractFurnaceBlock
				|| b instanceof DispenserBlock || b instanceof HopperBlock
				|| b instanceof BrewingStandBlock || b instanceof DecoratedPotBlock
				|| b instanceof CrafterBlock || b instanceof ChiseledBookShelfBlock
				|| b instanceof LecternBlock || b instanceof BeehiveBlock
				|| b instanceof JukeboxBlock;
	}

	private static boolean isDynamicPiston(BlockState st) {
		Block b = st.getBlock();
		return b instanceof MovingPistonBlock || b instanceof PistonHeadBlock
				|| (b instanceof PistonBaseBlock
						&& st.hasProperty(BlockStateProperties.EXTENDED)
						&& st.getValue(BlockStateProperties.EXTENDED));
	}

	/** Same set as the controller's interaction gate (activation toggle). */
	private static boolean isLiveControl(BlockState st) {
		Block b = st.getBlock();
		return b instanceof LeverBlock || b instanceof DoorBlock
				|| b instanceof TrapDoorBlock;
	}
}
