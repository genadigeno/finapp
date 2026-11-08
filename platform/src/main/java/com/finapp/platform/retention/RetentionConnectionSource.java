package com.finapp.platform.retention;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies the connection one batch of a retention sweep runs on (`P15-TSK-029`).
 *
 * <p>The relay's reasoning ({@code OutboxConnectionSource}): the sweep has no caller and no caller transaction, so it owns its
 * own commit. Its own one-line interface because a sweep's connection is neither the relay's nor the consumer shell's.
 */
@FunctionalInterface
public interface RetentionConnectionSource {

    /** Opens a connection. The caller closes it. */
    Connection open() throws SQLException;
}
