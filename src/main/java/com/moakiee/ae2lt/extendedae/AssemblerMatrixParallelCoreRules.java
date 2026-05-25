package com.moakiee.ae2lt.extendedae;

public final class AssemblerMatrixParallelCoreRules {
    public static final int BASE_THREADS = 8;
    public static final int PARALLEL_MULTIPLIER = 4;
    public static final int MAX_PARALLEL_CORES = 1;
    public static final int EXTRA_THREADS = BASE_THREADS * (PARALLEL_MULTIPLIER - 1);

    private AssemblerMatrixParallelCoreRules() {
    }

    public static int addCore(int currentCores) {
        return Math.min(MAX_PARALLEL_CORES, Math.max(0, currentCores) + 1);
    }

    public static boolean hasCore(int cores) {
        return cores > 0;
    }

    public static int effectiveThreads(boolean hasCore) {
        return hasCore ? BASE_THREADS * PARALLEL_MULTIPLIER : BASE_THREADS;
    }

    public static int busyExtraThreads(int extraStateMask) {
        return Integer.bitCount(extraStateMask);
    }
}
