package dev.gentlebuild.ui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.config.BuildConfig;
import dev.gentlebuild.model.RunState;

/**
 * Read-only status panel drawn from the vanilla HUD pipeline (before chat).
 * Never scans the world, never touches input — it only reads one snapshot
 * per frame and draws text with the built-in font.
 */
public final class GentleBuildHud {
	private static final int BG = 0x121820;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int MUTED = 0xFFCBD5E1;
	private static final int ACCENT = 0xFF6EE7B7;
	private static final int WARN = 0xFFFBBF24;
	private static final int PAD = 5;
	private static final int LINE = 10;
	private static final int MAX_W = 244;
	private static final int MARGIN = 8;
	private static final int BOTTOM_CLEAR = 60;

	private GentleBuildHud() {}

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()
				|| mc.gui.screen() != null) {
			return;
		}
		BuildController c = BuildController.get();
		BuildConfig config = c.config();
		if (!config.hudEnabled) {
			return;
		}
		BuildController.HudInfo info = c.hudInfo();
		if (info.state() == RunState.EMPTY && c.blueprint() == null) {
			return;
		}
		Font font = mc.font;
		boolean compact = config.hudCompact;
		java.util.List<String[]> lines = new java.util.ArrayList<>();
		lines.add(new String[]{ellipsize(font, info.name(), MAX_W - 2 * PAD), "name"});
		lines.add(new String[]{stateLabel(info.state()), "state"});
		if (!compact) {
			if (!info.phase().isEmpty()) {
				lines.add(new String[]{"Bereich: " + info.phase(), "muted"});
			}
			if (info.total() > 0) {
				lines.add(new String[]{"Bauschritte: " + info.done() + "/" + info.total(), "muted"});
			}
			if (info.target() != null) {
				lines.add(new String[]{"Ziel: " + info.target().toShortString(), "muted"});
			}
		}
		if (!info.detail().isEmpty()) {
			lines.add(new String[]{ellipsize(font, info.detail(), MAX_W - 2 * PAD), "warn"});
		}
		if (info.state() == RunState.COMPLETE) {
			lines.add(new String[]{"Prüfung abgeschlossen", "accent"});
		}
		if (lines.size() > 6) {
			lines = new java.util.ArrayList<>(lines.subList(0, 6));
		}
		if (compact && lines.size() > 3) {
			lines = new java.util.ArrayList<>(lines.subList(0, 3));
		}
		int w = Math.min(MAX_W, longestWidth(font, lines) + 2 * PAD);
		int h = lines.size() * LINE + 2 * PAD - 2;
		if (info.supports() > 0 && !compact) {
			h += LINE;
		}

		int gw = graphics.guiWidth();
		int gh = graphics.guiHeight();
		double scale = Math.max(0.75, Math.min(1.5, config.hudScale));
		if (w * scale > gw - 2 * MARGIN) {
			scale = Math.max(0.5, (gw - 2.0 * MARGIN) / w);
		}
		int sw = (int) Math.ceil(w * scale);
		int sh = (int) Math.ceil(h * scale);
		int x0;
		int y0;
		switch (config.hudCorner == null ? BuildConfig.HudCorner.TOP_LEFT : config.hudCorner) {
			case TOP_RIGHT -> { x0 = gw - MARGIN - sw; y0 = MARGIN; }
			case BOTTOM_LEFT -> { x0 = MARGIN; y0 = gh - BOTTOM_CLEAR - sh; }
			case BOTTOM_RIGHT -> { x0 = gw - MARGIN - sw; y0 = gh - BOTTOM_CLEAR - sh; }
			default -> { x0 = MARGIN; y0 = MARGIN; }
		}
		if (y0 + sh > gh - MARGIN) {
			y0 = Math.max(MARGIN, gh - MARGIN - sh);
		}

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x0, y0);
		pose.scale((float) scale, (float) scale);
		int alpha = Math.max(0x32, Math.min(0xFF, config.hudOpacity * 255 / 100)) << 24;
		graphics.fill(0, 0, w, h, alpha | (BG & 0xFFFFFF));
		int y = PAD - 1;
		for (String[] l : lines) {
			int color = switch (l[1]) {
				case "muted" -> MUTED;
				case "accent" -> ACCENT;
				case "warn" -> WARN;
				case "state" -> info.state() == RunState.COMPLETE ? ACCENT
						: info.state() == RunState.PAUSED ? WARN : WHITE;
				default -> WHITE;
			};
			graphics.text(font, l[0], PAD, y, color);
			y += LINE;
		}
		if (info.supports() > 0 && !compact) {
			graphics.text(font, info.supports() + " Stützen aktiv", PAD, y, MUTED);
		}
		pose.popMatrix();
	}

	private static int longestWidth(Font font, java.util.List<String[]> lines) {
		int w = 0;
		for (String[] l : lines) {
			w = Math.max(w, font.width(l[0]));
		}
		return w;
	}

	private static String ellipsize(Font font, String s, int maxW) {
		if (font.width(s) <= maxW) {
			return s;
		}
		String e = "…";
		while (s.length() > 1 && font.width(s + e) > maxW) {
			s = s.substring(0, s.length() - 1);
		}
		return s + e;
	}

	public static String stateLabel(RunState state) {
		return switch (state) {
			case EMPTY -> "Kein Bauplan";
			case READY -> "Bereit";
			case PLANNING -> "Bauplan einlesen";
			case WALKING -> "Unterwegs";
			case AIMING -> "Ausrichten";
			case WAITING -> "Warten";
			case RESTING -> "Baupause";
			case PAUSED -> "Pausiert";
			case VERIFYING -> "Prüfen";
			case COMPLETE -> "Abgeschlossen";
		};
	}
}
