package dev.gentlebuild.faults;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side fault-injection entry point for the GentleBuild
 * standalone-server test harness. Registers the /fault command tree and
 * the per-tick fault scheduler. Packet-level faults are wired through the
 * Connection mixin, which is declared in gentlebuild-faults.mixins.json.
 */
public final class FaultsMod implements DedicatedServerModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gentlebuild-faults");

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> FaultCommands.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(FaultState::tick);
        LOGGER.info("[gentlebuild-faults] instrumentation loaded; use /fault for control");
    }
}
