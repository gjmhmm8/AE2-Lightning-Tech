package com.moakiee.ae2lt.extendedae;

import java.util.HashMap;

import appeng.api.stacks.AEItemKey;

/**
 * One slot of the EAE assembler-matrix batch wheel. Top-level (not nested
 * inside the mixin) because mixin transforms inner classes awkwardly; keeping
 * it standalone avoids any classloading footgun.
 *
 * <p>Each cell aggregates the assembled outputs of every {@code push} that
 * lands on this slot, in {@code AEItemKey → total count} form. The {@code
 * copies} counter mirrors how many crafting jobs are accounted for by the
 * cell so {@code threadsInFlight} decrements correctly on drain.
 */
public final class BatchWheelCell {
    public static final int WHEEL_SIZE = 8;
    public static final int WHEEL_MASK = 7;
    public static final int MAX_DELAY = 5;

    public final HashMap<AEItemKey, Long> outputs = new HashMap<>(4);
    public int copies;

    public static BatchWheelCell[] createWheel() {
        BatchWheelCell[] wheel = new BatchWheelCell[WHEEL_SIZE];
        for (int i = 0; i < WHEEL_SIZE; i++) {
            wheel[i] = new BatchWheelCell();
        }
        return wheel;
    }
}
