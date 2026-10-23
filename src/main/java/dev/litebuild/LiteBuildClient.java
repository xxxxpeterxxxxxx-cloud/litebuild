package dev.litebuild;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import dev.litebuild.build.BuildController;
import dev.litebuild.command.LiteBuildCommands;
import dev.litebuild.ui.LiteBuildHud;
import dev.litebuild.ui.LiteBuildScreen;

public class LiteBuildClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(LiteBuild.MOD_ID, "main"));

	public static KeyMapping openScreen;
	public static KeyMapping pauseKey;

	@Override
	public void onInitializeClient() {
		openScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.litebuild.open", GLFW.GLFW_KEY_G, CATEGORY));
		pauseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.litebuild.pause", GLFW.GLFW_KEY_H, CATEGORY));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				LiteBuildCommands.register(dispatcher));

		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(LiteBuild.MOD_ID, "status"),
				LiteBuildHud::render);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Emergency controls are handled BEFORE the controller tick.
			while (pauseKey.consumeClick()) {
				BuildController.get().pause("Hotkey");
			}
			BuildController.get().tick(client);
			while (openScreen.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new LiteBuildScreen());
				}
			}
		});
	}
}
