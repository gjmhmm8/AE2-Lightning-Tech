package com.moakiee.ae2lt.logic.batch;

import java.util.Iterator;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.inv.ListCraftingInventory;

/**
 * Abstraction over the &quot;executing crafting job&quot; surface used by
 * {@link BatchExecutor} / {@link ParallelBatchCpuHelper}. Decouples the batch
 * algorithm from the concrete execution-job class so it can run against both
 * vanilla AE2's {@code appeng.crafting.execution.ExecutingCraftingJob} and
 * AdvancedAE's forked {@code net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob},
 * which despite identical structure are distinct types — AAE forked the entire
 * execution package into its own namespace as of 1.6.x.
 *
 * <p>Implementations are typically allocated once per {@code tickCraftingLogic}
 * batch call and may double as their own {@link BatchTaskHandle} / iterator
 * (mutating internal state per {@link Iterator#next()}) to keep allocation
 * pressure on the hot path to a single object.
 *
 * <p>{@link #waitingFor()} is the same {@code appeng.crafting.inv.ListCraftingInventory}
 * type for both AE2 and AAE, so it is exposed directly. {@link #addContainerMaxItems}
 * abstracts over the differing {@code ElapsedTimeTracker} types.
 */
public interface BatchJobView {

    /**
     * Iterator over the live tasks map. Must support {@link Iterator#remove()},
     * which removes the most-recently-returned task from the underlying job
     * (mirrors vanilla {@code executeCrafting}'s {@code it.remove()} idiom).
     */
    Iterator<BatchTaskHandle> taskIterator();

    /** The job's {@code waitingFor} inventory. */
    ListCraftingInventory waitingFor();

    /**
     * Forwards to the job's {@code timeTracker.addMaxItems(count, type)} —
     * called for container-item bookkeeping when registering expected outputs.
     */
    void addContainerMaxItems(long count, AEKeyType type);
}
