package com.moakiee.ae2lt.mixin.extendedae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern;
import com.moakiee.ae2lt.api.crafting.IBatchCraftingProvider;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreHost;
import java.util.List;
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
        if (maxCraft <= 0) {
            return 0;
        }

        var self = (TileAssemblerMatrixPattern) (Object) this;
        var cluster = self.getCluster();
        if (!self.isFormed()
                || !this.ae2lt$getMainNode(self).isActive()
                || !this.patterns.contains(details)
                || !(cluster instanceof AssemblerMatrixParallelCoreHost host)
                || !host.ae2lt$hasParallelCore()) {
            return maxCraft;
        }

        KeyCounter[] oneCopy = this.ae2lt$buildOneCopyTemplate(scaledInputs, maxCraft);
        if (oneCopy == null) {
            return maxCraft;
        }

        int dispatched = 0;
        while (dispatched < maxCraft) {
            var crafter = ((ClusterAssemblerMatrixAccessor) cluster).ae2lt$invokeGetAvailableCrafter();
            if (crafter == null) {
                break;
            }

            KeyCounter[] copyForThread = this.ae2lt$copy(oneCopy);
            if (!crafter.pushJob(details, copyForThread)) {
                break;
            }

            this.ae2lt$removeOneCopy(scaledInputs, oneCopy);
            dispatched++;
        }
        return maxCraft - dispatched;
    }

    @Unique
    private IManagedGridNode ae2lt$getMainNode(TileAssemblerMatrixPattern pattern) {
        return pattern.getMainNode();
    }

    @Unique
    private KeyCounter[] ae2lt$buildOneCopyTemplate(KeyCounter[] scaledInputs, int maxCraft) {
        var oneCopy = new KeyCounter[scaledInputs.length];
        for (int i = 0; i < scaledInputs.length; i++) {
            var scaledSlot = scaledInputs[i];
            var singleSlot = new KeyCounter();
            for (var entry : scaledSlot) {
                long amount = entry.getLongValue();
                if (amount <= 0 || amount % maxCraft != 0) {
                    return null;
                }

                long perCopy = amount / maxCraft;
                if (perCopy <= 0) {
                    return null;
                }
                singleSlot.add(entry.getKey(), perCopy);
            }
            oneCopy[i] = singleSlot;
        }
        return oneCopy;
    }

    @Unique
    private KeyCounter[] ae2lt$copy(KeyCounter[] template) {
        var copy = new KeyCounter[template.length];
        for (int i = 0; i < template.length; i++) {
            copy[i] = new KeyCounter();
            for (var entry : template[i]) {
                copy[i].add(entry.getKey(), entry.getLongValue());
            }
        }
        return copy;
    }

    @Unique
    private void ae2lt$removeOneCopy(KeyCounter[] scaledInputs, KeyCounter[] oneCopy) {
        for (int i = 0; i < scaledInputs.length; i++) {
            for (var entry : oneCopy[i]) {
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (amount > 0) {
                    scaledInputs[i].remove(key, amount);
                }
            }
        }
    }
}
