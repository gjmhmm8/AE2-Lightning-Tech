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
        // availCache[i] = raw inv.list.get(chosenKeys[i]) snapshot from Phase 1's
        // SIMULATE. Phase 2 reads from this cache instead of issuing a second
        // SIMULATE per unique key, halving Phase-1+2 inventory HashMap probes
        // (~slots saved per call) and saving wrapper-method dispatch overhead.
        long[] availCache = new long[slots];

        // -- Phase 1: per-slot variant pick (SIMULATE only) -------------------
        // Use Long.MAX_VALUE so the returned value is the raw inventory amount,
        // not the clamped (avail, perCopy*maxCraft) value. Phase 2's aggregator
        // wants the unclamped figure anyway, and `canDo = avail / perCopy`
        // converges to the same number when clamped.
        for (int i = 0; i < slots; i++) {
            var input = inputs[i];
            long mult = input.getMultiplier();
            var possibles = input.getPossibleInputs();

            // Fast path: AE2's `AEPatternHelper.condenseStacks` collapses
            // identical ingredients to a single IInput, so the vast majority
            // of patterns surface ONE variant per slot here. Skip the inner
            // loop entirely in that case.
            if (possibles.length == 1) {
                var only = possibles[0];
                if (!(only.what() instanceof AEItemKey)) return null;
                long perCopy = only.amount() * mult;
                if (perCopy <= 0) return null;
                long avail = inv.extract(only.what(), Long.MAX_VALUE, Actionable.SIMULATE);
                if (avail < perCopy) return null;
                chosenKeys[i] = only.what();
                perCopyUnits[i] = perCopy;
                availCache[i] = avail;
                continue;
            }

            AEKey bestKey = null;
            long bestPerCopy = 0;
            long bestAvail = 0;
            long bestCopies = 0;
            for (var possible : possibles) {
                if (!(possible.what() instanceof AEItemKey)) continue;
                long perCopy = possible.amount() * mult;
                if (perCopy <= 0) continue;
                long avail = inv.extract(possible.what(), Long.MAX_VALUE, Actionable.SIMULATE);
                long canDo = avail / perCopy;
                if (canDo > bestCopies) {
                    bestKey = possible.what();
                    bestPerCopy = perCopy;
                    bestAvail = avail;
                    bestCopies = canDo;
                    if (bestCopies >= (long) maxCraft) break;
                }
            }
            if (bestKey == null || bestCopies <= 0) return null;
            chosenKeys[i] = bestKey;
            perCopyUnits[i] = bestPerCopy;
            availCache[i] = bestAvail;
        }

        // -- Phase 2: cross-slot aggregation ----------------------------------
        // Most patterns end up with all-distinct chosen keys (condense + no
        // substitution), so we first probe for any collision via an O(slots²)
        // scan — ≤ 36 cmps for the worst-case 9-slot pattern, much cheaper
        // than the HashMap alloc + entrySet iteration of the previous design.
        // Only on collision do we fall back to the HashMap aggregator.
        long actual = maxCraft;
        HashMap<AEKey, Long> totalPerCopy = null;
        boolean hasCollision = false;
        outer:
        for (int i = 1; i < slots; i++) {
            for (int j = 0; j < i; j++) {
                if (chosenKeys[i].equals(chosenKeys[j])) {
                    hasCollision = true;
                    break outer;
                }
            }
        }

        if (!hasCollision) {
            for (int i = 0; i < slots; i++) {
                long canDo = availCache[i] / perCopyUnits[i];
                if (canDo < actual) actual = canDo;
                if (actual <= 0) return null;
            }
        } else {
            // Collision exists: aggregate demand per unique key. All slots that
            // picked the same key cached the SAME availCache value (Phase 1
            // never modulated), so we read availability from the first slot.
            totalPerCopy = new HashMap<>(slots * 2);
            for (int i = 0; i < slots; i++) {
                totalPerCopy.merge(chosenKeys[i], perCopyUnits[i], Long::sum);
            }
            boolean[] visited = new boolean[slots];
            for (int i = 0; i < slots; i++) {
                if (visited[i]) continue;
                visited[i] = true;
                long perBatch = totalPerCopy.get(chosenKeys[i]);
                long canDo = perBatch > 0 ? availCache[i] / perBatch : 0;
                if (canDo < actual) actual = canDo;
                if (actual <= 0) return null;
                for (int j = i + 1; j < slots; j++) {
                    if (!visited[j] && chosenKeys[j].equals(chosenKeys[i])) {
                        visited[j] = true;
                    }
                }
            }
        }

        if (actual <= 0) return null;

        // -- Phase 3: MODULATE-extract ----------------------------------------
        // Hot path (no collision): one MODULATE per slot, identical to before.
        // Collision path: merge same-key demands into a single MODULATE per
        // unique key — saves the duplicate HashMap remove + listener.onChange
        // callback that vanilla `ListCraftingInventory.extract(MODULATE)`
        // would otherwise fire once per slot.
        if (!hasCollision) {
            long[] extracted = new long[slots];
            for (int i = 0; i < slots; i++) {
                long need = perCopyUnits[i] * actual;
                long got = inv.extract(chosenKeys[i], need, Actionable.MODULATE);
                extracted[i] = got;
                if (got < need) {
                    for (int j = 0; j <= i; j++) {
                        if (extracted[j] > 0) {
                            inv.insert(chosenKeys[j], extracted[j], Actionable.MODULATE);
                        }
                    }
                    return null;
                }
            }
        } else {
            // One MODULATE per unique key; rollback also unique-keyed.
            var perKeyExtracted = new HashMap<AEKey, Long>(totalPerCopy.size() * 2);
            for (var e : totalPerCopy.entrySet()) {
                long need = e.getValue() * actual;
                long got = inv.extract(e.getKey(), need, Actionable.MODULATE);
                perKeyExtracted.put(e.getKey(), got);
                if (got < need) {
                    for (var ee : perKeyExtracted.entrySet()) {
                        if (ee.getValue() > 0) {
                            inv.insert(ee.getKey(), ee.getValue(), Actionable.MODULATE);
                        }
                    }
                    return null;
                }
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
