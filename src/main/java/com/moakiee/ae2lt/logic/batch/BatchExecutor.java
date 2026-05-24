package com.moakiee.ae2lt.logic.batch;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;

import com.moakiee.ae2lt.api.crafting.IBatchCraftingProvider;
import com.moakiee.ae2lt.overload.pattern.OverloadedProviderOnlyPatternDetails;

/**
 * Shared cpu-side batch dispatch algorithm. Both the vanilla
 * {@code CraftingCpuLogic} mixin and the AAE {@code AdvCraftingCPULogic} mixin
 * delegate to {@link #runBatchOnly} — the do-while loop / executeCrafting
 * structure is identical between vanilla AE2 and AAE, the only behavioural
 * difference is how each CPU type marks itself dirty (vanilla uses
 * {@code cluster.markDirty}, AAE uses {@code cpu.markDirty}). The caller
 * supplies that as a {@link Runnable} along with a {@link BatchJobView}
 * abstracting over their differing crafting-job classes.
 *
 * <p>Each CPU instance owns its own per-tick {@code batchedByTask} map; the
 * helper reads/writes it as an out-parameter via the wrapping mixin's
 * {@code @Unique} fields.
 *
 * <p>Algorithm: see {@code ae2_并行发配_lib} plan §11 and the docstrings on
 * {@link ParallelBatchCpuHelper#bulkExtract}.
 */
public final class BatchExecutor {

    private BatchExecutor() {
    }

    public static int runBatchOnly(int remainingOps,
                                   CraftingService cs,
                                   IEnergyService es,
                                   Level level,
                                   BatchJobView job,
                                   ListCraftingInventory inv,
                                   Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                   Runnable markDirty) {
        if (job == null) return 0;

        var taskIter = job.taskIterator();
        if (!taskIter.hasNext()) return 0;

        int totalPushed = 0;
        boolean dirty = false;

        while (taskIter.hasNext()) {
            var task = taskIter.next();
            long taskValue = task.getValue();
            if (taskValue <= 0) {
                taskIter.remove();
                continue;
            }
            var details = task.details();

            // TODO(ae2lt-batch-overload): defer to vanilla path for overload patterns.
            // The legacy {@code CraftingCpuLogicMixin#ae2lt$registerOverloadExpectedOutputs}
            // hook (and its AAE twin) lives on {@code ICraftingProvider#pushPattern} —
            // batch dispatch goes straight to {@code IBatchCraftingProvider#pushBatch}
            // and would silently bypass {@code OverloadCpuStateManager.hasAmbiguousOutputRegistration}
            // / {@code registerExpectedOutputs}. Until the batch path explicitly mirrors
            // those calls (with {@code count = dispatched}), the safe contract is:
            // overload patterns always go through vanilla single-copy dispatch. Remove
            // this guard once the overload-state plumbing is wired into BatchExecutor.
            if (details instanceof OverloadedProviderOnlyPatternDetails) continue;

            int budget = (int) Math.min((long) (remainingOps - totalPushed), taskValue);
            if (budget <= 0) continue;

            var perTaskBatched = batchedByTask.computeIfAbsent(details, k -> new IdentityHashMap<>());

            // Bulk extract first; fall back to vanilla helper (substitution-aware,
            // 1 copy) only when no single variant can satisfy any slot.
            ParallelBatchCpuHelper.BulkResult result = ParallelBatchCpuHelper.bulkExtract(details, inv, budget);
            if (result == null) {
                var expectedOutputsBuf = new KeyCounter();
                var expectedContainerBuf = new KeyCounter();
                KeyCounter[] vanillaInputs = CraftingCpuHelper.extractPatternInputs(
                        details, inv, level, expectedOutputsBuf, expectedContainerBuf);
                if (vanillaInputs == null) continue;
                result = wrapVanillaExtract(vanillaInputs);
            }

            int realCraft = result.actualCopies;

            // Energy budget: SIMULATE total for realCraft copies; scale down or abort.
            double powerForReal = CraftingCpuHelper.calculatePatternPower(result.scaledInputs);
            double powerOne = realCraft > 0 ? powerForReal / realCraft : 0.0;
            double availPower = es.extractAEPower(powerForReal, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (availPower < powerForReal - 0.01) {
                int affordable = powerOne > 0 ? (int) Math.floor(availPower / powerOne) : 0;
                if (affordable <= 0) {
                    ParallelBatchCpuHelper.reinject(result, realCraft, inv);
                    if (dirty) markDirty.run();
                    return totalPushed; // CPU starved on energy; abort batch entirely
                }
                int scaleDownExcess = realCraft - affordable;
                if (scaleDownExcess > 0) {
                    ParallelBatchCpuHelper.reinject(result, scaleDownExcess, inv);
                    realCraft = affordable;
                }
            }

            int initialRealCraft = realCraft;
            int leftover = realCraft;

            // Collect eligible providers: instance-of, not-yet-batched-for-this-task, not busy.
            var eligible = new java.util.ArrayList<IBatchCraftingProvider>();
            for (var provider : cs.getProviders(details)) {
                if (!(provider instanceof IBatchCraftingProvider batch)) continue;
                if (perTaskBatched.containsKey(provider)) continue;
                if (provider.isBusy()) continue;
                eligible.add(batch);
            }

            for (int i = 0; i < eligible.size() && leftover > 0; i++) {
                var batch = eligible.get(i);
                int remainingProviders = eligible.size() - i;
                int slice = Math.max(1, leftover / (remainingProviders + 1));
                slice = Math.min(slice, leftover);

                KeyCounter[] subInputs = ParallelBatchCpuHelper.copySlice(result, slice);

                int subLeftover;
                try {
                    subLeftover = batch.pushBatch(details, subInputs, slice);
                } catch (Throwable t) {
                    appeng.core.AELog.warn("[ae2lt] IBatchCraftingProvider %s threw during pushBatch; "
                            + "treating as full leftover. %s", batch, t);
                    subLeftover = slice;
                }
                if (subLeftover < 0 || subLeftover > slice) {
                    appeng.core.AELog.warn("[ae2lt] IBatchCraftingProvider %s returned out-of-range leftover "
                            + "%d for slice=%d; treating as full leftover.", batch, subLeftover, slice);
                    subLeftover = slice;
                }

                int dispatched = slice - subLeftover;
                if (dispatched > 0) {
                    ParallelBatchCpuHelper.markDispatched(result, dispatched);
                    es.extractAEPower(powerOne * dispatched, Actionable.MODULATE, PowerMultiplier.CONFIG);
                    ParallelBatchCpuHelper.registerExpectedOutputs(job, details, dispatched);
                    dirty = true;

                    long newValue = task.getValue() - dispatched;
                    task.setValue(newValue);
                    totalPushed += dispatched;
                    leftover -= dispatched;

                    // Only exclude provider from vanilla loop when a real batch happened.
                    // For 1-copy probes (initialRealCraft <= 1) leave it visible to vanilla.
                    if (initialRealCraft > 1) {
                        perTaskBatched.put((ICraftingProvider) batch, Boolean.TRUE);
                    }

                    if (newValue <= 0) {
                        taskIter.remove();
                        if (leftover > 0) {
                            ParallelBatchCpuHelper.reinject(result, leftover, inv);
                            leftover = 0;
                        }
                        if (totalPushed >= remainingOps) {
                            if (dirty) markDirty.run();
                            return totalPushed;
                        }
                        break; // fall through to next task via outer while
                    }
                    if (totalPushed >= remainingOps) {
                        if (leftover > 0) {
                            ParallelBatchCpuHelper.reinject(result, leftover, inv);
                            leftover = 0;
                        }
                        if (dirty) markDirty.run();
                        return totalPushed;
                    }
                }
            }

            if (leftover > 0) {
                ParallelBatchCpuHelper.reinject(result, leftover, inv);
            }
        }

        if (dirty) markDirty.run();
        return totalPushed;
    }

    /**
     * Wrap a vanilla 1-copy {@link CraftingCpuHelper#extractPatternInputs} result
     * as a {@link ParallelBatchCpuHelper.BulkResult} so reinject/slice helpers
     * can treat both paths uniformly.
     */
    private static ParallelBatchCpuHelper.BulkResult wrapVanillaExtract(KeyCounter[] vanillaInputs) {
        int slots = vanillaInputs.length;
        var keys = new appeng.api.stacks.AEKey[slots];
        var perCopy = new long[slots];
        for (int i = 0; i < slots; i++) {
            long total = 0;
            appeng.api.stacks.AEKey firstKey = null;
            for (var e : vanillaInputs[i]) {
                if (firstKey == null) firstKey = e.getKey();
                total += e.getLongValue();
            }
            keys[i] = firstKey;
            perCopy[i] = total;
        }
        return new ParallelBatchCpuHelper.BulkResult(vanillaInputs, 1, keys, perCopy);
    }
}
