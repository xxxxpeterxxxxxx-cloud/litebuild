package dev.gentlebuild.gametest.mixin.ack;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.gentlebuild.gametest.ack.AckGate;

/**
 * Test-only interception of clientbound block updates BEFORE vanilla
 * applies them. Withheld packets never reach the production TAIL hook
 * that feeds AckTracker — the transaction sees a genuinely missing
 * acknowledgement, and the prediction handler reverts the optimistic
 * client state on the next (ungated) block-changed-ack.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientboundAckGateMixin {
	@Inject(method = "handleBlockUpdate", at = @At("HEAD"), cancellable = true)
	private void gentlebuild$gateBlockUpdate(ClientboundBlockUpdatePacket packet,
											 CallbackInfo ci) {
		if (AckGate.onBlockUpdate(packet.getPos(), packet.getBlockState())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"), cancellable = true)
	private void gentlebuild$gateChunkBlocksUpdate(
			ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		if (AckGate.onSectionUpdate(packet)) {
			ci.cancel();
		}
	}
}
