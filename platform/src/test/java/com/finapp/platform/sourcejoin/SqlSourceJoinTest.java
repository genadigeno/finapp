package com.finapp.platform.sourcejoin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How one join reads (`P15-TSK-035`), against a stand-in {@code Connection}: only keys it can name are bound, a row is matched to its
 * entry by its key text, a key with no row is absent from the answer, and several rows for one key (a proposal's lines by currency)
 * are combined. Its statements against the real schema are {@code SourceCensusDatabaseTest}'s (owed).
 */
@DisplayName("one source join reads its keys and combines its rows (P15-TSK-035)")
class SqlSourceJoinTest {

    private static final UUID REAL = UUID.fromString("018f4a2e-7b6a-7c3d-8e9f-0123456789ab");
    private static final UUID OTHER = UUID.fromString("018f4a2e-7b6a-7c3d-8e9f-0123456789ac");

    @Test
    @DisplayName("an entry whose key names no row is absent; a key that is not a key is never bound")
    void anEntryWithNoRowIsAbsent() throws SQLException {
        UUID withRecord = UUID.randomUUID();
        UUID noRecord = UUID.randomUUID();
        UUID garbage = UUID.randomUUID();
        Fake fake = new Fake(rows(row(REAL.toString(), "USD", 2, 100)));
        SourceJoin join = new SqlSourceJoin(SourceDoor.POSTING, "transfer:", "fake.table by id", "SELECT", Keys.suffixUuid(),
                row -> SourceFigures.total(row.getString("currency"), row.getInt("scale"), row.getLong("minor")));

        Map<UUID, SourceFigures> found = join.read(fake.connection(), List.of(
                new EntryRef(withRecord, REAL.toString(), "r"),
                new EntryRef(noRecord, OTHER.toString(), "r"),
                new EntryRef(garbage, "not-a-uuid", "r")));

        assertThat(found).containsOnlyKeys(withRecord);
        assertThat(fake.boundArray).as("the garbage key is not bound; it never reaches a cast").contains(REAL, OTHER).doesNotContain("not-a-uuid");
    }

    @Test
    @DisplayName("several rows for one key are combined: a proposal's lines by currency and direction, one figure per row")
    void severalRowsForOneKeyCombine() throws SQLException {
        UUID entry = UUID.randomUUID();
        Fake fake = new Fake(rows(row(REAL.toString(), "USD", 2, 100), row(REAL.toString(), "EUR", 2, 200)));
        SourceJoin join = new SqlSourceJoin(SourceDoor.ADJUSTMENT, "MANUAL", "fake.table by id", "SELECT", Keys.suffixUuid(),
                row -> SourceFigures.total(row.getString("currency"), row.getInt("scale"), row.getLong("minor")));

        SourceFigures figures = join.read(fake.connection(), List.of(new EntryRef(entry, REAL.toString(), "r"))).get(entry);

        assertThat(figures.legs()).extracting(ExpectedLeg::currency).containsExactly("USD", "USD", "EUR", "EUR");
    }

    @Test
    @DisplayName("a row that cannot be read into figures counts as this record's no-figure and the walk goes on: one row never stops the census")
    void anUnreadableRowIsNoFigure() throws SQLException {
        UUID entry = UUID.randomUUID();
        Fake fake = new Fake(rows(row(REAL.toString(), "not-a-currency", 2, 100)));
        SourceJoin join = new SqlSourceJoin(SourceDoor.POSTING, "transfer:", "fake.table by id", "SELECT", Keys.suffixUuid(),
                row -> SourceFigures.total(row.getString("currency"), row.getInt("scale"), row.getLong("minor")));

        assertThat(join.read(fake.connection(), List.of(new EntryRef(entry, REAL.toString(), "r"))).get(entry).missingFigure()).isTrue();
    }

    private static Object[] row(String key, String currency, int scale, long minor) {
        return new Object[] {key, currency, scale, minor};
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    /** A connection returning {@code rows} to its one statement, recording what was bound. */
    private static final class Fake {

        private final List<Object[]> rows;
        Object[] boundArray = new Object[0];

        Fake(List<Object[]> rows) {
            this.rows = new ArrayList<>(rows);
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement" -> {
                        return statement();
                    }
                    case "createArrayOf" -> {
                        boundArray = (Object[]) args[1];
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Array.class}, (p, m, a) -> null);
                    }
                    default -> {
                        return null;
                    }
                }
            });
        }

        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "executeQuery" -> resultSet();
                        default -> null;
                    });
        }

        private ResultSet resultSet() {
            int[] cursor = {-1};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next" -> {
                        cursor[0]++;
                        return cursor[0] < rows.size();
                    }
                    case "getString" -> {
                        Object[] row = rows.get(cursor[0]);
                        return args[0] instanceof Integer ? (Object) String.valueOf(row[0]) : (Object) String.valueOf(row[1]);
                    }
                    case "getInt" -> {
                        return rows.get(cursor[0])[2];
                    }
                    case "getLong" -> {
                        return rows.get(cursor[0])[3];
                    }
                    case "wasNull" -> {
                        return false;
                    }
                    default -> {
                        return null;
                    }
                }
            });
        }
    }
}
