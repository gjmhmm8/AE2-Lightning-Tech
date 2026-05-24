package com.moakiee.ae2lt.mixin;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;

import com.moakiee.ae2lt.logic.batch.AaeBatchJobView;
import com.moakiee.ae2lt.logic.batch.BatchExecutor;

/**
 * cpu-side batch dispatch path for AdvancedAE's quantum CPU
 * {@link AdvCraftingCPULogic}. Mirrors {@link CraftingCpuLogicBatchMixin}
 * with these differences:
 *
 * <ol>
 *   <li>{@code @Pseudo}: AAE is a soft dependency — when AAE is absent the
 *       mixin simply does not apply, no runtime errors.</li>
 *   <li>{@code cpu.markDirty()} instead of {@code cluster.markDirty()}; AAE's
 *       CPU class is named {@link AdvCraftingCPU}, not
 *       {@code CraftingCPUCluster}.</li>
 *   <li>AAE forked the entire {@code appeng.crafting.execution} package into
 *       {@code net.pedroksl.advanced_ae.common.logic} as of 1.6.x — its
 *       {@code ExecutingCraftingJob}, {@code ExecutingCraftingJob$TaskProgress}
 *       and {@code ElapsedTimeTracker} are <strong>distinct types</strong> from
 *       vanilla AE2's, even though they are field-for-field identical. The
 *       batch algorithm is decoupled via {@link AaeBatchJobView} which uses a
 *       parallel set of {@code @Pseudo} accessors targeting AAE's classes
 *       ({@link AaeExecutingCraftingJobAccessor}, {@link AaeTaskProgressAccessor},
 *       {@link AaeElapsedTimeTrackerAccessor}).</li>
 * </ol>
 */
@Pseudo
@Mixin(value = AdvCraftingCPULogic.class, remap = false)
public abstract class AdvCraftingCpuLogicBatchMixin {

    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    private ListCraftingInventory inventory;

    @Shadow
    @Final
    AdvCraftingCPU cpu;

    /** See {@code CraftingCpuLogicBatchMixin#ae2lt$batchedByTask}. */
    @Unique
    private final Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> ae2lt$batchedByTask
            = new HashMap<>();

    @WrapOperation(
            method = "tickCraftingLogic",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/pedroksl/advanced_ae/common/logic/AdvCraftingCPULogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"
            )
    )
    private int ae2lt$wrapExecuteCrafting(
            AdvCraftingCPULogic self,
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
                new AaeBatchJobView(this.job), this.inventory,
                ae2lt$batchedByTask,
                cpu::markDirty);

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
        return new AdvBatchFilterIterable(raw, perTask);
    }

    /** See {@code CraftingCpuLogicBatchMixin.BatchFilterIterable}. */
    @Unique
    private static final class AdvBatchFilterIterable implements Iterable<ICraftingProvider> {
        private final Iterable<ICraftingProvider> raw;
        private final IdentityHashMap<ICraftingProvider, Boolean> excluded;

        AdvBatchFilterIterable(Iterable<ICraftingProvider> raw,
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
