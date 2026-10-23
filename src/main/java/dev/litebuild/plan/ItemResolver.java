package dev.litebuild.plan;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Maps an expected block state to the item a survival player must hold.
 */
public final class ItemResolver {

	private ItemResolver() {}

	public static Item itemFor(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.WATER) {
			return Items.WATER_BUCKET;
		}
		if (block == Blocks.LAVA) {
			return Items.LAVA_BUCKET;
		}
		if (block == Blocks.REDSTONE_WIRE) {
			return Items.REDSTONE;
		}
		if (block == Blocks.WHEAT) {
			return Items.WHEAT_SEEDS;
		}
		if (block == Blocks.CARROTS) {
			return Items.CARROT;
		}
		if (block == Blocks.POTATOES) {
			return Items.POTATO;
		}
		if (block == Blocks.BEETROOTS) {
			return Items.BEETROOT_SEEDS;
		}
		if (block == Blocks.NETHER_WART) {
			return Items.NETHER_WART;
		}
		if (block == Blocks.COCOA) {
			return Items.COCOA_BEANS;
		}
		if (block == Blocks.SWEET_BERRY_BUSH) {
			return Items.SWEET_BERRIES;
		}
		if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) {
			return Items.GLOW_BERRIES;
		}
		if (block == Blocks.PUMPKIN_STEM) {
			return Items.PUMPKIN_SEEDS;
		}
		if (block == Blocks.MELON_STEM) {
			return Items.MELON_SEEDS;
		}
		if (block == Blocks.FIRE) {
			return Items.FLINT_AND_STEEL;
		}
		if (block == Blocks.SOUL_FIRE) {
			return Items.FLINT_AND_STEEL;
		}
		if (block == Blocks.TRIPWIRE) {
			return Items.STRING;
		}
		if (block == Blocks.FROGSPAWN || block == Blocks.POWDER_SNOW) {
			return block == Blocks.POWDER_SNOW ? Items.POWDER_SNOW_BUCKET : null;
		}
		if (state.hasProperty(BlockStateProperties.WATERLOGGED)
				&& state.getValue(BlockStateProperties.WATERLOGGED)) {
			// Waterlogged solids are placed dry; bucket task handled separately.
			Item dry = block.asItem();
			return dry == Items.AIR ? null : dry;
		}
		Item item = block.asItem();
		return item == Items.AIR ? null : item;
	}
}
