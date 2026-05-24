package com.moakiee.ae2lt.logic.batch;

import java.util.Iterator;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.ae2lt.mixin.ElapsedTimeTrackerAccessor;
import com.moakiee.ae2lt.mixin.ExecutingCraftingJobAccessor;
import com.moakiee.ae2lt.mixin.TaskProgressAccessor;

/**
 * {@link BatchJobView} backed by vanilla AE2's
 * {@code appeng.crafting.execution.ExecutingCraftingJob}. Doubles as its own
 * {@link BatchTaskHandle} and {@link Iterator} to keep batch-path allocation to
 * one wrapper object per CPU per call.
 */
public final class VanillaBatchJobView
        implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {

    private final ExecutingCraftingJob job;
    private Iterator<? extends Map.Entry<IPatternDetails, ?>> rawIter;
    private Map.Entry<IPatternDetails, ?> currentEntry;

    public VanillaBatchJobView(ExecutingCraftingJob job) {
        this.job = job;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<BatchTaskHandle> taskIterator() {
        Map<IPatternDetails, ?> tasks = ((ExecutingCraftingJobAccessor) (Object) job).getTasks();
        rawIter = (Iterator<? extends Map.Entry<IPatternDetails, ?>>) tasks.entrySet().iterator();
        currentEntry = null;
        return this;
    }

    @Override
    public boolean hasNext() {
        return rawIter.hasNext();
    }

    @Override
    public BatchTaskHandle next() {
        currentEntry = rawIter.next();
        return this;
    }

    @Override
    public void remove() {
        rawIter.remove();
        currentEntry = null;
    }

    @Override
    public IPatternDetails details() {
        return currentEntry.getKey();
    }

    @Override
    public long getValue() {
        return ((TaskProgressAccessor) currentEntry.getValue()).getValue();
    }

    @Override
    public void setValue(long value) {
        ((TaskProgressAccessor) currentEntry.getValue()).setValue(value);
    }

    @Override
    public ListCraftingInventory waitingFor() {
        return ((ExecutingCraftingJobAccessor) (Object) job).getWaitingFor();
    }

    @Override
    public void addContainerMaxItems(long count, AEKeyType type) {
        var tt = ((ExecutingCraftingJobAccessor) (Object) job).getTimeTracker();
        ((ElapsedTimeTrackerAccessor) tt).invokeAddMaxItems(count, type);
    }
}
