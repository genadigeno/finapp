package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code lending V032}'s ranks as the code declares them (`P14-TSK-021`) - a text pin, which proves only the text: the clauses run in
 * {@code LoanWriteOffDatabaseTest}, owed. It holds the migration to the enums and the doors to the names.
 */
@DisplayName("lending V032 holds the write-off's ranks as the code declares them (P14-TSK-021)")
class LoanWriteOffMigrationTest {

    private static final String V032 = read("db/migration/lending/V032__loan_write_off_under_four_eyes.sql");

    @Test
    @DisplayName("the status CHECKs admit every status a writer can produce, and every edge; WRITTEN_OFF leaves closed_at alone")
    void theChecksAdmitEveryStatus() {
        for (LoanStatus status : LoanStatus.values()) {
            assertThat(V032).contains("'" + status.name() + "'");
        }
        assertThat(V032).contains("(kind = 'INSTALMENT' AND status IN ('PENDING_DISBURSEMENT', 'ACTIVE', 'CLOSED', 'CANCELLED', 'WRITTEN_OFF'))");
        assertThat(V032).contains("(kind = 'REVOLVING' AND status IN ('ACTIVE', 'CLOSING', 'CLOSED', 'WRITTEN_OFF'))");
        assertThat(V032).contains("(from_status = 'ACTIVE' AND to_status IN ('CLOSING', 'CLOSED', 'WRITTEN_OFF'))");
        assertThat(V032).contains("(from_status = 'CLOSING' AND to_status IN ('CLOSED', 'WRITTEN_OFF'))");
        assertThat(V032).doesNotContain("DROP CONSTRAINT loan_terminal_coherent");
    }

    @Test
    @DisplayName("four eyes, one decision, one approval per loan; both tables INSERT-only, no UPDATE, no DELETE granted")
    void fourEyesOneDecision() {
        assertThat(V032).contains("CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by)");
        assertThat(V032).contains("CONSTRAINT loan_write_off_decision_once UNIQUE (write_off_id)");
        assertThat(V032).contains("CREATE UNIQUE INDEX loan_write_off_one_approved");
        assertThat(V032).contains("FOREIGN KEY (write_off_id, loan_id, proposed_by)");
        assertThat(V032).contains("GRANT SELECT, INSERT ON lending.loan_write_off TO finapp_app;");
        assertThat(V032).contains("GRANT SELECT, INSERT ON lending.loan_write_off_decision TO finapp_app;");
        assertThat(V032).doesNotContain("GRANT UPDATE").doesNotContain("GRANT DELETE");
    }

    @Test
    @DisplayName("the edge only beside an approved decision, from ACTIVE or CLOSING, never closed; V026's edges kept whole")
    void theEdge() {
        assertThat(V032).contains("IF OLD.status IN ('ACTIVE', 'CLOSING') AND NEW.status = 'WRITTEN_OFF' THEN");
        assertThat(V032).contains("d.loan_id = OLD.id AND d.outcome = 'APPROVED'");
        assertThat(V032).contains("IF proposal.agreement_version <> (SELECT max(a.version) FROM lending.loan_agreement a");
        assertThat(V032).contains("USING ERRCODE = 'LNEXP'").as("V026's body, verbatim");
        assertThat(V032).contains("NEW.recorded_order := nextval('lending.record_order');");
    }

    private static String read(String resource) {
        try (InputStream stream = LoanWriteOffMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
