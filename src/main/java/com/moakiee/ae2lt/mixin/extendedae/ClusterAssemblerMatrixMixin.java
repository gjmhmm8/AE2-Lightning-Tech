package com.moakiee.ae2lt.mixin.extendedae;

import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixCrafter;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreHost;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreRules;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = ClusterAssemblerMatrix.class, remap = false)
public abstract class ClusterAssemblerMatrixMixin implements AssemblerMatrixParallelCoreHost {

    @Shadow
    @Final
    private ReferenceSet<TileAssemblerMatrixCrafter> availableCrafters;

    @Shadow
    @Final
    private ReferenceSet<TileAssemblerMatrixCrafter> busyCrafters;

    @Shadow
    @Final
    private Reference2IntMap<TileAssemblerMatrixCrafter> crafterStatusCache;

    @Shadow
    public abstract void addCrafter(TileAssemblerMatrixCrafter crafter);

    @Unique
    private int ae2lt$parallelCores;

    @Override
    public void ae2lt$addParallelCore() {
        int before = this.ae2lt$parallelCores;
        this.ae2lt$parallelCores = AssemblerMatrixParallelCoreRules.addCore(before);
        if (before == 0 && this.ae2lt$parallelCores > 0) {
            this.ae2lt$reclassifyCrafters();
        }
    }

    @Override
    public boolean ae2lt$hasParallelCore() {
        return AssemblerMatrixParallelCoreRules.hasCore(this.ae2lt$parallelCores);
    }

    @Override
    public int ae2lt$effectiveThreads() {
        return AssemblerMatrixParallelCoreRules.effectiveThreads(this.ae2lt$hasParallelCore());
    }

    @Inject(method = "addCrafter", at = @At("HEAD"), cancellable = true)
    private void ae2lt$addCrafterWithParallelCore(TileAssemblerMatrixCrafter crafter, CallbackInfo ci) {
        if (!this.ae2lt$hasParallelCore()) {
            return;
        }

        if (crafter.usedThread() < this.ae2lt$effectiveThreads()) {
            this.availableCrafters.add(crafter);
        } else {
            this.busyCrafters.add(crafter);
        }
        ci.cancel();
    }

    @Inject(method = "getBusyCrafterAmount", at = @At("HEAD"), cancellable = true)
    private void ae2lt$countActualBusyThreads(CallbackInfoReturnable<Integer> cir) {
        int count = 0;
        for (var crafter : this.availableCrafters) {
            count += crafter.usedThread();
        }
        for (var crafter : this.busyCrafters) {
            count += crafter.usedThread();
        }
        cir.setReturnValue(count);
    }

    @Unique
    private void ae2lt$reclassifyCrafters() {
        var all = new ReferenceOpenHashSet<TileAssemblerMatrixCrafter>();
        all.addAll(this.availableCrafters);
        all.addAll(this.busyCrafters);
        this.availableCrafters.clear();
        this.busyCrafters.clear();
        this.crafterStatusCache.clear();
        for (var crafter : all) {
            this.addCrafter(crafter);
        }
    }
}
