package dev.gentlebuild;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.command.GentleBuildCommands;
import dev.gentlebuild.ui.GentleBuildHud;
import dev.gentlebuild.ui.GentleBuildScreen;

public class GentleBuildClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(GentleBuild.MOD_ID, "main"));

	public static KeyMapping openScreen;
	public static KeyMapping pauseKey;

	@Override
	public void onInitializeClient() {
		openScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.gentlebuild.open", GLFW.GLFW_KEY_G, CATEGORY));
		pauseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.gentlebuild.pause", GLFW.GLFW_KEY_H, CATEGORY));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				GentleBuildCommands.register(dispatcher));

		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(GentleBuild.MOD_ID, "status"),
				GentleBuildHud::render);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Emergency controls are handled BEFORE the controller tick.
			while (pauseKey.consumeClick()) {
				BuildController.get().pause("Hotkey");
			}
			BuildController.get().tick(client);
			while (openScreen.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new GentleBuildScreen());
				}
			}
		});
	}
}
