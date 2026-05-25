package com.moakiee.ae2lt.mixin.extendedae;

import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.KeyCounter;

import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern;
import com.moakiee.ae2lt.api.crafting.IBatchCraftingProvider;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreHost;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(value = TileAssemblerMatrixPattern.class, remap = false)
public abstract class TileAssemblerMatrixPatternBatchMixin implements IBatchCraftingProvider {

    @Shadow
    @Final
    private List<IPatternDetails> patterns;

    @Override
    public int pushBatch(IPatternDetails details, KeyCounter[] scaledInputs, int maxCraft) {
        if (maxCraft <= 0) return 0;

        TileAssemblerMatrixPattern self = (TileAssemblerMatrixPattern) (Object) this;
        var cluster = self.getCluster();
        // Wheel batching is decoupled from the parallel-core feature on purpose:
        // even base (8 thread/crafter) clusters benefit from batched assemble + delayed
        // flush, since wheel pushes skip the per-thread inventory walk entirely.
        if (!self.isFormed()
                || !this.ae2lt$getMainNode(self).isActive()
                || !this.patterns.contains(details)
                || !(cluster instanceof AssemblerMatrixParallelCoreHost host)) {
            return maxCraft;
        }

        return host.ae2lt$pushCraftingJobBatch(details, scaledInputs, maxCraft);
    }

    @Unique
    private IManagedGridNode ae2lt$getMainNode(TileAssemblerMatrixPattern pattern) {
        return pattern.getMainNode();
    }
}
