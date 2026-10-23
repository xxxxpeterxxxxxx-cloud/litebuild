package dev.gentlebuild.ui;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.build.PreflightReport;
import dev.gentlebuild.model.RunState;

/**
 * G → Vorpruefung. Read-only pre-flight report, computed when the screen
 * opens and again on "Aktualisieren": missing materials, manual work, risky
 * states and the session toggles the plan needs. Lines are paged like the
 * settings screen so long material lists stay readable in small windows.
 */
public class GentleBuildPreflightScreen extends Screen {
	private static final int MAX_W = 500;
	private static final int MIN_MARGIN = 12;
	private static final int ROW = 12;
	private static final int HEADER = 30;
	private static final int FOOTER = 30;
	private static final int BTN_H = 20;

	private final Screen parent;
	private List<PreflightReport.Line> lines = List.of();
	private int page;
	private int rowsPerPage;

	public GentleBuildPreflightScreen(Screen parent) {
		super(Component.literal("GentleBuild — Preflight"));
		this.parent = parent;
	}

	private void recompute() {
		BuildController c = BuildController.get();
		PreflightReport report = c.preflight();
		if (report != null) {
			lines = report.summaryLines();
		} else if (c.state() == RunState.PLANNING) {
			lines = List.of(new PreflightReport.Line(
					"Snapshot running — press 'Refresh' in a moment.",
					PreflightReport.Tone.WARN));
		} else {
			lines = List.of(new PreflightReport.Line(
					"No blueprint — choose 'Use placement' first.",
					PreflightReport.Tone.WARN));
		}
	}

	@Override
	protected void init() {
		recompute();
		int panelW = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		int x0 = (this.width - panelW) / 2;
		rowsPerPage = Math.max(1, (this.height - HEADER - FOOTER - 26) / ROW);
		int pages = Math.max(1, (lines.size() + rowsPerPage - 1) / rowsPerPage);
		page = Math.max(0, Math.min(page, pages - 1));
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"),
					b -> { page--; rebuildWidgets(); })
					.bounds(x0, this.height - FOOTER - 22, 30, BTN_H).build());
			addRenderableWidget(Button.builder(Component.literal(">"),
					b -> { page++; rebuildWidgets(); })
					.bounds(x0 + 34, this.height - FOOTER - 22, 30, BTN_H).build());
		}
		int fy = this.height - FOOTER + 5;
		int fw = (panelW - 8) / 2;
		addRenderableWidget(Button.builder(Component.literal("Aktualisieren"),
				b -> rebuildWidgets())
				.bounds(x0, fy, fw, BTN_H).build());
		addRenderableWidget(Button.builder(Component.literal("Zurueck"),
				b -> this.minecraft.gui.setScreen(parent))
				.bounds(x0 + fw + 8, fy, fw, BTN_H).build());
	}

	private static int toneColor(PreflightReport.Tone tone) {
		return switch (tone) {
			case HEADER -> 0xFFFFFFFF;
			case OK -> 0xFF86EFAC;
			case INFO -> 0xFFCBD5E1;
			case WARN -> 0xFFFBBF24;
			case BAD -> 0xFFFCA5A5;
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX,
								   int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, "GentleBuild — Preflight",
				this.width / 2, 10, 0xFFFFFFFF);
		int panelW = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		int x0 = (this.width - panelW) / 2;
		int idx = page * rowsPerPage;
		for (int row = 0; row < rowsPerPage && idx < lines.size(); row++, idx++) {
			PreflightReport.Line line = lines.get(idx);
			graphics.text(this.font, line.text(), x0 + 2,
					HEADER + row * ROW, toneColor(line.tone()));
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
