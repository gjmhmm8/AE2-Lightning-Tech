package com.moakiee.ae2lt.mixin;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import com.moakiee.ae2lt.logic.batch.BatchExecutor;
import com.moakiee.ae2lt.logic.batch.VanillaBatchJobView;

/**
 * cpu-side batch dispatch path for vanilla AE2 {@link CraftingCpuLogic}. See
 * {@code ae2_并行发配_lib} plan §11 and the AAE counterpart
 * {@link AdvCraftingCpuLogicBatchMixin}.
 *
 * <p>Two {@code @WrapOperation} injection points, both on
 * {@link CraftingCpuLogic} but on distinct INVOKE bytecodes (the do-while
 * call site in {@code tickCraftingLogic}, and the {@code getProviders} call
 * site inside {@code executeCrafting}). Neither overlaps with the
 * {@code provider.pushPattern} call that EAP / AdvancedAE / ECO / AE2LT-self
 * already wrap, so the conflict surface against the existing AE2 mod
 * ecosystem is zero.
 *
 * <p>The actual batch algorithm lives in
 * {@link BatchExecutor#runBatchOnly}; this class is just the AE2-vanilla
 * wiring layer.
 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicBatchMixin {

    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    CraftingCPUCluster cluster;

    @Shadow
    public abstract ListCraftingInventory getInventory();

    /**
     * Per-wrap-call set of providers our batch loop has handled, keyed by task.
     * Cleared at the start of every wrap call (i.e. every iteration of vanilla's
     * do-while), since persistence across iterations only blocks providers that
     * could otherwise be batched again — vanilla's real {@code provider.isBusy()}
     * already keeps post-push providers off-limits when their {@code sendList}
     * is still draining, so we don't need a separate cross-iteration memory.
     */
    @Unique
    private final Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> ae2lt$batchedByTask
            = new HashMap<>();

    @WrapOperation(
            method = "tickCraftingLogic",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/crafting/execution/CraftingCpuLogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"
            )
    )
    private int ae2lt$wrapExecuteCrafting(
            CraftingCpuLogic self,
            int remainingOps,
            CraftingService cs,
            IEnergyService es,
            Level level,
            Operation<Integer> original) {

        ae2lt$batchedByTask.clear();
        if (this.job == null) {
            return original.call(self, remainingOps, cs, es, level);
        }

        int batchPushed = BatchExecutor.runBatchOnly(
                remainingOps, cs, es, level,
                new VanillaBatchJobView(this.job), getInventory(),
                ae2lt$batchedByTask,
                cluster::markDirty);

        if (batchPushed >= remainingOps) {
            return batchPushed;
        }

        int normalPushed = original.call(self, remainingOps - batchPushed, cs, es, level);
        return batchPushed + normalPushed;
    }

    @WrapOperation(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;getProviders"
                            + "(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;"
            )
    )
    private Iterable<ICraftingProvider> ae2lt$filterBatched(
            CraftingService cs, IPatternDetails details,
            Operation<Iterable<ICraftingProvider>> original) {
        var raw = original.call(cs, details);
        if (ae2lt$batchedByTask.isEmpty()) return raw;
        var perTask = ae2lt$batchedByTask.get(details);
        if (perTask == null || perTask.isEmpty()) return raw;
        return new BatchFilterIterable(raw, perTask);
    }

    /**
     * Lazy filter iterable that hides providers our batch loop already handled
     * for the current task from vanilla's iteration in {@code executeCrafting}.
     * Allocated only when {@link #ae2lt$batchedByTask} has entries for the
     * current task.
     */
    @Unique
    private static final class BatchFilterIterable implements Iterable<ICraftingProvider> {
        private final Iterable<ICraftingProvider> raw;
        private final IdentityHashMap<ICraftingProvider, Boolean> excluded;

        BatchFilterIterable(Iterable<ICraftingProvider> raw,
                            IdentityHashMap<ICraftingProvider, Boolean> excluded) {
            this.raw = raw;
            this.excluded = excluded;
        }

        @Override
        public java.util.Iterator<ICraftingProvider> iterator() {
            var it = raw.iterator();
            return new java.util.Iterator<ICraftingProvider>() {
                ICraftingProvider next;
                boolean ready;

                @Override
                public boolean hasNext() {
                    while (!ready && it.hasNext()) {
                        var p = it.next();
                        if (!excluded.containsKey(p)) {
                            next = p;
                            ready = true;
                            return true;
                        }
                    }
                    return ready;
                }

                @Override
                public ICraftingProvider next() {
                    if (!ready && !hasNext()) throw new java.util.NoSuchElementException();
                    ready = false;
                    return next;
                }
            };
        }
    }
}
