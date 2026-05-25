package com.moakiee.ae2lt.extendedae;

import com.glodblock.github.extendedae.common.blocks.matrix.BlockAssemblerMatrixBase;
import com.moakiee.ae2lt.registry.ModBlocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class OverloadParallelCoreBlock extends BlockAssemblerMatrixBase<OverloadParallelCoreBlockEntity> {

    @Override
    public Item getPresentItem() {
        return ModBlocks.hasOverloadParallelCore()
                ? ModBlocks.OVERLOAD_PARALLEL_CORE.get().asItem()
                : Items.AIR;
    }
}
