package dev.litebuild.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@code BlockItem#getPlacementState} so the placement solver can
 * predict the exact result (including item-specific behavior such as
 * candles, slabs doubling, sign rotation, ...) instead of approximating with
 * {@code Block#getStateForPlacement}.
 */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {
	@Invoker("getPlacementState")
	BlockState litebuild$getPlacementState(BlockPlaceContext context);
}
