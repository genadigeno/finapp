package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V008`, read as text (`P8-TSK-021`): the pull permit is keyed per source and business
 * key, moves strictly forward for every writer — an EQUAL instant refused as a step back — is
 * never deleted, and the application may only insert and advance it. The behaviour of each rank
 * is proven against the real schema by {@code PullPermitDatabaseTest}.
 */
@DisplayName("settlement V008 - the pull permit (P8-TSK-021)")
class SettlementV008MigrationTest {

    private static final String MIGRATION = "db/migration/settlement/V008__the_pull_permit.sql";

    @Test
    @DisplayName("one permit per (source, business key), strictly forward, never deleted, and"
            + " the application inserts and advances it - nothing else")
    void thePermitIsPacingOnly() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("CONSTRAINT pull_permit_pk PRIMARY KEY (source_id, business_key)")
                .as("an equal instant is a step back: <=, never <")
                .contains("IF NEW.last_attempt_at <= OLD.last_attempt_at THEN")
                .contains("IF NEW.attempts <= OLD.attempts THEN")
                .contains("BEFORE DELETE ON settlement.pull_permit")
                .contains("GRANT SELECT, INSERT ON settlement.pull_permit TO finapp_app;")
                .contains("GRANT UPDATE (last_attempt_at, attempts) ON settlement.pull_permit"
                        + " TO finapp_app;")
                .doesNotContain("GRANT DELETE");
    }

    private static String migration() {
        try (InputStream in =
                SettlementV008MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }
}
