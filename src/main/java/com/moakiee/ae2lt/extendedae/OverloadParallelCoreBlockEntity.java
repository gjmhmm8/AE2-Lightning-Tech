package com.moakiee.ae2lt.extendedae;

import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixFunction;
import com.moakiee.ae2lt.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class OverloadParallelCoreBlockEntity extends TileAssemblerMatrixFunction {

    public OverloadParallelCoreBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.OVERLOAD_PARALLEL_CORE.get(), pos, blockState);
    }

    @Override
    public void add(ClusterAssemblerMatrix c) {
        if (c instanceof AssemblerMatrixParallelCoreHost host) {
            host.ae2lt$addParallelCore();
        }
    }
}
