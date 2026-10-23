package dev.gentlebuild.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.item.Items;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.config.BuildConfig;
import dev.gentlebuild.nav.LevelNavWorld;

/**
 * G → Settings. One draft copy across six categories; raw field text
 * survives tab/page switches and resizes. "Save" stays disabled until
 * every field parses and the draft validates; nothing is written before an
 * explicit save, and the controller only swaps the live config after a
 * successful atomic disk write.
 */
public class GentleBuildSettingsScreen extends Screen {
	private static final int MAX_W = 500;
	private static final int MIN_MARGIN = 12;
	private static final int ROW = 28;
	private static final int WIDGET_W = 104;
	private static final int BTN_H = 20;
	private static final int FOOTER = 30;
	private static final String[] TABS = {"Build", "Timings", "Look", "Movement", "Pauses", "HUD"};

	private enum Kind { INT, DOUBLE, TEXT, BOOL, CORNER }

	private record Field(String id, String label, String tooltip, Kind kind,
						 Function<BuildConfig, String> get,
						 BiFunction<BuildConfig, String, Boolean> set) {}

	private final Screen parent;
	private BuildConfig draft;
	private int tab;
	private int page;
	private int rowsPerPage;
	private final Map<String, String> raw = new LinkedHashMap<>();
	private final Map<String, String> fieldErrors = new LinkedHashMap<>();
	private List<String> otherErrors = List.of();
	private String saveError = "";
	private Button saveButton;

	public GentleBuildSettingsScreen(Screen parent) {
		super(Component.literal("GentleBuild — Settings"));
		this.parent = parent;
		this.draft = BuildController.get().config().copy();
	}

	private static String onOff(boolean v) {
		return v ? "on" : "off";
	}

	private static String cornerLabel(BuildConfig.HudCorner c) {
		return switch (c == null ? BuildConfig.HudCorner.TOP_LEFT : c) {
			case TOP_LEFT -> "Top left";
			case TOP_RIGHT -> "Top right";
			case BOTTOM_LEFT -> "Bottom left";
			case BOTTOM_RIGHT -> "Bottom right";
		};
	}

	private static BuildConfig.HudCorner nextCorner(BuildConfig.HudCorner c) {
		BuildConfig.HudCorner[] all = BuildConfig.HudCorner.values();
		return all[((c == null ? BuildConfig.HudCorner.TOP_LEFT : c).ordinal() + 1) % all.length];
	}

	private static String d2s(double v) {
		return String.valueOf(v);
	}

	private List<Field> fields(int tab) {
		List<Field> f = new ArrayList<>();
		switch (tab) {
			case 0 -> { // Build
				f.add(new Field("scaffoldBlock", "Support block (block id)",
						"Registered block id like minecraft:dirt. Full cubes only, no "
								+ "entity/content, no falling block, no TNT, no signal source.",
						Kind.TEXT, c -> c.scaffoldBlock,
						(c, v) -> { c.scaffoldBlock = v.trim(); return true; }));
				f.add(new Field("allowTemporarySupports", "Temporary supports",
						"The builder may place its own scaffold blocks and remove them later.",
						Kind.BOOL, c -> onOff(c.allowTemporarySupports),
						(c, v) -> { c.allowTemporarySupports = !c.allowTemporarySupports; return true; }));
				f.add(new Field("layerByLayer", "Layer by layer",
						"Build strictly bottom-up: the lowest unfinished layer "
								+ "is completed before the next one starts.",
						Kind.BOOL, c -> onOff(c.layerByLayer),
						(c, v) -> { c.layerByLayer = !c.layerByLayer; return true; }));
				f.add(new Field("automaticRepairs", "Automatic repair",
						"Detected own mistakes are fixed automatically within the limit.",
						Kind.BOOL, c -> onOff(c.automaticRepairs),
						(c, v) -> { c.automaticRepairs = !c.automaticRepairs; return true; }));
				f.add(new Field("maxRepairRounds", "Repair rounds",
						"Maximum repair passes per verification, 1..5.",
						Kind.INT, c -> String.valueOf(c.maxRepairRounds),
						(c, v) -> setInt(v, x -> c.maxRepairRounds = x)));
				f.add(new Field("checkpointStructureBlocks", "Checkpoint structure",
						"Structure build steps between checkpoint audits, 5..10.",
						Kind.INT, c -> String.valueOf(c.checkpointStructureBlocks),
						(c, v) -> setInt(v, x -> c.checkpointStructureBlocks = x)));
				f.add(new Field("checkpointSensitiveBlocks", "Checkpoint sensitive",
						"Mechanism/fluid steps between audits, 5..10, at most the structure limit.",
						Kind.INT, c -> String.valueOf(c.checkpointSensitiveBlocks),
						(c, v) -> setInt(v, x -> c.checkpointSensitiveBlocks = x)));
			}
			case 1 -> { // Timings (1 tick = 50 ms at 20 TPS)
				f.add(new Field("minPlaceIntervalTicks", "Placement interval",
						"Ticks between placements (1 tick = 50 ms), 2..100.",
						Kind.INT, c -> String.valueOf(c.minPlaceIntervalTicks),
						(c, v) -> setInt(v, x -> c.minPlaceIntervalTicks = x)));
				f.add(new Field("confirmationTimeoutTicks", "Server acknowledgement",
						"Maximum wait for a server ack in ticks, 20..1200.",
						Kind.INT, c -> String.valueOf(c.confirmationTimeoutTicks),
						(c, v) -> setInt(v, x -> c.confirmationTimeoutTicks = x)));
				f.add(new Field("settleTicks", "Settle before click",
						"The view must stay still for this many ticks (1 tick = 50 ms), 2..100.",
						Kind.INT, c -> String.valueOf(c.settleTicks),
						(c, v) -> setInt(v, x -> c.settleTicks = x)));
				f.add(new Field("hotbarSwitchTicks", "Hotbar switch",
						"Minimum ticks after a hotbar slot switch before clicking, 1..20.",
						Kind.INT, c -> String.valueOf(c.hotbarSwitchTicks),
						(c, v) -> setInt(v, x -> c.hotbarSwitchTicks = x)));
				f.add(new Field("mainRefillTicks", "Inventory refill",
						"Minimum ticks after a main-inventory swap before clicking, 1..20.",
						Kind.INT, c -> String.valueOf(c.mainRefillTicks),
						(c, v) -> setInt(v, x -> c.mainRefillTicks = x)));
				f.add(new Field("stallAuditTicks", "Stall check",
						"Ticks without progress until an intermediate audit runs, 60..2400.",
						Kind.INT, c -> String.valueOf(c.stallAuditTicks),
						(c, v) -> setInt(v, x -> c.stallAuditTicks = x)));
			}
			case 2 -> { // Look
				f.add(new Field("naturalMovement", "Natural look",
						"Varying turn speed and small look corrections. The placement aim stays exact.",
						Kind.BOOL, c -> onOff(c.naturalMovement),
						(c, v) -> { c.naturalMovement = !c.naturalMovement; return true; }));
				f.add(new Field("maxYawSpeed", "Max. yaw speed",
						"Degrees/second, 10..800.",
						Kind.DOUBLE, c -> d2s(c.maxYawSpeed),
						(c, v) -> setDouble(v, x -> c.maxYawSpeed = x)));
				f.add(new Field("maxPitchSpeed", "Max. pitch speed",
						"Degrees/second, 10..720.",
						Kind.DOUBLE, c -> d2s(c.maxPitchSpeed),
						(c, v) -> setDouble(v, x -> c.maxPitchSpeed = x)));
				f.add(new Field("maxAngularAccel", "Max. angular accel",
						"Degrees/second², 50..12000.",
						Kind.DOUBLE, c -> d2s(c.maxAngularAccel),
						(c, v) -> setDouble(v, x -> c.maxAngularAccel = x)));
				f.add(new Field("microCorrectionStrength", "Variation",
						"Strength of the look variation, 0.0..1.0.",
						Kind.DOUBLE, c -> d2s(c.microCorrectionStrength),
						(c, v) -> setDouble(v, x -> c.microCorrectionStrength = x)));
			}
			case 3 -> { // Movement
				f.add(new Field("sprintOnLongWalks", "Sprint on long walks",
						"The builder sprints only on safe, long path segments.",
						Kind.BOOL, c -> onOff(c.sprintOnLongWalks),
						(c, v) -> { c.sprintOnLongWalks = !c.sprintOnLongWalks; return true; }));
				f.add(new Field("maxPlanNodes", "Pathfinding limit",
						"Maximum A* nodes per path plan (advanced), 256..65536.",
						Kind.INT, c -> String.valueOf(c.maxPlanNodes),
						(c, v) -> setInt(v, x -> c.maxPlanNodes = x)));
			}
			case 4 -> { // Pauses
				f.add(new Field("automaticPauses", "Automatic pauses",
						"Short breaks and AFK pauses; only on safe ground.",
						Kind.BOOL, c -> onOff(c.automaticPauses),
						(c, v) -> { c.automaticPauses = !c.automaticPauses; return true; }));
				f.add(new Field("shortPauseMinActions", "Short pause after (actions)",
						"Actions before the first possible short pause, 1..100.",
						Kind.INT, c -> String.valueOf(c.shortPauseMinActions),
						(c, v) -> setInt(v, x -> c.shortPauseMinActions = x)));
				f.add(new Field("shortPauseMaxActions", "Short pause after (max.)",
						"Actions by which a short pause must happen, 1..100, >= min.",
						Kind.INT, c -> String.valueOf(c.shortPauseMaxActions),
						(c, v) -> setInt(v, x -> c.shortPauseMaxActions = x)));
				f.add(new Field("shortPauseMinTicks", "Short pause length (min)",
						"Ticks, 1..200.",
						Kind.INT, c -> String.valueOf(c.shortPauseMinTicks),
						(c, v) -> setInt(v, x -> c.shortPauseMinTicks = x)));
				f.add(new Field("shortPauseMaxTicks", "Short pause length (max)",
						"Ticks, 1..200, >= min.",
						Kind.INT, c -> String.valueOf(c.shortPauseMaxTicks),
						(c, v) -> setInt(v, x -> c.shortPauseMaxTicks = x)));
				f.add(new Field("afkIntervalMinTicks", "AFK interval (min)",
						"Ticks between AFK pauses, 20..72000.",
						Kind.INT, c -> String.valueOf(c.afkIntervalMinTicks),
						(c, v) -> setInt(v, x -> c.afkIntervalMinTicks = x)));
				f.add(new Field("afkIntervalMaxTicks", "AFK interval (max)",
						"Ticks, 20..72000, >= min.",
						Kind.INT, c -> String.valueOf(c.afkIntervalMaxTicks),
						(c, v) -> setInt(v, x -> c.afkIntervalMaxTicks = x)));
				f.add(new Field("afkDurationMinTicks", "AFK length (min)",
						"Ticks, 1..100.",
						Kind.INT, c -> String.valueOf(c.afkDurationMinTicks),
						(c, v) -> setInt(v, x -> c.afkDurationMinTicks = x)));
				f.add(new Field("afkDurationMaxTicks", "AFK length (max)",
						"Ticks, 1..100 (at most 5 s), >= min.",
						Kind.INT, c -> String.valueOf(c.afkDurationMaxTicks),
						(c, v) -> setInt(v, x -> c.afkDurationMaxTicks = x)));
			}
			case 5 -> { // HUD
				f.add(new Field("hudEnabled", "Show HUD",
						"Show or hide the in-game status overlay.",
						Kind.BOOL, c -> onOff(c.hudEnabled),
						(c, v) -> { c.hudEnabled = !c.hudEnabled; return true; }));
				f.add(new Field("hudCorner", "HUD corner",
						"Screen corner of the overlay.",
						Kind.CORNER, c -> cornerLabel(c.hudCorner),
						(c, v) -> { c.hudCorner = nextCorner(c.hudCorner); return true; }));
				f.add(new Field("hudCompact", "Compact HUD",
						"Only name, state and the most important message (3 lines).",
						Kind.BOOL, c -> onOff(c.hudCompact),
						(c, v) -> { c.hudCompact = !c.hudCompact; return true; }));
				f.add(new Field("hudScale", "HUD scale",
						"Scaling, 0.75..1.5.",
						Kind.DOUBLE, c -> d2s(c.hudScale),
						(c, v) -> setDouble(v, x -> c.hudScale = x)));
				f.add(new Field("hudOpacity", "HUD opacity",
						"Percent, 50..100.",
						Kind.INT, c -> String.valueOf(c.hudOpacity),
						(c, v) -> setInt(v, x -> c.hudOpacity = x)));
			}
		}
		return f;
	}

	private Boolean setInt(String v, java.util.function.IntConsumer sink) {
		try {
			sink.accept(Integer.parseInt(v.trim()));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private Boolean setDouble(String v, java.util.function.DoubleConsumer sink) {
		try {
			sink.accept(Double.parseDouble(v.trim().replace(',', '.')));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private void revalidate() {
		fieldErrors.clear();
		for (int t = 0; t < TABS.length; t++) {
			for (Field f : fields(t)) {
				String rawText = raw.get(f.id());
				if (rawText != null && (f.kind() == Kind.INT || f.kind() == Kind.DOUBLE)) {
					boolean ok = f.kind() == Kind.INT
							? setInt(rawText, x -> {})
							: setDouble(rawText, x -> {});
					if (!ok) {
						fieldErrors.put(f.id(), "not a valid number");
					}
				}
			}
		}
		Set<String> ids = new LinkedHashSet<>();
		for (int t = 0; t < TABS.length; t++) {
			for (Field f : fields(t)) {
				ids.add(f.id());
			}
		}
		List<String> other = new ArrayList<>();
		for (String err : draft.validationErrors()) {
			String id = err.substring(0, err.indexOf(':'));
			if (ids.contains(id)) {
				fieldErrors.put(id, err.substring(err.indexOf(':') + 1).trim());
			} else {
				other.add(err);
			}
		}
		String scaffold = scaffoldError(draft.scaffoldBlock);
		if (scaffold != null) {
			fieldErrors.put("scaffoldBlock", scaffold);
		}
		otherErrors = other;
	}

	/** Registry-level eligibility, same rules as the production scaffold check. */
	private String scaffoldError(String id) {
		if (id == null || id.isBlank() || this.minecraft.level == null) {
			return null;
		}
		String trimmed = id.trim();
		if (!trimmed.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			return "invalid block id (format e.g. minecraft:dirt)";
		}
		var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(trimmed));
		if (block == null || block == Blocks.AIR || block.asItem() == Items.AIR) {
			return "unknown block or not a block item";
		}
		if (block instanceof BaseEntityBlock || block instanceof FallingBlock
				|| block instanceof TntBlock
				|| block.defaultBlockState().isSignalSource()
				|| !block.defaultBlockState().isCollisionShapeFullBlock(
						new LevelNavWorld(this.minecraft.level), net.minecraft.core.BlockPos.ZERO)) {
			return "not a suitable support block (entity/falling/TNT/signal)";
		}
		return null;
	}

	private boolean valid() {
		return fieldErrors.isEmpty() && otherErrors.isEmpty()
				&& draft.validationErrors().isEmpty();
	}

	@Override
	protected void init() {
		revalidate();
		int panelW = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		int x0 = (this.width - panelW) / 2;

		// Tab bar: one row of six when wide, two rows of three when narrow.
		boolean narrow = panelW < 480;
		int cols = narrow ? 3 : 6;
		int tabW = (panelW - (cols - 1) * 4) / cols;
		for (int i = 0; i < TABS.length; i++) {
			final int t = i;
			int tx = x0 + (i % cols) * (tabW + 4);
			int ty = 26 + (i / cols) * 22;
			Button b = Button.builder(Component.literal(TABS[i]),
							btn -> { tab = t; page = 0; rebuildWidgets(); })
					.bounds(tx, ty, tabW, BTN_H).build();
			b.active = (t != tab);
			this.addRenderableWidget(b);
		}
		int bodyTop = narrow ? 74 : 52;
		List<Field> fs = fields(tab);
		rowsPerPage = Math.max(1, (this.height - FOOTER - 6 - bodyTop) / ROW);
		int pages = Math.max(1, (fs.size() + rowsPerPage - 1) / rowsPerPage);
		page = Math.max(0, Math.min(page, pages - 1));
		int widgetX = x0 + panelW - WIDGET_W;

		int idx = page * rowsPerPage;
		for (int row = 0; row < rowsPerPage && idx < fs.size(); row++, idx++) {
			Field f = fs.get(idx);
			int y = bodyTop + row * ROW;
			if (f.kind() == Kind.BOOL || f.kind() == Kind.CORNER) {
				Button b = Button.builder(Component.literal(f.get().apply(draft)),
								btn -> { f.set().apply(draft, ""); rebuildWidgets(); })
						.bounds(widgetX, y + 2, WIDGET_W, BTN_H)
						.tooltip(Tooltip.create(Component.literal(f.tooltip()))).build();
				b.setMessage(Component.literal(f.kind() == Kind.BOOL
						? f.get().apply(draft)
						: cornerLabel(draft.hudCorner)));
				this.addRenderableWidget(b);
			} else {
				EditBox box = new EditBox(this.font, widgetX, y + 2, WIDGET_W, BTN_H,
						Component.literal(f.label()));
				box.setTooltip(Tooltip.create(Component.literal(f.tooltip())));
				box.setValue(raw.getOrDefault(f.id(), f.get().apply(draft)));
				String err = fieldErrors.get(f.id());
				if (err != null) {
					box.setTextColor(0xFFFCA5A5);
				}
				box.setResponder(v -> {
					raw.put(f.id(), v);
					if (f.set().apply(draft, v)) {
						fieldErrors.remove(f.id());
					} else {
						fieldErrors.put(f.id(), "not a valid number");
					}
					// re-validate cross-field pairs without rebuilding mid-typing
					List<String> keep = new ArrayList<>(otherErrors);
					otherErrors = new ArrayList<>();
					for (String e : draft.validationErrors()) {
						String id = e.substring(0, e.indexOf(':'));
						boolean knownField = false;
						for (int t2 = 0; t2 < TABS.length && !knownField; t2++) {
							for (Field f2 : fields(t2)) {
								if (f2.id().equals(id)) {
									fieldErrors.putIfAbsent(id, e.substring(e.indexOf(':') + 1).trim());
									knownField = true;
									break;
								}
							}
						}
						if (!knownField) {
							otherErrors.add(e);
						}
					}
					String sc = scaffoldError(draft.scaffoldBlock);
					if (f.id().equals("scaffoldBlock")) {
						if (sc != null) {
							fieldErrors.put("scaffoldBlock", sc);
						} else {
							fieldErrors.remove("scaffoldBlock");
						}
					}
					if (saveButton != null) {
						saveButton.active = valid();
					}
				});
				this.addRenderableWidget(box);
			}
		}
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("<<"),
					b -> { page--; rebuildWidgets(); })
					.bounds(x0, bodyTop + rowsPerPage * ROW, 30, BTN_H).build());
			addRenderableWidget(Button.builder(Component.literal(">>"),
					b -> { page++; rebuildWidgets(); })
					.bounds(x0 + 34, bodyTop + rowsPerPage * ROW, 30, BTN_H).build());
		}

		int fy = this.height - FOOTER + 5;
		int fw = (panelW - 16) / 3;
		saveButton = Button.builder(Component.literal("Save"), b -> save())
				.bounds(x0, fy, fw, BTN_H)
				.tooltip(Tooltip.create(Component.literal(
						"Writes to disk and applies immediately.")))
				.build();
		saveButton.active = valid();
		addRenderableWidget(saveButton);
		addRenderableWidget(Button.builder(Component.literal("Cancel"),
				b -> this.minecraft.gui.setScreen(parent))
				.bounds(x0 + fw + 8, fy, fw, BTN_H)
				.tooltip(Tooltip.create(Component.literal("Discards all unsaved changes.")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("All defaults"), b -> {
					draft = new BuildConfig();
					raw.clear();
					fieldErrors.clear();
					rebuildWidgets();
				})
				.bounds(x0 + 2 * (fw + 8), fy, fw, BTN_H)
				.tooltip(Tooltip.create(Component.literal(
						"Resets the draft; only 'Save' applies it.")))
				.build());
	}

	private void save() {
		revalidate();
		if (!valid()) {
			rebuildWidgets();
			return;
		}
		if (BuildController.get().saveConfig(draft)) {
			this.minecraft.gui.setScreen(parent);
		} else {
			saveError = "Save failed (file not writable?)";
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, "GentleBuild — Settings: " + TABS[tab],
				this.width / 2, 10, 0xFFFFFFFF);
		int panelW = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		int x0 = (this.width - panelW) / 2;
		boolean narrow = panelW < 480;
		int bodyTop = narrow ? 74 : 52;
		List<Field> fs = fields(tab);
		int idx = page * rowsPerPage;
		for (int row = 0; row < rowsPerPage && idx < fs.size(); row++, idx++) {
			Field f = fs.get(idx);
			int y = bodyTop + row * ROW;
			graphics.text(this.font, f.label(), x0 + 2, y + 5, 0xFFCBD5E1);
			String err = fieldErrors.get(f.id());
			if (err != null) {
				graphics.text(this.font, err, x0 + 2, y + 16, 0xFFFCA5A5);
			}
		}
		if (tab == 3 && page == Math.max(0, (fs.size() - 1) / Math.max(1, rowsPerPage))) {
			graphics.text(this.font, "Build order: automatic, layered and",
					x0 + 2, bodyTop + rowsPerPage * ROW + 4, 0xFF94A3B8);
			graphics.text(this.font, "dependency-aware; reachable blocks are batched.",
					x0 + 2, bodyTop + rowsPerPage * ROW + 14, 0xFF94A3B8);
		}
		int ey = this.height - FOOTER - (otherErrors.isEmpty() && saveError.isEmpty() ? 0 : 12);
		for (String e : otherErrors) {
			graphics.text(this.font, e, x0 + 2, ey, 0xFFFCA5A5);
			ey += 10;
		}
		if (!saveError.isEmpty()) {
			graphics.text(this.font, saveError, x0 + 2, ey, 0xFFFCA5A5);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
