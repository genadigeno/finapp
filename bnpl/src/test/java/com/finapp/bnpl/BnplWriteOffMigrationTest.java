package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V020}'s ranks as the code declares them (`P14-TSK-021`) - a text pin, which proves only the text: the clauses run in
 * {@code BnplWriteOffDatabaseTest}, owed. It holds the migration to the enums and the door to the CHECK's names.
 */
@DisplayName("bnpl V020 holds the write-off's ranks as the code declares them (P14-TSK-021)")
class BnplWriteOffMigrationTest {

    private static final String V020 = read("db/migration/bnpl/V020__bnpl_write_off_under_four_eyes.sql");

    @Test
    @DisplayName("the status CHECKs admit every status a writer can produce, and WRITTEN_OFF only ever with no closure")
    void theChecksAdmitEveryStatus() {
        for (BnplObligationStatus status : BnplObligationStatus.values()) {
            assertThat(V020).contains("'" + status.name() + "'");
        }
        for (BnplClosureReason reason : BnplClosureReason.values()) {
            assertThat(V020).as("the rebuilt closure CHECK keeps every reason").contains("'" + reason.name() + "'");
        }
        assertThat(V020).contains("(status IN ('ACTIVE', 'WRITTEN_OFF') AND closed_at IS NULL AND closure_reason IS NULL)");
        assertThat(V020).contains("(from_status = 'ACTIVE' AND to_status IN ('CLOSED', 'WRITTEN_OFF'))");
    }

    @Test
    @DisplayName("four eyes, one decision, one approval per obligation; both tables INSERT-only, no UPDATE, no DELETE granted")
    void fourEyesOneDecision() {
        assertThat(V020).contains("CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by)");
        assertThat(V020).contains("CONSTRAINT bnpl_write_off_decision_once UNIQUE (write_off_id)");
        assertThat(V020).contains("CREATE UNIQUE INDEX bnpl_write_off_one_approved");
        assertThat(V020).contains("FOREIGN KEY (write_off_id, obligation_id, proposed_by)");
        assertThat(V020).contains("GRANT SELECT, INSERT ON bnpl.bnpl_write_off TO finapp_app;");
        assertThat(V020).contains("GRANT SELECT, INSERT ON bnpl.bnpl_write_off_decision TO finapp_app;");
        assertThat(V020).doesNotContain("GRANT UPDATE").doesNotContain("GRANT DELETE");
    }

    @Test
    @DisplayName("the edge only beside an approved decision, never closed; a refund and a dispute refused on a written-off obligation")
    void theEdgeAndTheDoors() {
        assertThat(V020).contains("IF OLD.status = 'ACTIVE' AND NEW.status = 'WRITTEN_OFF' THEN");
        assertThat(V020).contains("d.obligation_id = OLD.id AND d.outcome = 'APPROVED'");
        assertThat(V020).contains("BEFORE INSERT ON bnpl.bnpl_refund").contains("BEFORE INSERT ON bnpl.bnpl_dispute");
    }

    @Test
    @DisplayName("the decision's order is drawn from the record's sequence after the lock, and the amounts never float")
    void theOrderAndTheAmounts() {
        assertThat(V020).contains("NEW.recorded_order := nextval('bnpl.record_order');");
        assertThat(V020).contains("principal_minor         bigint").doesNotContain("double precision").doesNotContain("real ");
    }

    private static String read(String resource) {
        try (InputStream stream = BnplWriteOffMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
