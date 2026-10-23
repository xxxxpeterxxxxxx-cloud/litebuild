package dev.gentlebuild.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.gentlebuild.build.BuildController;

/**
 * Any screen opened while the builder is running (pause menu via Escape,
 * chat, inventory, Litematica GUIs, ...) halts the run. The mod's own
 * screen only opens while not building, so no exemption is needed beyond
 * the running-state check.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "setScreen", at = @At("HEAD"))
	private void gentlebuild$onScreenOpen(Screen screen, CallbackInfo ci) {
		if (screen != null) {
			BuildController.get().onScreenOpened(screen);
		}
	}
}
