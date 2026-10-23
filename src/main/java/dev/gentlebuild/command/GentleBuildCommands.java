package dev.gentlebuild.command;

import java.nio.file.Files;
import java.nio.file.Path;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.model.BuildIssue;

public final class GentleBuildCommands {

	private GentleBuildCommands() {}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		var root = ClientCommands.literal("gentlebuild");
		var alias = ClientCommands.literal("gb");
		for (var base : new com.mojang.brigadier.builder.LiteralArgumentBuilder[]{root, alias}) {
			@SuppressWarnings("unchecked")
			var b = (com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>) base;
			b.executes(c -> {
				c.getSource().sendFeedback(Component.literal(
						"GentleBuild: select | load <datei> | start | pause | stop"
								+ " | status | materials | issues | fluids | activate | verify | cleanup"));
				return 1;
			});
			b.then(ClientCommands.literal("select")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { BuildController.get().importSelected();
								fb(c.getSource(), "Snapshot started (or failed, see status)"); return 1; }))
					.then(ClientCommands.literal("load")
							.requires(FabricClientCommandSource::attended)
							.then(ClientCommands.argument("file", StringArgumentType.greedyString())
									.executes(c -> load(c.getSource(),
											StringArgumentType.getString(c, "file")))))
					.then(ClientCommands.literal("start")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { BuildController.get().start();
								fb(c.getSource(), "Status: " + BuildController.get().state()); return 1; }))
					.then(ClientCommands.literal("pause")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { BuildController.get().pause("Command");
								fb(c.getSource(), "Paused"); return 1; }))
					.then(ClientCommands.literal("stop")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { BuildController.get().stop();
								fb(c.getSource(), "Stopped"); return 1; }))
					.then(ClientCommands.literal("status")
							.executes(c -> status(c.getSource())))
					.then(ClientCommands.literal("materials")
							.executes(c -> materials(c.getSource())))
					.then(ClientCommands.literal("issues")
							.executes(c -> issues(c.getSource())))
					.then(ClientCommands.literal("fluids")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { var cfg = BuildController.get().config();
								cfg.fluidsEnabled = !cfg.fluidsEnabled;
								fb(c.getSource(), "Fluids: " + (cfg.fluidsEnabled ? "an" : "aus")); return 1; }))
					.then(ClientCommands.literal("activate")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { var cfg = BuildController.get().config();
								cfg.activationEnabled = !cfg.activationEnabled;
								fb(c.getSource(), "Activation: " + (cfg.activationEnabled ? "an" : "aus")); return 1; }))
					.then(ClientCommands.literal("verify")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { BuildController.get().verify();
								fb(c.getSource(), "Final verification started"); return 1; }))
					.then(ClientCommands.literal("cleanup")
							.requires(FabricClientCommandSource::attended)
							.executes(c -> { BuildController.get().startCleanup();
								fb(c.getSource(), "Cleanup: " + BuildController.get().supportCount() + " supports"); return 1; }));
			dispatcher.register(b);
		}
	}

	private static int ok(FabricClientCommandSource src) {
		return 1;
	}

	private static void fb(FabricClientCommandSource src, String msg) {
		src.sendFeedback(Component.literal("GentleBuild: " + msg));
	}

	private static int status(FabricClientCommandSource src) {
		BuildController c = BuildController.get();
		src.sendFeedback(Component.literal("GentleBuild: " + c.state()
				+ (c.planner() != null ? " | " + c.planner().verifiedCount()
				+ "/" + c.planner().totalTargets() : "")));
		for (String line : c.statusLines()) {
			src.sendFeedback(Component.literal(line));
		}
		return 1;
	}

	private static int materials(FabricClientCommandSource src) {
		BuildController c = BuildController.get();
		var report = c.materialsReport();
		if (report.isEmpty()) {
			src.sendFeedback(Component.literal("No outstanding materials (or no blueprint)"));
			return 1;
		}
		for (String line : report) {
			src.sendFeedback(Component.literal(line));
		}
		return 1;
	}

	private static int issues(FabricClientCommandSource src) {
		var list = BuildController.get().issues();
		if (list.isEmpty()) {
			src.sendFeedback(Component.literal("No issues"));
			return 1;
		}
		for (BuildIssue issue : list) {
			src.sendFeedback(Component.literal("[" + issue.severity() + "] "
					+ issue.pos().toShortString() + ": " + issue.message()));
		}
		return 1;
	}

	private static int load(FabricClientCommandSource src, String fileName) {
		Path dir = DataManager.getDefaultBaseSchematicDirectory();
		Path file = dir.resolve(fileName);
		if (!Files.isRegularFile(file)) {
			src.sendError(Component.literal("File not found: " + file));
			return 0;
		}
		FileType type = FileType.fromFile(file);
		LitematicaSchematic schematic = switch (type) {
			case LITEMATICA_SCHEMATIC ->
					LitematicaSchematic.createFromFile(dir, fileName);
			case SCHEMATICA_SCHEMATIC ->
					WorldUtils.convertSchematicaSchematicToLitematicaSchematic(dir, fileName,
							false, (IStringConsumer) s -> src.sendError(Component.literal(s)));
			case SPONGE_SCHEMATIC ->
					WorldUtils.convertSpongeSchematicToLitematicaSchematic(dir, fileName);
			case VANILLA_STRUCTURE ->
					WorldUtils.convertStructureToLitematicaSchematic(dir, fileName);
			default -> null;
		};
		if (schematic == null) {
			src.sendError(Component.literal("Could not load '" + fileName + "'"));
			return 0;
		}
		SchematicHolder.getInstance().addSchematic(schematic, true);
		var player = Minecraft.getInstance().player;
		BlockPos origin = player != null ? player.blockPosition() : BlockPos.ZERO;
		SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin,
				schematic.getMetadata().getName(), true, true);
		SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
		manager.addSchematicPlacement(placement, true);
		manager.setSelectedSchematicPlacement(placement);
		src.sendFeedback(Component.literal("Geladen: " + fileName));
		return 1;
	}
}
