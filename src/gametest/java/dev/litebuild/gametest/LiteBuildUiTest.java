package dev.litebuild.gametest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;
import dev.litebuild.build.BuildController;
import dev.litebuild.config.BuildConfig;
import dev.litebuild.ui.LiteBuildScreen;
import dev.litebuild.ui.LiteBuildSettingsScreen;

/**
 * UI end-to-end test (beta.6): the G overview and the settings draft go
 * through real screens. Save only applies validated drafts atomically;
 * raw invalid input survives tab/resize; every widget stays inside bounds
 * without overlap at small and normal GUI sizes. The live config is saved
 * and restored around the test.
 */
public final class LiteBuildUiTest implements FabricClientGameTest {

	private static Screen screen(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen());
	}

	private static List<AbstractWidget> widgets(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			List<AbstractWidget> out = new ArrayList<>();
			if (mc.gui.screen() != null) {
				for (var child : mc.gui.screen().children()) {
					if (child instanceof AbstractWidget w && w.visible) {
						out.add(w);
					}
				}
			}
			return out;
		});
	}

	private static EditBox box(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> {
			if (mc.gui.screen() != null) {
				for (var child : mc.gui.screen().children()) {
					if (child instanceof EditBox b
							&& b.getMessage().getString().equals(label)) {
						return b;
					}
				}
			}
			return null;
		});
	}

	private static void setBox(ClientGameTestContext context, String label, String value) {
		pageTo(context, label);
		context.runOnClient(mc -> {
			EditBox b = box(context, label);
			if (b == null) {
				StringBuilder visible = new StringBuilder();
				if (mc.gui.screen() != null) {
					for (var child : mc.gui.screen().children()) {
						visible.append(child.getClass().getSimpleName()).append(":")
								.append(child instanceof AbstractWidget w
										? w.getMessage().getString() : "?")
								.append(";");
					}
				}
				throw new AssertionError("EditBox not found: " + label
						+ " | sichtbar: " + visible);
			}
			b.setValue(value);
		});
	}

	private static String boxValue(ClientGameTestContext context, String label) {
		pageTo(context, label);
		EditBox b = box(context, label);
		return b == null ? null : b.getValue();
	}

	/** Follows the '>>' pager until the field's EditBox is visible (wraps after 6 pages). */
	private static void pageTo(ClientGameTestContext context, String label) {
		for (int i = 0; i < 6 && box(context, label) == null; i++) {
			if (!context.tryClickScreenButton(">>")) {
				break;
			}
			context.waitTicks(3);
		}
		if (box(context, label) == null) {
			throw new AssertionError("EditBox not found on any page: " + label);
		}
	}

	private static void assertWidgetsInsideAndDisjoint(ClientGameTestContext context, String tag) {
		List<AbstractWidget> ws = widgets(context);
		if (ws.isEmpty()) {
			throw new AssertionError(tag + ": no widgets visible");
		}
		int w = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
		int h = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
		for (AbstractWidget a : ws) {
			if (a.getX() < 0 || a.getY() < 0 || a.getRight() > w || a.getBottom() > h) {
				throw new AssertionError(tag + ": Widget outside des Bildschirms: "
						+ a.getMessage().getString() + " @" + a.getX() + "," + a.getY());
			}
		}
		for (int i = 0; i < ws.size(); i++) {
			for (int j = i + 1; j < ws.size(); j++) {
				AbstractWidget a = ws.get(i), b = ws.get(j);
				if (a.getX() < b.getRight() && b.getX() < a.getRight()
						&& a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
					throw new AssertionError(tag + ": widgets overlap: "
							+ a.getMessage().getString() + " / " + b.getMessage().getString());
				}
			}
		}
	}

	private static boolean saveEnabled(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			if (mc.gui.screen() != null) {
				for (var child : mc.gui.screen().children()) {
					if (child instanceof AbstractWidget w
							&& "Save".equals(w.getMessage().getString())) {
						return w.active;
					}
				}
			}
			return false;
		});
	}

	private static void assertCannotSave(ClientGameTestContext context, String tag) {
		if (saveEnabled(context)) {
			throw new AssertionError(tag + ": Save active despite invalid draft");
		}
		context.tryClickScreenButton("Save");
		context.waitTicks(3);
		if (!(screen(context) instanceof LiteBuildSettingsScreen)) {
			throw new AssertionError(tag + ": invalid draft was saved");
		}
	}

	private static int liveHotbarDelay(ClientGameTestContext context) {
		return context.computeOnClient(mc -> BuildController.get().config().hotbarSwitchTicks);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
		BuildConfig original = context.computeOnClient(
				mc -> BuildController.get().config().copy());
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			sp.getServer().runOnServer(server -> {
				server.overworld().setBlockAndUpdate(new BlockPos(0, 63, 0),
						Blocks.STONE.defaultBlockState());
				server.getGameRules().set(
						net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,
						false, server);
			});
			sp.getConnection().waitForChunksRender();
			context.waitTicks(10);

			// ---- overview ----
			context.setScreen(LiteBuildScreen::new);
			context.waitForScreen(LiteBuildScreen.class);
			assertWidgetsInsideAndDisjoint(context, "overview");
			context.takeScreenshot("ui-overview");

			context.getInput().resizeWindow(320, 240);
			context.waitTicks(10);
			assertWidgetsInsideAndDisjoint(context, "overview-320");
			context.takeScreenshot("ui-overview-320");
			context.getInput().resizeWindow(854, 480);
			context.waitTicks(10);

			// ---- settings: draft does not go live before save ----
			context.clickScreenButton("Settings");
			context.waitForScreen(LiteBuildSettingsScreen.class);
			int before = liveHotbarDelay(context);
			setBox(context, "Support block (block id)", "minecraft:cobblestone");
			clickTab(context, "Timings");
			setBox(context, "Hotbar switch", "6");
			setBox(context, "Inventory refill", "10");
			if (liveHotbarDelay(context) != before) {
				throw new AssertionError("Draft became active before save");
			}

			// invalid input must not save
			clickTab(context, "Build");
			setBox(context, "Support block (block id)", "minecraft:tnt");
			context.waitTicks(3);
			context.takeScreenshot("ui-invalid-field");
			assertCannotSave(context, "tnt");
			if (liveHotbarDelay(context) != before) {
				throw new AssertionError("Invalid draft became active anyway");
			}

			// raw invalid text survives tab switch and resize
			setBox(context, "Support block (block id)", "gibtsnicht!");
			clickTab(context, "Pauses");
			clickTab(context, "Build");
			if (!"gibtsnicht!".equals(boxValue(context, "Support block (block id)"))) {
				throw new AssertionError("invalid input text lost on tab switch");
			}
			context.getInput().resizeWindow(320, 240);
			context.waitTicks(10);
			assertWidgetsInsideAndDisjoint(context, "settings-320");
			context.takeScreenshot("ui-settings-320");
			if (!"gibtsnicht!".equals(boxValue(context, "Support block (block id)"))) {
				throw new AssertionError("invalid input text lost on resize");
			}
			context.getInput().resizeWindow(854, 480);
			context.waitTicks(10);

			// wrong min>max blocks save too
			clickTab(context, "Pauses");
			setBox(context, "Short pause after (actions)", "20");
			setBox(context, "Short pause after (max.)", "5");
			context.waitTicks(3);
			assertCannotSave(context, "minmax");
			context.takeScreenshot("ui-settings-pausen");

			// every tab is reachable; paginate Pausen
			for (String t : new String[]{"Build", "Timings", "Look", "Movement", "Pauses", "HUD"}) {
				clickTab(context, t);
				assertWidgetsInsideAndDisjoint(context, "tab-" + t);
			}
			if (context.tryClickScreenButton(">>")) {
				assertWidgetsInsideAndDisjoint(context, "pausen-page2");
			}

			// 'All defaults' resets only the draft
			clickTab(context, "Build");
			context.clickScreenButton("All defaults");
			setBox(context, "Support block (block id)", "minecraft:cobblestone");
			if (liveHotbarDelay(context) != before) {
				throw new AssertionError("All-defaults saved without a click");
			}

			// valid save: cobblestone scaffold + delays + corner
			context.clickScreenButton("Save");
			context.waitForScreen(LiteBuildScreen.class);
			BuildConfig live = context.computeOnClient(mc -> BuildController.get().config());
			if (live.hotbarSwitchTicks != 4 || !"minecraft:cobblestone".equals(live.scaffoldBlock)) {
				throw new AssertionError("saved values not active: "
						+ live.hotbarSwitchTicks + " / " + live.scaffoldBlock);
			}
			BuildConfig reloaded = context.computeOnClient(mc -> BuildConfig.load());
			if (!"minecraft:cobblestone".equals(reloaded.scaffoldBlock)) {
				throw new AssertionError("saved support block did not survive reload");
			}

			// timings/corner via a second pass
			context.clickScreenButton("Settings");
			context.waitForScreen(LiteBuildSettingsScreen.class);
			clickTab(context, "Timings");
			setBox(context, "Hotbar switch", "6");
			setBox(context, "Inventory refill", "10");
			clickTab(context, "HUD");
			context.clickScreenButton("Top left");
			context.takeScreenshot("ui-settings-hud");
			context.clickScreenButton("Save");
			context.waitForScreen(LiteBuildScreen.class);
			live = context.computeOnClient(mc -> BuildController.get().config());
			if (live.hotbarSwitchTicks != 6 || live.mainRefillTicks != 10
					|| live.hudCorner != BuildConfig.HudCorner.TOP_RIGHT) {
				throw new AssertionError("Timings/corner not saved: "
						+ live.hotbarSwitchTicks + "/" + live.mainRefillTicks
						+ "/" + live.hudCorner);
			}

			// keyboard: Tab cycles focus, Escape returns to overview
			context.clickScreenButton("Settings");
			context.waitForScreen(LiteBuildSettingsScreen.class);
			var focused0 = context.computeOnClient(mc -> mc.gui.screen().getFocused());
			context.getInput().pressKey(GLFW.GLFW_KEY_TAB);
			context.waitTicks(5);
			var focused1 = context.computeOnClient(mc -> mc.gui.screen().getFocused());
			if (focused1 == null || focused1 == focused0) {
				throw new AssertionError("Tab does not refocus");
			}
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitForScreen(LiteBuildScreen.class);
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitTicks(10);
			if (screen(context) != null) {
				throw new AssertionError("Escape does not close the screen");
			}
			context.takeScreenshot("ui-ingame-hud-empty");
		} finally {
			context.runOnClient(mc -> {
				BuildController c = BuildController.get();
				BuildConfig restore = original.copy();
				restore.fluidsEnabled = c.config().fluidsEnabled;
				restore.activationEnabled = c.config().activationEnabled;
				restore.save();
			});
		}
	}

	private static void clickTab(ClientGameTestContext context, String name) {
		context.clickScreenButton(name);
		context.waitTicks(3);
	}

}
