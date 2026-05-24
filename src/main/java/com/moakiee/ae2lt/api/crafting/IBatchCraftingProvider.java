package com.moakiee.ae2lt.api.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

/**
 * Public, addon-facing extension to {@link ICraftingProvider} that lets a single
 * {@code pushPattern}-equivalent call consume up to N copies of a pattern's
 * inputs in O(1)/O(log n) time, collapsing the TPS cost of N vanilla pushes into
 * one batch push.
 *
 * <p><b>Side-effect parity</b>: when this provider's {@link #pushBatch} returns
 * {@code leftover R}, the calling CPU treats {@code M = maxCraft - R} as if M
 * vanilla {@code pushPattern} calls had succeeded back-to-back: it deducts
 * energy ×M, registers expectedOutputs ×M, decrements task by M, and reinjects
 * the remaining R copies' worth of inputs (left untouched in the
 * {@code scaledInputs} array) back into the CPU inventory.
 *
 * <p><b>All-or-nothing per call is NOT required</b>: providers may return any
 * leftover R in {@code [0, maxCraft]}; values outside this range are treated as
 * a protocol violation by the caller (logged + full rollback).
 *
 * <h2>Two-method, one-default contract</h2>
 *
 * <p>Implementors only need to implement {@link #pushBatch}; the inherited
 * {@link ICraftingProvider#pushPattern} default below routes a vanilla single
 * push to {@code pushBatch(details, inputs, 1)}, so the provider works
 * unchanged when the cpu module that knows how to call {@code pushBatch} is
 * absent. If a provider needs different semantics for the {@code maxCraft == 1}
 * case (e.g., to opt out of batch entirely on the vanilla path), it may
 * override {@link #pushPattern} again.
 *
 * <h2>Time complexity</h2>
 *
 * <p>{@code pushBatch} must complete in O(1) or O(log n). Implementations must
 * NOT internally loop {@code maxCraft} times calling
 * {@link ICraftingProvider#pushPattern} as that defeats the entire purpose of
 * the interface; instead, they should compute target capacity once and dispatch
 * a single bulk push to the underlying machine(s).
 *
 * @see com.moakiee.ae2lt.api.crafting public addon-facing crafting API
 */
public interface IBatchCraftingProvider extends ICraftingProvider {

    /**
     * Try to consume up to {@code maxCraft} copies of {@code details}'s inputs.
     *
     * <p>The {@code scaledInputs} array is the per-pattern {@link KeyCounter}[]
     * with each entry's amount already multiplied by {@code maxCraft} by the
     * caller (CPU has physically extracted that amount from its inventory).
     *
     * <p><b>Return contract</b>: leftover {@code R} where M = maxCraft - R is
     * treated as the number of copies actually pushed.
     * <ul>
     *   <li>{@code R == maxCraft}: this method MUST NOT modify any entry of
     *       {@code scaledInputs} and MUST NOT modulate the underlying target.
     *       Caller will reinject the full {@code maxCraft} copies' worth back
     *       into its inventory.</li>
     *   <li>{@code 0 <= R < maxCraft}: M = maxCraft - R copies have been
     *       physically delivered to the underlying target; entries in
     *       {@code scaledInputs} that have been consumed should have their
     *       amounts decremented (so the array effectively now contains R
     *       copies' worth, which the caller will reinject).</li>
     *   <li>Returning a value outside {@code [0, maxCraft]} is a protocol
     *       violation; callers will log a warning and treat it as full
     *       rollback.</li>
     * </ul>
     *
     * @param details      the pattern being crafted
     * @param scaledInputs CPU-pre-extracted inputs, sized for {@code maxCraft} copies
     * @param maxCraft     upper bound the caller is willing to commit this call;
     *                     always {@code >= 1}
     * @return leftover R in {@code [0, maxCraft]}; 0 means full success
     */
    int pushBatch(IPatternDetails details, KeyCounter[] scaledInputs, int maxCraft);

    /**
     * Default implementation routes a vanilla single push to a batch of size 1.
     *
     * <p>This makes the provider behave correctly under a stock AE2 CPU
     * (without the optional cpu-module mixin); subclasses generally do not need
     * to override this.
     *
     * @return {@code true} iff {@code pushBatch} consumed all (1) copies, i.e.
     *         returned 0 leftover.
     */
    @Override
    default boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return pushBatch(patternDetails, inputHolder, 1) == 0;
    }
}
