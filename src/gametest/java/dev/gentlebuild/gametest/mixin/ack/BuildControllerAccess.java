package dev.gentlebuild.gametest.mixin.ack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.build.PlacementJournal;

/**
 * Test-only read access to the placement journal: the break scenario
 * fabricates an "owned" block so RepairPolicy approves an automatic
 * REMOVE through the real BREAK_REPAIR path.
 */
@Mixin(value = BuildController.class, remap = false)
public interface BuildControllerAccess {
	@Accessor("journal")
	PlacementJournal gentlebuild$journal();
}
