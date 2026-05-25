package com.moakiee.ae2lt.mixin.extendedae;

import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixCrafter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(value = ClusterAssemblerMatrix.class, remap = false)
public interface ClusterAssemblerMatrixAccessor {
    @Invoker("getAvailableCrafter")
    TileAssemblerMatrixCrafter ae2lt$invokeGetAvailableCrafter();
}
