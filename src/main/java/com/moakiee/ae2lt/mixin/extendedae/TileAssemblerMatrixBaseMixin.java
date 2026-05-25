package com.moakiee.ae2lt.mixin.extendedae;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixBase;
import com.moakiee.ae2lt.extendedae.AssemblerMatrixParallelCoreHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires wheel-state serialization into the core BE's NBT round-trip.
 *
 * <p><b>Save</b>: after EAE finishes writing its own keys, we ask the
 * cluster mixin to append the wheel sub-tag. Restricted to the multiblock
 * core only — non-core tiles save nothing wheel-related.
 *
 * <p><b>Load</b>: no injection needed here. EAE already copies the loaded
 * {@code CompoundTag} into {@code previousState} when {@code isCore}, and
 * the cluster mixin's {@code done()} hook reads our sub-tag from there
 * before EAE clears it.
 */
@Pseudo
@Mixin(value = TileAssemblerMatrixBase.class, remap = false)
public abstract class TileAssemblerMatrixBaseMixin {

    @Inject(method = "saveAdditional", at = @At("RETURN"), remap = false)
    private void ae2lt$writeWheel(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        TileAssemblerMatrixBase self = (TileAssemblerMatrixBase) (Object) this;
        if (!self.isCore()) return;
        ClusterAssemblerMatrix cluster = self.getCluster();
        if (!(cluster instanceof AssemblerMatrixParallelCoreHost host)) return;
        try {
            host.ae2lt$writeWheelToTag(data, registries);
        } catch (Throwable t) {
            appeng.core.AELog.warn("[ae2lt] failed to serialize EAE wheel to NBT: %s", t);
        }
    }
}
