package dev.gentlebuild.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.gentlebuild.build.AckTracker;

/**
 * Feeds authoritative server block updates into the AckTracker AFTER vanilla
 * handling has applied them, so pending placement transactions can complete
 * only on server acknowledgement.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void gentlebuild$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		AckTracker.onServerBlock(packet.getPos(), packet.getBlockState());
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
	private void gentlebuild$onChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet,
												 CallbackInfo ci) {
		packet.runUpdates(AckTracker::onServerBlock);
	}
}
