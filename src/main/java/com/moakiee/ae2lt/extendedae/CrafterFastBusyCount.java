package com.moakiee.ae2lt.extendedae;

/**
 * O(1) replacement for EAE {@code TileAssemblerMatrixCrafter#usedThread()}.
 *
 * <p>EAE's {@code usedThread()} walks every thread and probes
 * {@code getInternalInventory().isEmpty()} — that probe goes through
 * {@code InternalInventory#isEmpty()} which iterates and allocates a fresh
 * {@code InternalInventoryIterator} per call. With many crafters/threads
 * (and our wheel calls it on every batch push), this dominates the dispatch
 * profile.
 *
 * <p>Instead, we count set bits in the crafter's existing {@code states} mask
 * (vanilla 0..7) plus our {@code ae2lt$extraStates} mask (parallel 0..23).
 * EAE keeps these masks in sync with thread state via {@code updateSleepiness}
 * on every {@code acceptJob}/tick/stop, so {@code Integer.bitCount} on them
 * faithfully reproduces {@code usedThread()} without iterator allocation.
 */
public interface CrafterFastBusyCount {
    int ae2lt$usedThreadFast();
}
