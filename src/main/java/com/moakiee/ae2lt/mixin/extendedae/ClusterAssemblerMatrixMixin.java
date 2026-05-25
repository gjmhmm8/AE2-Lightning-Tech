package com.moakiee.ae2lt.mixin.extendedae;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;

import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixBase;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixCrafter;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreHost;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreRules;
import com.moakiee.ae2lt.extendedae.BatchWheelCell;
import com.moakiee.ae2lt.extendedae.BatchWheelRegistry;
import com.moakiee.ae2lt.extendedae.CrafterFastBusyCount;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = ClusterAssemblerMatrix.class, remap = false)
public abstract class ClusterAssemblerMatrixMixin implements AssemblerMatrixParallelCoreHost {

    @Shadow
    @Final
    private ReferenceSet<TileAssemblerMatrixCrafter> availableCrafters;

    @Shadow
    @Final
    private ReferenceSet<TileAssemblerMatrixCrafter> busyCrafters;

    @Shadow
    @Final
    private Reference2IntMap<TileAssemblerMatrixCrafter> crafterStatusCache;

    @Shadow
    public abstract void addCrafter(TileAssemblerMatrixCrafter crafter);

    @Shadow
    public abstract int getSpeedCore();

    @Shadow
    public abstract int getBusyCrafterAmount();

    @Shadow
    public abstract appeng.api.networking.security.IActionSource getSrc();

    @Shadow
    public abstract appeng.api.networking.IGridNode getNode();

    @Unique
    private int ae2lt$parallelCores;

    @Unique
    private static final double AE2LT$AE_PER_COPY = 20.0D;

    @Unique
    private final BatchWheelCell[] ae2lt$wheel = BatchWheelCell.createWheel();

    @Unique
    private int ae2lt$threadsInFlight;

    @Override
    public void ae2lt$addParallelCore() {
        int before = this.ae2lt$parallelCores;
        this.ae2lt$parallelCores = AssemblerMatrixParallelCoreRules.addCore(before);
        if (before == 0 && this.ae2lt$parallelCores > 0) {
            this.ae2lt$reclassifyCrafters();
        }
    }

    @Override
    public boolean ae2lt$hasParallelCore() {
        return AssemblerMatrixParallelCoreRules.hasCore(this.ae2lt$parallelCores);
    }

    @Override
    public int ae2lt$effectiveThreads() {
        return AssemblerMatrixParallelCoreRules.effectiveThreads(this.ae2lt$hasParallelCore());
    }

    @Inject(method = "addCrafter", at = @At("HEAD"), cancellable = true)
    private void ae2lt$addCrafterWithParallelCore(TileAssemblerMatrixCrafter crafter, CallbackInfo ci) {
        if (!this.ae2lt$hasParallelCore()) {
            return;
        }

        if (crafter.usedThread() < this.ae2lt$effectiveThreads()) {
            this.availableCrafters.add(crafter);
        } else {
            this.busyCrafters.add(crafter);
        }
        ci.cancel();
    }

    /**
     * Vanilla EAE counts only {@code crafter.usedThread()} sums, which would
     * report 0 after our mixin routes every dispatch through the time wheel
     * — the crafters' {@code states} stay clean. To preserve the screen's
     * "正在运行的工作 / Running Jobs" semantics we add the in-flight wheel
     * count on top of the popcnt sum. The popcnt sum is non-zero only for
     * residual NBT-loaded threads (old saves) or future code paths that
     * deliberately call vanilla {@code crafter.pushJob}; under steady state
     * after this mixin loads it is always 0 and only the wheel count shows.
     */
    @Inject(method = "getBusyCrafterAmount", at = @At("HEAD"), cancellable = true)
    private void ae2lt$countActualBusyThreads(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.ae2lt$busyThreadCount() + this.ae2lt$threadsInFlight);
    }

    /**
     * O(crafters) busy-thread count, no iterator allocation. Sums the popcnt
     * of each crafter's {@code states} + {@code ae2lt$extraStates} bitmask;
     * see {@link CrafterFastBusyCount#ae2lt$usedThreadFast()} for why this
     * reproduces vanilla {@code usedThread()} faithfully.
     */
    @Unique
    private int ae2lt$busyThreadCount() {
        int count = 0;
        for (var crafter : this.availableCrafters) {
            count += ((CrafterFastBusyCount) crafter).ae2lt$usedThreadFast();
        }
        for (var crafter : this.busyCrafters) {
            count += ((CrafterFastBusyCount) crafter).ae2lt$usedThreadFast();
        }
        return count;
    }

    @Unique
    private void ae2lt$reclassifyCrafters() {
        var all = new ReferenceOpenHashSet<TileAssemblerMatrixCrafter>();
        all.addAll(this.availableCrafters);
        all.addAll(this.busyCrafters);
        this.availableCrafters.clear();
        this.busyCrafters.clear();
        this.crafterStatusCache.clear();
        for (var crafter : all) {
            this.addCrafter(crafter);
        }
    }

    // ------------------------------------------------------------------
    // Batch wheel
    // ------------------------------------------------------------------

    @Override
    public int ae2lt$currentDelay() {
        return ae2lt$computeDelay(this.getSpeedCore());
    }

    @Unique
    private static int ae2lt$computeDelay(int speedCore) {
        return Math.max(1, BatchWheelCell.MAX_DELAY - speedCore);
    }

    @Unique
    private int ae2lt$maxThreads() {
        int crafterCount = this.availableCrafters.size() + this.busyCrafters.size();
        return crafterCount * this.ae2lt$effectiveThreads();
    }

    @Override
    public int ae2lt$pushCraftingJobBatch(IPatternDetails details, KeyCounter[] scaledInputs, int maxCraft) {
        if (maxCraft <= 0) return 0;
        if (!(details instanceof IMolecularAssemblerSupportedPattern pattern)) return maxCraft;

        var self = (ClusterAssemblerMatrix) (Object) this;
        TileAssemblerMatrixBase core = ((ClusterAssemblerMatrixAccessor) self).ae2lt$invokeGetCore();
        if (core == null) return maxCraft;
        Level level = core.getLevel();
        if (level == null) return maxCraft;

        long gameTime = level.getGameTime();
        int delay = ae2lt$computeDelay(this.getSpeedCore());

        // 1. Sweep non-live slots so freshly-vacated threads count toward avail.
        this.ae2lt$sweepNonLive(gameTime, delay);

        // 2. Compute capacity in O(1). After this mixin both pushBatch AND
        //    single-copy pushCraftingJob route through the wheel, so EAE's
        //    per-thread states stay 0 in steady state. The only source of
        //    occupancy is our own wheel; subtract just `threadsInFlight`.
        //
        //    Edge case: NBT-loaded threads from old saves may transiently have
        //    states>0; the wheel may overcommit by that residual amount which
        //    is bounded by maxThreads and drains within a few ticks. No dup
        //    risk because each path runs its own assemble + energy accounting.
        int avail = this.ae2lt$maxThreads() - this.ae2lt$threadsInFlight;
        if (avail <= 0) return maxCraft;

        int requested = Math.min(maxCraft, avail);

        // 3. Build per-copy template (slot-aligned 1× KeyCounter[]).
        KeyCounter[] oneCopy = ae2lt$buildOneCopyTemplate(scaledInputs, maxCraft);
        if (oneCopy == null) return maxCraft;

        // 4. Energy: pre-charge K × 20 AE, scale down if insufficient.
        var node = this.getNode();
        IGrid grid = node != null ? node.getGrid() : null;
        if (grid == null) return maxCraft;
        var energy = grid.getEnergyService();

        int copies = requested;
        double powerWanted = AE2LT$AE_PER_COPY * copies;
        double extracted = energy.extractAEPower(powerWanted, Actionable.MODULATE, PowerMultiplier.CONFIG);
        int affordable = (int) Math.floor(extracted / AE2LT$AE_PER_COPY);
        if (affordable <= 0) {
            if (extracted > 0.0D) {
                energy.injectPower(extracted, Actionable.MODULATE);
            }
            return maxCraft;
        }
        if (affordable < copies) {
            double refund = extracted - affordable * AE2LT$AE_PER_COPY;
            if (refund > 0.0D) {
                energy.injectPower(refund, Actionable.MODULATE);
            }
            copies = affordable;
        }

        // 5. Assemble once (the batch model treats K copies as homogeneous).
        ItemStack proto;
        NonNullList<ItemStack> remainders;
        try {
            CraftingInput input = ae2lt$buildCraftingInput(pattern, oneCopy);
            proto = pattern.assemble(input, level);
            if (proto.isEmpty()) {
                energy.injectPower(copies * AE2LT$AE_PER_COPY, Actionable.MODULATE);
                return maxCraft;
            }
            remainders = pattern.getRemainingItems(input);
        } catch (Throwable t) {
            appeng.core.AELog.warn("[ae2lt] EAE batch assemble failed for %s; refunding %d copies. %s",
                    details, copies, t);
            energy.injectPower(copies * AE2LT$AE_PER_COPY, Actionable.MODULATE);
            return maxCraft;
        }

        // 6. Write into slot[T & 7].
        BatchWheelCell cell = this.ae2lt$wheel[(int) (gameTime & BatchWheelCell.WHEEL_MASK)];
        ae2lt$accumulate(cell.outputs, AEItemKey.of(proto), (long) proto.getCount() * copies);
        if (remainders != null) {
            for (ItemStack rem : remainders) {
                if (rem.isEmpty()) continue;
                ae2lt$accumulate(cell.outputs, AEItemKey.of(rem), (long) rem.getCount() * copies);
            }
        }
        cell.copies += copies;
        this.ae2lt$threadsInFlight += copies;

        BatchWheelRegistry.markActive(this);

        return maxCraft - copies;
    }

    @Override
    public boolean ae2lt$sweepActiveTick() {
        var self = (ClusterAssemblerMatrix) (Object) this;
        if (self.isDestroyed()) return false;

        TileAssemblerMatrixBase core = ((ClusterAssemblerMatrixAccessor) self).ae2lt$invokeGetCore();
        if (core == null || core.getLevel() == null) return this.ae2lt$threadsInFlight > 0;

        long gameTime = core.getLevel().getGameTime();
        int delay = ae2lt$computeDelay(this.getSpeedCore());
        this.ae2lt$sweepNonLive(gameTime, delay);
        return this.ae2lt$threadsInFlight > 0;
    }

    @Unique
    private void ae2lt$sweepNonLive(long gameTime, int delay) {
        if (this.ae2lt$threadsInFlight == 0) return;

        // Mark the live window: slot[T & 7], slot[(T-1) & 7] ... slot[(T-delay+1) & 7].
        int liveMask = 0;
        for (int i = 0; i < delay; i++) {
            liveMask |= 1 << ((int) ((gameTime - i) & BatchWheelCell.WHEEL_MASK));
        }

        for (int slot = 0; slot < BatchWheelCell.WHEEL_SIZE; slot++) {
            if ((liveMask & (1 << slot)) != 0) continue;
            this.ae2lt$drainSlot(slot, false);
            if (this.ae2lt$threadsInFlight == 0) return;
        }
    }

    /**
     * Drain a single wheel cell into the ME network.
     *
     * <p>Two flush modes:
     * <ul>
     *   <li><b>Soft flush</b> ({@code forceSpawn == false}): try to insert
     *       into the storage network. Any leftover (network full or grid
     *       disconnected) is kept in the cell and retried on the next
     *       non-live sweep — mirroring AE2 vanilla {@code CraftingCpuLogic.storeItems}
     *       / {@code cantStoreItems} semantics. Crucially, {@code cell.copies}
     *       and {@code threadsInFlight} are <em>only</em> released when the
     *       cell fully drained, so the wheel naturally throttles new pushes
     *       while the network is saturated (the same way vanilla CPU stops
     *       pushing new jobs when the cluster's local inventory backs up).
     *       When the grid is null (cluster disconnected from a powered/intact
     *       network), the entire cell is preserved verbatim — items wait
     *       safely in-cell until the network comes back online.</li>
     *   <li><b>Hard flush</b> ({@code forceSpawn == true}): dump everything
     *       to the world as ItemEntity. Reserved for {@code destroy()} where
     *       there is no other place for the items to go — losing them
     *       in-cell would be worse. The thread slots are always released.</li>
     * </ul>
     *
     * <p><b>Wrap-around interaction</b>: if a cell stays stuck for > 8 ticks
     * (network permanently full / grid offline for long), it re-enters the
     * live window. A subsequent push at the same slot index will then merge
     * new outputs into the same {@link BatchWheelCell#outputs} map (via
     * {@link #ae2lt$accumulate}) and sum {@code copies}. That's intentional
     * — the cell now represents the combined batch, and drain accounting
     * still balances out.
     */
    @Unique
    private void ae2lt$drainSlot(int idx, boolean forceSpawn) {
        BatchWheelCell cell = this.ae2lt$wheel[idx];
        if (cell.copies == 0) return;

        var self = (ClusterAssemblerMatrix) (Object) this;
        var node = this.getNode();
        IGrid grid = node != null ? node.getGrid() : null;

        if (forceSpawn) {
            // Hard flush path: dump to world. Only reached from destroy() as
            // a last-resort second pass. Losing items in-cell would be worse
            // than the visual noise of spawned drops.
            for (Map.Entry<AEItemKey, Long> entry : cell.outputs.entrySet()) {
                long amount = entry.getValue();
                if (amount <= 0) continue;
                ae2lt$spawnLeftover(self, entry.getKey(), amount);
            }
            this.ae2lt$threadsInFlight -= cell.copies;
            if (this.ae2lt$threadsInFlight < 0) this.ae2lt$threadsInFlight = 0;
            cell.outputs.clear();
            cell.copies = 0;
            return;
        }

        if (grid == null) {
            // Grid disconnected: preserve the cell intact. When the network
            // reconnects, the next sweep will drain it normally. No item is
            // ever silently lost to the world from a disconnect event.
            return;
        }

        // Soft flush: try to insert into the network; keep leftovers in-cell
        // for the next sweep. Never spawn to world here — the network may
        // simply be temporarily saturated.
        var monitor = grid.getStorageService().getInventory();
        var src = this.getSrc();
        boolean anyLeft = false;
        var iter = cell.outputs.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long amount = entry.getValue();
            if (amount <= 0) {
                iter.remove();
                continue;
            }
            long inserted = monitor.insert(entry.getKey(), amount, Actionable.MODULATE, src);
            long leftover = amount - inserted;
            if (leftover > 0) {
                entry.setValue(leftover);
                anyLeft = true;
            } else {
                iter.remove();
            }
        }

        if (!anyLeft) {
            this.ae2lt$threadsInFlight -= cell.copies;
            if (this.ae2lt$threadsInFlight < 0) this.ae2lt$threadsInFlight = 0;
            cell.copies = 0;
        }
        // else: cell retains both copies and threadsInFlight accounting; next
        // non-live sweep retries. Wheel capacity is reduced until drained,
        // which is the correct back-pressure when storage is full.
    }

    /**
     * Fully replace {@code cluster.pushCraftingJob} with a wheel push. Every
     * single-copy dispatch (vanilla AE2 {@code ICraftingProvider.pushPattern}
     * → cluster) now routes through {@link #ae2lt$pushCraftingJobBatch} with
     * {@code maxCraft=1}.
     *
     * <p>No fallback to the EAE thread path: every rejection condition the
     * wheel can produce (capacity full, energy starved, non-supported pattern,
     * grid null, assemble failure) the EAE thread path rejects identically —
     * retrying via thread is pure waste. Always {@code setReturnValue} so the
     * EAE thread state machine stays untouched, which keeps {@code states==0}
     * in steady state and validates the busy-count-free {@code avail}
     * computation above.
     */
    @Inject(method = "pushCraftingJob", at = @At("HEAD"), cancellable = true)
    private void ae2lt$replacePushCraftingJobWithWheel(IPatternDetails details, KeyCounter[] inputs,
                                                       CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.ae2lt$pushCraftingJobBatch(details, inputs, 1) == 0);
    }

    /**
     * On cluster destroy, drain everything. First pass tries soft-flush
     * (network insert); whatever the network couldn't take is then hard-
     * flushed to world via a second pass. This preserves storage when the
     * grid is still alive at destroy time, and only resorts to ItemEntity
     * spawn for items the network refused (full / no storage cell match).
     */
    @Inject(method = "destroy", at = @At("HEAD"))
    private void ae2lt$flushWheelOnDestroy(CallbackInfo ci) {
        var self = (ClusterAssemblerMatrix) (Object) this;
        if (self.isDestroyed()) return;
        for (int i = 0; i < BatchWheelCell.WHEEL_SIZE; i++) {
            this.ae2lt$drainSlot(i, false);
        }
        for (int i = 0; i < BatchWheelCell.WHEEL_SIZE; i++) {
            this.ae2lt$drainSlot(i, true);
        }
        this.ae2lt$threadsInFlight = 0;
        BatchWheelRegistry.markInactive(this);
    }

    // ------------------------------------------------------------------
    // NBT persistence
    //
    // Wheel state is saved on the core BE's `saveAdditional` (see
    // TileAssemblerMatrixBaseMixin) and restored from the core BE's
    // `previousState` field at cluster `done()` time. EAE's `loadTag`
    // already copies the full BE tag into `previousState` when isCore is
    // true, and `done()` clears it shortly after — we just need to inject
    // BEFORE the clear to read our sub-tag.
    // ------------------------------------------------------------------

    @Unique private static final String AE2LT$NBT_WHEEL = "ae2lt:wheel";
    @Unique private static final String AE2LT$NBT_CELLS = "cells";
    @Unique private static final String AE2LT$NBT_IDX = "i";
    @Unique private static final String AE2LT$NBT_COPIES = "n";
    @Unique private static final String AE2LT$NBT_OUTPUTS = "out";
    @Unique private static final String AE2LT$NBT_KEY = "k";
    @Unique private static final String AE2LT$NBT_AMOUNT = "v";

    @Override
    public void ae2lt$writeWheelToTag(CompoundTag data, HolderLookup.Provider registries) {
        if (this.ae2lt$threadsInFlight <= 0) return;

        ListTag cells = new ListTag();
        for (int i = 0; i < BatchWheelCell.WHEEL_SIZE; i++) {
            BatchWheelCell cell = this.ae2lt$wheel[i];
            if (cell.copies <= 0 || cell.outputs.isEmpty()) continue;

            CompoundTag cellTag = new CompoundTag();
            cellTag.putByte(AE2LT$NBT_IDX, (byte) i);
            cellTag.putInt(AE2LT$NBT_COPIES, cell.copies);

            ListTag outputs = new ListTag();
            for (Map.Entry<AEItemKey, Long> entry : cell.outputs.entrySet()) {
                AEItemKey key = entry.getKey();
                long amount = entry.getValue();
                if (key == null || amount <= 0) continue;
                CompoundTag outTag = new CompoundTag();
                outTag.put(AE2LT$NBT_KEY, key.toTagGeneric(registries));
                outTag.putLong(AE2LT$NBT_AMOUNT, amount);
                outputs.add(outTag);
            }
            if (outputs.isEmpty()) continue;
            cellTag.put(AE2LT$NBT_OUTPUTS, outputs);
            cells.add(cellTag);
        }

        if (cells.isEmpty()) return;
        CompoundTag wheelTag = new CompoundTag();
        wheelTag.put(AE2LT$NBT_CELLS, cells);
        data.put(AE2LT$NBT_WHEEL, wheelTag);
    }

    @Override
    public void ae2lt$readWheelFromTag(CompoundTag data, HolderLookup.Provider registries) {
        if (!data.contains(AE2LT$NBT_WHEEL, Tag.TAG_COMPOUND)) return;
        CompoundTag wheelTag = data.getCompound(AE2LT$NBT_WHEEL);
        if (!wheelTag.contains(AE2LT$NBT_CELLS, Tag.TAG_LIST)) return;

        // Reset any pre-existing state defensively — formation happens once
        // per cluster lifetime, but this is also the natural reset point.
        for (int i = 0; i < BatchWheelCell.WHEEL_SIZE; i++) {
            this.ae2lt$wheel[i].outputs.clear();
            this.ae2lt$wheel[i].copies = 0;
        }
        this.ae2lt$threadsInFlight = 0;

        ListTag cells = wheelTag.getList(AE2LT$NBT_CELLS, Tag.TAG_COMPOUND);
        for (int n = 0; n < cells.size(); n++) {
            CompoundTag cellTag = cells.getCompound(n);
            int idx = cellTag.getByte(AE2LT$NBT_IDX) & BatchWheelCell.WHEEL_MASK;
            int copies = cellTag.getInt(AE2LT$NBT_COPIES);
            if (copies <= 0) continue;

            BatchWheelCell cell = this.ae2lt$wheel[idx];
            ListTag outputs = cellTag.getList(AE2LT$NBT_OUTPUTS, Tag.TAG_COMPOUND);
            for (int o = 0; o < outputs.size(); o++) {
                CompoundTag outTag = outputs.getCompound(o);
                long amount = outTag.getLong(AE2LT$NBT_AMOUNT);
                if (amount <= 0) continue;
                AEKey rawKey = AEKey.fromTagGeneric(registries, outTag.getCompound(AE2LT$NBT_KEY));
                if (rawKey instanceof AEItemKey itemKey) {
                    cell.outputs.merge(itemKey, amount, Long::sum);
                }
            }
            if (cell.outputs.isEmpty()) continue;
            cell.copies += copies;
            this.ae2lt$threadsInFlight += copies;
        }

        if (this.ae2lt$threadsInFlight > 0) {
            BatchWheelRegistry.markActive(this);
        }
    }

    /**
     * After EAE's MBCalculator finalizes formation, the core BE's
     * {@code previousState} holds the full saved NBT from the prior session.
     * EAE clears it inside {@code done()}; injecting at HEAD lets us read
     * our wheel sub-tag first. Items restored here naturally drain via the
     * normal sweep cycle within a few ticks of being scheduled.
     */
    @Inject(method = "done", at = @At("HEAD"))
    private void ae2lt$restoreWheelOnFormation(CallbackInfo ci) {
        var self = (ClusterAssemblerMatrix) (Object) this;
        TileAssemblerMatrixBase core = ((ClusterAssemblerMatrixAccessor) self).ae2lt$invokeGetCore();
        if (core == null) return;
        CompoundTag prev = core.getPreviousState();
        if (prev == null) return;
        Level level = core.getLevel();
        if (level == null) return;
        try {
            this.ae2lt$readWheelFromTag(prev, level.registryAccess());
        } catch (Throwable t) {
            appeng.core.AELog.warn("[ae2lt] failed to restore EAE wheel from NBT: %s", t);
        }
    }

    @Unique
    private static void ae2lt$spawnLeftover(ClusterAssemblerMatrix self, AEKey key, long amount) {
        if (!(key instanceof AEItemKey itemKey)) return;
        TileAssemblerMatrixBase core = ((ClusterAssemblerMatrixAccessor) self).ae2lt$invokeGetCore();
        if (core == null) return;
        Level level = core.getLevel();
        if (level == null || level.isClientSide()) return;
        BlockPos pos = core.getBlockPos();

        int maxStack = itemKey.toStack().getMaxStackSize();
        long remaining = amount;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, maxStack);
            ItemStack stack = itemKey.toStack(chunk);
            ItemEntity entity = new ItemEntity(level,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
            remaining -= chunk;
        }
    }

    @Unique
    private static void ae2lt$accumulate(HashMap<AEItemKey, Long> map, AEItemKey key, long add) {
        if (key == null || add <= 0) return;
        map.merge(key, add, Long::sum);
    }

    @Unique
    private static KeyCounter[] ae2lt$buildOneCopyTemplate(KeyCounter[] scaledInputs, int maxCraft) {
        KeyCounter[] result = new KeyCounter[scaledInputs.length];
        for (int i = 0; i < scaledInputs.length; i++) {
            KeyCounter src = scaledInputs[i];
            KeyCounter dst = new KeyCounter();
            for (var entry : src) {
                long amount = entry.getLongValue();
                if (amount <= 0 || amount % maxCraft != 0) {
                    return null;
                }
                long perCopy = amount / maxCraft;
                if (perCopy <= 0) return null;
                dst.add(entry.getKey(), perCopy);
            }
            result[i] = dst;
        }
        return result;
    }

    @Unique
    private static CraftingInput ae2lt$buildCraftingInput(IMolecularAssemblerSupportedPattern pattern,
                                                          KeyCounter[] oneCopy) {
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < 9; i++) grid[i] = ItemStack.EMPTY;

        pattern.fillCraftingGrid(oneCopy, (slot, stack) -> {
            if (slot >= 0 && slot < 9 && stack != null) {
                grid[slot] = stack;
            }
        });

        NonNullList<ItemStack> list = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int i = 0; i < 9; i++) list.set(i, grid[i]);
        return CraftingInput.of(3, 3, list);
    }
}
