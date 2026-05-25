package com.moakiee.ae2lt.mixin.extendedae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.KeyCounter;
import appeng.util.inv.CombinedInternalInventory;
import appeng.util.inv.AppEngInternalInventory;
import com.glodblock.github.extendedae.common.me.CraftingMatrixThread;
import com.glodblock.github.extendedae.common.me.CraftingThread;
import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixCrafter;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreHost;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = TileAssemblerMatrixCrafter.class, remap = false)
public abstract class TileAssemblerMatrixCrafterMixin {

    @Shadow
    @Final
    private CraftingThread[] threads;

    @Shadow
    @Final
    @Mutable
    private InternalInventory internalInv;

    @Shadow
    private short states;

    @Unique
    private CraftingThread[] ae2lt$extraThreads;

    @Unique
    private int ae2lt$extraStates;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ae2lt$createExtraThreads(BlockPos pos, BlockState blockState, CallbackInfo ci) {
        this.ae2lt$extraThreads = new CraftingThread[AssemblerMatrixParallelCoreRules.EXTRA_THREADS];
        var allInventories = new InternalInventory[AssemblerMatrixParallelCoreRules.effectiveThreads(true)];

        for (int i = 0; i < this.threads.length; i++) {
            allInventories[i] = this.threads[i].getInternalInventory();
        }

        for (int i = 0; i < this.ae2lt$extraThreads.length; i++) {
            final int extraIndex = i;
            var thread = new CraftingMatrixThread(
                    (TileAssemblerMatrixCrafter) (Object) this,
                    this::ae2lt$getSource,
                    signal -> this.ae2lt$changeExtraState(extraIndex, signal));
            this.ae2lt$extraThreads[i] = thread;
            allInventories[this.threads.length + i] = thread.getInternalInventory();
        }

        this.internalInv = new CombinedInternalInventory(allInventories);
    }

    @Inject(method = "usedThread", at = @At("RETURN"), cancellable = true)
    private void ae2lt$countExtraThreads(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue()
                + AssemblerMatrixParallelCoreRules.busyExtraThreads(this.ae2lt$extraStates));
    }

    @Inject(method = "pushJob", at = @At("RETURN"), cancellable = true)
    private void ae2lt$pushJobToExtraThreads(IPatternDetails patternDetails, KeyCounter[] inputHolder,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }

        var cluster = this.ae2lt$getCluster();
        if (!(cluster instanceof AssemblerMatrixParallelCoreHost host) || !host.ae2lt$hasParallelCore()) {
            return;
        }

        for (var thread : this.ae2lt$extraThreads) {
            if (thread.acceptJob(patternDetails, inputHolder, Direction.DOWN)) {
                cluster.updateCrafter((TileAssemblerMatrixCrafter) (Object) this);
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "stop", at = @At("RETURN"))
    private void ae2lt$stopExtraThreads(CallbackInfo ci) {
        for (var thread : this.ae2lt$extraThreads) {
            thread.stop();
        }
        this.ae2lt$extraStates = 0;
    }

    @Inject(method = "saveAdditional", at = @At("RETURN"))
    private void ae2lt$saveExtraThreads(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        for (int i = 0; i < this.ae2lt$extraThreads.length; i++) {
            data.put("#ct" + (this.threads.length + i), this.ae2lt$extraThreads[i].writeNBT(registries));
        }
    }

    @Inject(method = "loadTag", at = @At("RETURN"))
    private void ae2lt$loadExtraThreads(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        for (int i = 0; i < this.ae2lt$extraThreads.length; i++) {
            int threadIndex = this.threads.length + i;
            if (data.contains("#ct" + threadIndex)) {
                this.ae2lt$extraThreads[i].readNBT(data.getCompound("#ct" + threadIndex), registries);
            }
        }
    }

    @Inject(method = "getTickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2lt$getCombinedTickingRequest(IGridNode node, CallbackInfoReturnable<TickingRequest> cir) {
        boolean awake = false;
        for (var thread : this.threads) {
            thread.recalculatePlan();
            thread.updateSleepiness();
            awake |= thread.isAwake();
        }
        for (var thread : this.ae2lt$extraThreads) {
            thread.recalculatePlan();
            thread.updateSleepiness();
            awake |= thread.isAwake();
        }
        cir.setReturnValue(new TickingRequest(1, 1, !awake));
    }

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2lt$tickExtraThreads(IGridNode node, int ticksSinceLastCall,
                                        CallbackInfoReturnable<TickRateModulation> cir) {
        var cluster = this.ae2lt$getCluster();
        if (cluster == null) {
            cir.setReturnValue(TickRateModulation.SLEEP);
            return;
        }

        var rate = TickRateModulation.SLEEP;
        for (var thread : this.threads) {
            rate = this.ae2lt$tickOne(thread, cluster, ticksSinceLastCall, rate);
        }
        for (var thread : this.ae2lt$extraThreads) {
            rate = this.ae2lt$tickOne(thread, cluster, ticksSinceLastCall, rate);
        }
        cluster.updateCrafter((TileAssemblerMatrixCrafter) (Object) this);
        cir.setReturnValue(rate);
    }

    @Inject(method = "saveChangedInventory", at = @At("HEAD"))
    private void ae2lt$recalculateExtraInventory(AppEngInternalInventory inv, CallbackInfo ci) {
        for (var thread : this.ae2lt$extraThreads) {
            if (inv == thread.getInternalInventory()) {
                thread.recalculatePlan();
                return;
            }
        }
    }

    @Inject(method = "changeState", at = @At("RETURN"))
    private void ae2lt$keepAwakeForExtraThreads(int index, boolean state, CallbackInfo ci) {
        if (this.ae2lt$extraStates > 0) {
            ((TileAssemblerMatrixCrafter) (Object) this).getMainNode()
                    .ifPresent((grid, node) -> grid.getTickManager().wakeDevice(node));
        }
    }

    @Unique
    private TickRateModulation ae2lt$tickOne(CraftingThread thread, ClusterAssemblerMatrix cluster,
                                             int ticksSinceLastCall, TickRateModulation currentRate) {
        if (!thread.isAwake()) {
            return currentRate;
        }

        var threadRate = thread.tick(cluster.getSpeedCore(), ticksSinceLastCall);
        return threadRate.ordinal() > currentRate.ordinal() ? threadRate : currentRate;
    }

    @Unique
    private void ae2lt$changeExtraState(int index, boolean state) {
        boolean wasAwake = this.states > 0 || this.ae2lt$extraStates > 0;
        if (state) {
            this.ae2lt$extraStates |= (1 << index);
        } else {
            this.ae2lt$extraStates &= ~(1 << index);
        }

        boolean isAwake = this.states > 0 || this.ae2lt$extraStates > 0;
        if (!wasAwake && isAwake) {
            ((TileAssemblerMatrixCrafter) (Object) this).getMainNode()
                    .ifPresent((grid, node) -> grid.getTickManager().wakeDevice(node));
        } else if (wasAwake && !isAwake) {
            ((TileAssemblerMatrixCrafter) (Object) this).getMainNode()
                    .ifPresent((grid, node) -> grid.getTickManager().sleepDevice(node));
        }
    }

    @Unique
    private IActionSource ae2lt$getSource() {
        return this.ae2lt$getCluster().getSrc();
    }

    @Unique
    private ClusterAssemblerMatrix ae2lt$getCluster() {
        return ((TileAssemblerMatrixCrafter) (Object) this).getCluster();
    }
}
