package com.moakiee.ae2lt.logic.batch;

import appeng.api.crafting.IPatternDetails;

/**
 * Cursor over a single {@code (IPatternDetails, TaskProgress)} entry in a
 * crafting job's {@code tasks} map. Returned by
 * {@link BatchJobView#taskIterator()}.
 *
 * <p>Implementations are usually shared across iterator positions (i.e. the
 * same instance is returned from consecutive {@code next()} calls and reflects
 * the current entry); callers must not retain a handle past the next
 * {@code next()} or {@code remove()} call.
 */
public interface BatchTaskHandle {
    IPatternDetails details();

    long getValue();

    void setValue(long value);
}
