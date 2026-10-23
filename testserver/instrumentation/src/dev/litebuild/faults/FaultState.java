package dev.litebuild.faults;

import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Mutable fault-injection state plus the packet hooks invoked from the
 * Connection mixin. All knobs are volatile so they can be flipped from
 * command handlers running on the server thread while the netty event
 * loops call into the hooks.
 */
public final class FaultState {

    private FaultState() {}

    // ---- outbound (server -> client) faults ----
    /** Delay applied to every clientbound packet, in milliseconds. */
    public static volatile int delayMs = 0;
    /** Random drop probability (percent) for every clientbound packet. */
    public static volatile int dropOutPercent = 0;
    /** Extra drop probability for block-update acks only. */
    public static volatile int ackDropPercent = 0;
    /** Extra delay for block-update acks only. */
    public static volatile int ackDelayMs = 0;

    // ---- inbound (client -> server) faults ----
    /** Random drop probability for serverbound packets in play state. */
    public static volatile int dropInPercent = 0;

    // ---- server stall (TPS drop) ----
    public static volatile int stallMsPerTick = 0;
    public static final AtomicInteger stallTicksRemaining = new AtomicInteger();

    // ---- chaos mode: randomly fires short fault bursts ----
    public static volatile boolean chaos = false;

    // ---- block snapshot for rollback simulation ----
    private static final Map<ServerLevel, Map<BlockPos, BlockState>> SNAPSHOTS = new IdentityHashMap<>();
    public static volatile boolean snapshotTaken = false;

    // ---- statistics ----
    public static final AtomicLong sentOk = new AtomicLong();
    public static final AtomicLong sentDropped = new AtomicLong();
    public static final AtomicLong sentDelayed = new AtomicLong();
    public static final AtomicLong recvDropped = new AtomicLong();

    private static final ScheduledExecutorService DELAYER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "litebuild-faults-delayer");
            t.setDaemon(true);
            return t;
        });

    /** Reentrancy guard: packets flushed by the delayer must not be re-intercepted. */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private static int chaosCountdown = 300;
    private static int ackBurstTicks = 0;
    private static int dropOutBurstTicks = 0;

    // ------------------------------------------------------------------
    // Packet hooks (called from ConnectionMixin on netty event loops)
    // ------------------------------------------------------------------

    /**
     * Intercept a clientbound send. Returns true when the packet was
     * swallowed or queued for delayed delivery (i.e. the original send
     * must be cancelled).
     */
    public static boolean onSend(Connection conn, Packet<?> packet,
                                 ChannelFutureListener listener, boolean flush) {
        if (BYPASS.get()) {
            return false;
        }
        if (!(conn.getPacketListener() instanceof ServerGamePacketListenerImpl)) {
            return false; // only tamper with the play phase
        }
        boolean ack = isAckPacket(packet);
        int delay = ack ? Math.max(ackDelayMs, delayMs) : delayMs;
        int drop = ack ? Math.max(ackDropPercent, dropOutPercent) : dropOutPercent;

        if (drop > 0 && ThreadLocalRandom.current().nextInt(100) < drop) {
            sentDropped.incrementAndGet();
            return true;
        }
        if (delay > 0) {
            sentDelayed.incrementAndGet();
            DELAYER.schedule(() -> {
                BYPASS.set(true);
                try {
                    conn.send(packet, listener, flush);
                } catch (Throwable ignored) {
                    // connection may be gone by the time the delay elapses
                } finally {
                    BYPASS.remove();
                }
            }, delay, TimeUnit.MILLISECONDS);
            return true;
        }
        sentOk.incrementAndGet();
        return false;
    }

    /**
     * Intercept an inbound (serverbound) packet. Returns true when the
     * packet should be silently discarded.
     */
    public static boolean onReceive(Connection conn, Packet<?> packet) {
        if (dropInPercent <= 0) {
            return false;
        }
        if (!(conn.getPacketListener() instanceof ServerGamePacketListenerImpl)) {
            return false; // let login/handshake through
        }
        if (ThreadLocalRandom.current().nextInt(100) < dropInPercent) {
            recvDropped.incrementAndGet();
            return true;
        }
        return false;
    }

    private static boolean isAckPacket(Packet<?> packet) {
        // The confirmations a building mod waits for: single block updates,
        // multi-block section updates and inventory slot confirmations.
        return packet instanceof ClientboundBlockUpdatePacket
            || packet instanceof ClientboundSectionBlocksUpdatePacket
            || packet instanceof ClientboundContainerSetSlotPacket;
    }

    // ------------------------------------------------------------------
    // Server-tick logic (stall + chaos)
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (stallTicksRemaining.get() > 0 && stallMsPerTick > 0) {
            stallTicksRemaining.decrementAndGet();
            try {
                Thread.sleep(stallMsPerTick);
            } catch (InterruptedException ignored) {
            }
        }
        if (chaos) {
            chaosTick(server);
        }
    }

    private static void chaosTick(MinecraftServer server) {
        if (ackBurstTicks > 0 && --ackBurstTicks == 0) {
            ackDropPercent = 0;
        }
        if (dropOutBurstTicks > 0 && --dropOutBurstTicks == 0) {
            dropOutPercent = 0;
        }
        if (--chaosCountdown > 0) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        chaosCountdown = 200 + rnd.nextInt(400); // every ~10-30 s
        switch (rnd.nextInt(4)) {
            case 0 -> {
                ackBurstTicks = 60;
                ackDropPercent = 100;
                announce(server, "chaos: suppressing all block-update acks for ~3 s");
            }
            case 1 -> {
                stallMsPerTick = 40 + rnd.nextInt(80);
                stallTicksRemaining.set(30 + rnd.nextInt(40));
                announce(server, "chaos: TPS stall burst (" + stallMsPerTick + " ms/tick)");
            }
            case 2 -> {
                if (snapshotTaken) {
                    int n = rollback(server);
                    announce(server, "chaos: rolled back " + n + " blocks");
                }
            }
            case 3 -> {
                dropOutBurstTicks = 40;
                dropOutPercent = 25;
                announce(server, "chaos: 25% outbound packet loss for ~2 s");
            }
            default -> { }
        }
    }

    private static void announce(MinecraftServer server, String msg) {
        FaultsMod.LOGGER.info("[litebuild-faults] {}", msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("[faults] " + msg));
        }
    }

    // ------------------------------------------------------------------
    // Snapshot / rollback
    // ------------------------------------------------------------------

    /** Records block states in a cube of +/- radius around every player. */
    public static int snapshot(MinecraftServer server, int radius) {
        int count = 0;
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.clear();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                ServerLevel level = p.level();
                Map<BlockPos, BlockState> map =
                    SNAPSHOTS.computeIfAbsent(level, l -> new HashMap<>());
                BlockPos center = p.blockPosition();
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos column = center.offset(dx, 0, dz);
                        if (!level.getChunkSource().hasChunk(column.getX() >> 4, column.getZ() >> 4)) {
                            continue;
                        }
                        for (int dy = -radius; dy <= radius; dy++) {
                            BlockPos pos = center.offset(dx, dy, dz);
                            if (!level.isLoaded(pos)) {
                                continue;
                            }
                            map.put(pos.immutable(), level.getBlockState(pos));
                            count++;
                        }
                    }
                }
            }
        }
        snapshotTaken = true;
        return count;
    }

    /**
     * Restores every recorded position whose live state differs from the
     * snapshot. From the client's point of view this is a server-side
     * rollback: placed blocks vanish, broken blocks reappear.
     */
    public static int rollback(MinecraftServer server) {
        int restored = 0;
        synchronized (SNAPSHOTS) {
            for (Map.Entry<ServerLevel, Map<BlockPos, BlockState>> e : SNAPSHOTS.entrySet()) {
                ServerLevel level = e.getKey();
                for (Map.Entry<BlockPos, BlockState> b : e.getValue().entrySet()) {
                    BlockPos pos = b.getKey();
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    if (!level.getBlockState(pos).equals(b.getValue())) {
                        level.setBlock(pos, b.getValue(), 3);
                        restored++;
                    }
                }
            }
        }
        return restored;
    }

    // ------------------------------------------------------------------
    // Chunk reload: forget + resend every chunk in each player's view
    // ------------------------------------------------------------------

    public static int chunkReload(MinecraftServer server) {
        int resent = 0;
        int view = server.getPlayerList().getViewDistance();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerLevel level = p.level();
            ChunkPos center = p.chunkPosition();
            for (int dx = -view; dx <= view; dx++) {
                for (int dz = -view; dz <= view; dz++) {
                    int cx = center.x() + dx;
                    int cz = center.z() + dz;
                    if (!level.getChunkSource().hasChunk(cx, cz)) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunk(cx, cz);
                    p.connection.send(new ClientboundForgetLevelChunkPacket(new ChunkPos(cx, cz)));
                    p.connection.send(new ClientboundLevelChunkWithLightPacket(
                        chunk, level.getChunkSource().getLightEngine(), null, null));
                    resent++;
                }
            }
        }
        return resent;
    }

    public static int kickAll(MinecraftServer server) {
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.disconnect(Component.literal("litebuild-faults: simulated disconnect"));
            n++;
        }
        return n;
    }

    public static void clear() {
        delayMs = 0;
        dropOutPercent = 0;
        ackDropPercent = 0;
        ackDelayMs = 0;
        dropInPercent = 0;
        stallMsPerTick = 0;
        stallTicksRemaining.set(0);
        chaos = false;
        ackBurstTicks = 0;
        dropOutBurstTicks = 0;
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("delay=").append(delayMs).append("ms");
        sb.append(" ackdelay=").append(ackDelayMs).append("ms");
        sb.append(" dropout=").append(dropOutPercent).append('%');
        sb.append(" ackdrop=").append(ackDropPercent).append('%');
        sb.append(" dropin=").append(dropInPercent).append('%');
        sb.append(" stall=").append(stallMsPerTick).append("ms x")
          .append(stallTicksRemaining.get()).append("t");
        sb.append(" chaos=").append(chaos);
        sb.append(" snapshot=").append(snapshotTaken);
        sb.append(" | sent ok=").append(sentOk.get())
          .append(" delayed=").append(sentDelayed.get())
          .append(" dropped=").append(sentDropped.get())
          .append(" in-dropped=").append(recvDropped.get());
        return sb.toString();
    }
}
