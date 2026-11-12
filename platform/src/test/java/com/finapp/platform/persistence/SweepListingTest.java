package com.finapp.platform.persistence;

import static com.finapp.platform.testing.ScriptedListingConnection.row;
import static com.finapp.platform.testing.ScriptedListingConnection.versionFour;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.ScriptedListingConnection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sweep's listing skips and reports a row it cannot read, and never mistakes a store failure for one (`P16-TSK-017`;
 * {@code THREAT_MODEL.md} section 6 item 42 (i) (ii); {@code OPERATIONS_RUNBOOK.md} section 11.39 (a)).
 */
@DisplayName("a sweep's listing skips and reports a row it cannot read, and a store failure is never one (P16-TSK-017)")
class SweepListingTest {

    /** A reader that refuses what a typed id refuses: an id that is not a UUIDv7. */
    private static UUID readV7(ResultSet row) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        if (id.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7");
        }
        return id;
    }

    private static UUID v7(long millis) {
        // Version 7 by its bits: the 48-bit time, version nibble 7, variant 10.
        long most = (millis << 16) | 0x7000L | 0x0123L;
        long least = 0x8000_0000_0000_0001L;
        return new UUID(most, least);
    }

    private static ResultSet rowsOf(List<java.util.Map<String, Object>> rows) throws SQLException {
        ScriptedListingConnection jdbc = new ScriptedListingConnection(Instant.EPOCH).answering("FROM t", rows);
        PreparedStatement select = jdbc.connection().prepareStatement("SELECT id FROM t");
        return select.executeQuery();
    }

    @Test
    @DisplayName("a corrupt id at the head is skipped and reported, and every row behind it is read, in order")
    void aCorruptIdAtTheHeadIsSkippedAndTheRestRead() throws SQLException {
        UUID corrupt = versionFour();
        UUID first = v7(1_700_000_000_000L);
        UUID second = v7(1_700_000_000_001L);
        SweepListing<UUID> listed = SweepListing.read(
                rowsOf(List.of(row("id", corrupt), row("id", first), row("id", second))), "id", SweepListingTest::readV7);
        assertThat(listed.candidates()).as("the rows behind the poisoned one, reached").containsExactly(first, second);
        assertThat(listed.unreadable()).as("the poisoned row, by its raw id, never judged").containsExactly(corrupt);
    }

    @Test
    @DisplayName("the positive control: a listing with nothing wrong reports nothing unreadable")
    void aCleanListingReportsNothing() throws SQLException {
        UUID only = v7(1_700_000_000_002L);
        SweepListing<UUID> listed = SweepListing.read(rowsOf(List.of(row("id", only))), "id", SweepListingTest::readV7);
        assertThat(listed.candidates()).containsExactly(only);
        assertThat(listed.unreadable()).isEmpty();
        assertThat(SweepListing.of(List.of(only)).unreadable()).isEmpty();
    }

    @Test
    @DisplayName("every row unreadable: the listing is empty and reports each, so a batch of them never ends the tick")
    void everyRowUnreadableEndsNothing() throws SQLException {
        UUID one = versionFour();
        UUID two = versionFour();
        SweepListing<UUID> listed = SweepListing.read(
                rowsOf(List.of(row("id", one), row("id", two))), "id", SweepListingTest::readV7);
        assertThat(listed.candidates()).isEmpty();
        assertThat(listed.unreadable()).containsExactly(one, two);
    }

    @Test
    @DisplayName("an SQLException is the store's and fails the listing: it is never read as a poisoned row")
    void aStoreFailureIsNeverAnUnreadableRow() throws SQLException {
        ResultSet rows = rowsOf(List.of(row("id", v7(1_700_000_000_003L))));
        assertThatThrownBy(() -> SweepListing.read(rows, "id", row -> {
            throw new SQLException("the connection was lost", "08006");
        })).isInstanceOf(SQLException.class).hasMessageContaining("connection was lost");
    }

    @Test
    @DisplayName("the failure's class is the only thing kept, an anonymous class's binary name included, bounded")
    void onlyTheFailuresClassIsKept() {
        assertThat(SweepBackoff.failureClass(new IllegalStateException("host db-1 said 42"))).isEqualTo("IllegalStateException");
        RuntimeException anonymous = new RuntimeException("secret") {};
        assertThat(SweepBackoff.failureClass(anonymous)).startsWith("SweepListingTest$").matches("^[A-Za-z][A-Za-z0-9_$]{0,127}$");
        assertThat(SweepBackoff.UNREADABLE).matches("^[A-Za-z][A-Za-z0-9_$]{0,127}$");
    }
}
