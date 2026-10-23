package dev.litebuild.gametest.mixin.audit;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.litebuild.gametest.audit.LiteBuildAudit;

/**
 * Test-only tap on the real outbound channel: every serverbound packet the
 * client emits (via ClientPacketListener.send -> Connection.send) is handed
 * to {@link LiteBuildAudit}. This is the honest wire-level signal an
 * anticheat would see; nothing is synthesized client-side.
 */
@Mixin(Connection.class)
public abstract class ConnectionSendMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;"
			+ "Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
	private void litebuild$auditSend(Packet<?> packet,
									   ChannelFutureListener listener,
									   boolean flush, CallbackInfo ci) {
		LiteBuildAudit.onSend((Connection) (Object) this, packet);
	}
}
