package com.finapp.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reason and reference bounds live in reconciled places, and the copies cannot drift
 * (`P3-TSK-017` — the `P1-TSK-028` finding made a standing check: a boundary wider than the
 * last write fails as our {@code 500} after the work was already done).
 *
 * <p>The annotations are read from the <strong>fields</strong>, because {@code @Size}
 * declares no {@code RECORD_COMPONENT} target and the compiler propagates it to the field —
 * a component-level lookup returns null for a constraint that is present and working
 * (`P1-TSK-028`'s own first failure).
 */
@DisplayName("the adjustment bounds and their sources agree (P3-TSK-017)")
class AdjustmentRequestTest {

    @Test
    @DisplayName("the reason bound is AuditRecord's own, on the field the validator reads")
    void theReasonBoundIsAuditRecords() throws Exception {
        Field reason = AdjustmentRequest.class.getDeclaredField("reason");
        Size size = reason.getAnnotation(Size.class);
        assertThat(size).as("@Size must land on the field for the validator to see it").isNotNull();
        assertThat(size.max()).isEqualTo(AuditRecord.MAX_REASON_LENGTH);
    }

    @Test
    @DisplayName("the reference bound is V004's own CHECK, character for character")
    void theReferenceBoundIsTheSchemas() throws Exception {
        Field reference = AdjustmentRequest.class.getDeclaredField("reference");
        Size size = reference.getAnnotation(Size.class);
        assertThat(size).isNotNull();
        assertThat(size.max()).isEqualTo(AdjustmentRequest.MAX_REFERENCE_LENGTH);
        // The third copy: the schema's CHECK. The migration is on the classpath because app
        // depends on ledger, so the parity is held to the artefact that enforces it.
        assertThat(migration("db/migration/ledger/V004__create_journal_entry_and_line.sql"))
                .contains(
                        "length(reference) BETWEEN 1 AND "
                                + AdjustmentRequest.MAX_REFERENCE_LENGTH);
    }

    private static String migration(String resource) {
        try (InputStream migration =
                AdjustmentRequestTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
