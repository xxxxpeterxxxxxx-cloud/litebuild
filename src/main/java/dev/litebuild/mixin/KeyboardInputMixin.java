package dev.litebuild.mixin;

import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.litebuild.motion.InputBroker;

/**
 * Routes mod-owned movement input through the vanilla KeyboardInput path and
 * recomputes the derived move vector exactly like vanilla does, so both
 * fields stay consistent. If the real keyboard produced any input in the
 * same tick, our override is skipped entirely — the player always wins.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void litebuild$overrideInput(CallbackInfo ci) {
		KeyboardInput self = (KeyboardInput) (Object) this;
		Input vanilla = self.keyPresses;
		if (!InputBroker.isControlling()) {
			return;
		}
		InputBroker.noteManualInput(vanilla);
		if (!Input.EMPTY.equals(vanilla)) {
			return; // real input wins this tick; controller pauses itself
		}
		Input desired = InputBroker.apply(vanilla);
		self.keyPresses = desired;
		// Vanilla normalization: Vec2(left-right, forward-back).normalized()
		float strafe = impulse(desired.left(), desired.right());
		float forward = impulse(desired.forward(), desired.backward());
		((ClientInputAccessor) self).litebuild$setMoveVector(
				new Vec2(strafe, forward).normalized());
	}

	private static float impulse(boolean a, boolean b) {
		return a == b ? 0.0f : (a ? 1.0f : -1.0f);
	}
}
