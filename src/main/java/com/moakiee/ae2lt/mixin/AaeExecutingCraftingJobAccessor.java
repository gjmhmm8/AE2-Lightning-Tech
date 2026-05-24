package com.moakiee.ae2lt.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.inv.ListCraftingInventory;

/**
 * Accessor for AdvancedAE's forked {@code ExecutingCraftingJob}. As of AAE
 * 1.6.x, AAE forked the entire {@code appeng.crafting.execution} package into
 * {@code net.pedroksl.advanced_ae.common.logic} — same field structure
 * ({@code tasks}, {@code waitingFor}, {@code timeTracker}, etc.), different
 * package. This mirror lets the batch-dispatch code work uniformly on either
 * job class via the {@link com.moakiee.ae2lt.logic.batch.BatchJobView}
 * abstraction.
 *
 * <p>{@code @Pseudo} so the mixin silently no-ops when AAE is absent;
 * {@code remap = false} because AAE classes are never SRG-mapped. Direct
 * compile-time reference to AAE's {@link ElapsedTimeTracker} is fine because
 * mixin's class loader only loads {@code @Pseudo} mixin classes when their
 * target type resolves at apply time — i.e. only when AAE is present.
 */
@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob", remap = false)
public interface AaeExecutingCraftingJobAccessor {
    @Accessor("tasks")
    Map<IPatternDetails, ?> getTasks();

    @Accessor("waitingFor")
    ListCraftingInventory getWaitingFor();

    @Accessor("timeTracker")
    ElapsedTimeTracker getTimeTracker();
}
