/**
 * Public, addon-facing crafting API extensions for AE2 Lightning Tech.
 *
 * <p>This sub-package is part of the frozen API contract described in
 * {@link com.moakiee.ae2lt.api}. The single type
 * {@link com.moakiee.ae2lt.api.crafting.IBatchCraftingProvider} extends AE2's
 * {@link appeng.api.networking.crafting.ICraftingProvider} so that one push
 * call can consume up to N copies of a pattern's inputs in O(1) time, while
 * remaining backwards-compatible with stock AE2 CPUs.
 *
 * <p>The cpu side of this contract (the actual mixin into AE2 that recognizes
 * batch providers and bulk-extracts inputs) is implementation, not API; see
 * {@code com.moakiee.ae2lt.mixin.CraftingCpuLogicBatchMixin} for the entry
 * point.
 */
package com.moakiee.ae2lt.api.crafting;
