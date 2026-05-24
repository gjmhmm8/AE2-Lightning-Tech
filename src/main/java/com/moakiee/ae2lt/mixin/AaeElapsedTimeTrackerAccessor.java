package com.moakiee.ae2lt.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKeyType;

/**
 * Invoker for AAE's forked {@code ElapsedTimeTracker}. Exposes the
 * package-private {@code addMaxItems(long, AEKeyType)} so batch-side
 * container-item bookkeeping can replay vanilla's per-copy accounting.
 */
@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker", remap = false)
public interface AaeElapsedTimeTrackerAccessor {
    @Invoker("addMaxItems")
    void invokeAddMaxItems(long amount, AEKeyType keyType);
}
