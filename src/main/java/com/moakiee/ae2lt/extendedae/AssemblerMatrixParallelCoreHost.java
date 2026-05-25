package com.moakiee.ae2lt.extendedae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

public interface AssemblerMatrixParallelCoreHost {
    void ae2lt$addParallelCore();

    boolean ae2lt$hasParallelCore();

    int ae2lt$effectiveThreads();

    /**
     * O(1) batch dispatch entrypoint. Bypasses EAE's per-thread state machine
     * and writes the assembled outputs of {@code K = min(maxCraft, avail)}
     * copies into the hashed wheel slot {@code slot[gameTime & 7]}.
     *
     * <p>Caller already extracted {@code maxCraft × per-copy} inputs from the
     * crafting inventory via {@code ParallelBatchCpuHelper.bulkExtract}, and
     * has guaranteed the inputs are integer-divisible by {@code maxCraft}.
     *
     * @return number of copies <em>not</em> dispatched, i.e.
     *         {@code maxCraft - K}. CPU will reinject the leftover and retry.
     */
    int ae2lt$pushCraftingJobBatch(IPatternDetails details, KeyCounter[] scaledInputs, int maxCraft);

    /**
     * Returns the current per-push wheel delay (in ticks), determined by the
     * cluster's speed core count: {@code max(1, 5 - speedCore)}.
     */
    int ae2lt$currentDelay();

    /**
     * Drives the safety sweep from a {@code ServerTickEvent.Post} listener.
     * Implementation must be cheap when the wheel is empty (early exit).
     *
     * @return {@code true} if the wheel still has copies in flight, i.e. the
     *         cluster should remain on the active-sweep list.
     */
    boolean ae2lt$sweepActiveTick();

    /**
     * Serialize the wheel's non-empty cells into {@code data} under a
     * vendor-prefixed sub-tag. Called from the core BE's {@code saveAdditional}
     * mixin while the cluster is still alive, so we have a {@code HolderLookup}
     * for AEKey encoding.
     */
    void ae2lt$writeWheelToTag(CompoundTag data, HolderLookup.Provider registries);

    /**
     * Restore the wheel from a previously-saved core BE tag. Called from the
     * cluster's {@code done()} HEAD hook by reading {@code core.getPreviousState()}
     * — that state lives on the core BE after {@code loadTag} and is cleared
     * by EAE later in {@code done()}, so the hook must run before the clear.
     */
    void ae2lt$readWheelFromTag(CompoundTag data, HolderLookup.Provider registries);
}
