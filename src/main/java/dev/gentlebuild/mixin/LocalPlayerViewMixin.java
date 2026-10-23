package dev.gentlebuild.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.gentlebuild.build.BuildController;

/**
 * MC 26.2 LocalPlayer overrides Entity's interpolated view getters and
 * returns the raw current rotation, so controller-driven turns would snap
 * every tick. While the controller is actively aiming we lerp between the
 * previous and current angles — the same result vanilla produces for any
 * other rotation source. At partialTick=1 (raycasts, useItem) this is the
 * actual rotation, so nothing is faked for the server.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerViewMixin {
	@Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
	private void gentlebuild$lerpViewY(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (BuildController.get().isAimingView()) {
			LocalPlayer self = (LocalPlayer) (Object) this;
			cir.setReturnValue(Mth.rotLerp(Mth.clamp(partialTick, 0.0f, 1.0f),
					self.yRotO, self.getYRot()));
		}
	}

	@Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true)
	private void gentlebuild$lerpViewX(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (BuildController.get().isAimingView()) {
			LocalPlayer self = (LocalPlayer) (Object) this;
			cir.setReturnValue(Mth.lerp(Mth.clamp(partialTick, 0.0f, 1.0f),
					self.xRotO, self.getXRot()));
		}
	}
}
