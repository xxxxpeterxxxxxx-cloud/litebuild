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

/**
 * Control screen (default key G). Compact status card, two button columns
 * with paging when the window is short, footer with Einstellungen +
 * Schließen always visible. Import happens through Litematica's own file
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

	public GentleBuildScreen() {
		super(Component.literal("GentleBuild"));
	}

	private void defineActions() {
		BuildController c = BuildController.get();
		actions.clear();
		actions.add(new Action("Start / Weiter",
				b -> { c.start(); onClose(); },
				"Startet oder setzt den Bau fort. Ohne Bauplan wird zuerst die "
						+ "Placement uebernommen."));
		actions.add(new Action("Stopp", b -> c.stop(), "Stoppt den Lauf (fortsetzbar)."));
		actions.add(new Action("Schematic waehlen",
				b -> GuiBase.openGui(new GuiSchematicLoad()),
				"Oeffnet Litematicas Browser (.litematic/.schem/.schematic/.nbt). "
						+ "Laden, Placement auswaehlen, dann zurueckkehren."));
		actions.add(new Action("Placement nutzen",
				b -> c.importSelected(),
				"Erstellt den Bauplan aus der ausgewaehlten Litematica-Placement."));
		actions.add(new Action("Pruefen + Fixen",
				b -> { c.verify(); onClose(); },
				"Volle Endpruefung; sichere eigene Fehler werden automatisch "
						+ "korrigiert. H pausiert weiterhin."));
		actions.add(new Action("Stuetzen abbauen",
				b -> { c.startCleanup(); onClose(); },
				"Entfernt eigene temporaere Gerueste (explizit, Rueckwaerts-Reihenfolge)."));
		actions.add(new Action("Fluide: " + onOff(c.config().fluidsEnabled),
				b -> {
					c.config().fluidsEnabled = !c.config().fluidsEnabled;
					b.setMessage(Component.literal("Fluide: " + onOff(c.config().fluidsEnabled)));
				},
				"Wasser/Lava nur in geprueften, abgedichteten Bereichen (nur diese Sitzung)."));
		actions.add(new Action("Aktivierung: " + onOff(c.config().activationEnabled),
				b -> {
					c.config().activationEnabled = !c.config().activationEnabled;
					b.setMessage(Component.literal("Aktivierung: " + onOff(c.config().activationEnabled)));
				},
				"Observer, Hebel, Knoepfe u.ae. Update-Verlaeufe dynamischer "
						+ "Farmen koennen nicht garantiert werden (nur diese Sitzung)."));
		actions.add(new Action("Materialien",
				b -> {
					onClose();
					for (String line : c.materialsReport()) {
						sendLine(line);
					}
				},
				"Fehlende Materialien in den Chat (Registrierungs-ID sortiert)."));
		actions.add(new Action("Probleme",
				b -> {
					onClose();
					for (var issue : c.issues()) {
						sendLine("[" + issue.severity() + "] "
								+ issue.pos().toShortString() + ": " + issue.message());
					}
				},
				"Aktuelle Konflikte/manuelle Aufgaben in den Chat."));
	}

	@Override
	protected void init() {
		defineActions();
		int panelW = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		int colW = (panelW - GAP) / 2;
		int x0 = (this.width - (2 * colW + GAP)) / 2;
		rowsPerPage = Math.max(1, (this.height - HEADER - FOOTER - 8) / ROW);
		int pages = Math.max(1, (actions.size() + 2 * rowsPerPage - 1) / (2 * rowsPerPage));
		page = Math.max(0, Math.min(page, pages - 1));

		int y = HEADER;
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
		addRenderableWidget(Button.builder(Component.literal("Einstellungen"),
				b -> this.minecraft.gui.setScreen(new GentleBuildSettingsScreen(this)))
				.bounds(x0, fy, colW, BTN_H)
				.tooltip(Tooltip.create(Component.literal(
						"Alle Bau-, Blick-, Pausen- und HUD-Einstellungen.")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Schließen"), b -> onClose())
				.bounds(x0 + colW + GAP, fy, colW, BTN_H).build());
	}

	private static void sendLine(String text) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(Component.literal(text));
		}
	}

	private static String onOff(boolean v) {
		return v ? "an" : "aus";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		BuildController c = BuildController.get();
		graphics.centeredText(this.font, "GentleBuild", this.width / 2, 8, 0xFFFFFFFF);
		var info = c.hudInfo();
		int cx = this.width / 2 - Math.min(MAX_W, this.width - 2 * MIN_MARGIN) / 2;
		int cw = Math.min(MAX_W, this.width - 2 * MIN_MARGIN);
		graphics.fill(cx, 20, cx + cw, 58, 0x66121820);
		graphics.text(this.font, info.name(), cx + 6, 26, 0xFFFFFFFF);
		graphics.text(this.font, GentleBuildHud.stateLabel(info.state())
				+ (info.total() > 0 ? "  ·  " + info.done() + "/" + info.total() + " Bauschritte" : ""),
				cx + 6, 38, 0xFFCBD5E1);
		String status = c.statusLines().isEmpty() ? "" : c.statusLines().get(0);
		if (!status.isEmpty()) {
			graphics.text(this.font, status, cx + 6, 50, 0xFFFBBF24);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
