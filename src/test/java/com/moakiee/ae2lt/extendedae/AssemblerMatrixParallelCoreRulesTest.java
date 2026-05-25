package com.moakiee.ae2lt.extendedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AssemblerMatrixParallelCoreRulesTest {

    @Test
    void capsParallelCoreCountAtOne() {
        int cores = 0;

        cores = AssemblerMatrixParallelCoreRules.addCore(cores);
        cores = AssemblerMatrixParallelCoreRules.addCore(cores);
        cores = AssemblerMatrixParallelCoreRules.addCore(cores);

        assertEquals(1, cores);
        assertTrue(AssemblerMatrixParallelCoreRules.hasCore(cores));
    }

    @Test
    void multipliesAssemblerMatrixThreadCapacityByFourWhenCoreIsPresent() {
        assertEquals(8, AssemblerMatrixParallelCoreRules.effectiveThreads(false));
        assertEquals(32, AssemblerMatrixParallelCoreRules.effectiveThreads(true));
    }

    @Test
    void countsBusyExtraThreadsFromStateMask() {
        int firstSecondAndLastExtraThread = 0b11 | (1 << (AssemblerMatrixParallelCoreRules.EXTRA_THREADS - 1));

        assertEquals(0, AssemblerMatrixParallelCoreRules.busyExtraThreads(0));
        assertEquals(3, AssemblerMatrixParallelCoreRules.busyExtraThreads(firstSecondAndLastExtraThread));
    }
}
