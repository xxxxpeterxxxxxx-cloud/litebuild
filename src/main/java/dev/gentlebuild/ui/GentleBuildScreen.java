package dev.gentlebuild.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.malilib.gui.GuiBase;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.build.PreflightReport;

/**
 * Control screen (default key G). Compact status card, two button columns
 * with paging when the window is short, footer with Settings +
 * Close always visible. Import happens through Litematica's own file
 * browser (.litematic/.schem/.schematic/.nbt).
 */
public class GentleBuildScreen extends Screen {
	private static final int ROW = 24;
	private static final int HEADER = 66;
	private static final int FOOTER = 28;
	private static final int BTN_H = 20;
	private static final int GAP = 8;
	private static final int MAX_W = 500;
	private static final int MIN_MARGIN = 12;

	private record Action(String label, Button.OnPress press, String tooltip) {}

	private final List<Action> actions = new ArrayList<>();
	private int page;
	private int rowsPerPage;
	private String preflightHint = "";

	public GentleBuildScreen() {
		super(Component.literal("GentleBuild"));
	}

	private void defineActions() {
		BuildController c = BuildController.get();
		actions.clear();
		actions.add(new Action("Start / Resume",
				b -> { c.start(); onClose(); },
				"Starts or resumes the build. Without a blueprint the selected "
						+ "placement is imported first."));
		actions.add(new Action("Stop", b -> c.stop(), "Stops the run (resumable)."));
		actions.add(new Action("Choose schematic",
				b -> GuiBase.openGui(new GuiSchematicLoad()),
				"Opens Litematica's browser (.litematic/.schem/.schematic/.nbt). "
						+ "Load a file, select the placement, then come back."));
		actions.add(new Action("Use placement",
				b -> c.importSelected(),
				"Builds the blueprint from the selected Litematica placement."));
		actions.add(new Action("Preflight",
				b -> this.minecraft.gui.setScreen(new GentleBuildPreflightScreen(this)),
				"Report before starting: missing materials, manual tasks, "
						+ "risks and required toggles."));
		actions.add(new Action("Verify + fix",
				b -> { c.verify(); onClose(); },
				"Full final check; safe own mistakes are corrected "
						+ "automatically. H still pauses."));
		actions.add(new Action("Remove supports",
				b -> { c.startCleanup(); onClose(); },
				"Removes own temporary scaffolds (explicit, reverse order)."));
		actions.add(new Action("Fluids: " + onOff(c.config().fluidsEnabled),
				b -> {
					c.config().fluidsEnabled = !c.config().fluidsEnabled;
					b.setMessage(Component.literal("Fluids: " + onOff(c.config().fluidsEnabled)));
				},
				"Water/lava only in verified, sealed areas (this session only)."));
		actions.add(new Action("Activation: " + onOff(c.config().activationEnabled),
				b -> {
					c.config().activationEnabled = !c.config().activationEnabled;
					b.setMessage(Component.literal("Activation: " + onOff(c.config().activationEnabled)));
				},
				"Observers, levers, buttons etc. Update chains of dynamic "
						+ "farms cannot be guaranteed (this session only)."));
		actions.add(new Action("Materials",
				b -> {
					onClose();
					for (String line : c.materialsReport()) {
						sendLine(line);
					}
				},
				"Prints missing materials to chat (sorted by registry id)."));
		actions.add(new Action("Issues",
				b -> {
					onClose();
					for (var issue : c.issues()) {
						sendLine("[" + issue.severity() + "] "
								+ issue.pos().toShortString() + ": " + issue.message());
					}
				},
				"Prints current conflicts/manual tasks to chat."));
	}

	@Override
	protected void init() {
		defineActions();
		PreflightReport pre = BuildController.get().preflight();
		preflightHint = pre == null ? "" : pre.headline();
		// One extra header row while a preflight hint exists; the button grid
		// simply starts lower — all other layout logic is unchanged.
		int headerH = HEADER + (preflightHint.isEmpty() ? 0 : 16);
		int panelW = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		int colW = (panelW - GAP) / 2;
		int x0 = (this.width - (2 * colW + GAP)) / 2;
		rowsPerPage = Math.max(1, (this.height - headerH - FOOTER - 8) / ROW);
		int pages = Math.max(1, (actions.size() + 2 * rowsPerPage - 1) / (2 * rowsPerPage));
		page = Math.max(0, Math.min(page, pages - 1));

		int y = headerH;
		int idx = page * 2 * rowsPerPage;
		for (int row = 0; row < rowsPerPage; row++) {
			for (int col = 0; col < 2; col++) {
				if (idx >= actions.size()) {
					break;
				}
				Action a = actions.get(idx++);
				Button.Builder b = Button.builder(Component.literal(a.label()), a.press())
						.bounds(x0 + col * (colW + GAP), y, colW, BTN_H);
				if (a.tooltip() != null) {
					b.tooltip(Tooltip.create(Component.literal(a.tooltip())));
				}
				this.addRenderableWidget(b.build());
			}
			y += ROW;
		}
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"),
					b -> { page--; rebuildWidgets(); })
					.bounds(x0, this.height - FOOTER - 22, 30, BTN_H).build());
			addRenderableWidget(Button.builder(Component.literal(">"),
					b -> { page++; rebuildWidgets(); })
					.bounds(x0 + 34, this.height - FOOTER - 22, 30, BTN_H).build());
		}
		int fy = this.height - FOOTER + 4;
		addRenderableWidget(Button.builder(Component.literal("Settings"),
				b -> this.minecraft.gui.setScreen(new GentleBuildSettingsScreen(this)))
				.bounds(x0, fy, colW, BTN_H)
				.tooltip(Tooltip.create(Component.literal(
						"All build, look, pause and HUD settings.")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(x0 + colW + GAP, fy, colW, BTN_H).build());
	}

	private static void sendLine(String text) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(Component.literal(text));
		}
	}

	private static String onOff(boolean v) {
		return v ? "on" : "off";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		BuildController c = BuildController.get();
		graphics.centeredText(this.font, "GentleBuild", this.width / 2, 8, 0xFFFFFFFF);
		var info = c.hudInfo();
		int cx = this.width / 2 - Math.min(MAX_W, this.width - 2 * MIN_MARGIN) / 2;
		int cw = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		int cardBottom = preflightHint.isEmpty() ? 58 : 74;
		graphics.fill(cx, 20, cx + cw, cardBottom, 0x66121820);
		graphics.text(this.font, info.name(), cx + 6, 26, 0xFFFFFFFF);
		graphics.text(this.font, GentleBuildHud.stateLabel(info.state())
				+ (info.total() > 0 ? "  ·  " + info.done() + "/" + info.total() + " steps" : ""),
				cx + 6, 38, 0xFFCBD5E1);
		String status = c.statusLines().isEmpty() ? "" : c.statusLines().get(0);
		if (!status.isEmpty()) {
			graphics.text(this.font, status, cx + 6, 50, 0xFFFBBF24);
		}
		if (!preflightHint.isEmpty()) {
			graphics.text(this.font, preflightHint, cx + 6, 62, 0xFF93C5FD);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
