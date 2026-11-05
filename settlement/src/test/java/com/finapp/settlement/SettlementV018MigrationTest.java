package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V018`, read as text (`P14-TSK-025`; the owner's rulings of 2026-11-06 on `P14-TSK-012`'s (c)): a source's admission and
 * retirement days, the backfill that excuses no month, the days moving only towards requiring more, and nothing else changed - V002's three
 * rules restated verbatim, no grant, no seed. What PostgreSQL does with it is {@code SourceDatesDatabaseTest}'s, owed (C5).
 */
@DisplayName("settlement V018 - a source's admission and retirement days (P14-TSK-025)")
class SettlementV018MigrationTest {

    private static final String MIGRATION = "db/migration/settlement/V018__a_sources_admission_and_retirement.sql";
    private static final String V002 = "db/migration/settlement/V002__source_register_file_store_and_door_screen.sql";

    @Test
    @DisplayName("the backfill: every standing row admitted 1970-01-01, the day before any month, and the default dropped so a new source's migration states its own; retired_on NULL")
    void theBackfillExcusesNoMonth() {
        String sql = normalized(migration(MIGRATION));
        assertThat(sql)
                .contains("ADD COLUMN admitted_on DATE NOT NULL DEFAULT DATE '1970-01-01'")
                .contains("ADD COLUMN retired_on DATE;")
                .contains("ALTER TABLE settlement.source ALTER COLUMN admitted_on DROP DEFAULT;");
        assertThat(sql.indexOf("DROP DEFAULT")).as("dropped after the backfill stands").isGreaterThan(sql.indexOf("DEFAULT DATE '1970-01-01'"));
        assertThat(sql).as("no row's retirement is guessed").doesNotContain("SET retired_on").doesNotContain("UPDATE settlement.source");
    }

    @Test
    @DisplayName("the CHECKs: a retirement day exactly when RETIRED, and never before the admission")
    void theDaysAreCoherent() {
        String sql = normalized(migration(MIGRATION));
        assertThat(sql)
                .contains("ADD CONSTRAINT source_retired_on_iff_retired CHECK ((status = 'RETIRED') = (retired_on IS NOT NULL))")
                .contains("ADD CONSTRAINT source_retired_after_admitted CHECK (retired_on IS NULL OR retired_on >= admitted_on)");
    }

    @Test
    @DisplayName("the trigger replaced whole: V002's three rules verbatim, then the admission never later, the edge stamped with the database's UTC day when unstated, the retirement never cleared, earlier or ahead of today")
    void theDaysMoveOnlyTowardsRequiringMore() {
        String sql = normalized(migration(MIGRATION));
        String v002 = normalized(migration(V002));
        for (String rule : new String[] {
                "IF NEW.id IS DISTINCT FROM OLD.id OR NEW.code IS DISTINCT FROM OLD.code OR NEW.kind IS DISTINCT FROM OLD.kind THEN"
                        + " RAISE EXCEPTION 'a settlement source''s identity is frozen (P8-TSK-002)'; END IF;",
                "IF NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED') THEN",
                "IF NEW.next_sequence < OLD.next_sequence THEN RAISE EXCEPTION 'a settlement source''s sequence never walks backwards (P8-TSK-002)'; END IF;"}) {
            assertThat(v002).as("V002's own rule").contains(rule);
            assertThat(sql).as("restated verbatim").contains(rule);
        }
        assertThat(sql)
                .contains("CREATE OR REPLACE FUNCTION settlement.source_moves_forward_only()")
                .contains("IF NEW.admitted_on > OLD.admitted_on THEN")
                .contains("IF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' AND NEW.retired_on IS NULL THEN NEW.retired_on := (now() AT TIME ZONE 'UTC')::date; END IF;")
                .contains("IF NEW.retired_on IS NULL OR NEW.retired_on < OLD.retired_on THEN")
                .contains("IF NEW.retired_on > (now() AT TIME ZONE 'UTC')::date THEN")
                .as("the binding stands: no trigger re-created").doesNotContain("CREATE TRIGGER");
        assertThat(sql.indexOf("NEW.retired_on := ")).as("stamped before it is judged").isLessThan(sql.indexOf("IF NEW.retired_on IS DISTINCT FROM OLD.retired_on THEN"));
    }

    @Test
    @DisplayName("nothing else: no grant - the days are the migrator's - no seed, no vocabulary, no table")
    void nothingElse() {
        String sql = normalized(migration(MIGRATION));
        assertThat(sql)
                .doesNotContain("GRANT")
                .doesNotContain("INSERT INTO")
                .doesNotContain("source_kind")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("DROP TRIGGER");
    }

    private static String migration(String path) {
        try (InputStream in = SettlementV018MigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Comments dropped, whitespace folded. */
    private static String normalized(String sql) {
        return sql.replaceAll("(?m)--.*$", "").replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }
}
