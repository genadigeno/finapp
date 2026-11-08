package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every settlement table refuses {@code TRUNCATE}, its owner included ({@code P15-TSK-004}, settlement {@code V019};
 * {@code THREAT_MODEL.md} T33). The tables are read from the catalogue, so one added later is covered. Owed to
 * {@code P15-TST-002} (C14); the same refusal on the image's database is walked in {@code infra/scripts/migrator-verify.sh}.
 */
@Tag("database")
@DisplayName("every settlement table refuses its owner's TRUNCATE by a statement trigger of its own (P15-TSK-004)")
class SettlementTablesRefuseTruncateDatabaseTest {

    @Test
    @DisplayName("as the migrator, TRUNCATE ... CASCADE is refused by the named table's own trigger, on all 13")
    void everyTableRefusesItsOwner() throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            owner.setAutoCommit(false);
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'settlement' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history' ORDER BY 1")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            assertThat(tables).as("a new table cannot slip past this scan").hasSize(13);
            for (String table : tables) {
                Savepoint before = owner.setSavepoint();
                try {
                    statement.execute("TRUNCATE settlement." + table + " CASCADE");
                    throw new AssertionError("the owner truncated settlement." + table);
                } catch (SQLException refusal) {
                    assertThat(refusal.getSQLState()).as(table).isEqualTo("P0001");
                    assertThat(refusal.getMessage())
                            .as("refused by %s's own trigger, not by one a cascade reached first", table)
                            .contains("settlement." + table + " is never truncated");
                } finally {
                    owner.rollback(before);
                }
            }
            owner.rollback();
        }
    }
}
