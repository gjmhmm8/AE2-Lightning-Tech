package com.moakiee.ae2lt.extendedae;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

/**
 * Server-tick driver for {@link AssemblerMatrixParallelCoreHost#ae2lt$sweepActiveTick()}.
 *
 * <p>Clusters self-register on first push that lands items in the wheel; they
 * self-deregister either via {@code destroy} flush or once a sweep observes
 * an empty wheel. All accesses happen on the server thread.
 *
 * <p>Iteration tolerates the very common case of the wheel draining to zero
 * during sweep: we use the iterator's {@code remove()} hook so deregistration
 * does not require an extra map lookup.
 */
public final class BatchWheelRegistry {
    private static final Set<AssemblerMatrixParallelCoreHost> ACTIVE =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private BatchWheelRegistry() {
    }

    public static void markActive(AssemblerMatrixParallelCoreHost host) {
        ACTIVE.add(host);
    }

    public static void markInactive(AssemblerMatrixParallelCoreHost host) {
        ACTIVE.remove(host);
    }

    public static void tickAll() {
        if (ACTIVE.isEmpty()) return;
        Iterator<AssemblerMatrixParallelCoreHost> it = ACTIVE.iterator();
        while (it.hasNext()) {
            AssemblerMatrixParallelCoreHost host = it.next();
            try {
                if (!host.ae2lt$sweepActiveTick()) {
                    it.remove();
                }
            } catch (Throwable t) {
                appeng.core.AELog.warn("[ae2lt] batch wheel sweep failed for %s; removing. %s", host, t);
                it.remove();
            }
        }
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
