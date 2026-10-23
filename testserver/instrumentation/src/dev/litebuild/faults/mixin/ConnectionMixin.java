package dev.litebuild.faults.mixin;

import dev.litebuild.faults.FaultState;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the two choke points of a server-side Connection:
 * <ul>
 *   <li>{@code sendPacket} - every clientbound packet funnels through here,
 *       so dropping/delaying at this point simulates real network loss and
 *       latency (including suppressed LiteBuild ack packets).</li>
 *   <li>{@code channelRead0} - every inbound serverbound packet passes here
 *       before being handed to the packet listener.</li>
 * </ul>
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(
        method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void litebuild$interceptSend(Packet<?> packet, ChannelFutureListener listener,
                                           boolean flush, CallbackInfo ci) {
        if (FaultState.onSend((Connection) (Object) this, packet, listener, flush)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void litebuild$interceptReceive(ChannelHandlerContext ctx, Packet<?> packet,
                                              CallbackInfo ci) {
        if (FaultState.onReceive((Connection) (Object) this, packet)) {
            ci.cancel();
        }
    }
}
