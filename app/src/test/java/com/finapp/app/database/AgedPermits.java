package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

/**
 * A dispatched row's birth moved into the past, on the database's clock (`X-TSK-013`).
 *
 * <p>Since the send permits are the database's - born {@code GREATEST(created_at,
 * statement_timestamp())}, judged against {@code statement_timestamp()} - a row a test dispatches for
 * real is due only once the DATABASE's clock has passed its permit. The test database runs in a VM
 * whose clock steps back about 1.6 s every ~27 s (measured at {@code P7-TSK-015}'s gate), so a row
 * born while the host runs ahead carries a permit in the database's future: a {@code Thread.sleep}
 * past a tiny bound is a race against that VM, not a wait. This is the deterministic form - created_at
 * and the permit moved together (each table's CHECK keeps them ordered), as the table's owner with
 * its triggers off for the one statement: neither the forward-only rule nor the database's stamp lets
 * a writer age a permit, which is the point of them (the outbound credit's {@code agePermit} idiom).
 */
public final class AgedPermits {

    private AgedPermits() {}

    /** Moves {@code table}'s row {@code id} - its created_at and its {@code permit} - back by {@code by}. */
    public static void age(String table, String permit, UUID id, Duration by) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement triggers = owner.createStatement();
                    PreparedStatement age =
                            owner.prepareStatement(
                                    "UPDATE " + table + " SET created_at = created_at - ? * interval '1 millisecond', "
                                            + permit + " = " + permit + " - ? * interval '1 millisecond'"
                                            + " WHERE id = ?")) {
                triggers.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
                age.setLong(1, by.toMillis());
                age.setLong(2, by.toMillis());
                age.setObject(3, id);
                assertThat(age.executeUpdate()).as("the row to age exists").isEqualTo(1);
                triggers.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
            }
            owner.commit();
        }
    }
}
