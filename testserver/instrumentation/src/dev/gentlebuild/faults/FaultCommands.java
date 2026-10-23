package dev.gentlebuild.faults;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * /fault command tree. Registered for everyone (the harness is a private
 * local test server); the console can use every subcommand as well.
 */
final class FaultCommands {

    private FaultCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("fault")
            .executes(ctx -> reply(ctx, "faults: " + FaultState.describe()))

            .then(Commands.literal("status")
                .executes(ctx -> reply(ctx, "faults: " + FaultState.describe())))

            .then(Commands.literal("clear")
                .executes(ctx -> {
                    FaultState.clear();
                    return reply(ctx, "all faults cleared");
                }))

            .then(Commands.literal("stall")
                .then(Commands.argument("ms", IntegerArgumentType.integer(1, 30000))
                    .executes(ctx -> stall(ctx, 100))
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 200000))
                        .executes(ctx -> stall(ctx, IntegerArgumentType.getInteger(ctx, "ticks"))))))

            .then(Commands.literal("delay")
                .then(Commands.argument("ms", IntegerArgumentType.integer(0, 60000))
                    .executes(ctx -> {
                        FaultState.delayMs = IntegerArgumentType.getInteger(ctx, "ms");
                        return reply(ctx, "outbound packet delay = " + FaultState.delayMs + " ms");
                    })))

            .then(Commands.literal("ackdelay")
                .then(Commands.argument("ms", IntegerArgumentType.integer(0, 60000))
                    .executes(ctx -> {
                        FaultState.ackDelayMs = IntegerArgumentType.getInteger(ctx, "ms");
                        return reply(ctx, "block-ack delay = " + FaultState.ackDelayMs + " ms");
                    })))

            .then(Commands.literal("drop")
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                    .executes(ctx -> {
                        FaultState.dropOutPercent = IntegerArgumentType.getInteger(ctx, "percent");
                        return reply(ctx, "outbound drop = " + FaultState.dropOutPercent + "%");
                    })))

            .then(Commands.literal("ackdrop")
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                    .executes(ctx -> {
                        FaultState.ackDropPercent = IntegerArgumentType.getInteger(ctx, "percent");
                        return reply(ctx, "block-ack drop = " + FaultState.ackDropPercent + "%");
                    })))

            .then(Commands.literal("dropin")
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                    .executes(ctx -> {
                        FaultState.dropInPercent = IntegerArgumentType.getInteger(ctx, "percent");
                        return reply(ctx, "inbound drop = " + FaultState.dropInPercent + "%");
                    })))

            .then(Commands.literal("snapshot")
                .executes(ctx -> snapshot(ctx, 24))
                .then(Commands.argument("radius", IntegerArgumentType.integer(4, 128))
                    .executes(ctx -> snapshot(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

            .then(Commands.literal("rollback")
                .executes(ctx -> {
                    int n = FaultState.rollback(server(ctx));
                    return reply(ctx, "rollback restored " + n + " blocks");
                }))

            .then(Commands.literal("chunkreload")
                .executes(ctx -> {
                    int n = FaultState.chunkReload(server(ctx));
                    return reply(ctx, "re-sent " + n + " chunks to all players");
                }))

            .then(Commands.literal("kick")
                .executes(ctx -> {
                    int n = FaultState.kickAll(server(ctx));
                    return reply(ctx, "disconnected " + n + " player(s)");
                }))

            .then(Commands.literal("chaos")
                .executes(ctx -> {
                    FaultState.chaos = !FaultState.chaos;
                    return reply(ctx, "chaos mode " + (FaultState.chaos ? "ON" : "OFF"));
                })
                .then(Commands.literal("on")
                    .executes(ctx -> {
                        FaultState.chaos = true;
                        return reply(ctx, "chaos mode ON");
                    }))
                .then(Commands.literal("off")
                    .executes(ctx -> {
                        FaultState.chaos = false;
                        return reply(ctx, "chaos mode OFF");
                    }))
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(ctx -> {
                        FaultState.chaos = BoolArgumentType.getBool(ctx, "enabled");
                        return reply(ctx, "chaos mode " + (FaultState.chaos ? "ON" : "OFF"));
                    })))
        );
    }

    private static MinecraftServer server(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getServer();
    }

    private static int stall(CommandContext<CommandSourceStack> ctx, int ticks) {
        FaultState.stallMsPerTick = IntegerArgumentType.getInteger(ctx, "ms");
        FaultState.stallTicksRemaining.set(ticks);
        return reply(ctx, "stalling " + FaultState.stallMsPerTick + " ms/tick for " + ticks + " ticks");
    }

    private static int snapshot(CommandContext<CommandSourceStack> ctx, int radius) {
        int n = FaultState.snapshot(server(ctx), radius);
        return reply(ctx, "snapshot recorded " + n + " block states (radius " + radius + ")");
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String msg) {
        FaultsMod.LOGGER.info("[gentlebuild-faults] {}", msg);
        ctx.getSource().sendSystemMessage(Component.literal("[faults] " + msg));
        return 1;
    }
}
