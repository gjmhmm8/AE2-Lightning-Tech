package com.moakiee.ae2lt.logic.batch;

import java.util.Arrays;
import java.util.HashMap;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;

/**
 * Bulk-extract / reinject / register helpers for the cpu-side batch path.
 * <p>
 * Design contract (see {@code ae2_并行发配_lib} plan §11):
 * <ul>
 *   <li>{@link #bulkExtract} performs O(K) inventory ops (K = pattern input
 *       slots, ≤ 9) regardless of {@code maxCraft}, using strict primary-key
 *       extraction. It is always safe to call (strict matching is a SUBSET of
 *       any fuzz/substitution/ID_ONLY semantics), but may return {@code null}
 *       for patterns where strict-key inventory cannot satisfy even one copy
 *       — in that case the caller should fall back to
 *       {@code CraftingCpuHelper.extractPatternInputs} for a 1-copy
 *       substitution-aware probe.</li>
 *   <li>{@link #registerExpectedOutputs} replays vanilla's per-copy
 *       waitingFor/timeTracker bookkeeping, scaled by the dispatched copy
 *       count {@code dispatched}, in O(outputs + inputs) time.</li>
 * </ul>
 */
public final class ParallelBatchCpuHelper {

    private ParallelBatchCpuHelper() {
    }

    /**
     * Pulls up to {@code maxCraft} copies of the pattern's inputs from {@code inv}.
     *
     * <p><b>Pure sim-first / single-MODULATE flow</b>: phase 1 is
     * {@code Actionable.SIMULATE} only across all variants of all slots; phase
     * 2 aggregates demand and computes the final {@code actual} batch size;
     * phase 3 does exactly one MODULATE-extract per slot for {@code perCopy *
     * actual} units. No partial-extract-then-reinject is performed on the
     * happy path; the {@code got < need} branch in phase 3 is purely
     * defensive against simulation/realtime drift.
     *
     * <p><b>AE2 already condenses identical inputs</b>:
     * {@code AECraftingPattern} runs {@code AEPatternHelper.condenseStacks}
     * during construction so that, for example, a "4 oak planks → 1 crafting
     * table" recipe surfaces as ONE {@code IInput} with
     * {@link IPatternDetails.IInput#getMultiplier() multiplier == 4}, not
     * four separate IInputs. We therefore treat each entry in
     * {@code details.getInputs()} as a distinct ingredient group already, and
     * iterate {@link IPatternDetails.IInput#getPossibleInputs()} to evaluate
     * variants within that group.
     *
     * <p><b>Per-slot variant selection (no "压类" mixing)</b>: for each
     * (already-condensed) input group we pick the <em>single best variant</em>
     * — the one whose simulated extract yields the most whole copies. We never
     * combine multiple variants inside the same slot, so the
     * {@code KeyCounter[]} handed to {@code pushBatch} carries exactly one
     * {@link AEKey} per slot, identical in shape to a vanilla single-copy push,
     * and avoids cross-variant interactions that could lead to dupes in
     * downstream recipe processing.
     *
     * <p><b>Cross-slot demand aggregation</b>: even with AE2's condense step,
     * substitution / ID_ONLY can cause two distinct ingredient groups to
     * resolve to the SAME concrete variant (e.g. one slot wants "any plank"
     * and another wants "any wood block"; with the right inventory both
     * collapse to spruce). Phase 2 sums each variant's per-copy demand across
     * slots and clamps the global batch size {@code actual} so the chosen
     * variant is not over-allocated. Without this step the greedy per-slot
     * algorithm would happily reserve the same items for multiple slots and
     * end up extracting nothing.
     *
     * <p><b>Substitution / ID_ONLY full-speed path</b>: even when the strict
     * primary variant is missing from inventory but a different valid variant
     * is present (e.g. all-spruce inventory for a "4 oak planks" substitution
     * recipe), we still reach full batch acceleration because phase 1 picks
     * the variant with the best simulated yield rather than always trying the
     * encoded primary key.
     *
     * <p>Returns {@code null} when no slot can be satisfied by a single
     * variant for at least one whole copy after demand aggregation — caller
     * should then fall back to
     * {@code CraftingCpuHelper.extractPatternInputs} for a 1-copy probe that
     * additionally supports vanilla's cross-variant in-slot assembly (the
     * "压类" path we deliberately avoid here).
     */
    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details,
                                         ListCraftingInventory inv,
                                         int maxCraft) {
        if (maxCraft <= 0) return null;

        var inputs = details.getInputs();
        int slots = inputs.length;
        AEKey[] chosenKeys = new AEKey[slots];
        long[] perCopyUnits = new long[slots];

        // Phase 1: per-slot SIMULATE-only — pick best variant. No inventory mutation
        // yet; we'll do the actual MODULATE in phase 3 once we know the global
        // batch size after cross-slot aggregation.
        for (int i = 0; i < slots; i++) {
            var input = inputs[i];
            long mult = input.getMultiplier();

            AEKey bestKey = null;
            long bestPerCopy = 0;
            long bestSimAvail = 0;
            long bestCopies = 0;

            for (var possible : input.getPossibleInputs()) {
                if (!(possible.what() instanceof AEItemKey)) continue;
                long perCopy = possible.amount() * mult;
                if (perCopy <= 0) continue;
                long need = perCopy * maxCraft;
                long avail = inv.extract(possible.what(), need, Actionable.SIMULATE);
                long canDo = avail / perCopy;
                if (canDo > bestCopies) {
                    bestKey = possible.what();
                    bestPerCopy = perCopy;
                    bestSimAvail = avail;
                    bestCopies = canDo;
                }
                if (bestCopies >= maxCraft) break;
            }

            if (bestKey == null || bestCopies <= 0) {
                return null; // some slot has no viable variant
            }

            chosenKeys[i] = bestKey;
            perCopyUnits[i] = bestPerCopy;
            // bestSimAvail is implicitly bounded by per-call need (perCopy * maxCraft)
            // and also by inventory total; phase 2 below uses it indirectly via
            // inv.extract(SIMULATE, ∞) for the global aggregation step.
            // (Suppress unused-warning by referencing.)
            if (bestSimAvail < 0) bestSimAvail = 0;
        }

        // Phase 2: aggregate per-variant demand across slots, find global cap.
        // For each distinct chosen variant K, totalPerCopy[K] = sum of
        // perCopyUnits[i] over slots i that picked K. Then maxBatchForK =
        // totalAvailable[K] / totalPerCopy[K]. The global actual is the min.
        var totalPerCopy = new HashMap<AEKey, Long>();
        for (int i = 0; i < slots; i++) {
            totalPerCopy.merge(chosenKeys[i], perCopyUnits[i], Long::sum);
        }

        long actual = maxCraft;
        for (var e : totalPerCopy.entrySet()) {
            long avail = inv.extract(e.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            long perBatch = e.getValue();
            long canDo = perBatch > 0 ? avail / perBatch : 0;
            if (canDo < actual) actual = canDo;
            if (actual <= 0) break;
        }

        if (actual <= 0) return null;

        // Phase 3: MODULATE-extract per slot using the agreed-upon global actual.
        long[] extracted = new long[slots];
        for (int i = 0; i < slots; i++) {
            long need = perCopyUnits[i] * actual;
            long got = inv.extract(chosenKeys[i], need, Actionable.MODULATE);
            extracted[i] = got;
            if (got < need) {
                // Should not happen because phase 2 already accounted for cross-slot
                // demand on the same key; defensive rollback if a race or fuzziness
                // breaks the simulation invariant.
                for (int j = 0; j <= i; j++) {
                    if (extracted[j] > 0) {
                        inv.insert(chosenKeys[j], extracted[j], Actionable.MODULATE);
                    }
                }
                return null;
            }
        }

        // Build scaledInputs: one variant per slot, exactly perCopy * actual.
        var scaled = new KeyCounter[slots];
        for (int i = 0; i < slots; i++) {
            scaled[i] = new KeyCounter();
            long keep = perCopyUnits[i] * actual;
            if (keep > 0) {
                scaled[i].add(chosenKeys[i], keep);
            }
        }

        return new BulkResult(scaled, (int) actual, chosenKeys, perCopyUnits);
    }

    /**
     * Reinjects {@code leftoverCopies} worth of inputs back into {@code inv} and
     * trims those amounts from {@code result.scaledInputs}. After this call the
     * scaled inputs represent the dispatched portion only.
     */
    public static void reinject(BulkResult result, int leftoverCopies, ListCraftingInventory inv) {
        if (leftoverCopies <= 0) return;
        for (int i = 0; i < result.scaledInputs.length; i++) {
            long amount = result.perCopyUnits[i] * leftoverCopies;
            if (amount > 0) {
                inv.insert(result.keys[i], amount, Actionable.MODULATE);
                result.scaledInputs[i].remove(result.keys[i], amount);
            }
        }
    }

    /**
     * Registers {@code dispatched} copies of expected outputs and container
     * items into the job's waitingFor / timeTracker, mirroring what vanilla
     * {@code executeCrafting} would have written across {@code dispatched}
     * separate single-copy iterations.
     *
     * <p>The {@link BatchJobView} indirection here is what lets us run on both
     * vanilla AE2's {@code ExecutingCraftingJob} and AAE's forked equivalent —
     * AAE's {@code ElapsedTimeTracker} is a different class, so we bind the
     * {@code addMaxItems} call through the view rather than the tracker
     * directly.
     */
    public static void registerExpectedOutputs(BatchJobView job,
                                               IPatternDetails details,
                                               int dispatched) {
        if (dispatched <= 0) return;

        var waitingFor = job.waitingFor();
        for (GenericStack output : details.getOutputs()) {
            long total = output.amount() * (long) dispatched;
            waitingFor.insert(output.what(), total, Actionable.MODULATE);
            // Note: vanilla executeCrafting does NOT call timeTracker.addMaxItems for
            // outputs (it's pre-populated at job construction); only for container items.
        }

        for (var input : details.getInputs()) {
            var first = input.getPossibleInputs()[0];
            AEKey containerKey = input.getRemainingKey(first.what());
            if (containerKey != null) {
                long count = input.getMultiplier() * (long) dispatched;
                waitingFor.insert(containerKey, count, Actionable.MODULATE);
                job.addContainerMaxItems(count, containerKey.getType());
            }
        }
    }

    /** Clones a single-copy KeyCounter[] for energy / power computation. */
    public static KeyCounter[] cloneSingleCopy(BulkResult result) {
        var slots = result.scaledInputs.length;
        var copy = new KeyCounter[slots];
        for (int i = 0; i < slots; i++) {
            copy[i] = new KeyCounter();
            if (result.perCopyUnits[i] > 0) {
                copy[i].add(result.keys[i], result.perCopyUnits[i]);
            }
        }
        return copy;
    }

    /**
     * Builds a fresh {@link KeyCounter}[] sized for {@code sliceCount} copies,
     * suitable to be passed as {@code scaledInputs} to a single
     * {@code pushBatch(details, sub, sliceCount)} call. The returned array is
     * independent from {@link BulkResult#scaledInputs}; consumption by the
     * provider mutates only the returned array.
     */
    public static KeyCounter[] copySlice(BulkResult result, int sliceCount) {
        if (sliceCount <= 0) {
            var empty = new KeyCounter[result.scaledInputs.length];
            for (int i = 0; i < empty.length; i++) empty[i] = new KeyCounter();
            return empty;
        }
        int slots = result.scaledInputs.length;
        var sub = new KeyCounter[slots];
        for (int i = 0; i < slots; i++) {
            sub[i] = new KeyCounter();
            long take = result.perCopyUnits[i] * sliceCount;
            if (take > 0 && result.keys[i] != null) {
                sub[i].add(result.keys[i], take);
            }
        }
        return sub;
    }

    /**
     * Removes {@code dispatchedCopies} worth of inputs from the original
     * {@link BulkResult#scaledInputs} after a successful slice push. Use this
     * in concert with {@link #copySlice} when distributing one batch across
     * multiple providers via slicing.
     */
    public static void markDispatched(BulkResult result, int dispatchedCopies) {
        if (dispatchedCopies <= 0) return;
        for (int i = 0; i < result.scaledInputs.length; i++) {
            long take = result.perCopyUnits[i] * dispatchedCopies;
            if (take > 0 && result.keys[i] != null) {
                result.scaledInputs[i].remove(result.keys[i], take);
            }
        }
    }

    /**
     * Result of {@link #bulkExtract}. Mutable: providers consume from
     * {@code scaledInputs} during {@code pushBatch}, callers reinject the
     * unconsumed remainder via {@link #reinject(BulkResult, int, ListCraftingInventory)}.
     */
    public static final class BulkResult {
        public final KeyCounter[] scaledInputs;
        public final int actualCopies;
        final AEKey[] keys;
        final long[] perCopyUnits;

        public BulkResult(KeyCounter[] scaledInputs, int actualCopies, AEKey[] keys, long[] perCopyUnits) {
            this.scaledInputs = scaledInputs;
            this.actualCopies = actualCopies;
            this.keys = Arrays.copyOf(keys, keys.length);
            this.perCopyUnits = Arrays.copyOf(perCopyUnits, perCopyUnits.length);
        }
    }
}
