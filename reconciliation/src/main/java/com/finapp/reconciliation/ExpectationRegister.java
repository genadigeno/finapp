package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The one writer of settlement expectations, their keys and their aliases (`P8-TSK-004`,
 * ADR-0067) — called on the completing transaction's own connection, after the posting whose
 * clearing line the expectation copies, and infallible for valid input: every insert is
 * {@code ON CONFLICT DO NOTHING}, a collision is recorded and counted, and only a
 * programming defect or the database itself throws — rolling the completion back with its
 * expectation, which is the coupling the phase accepts (ADR-0067 §6).
 */
public interface ExpectationRegister {

    /** What one opening did — either way, exactly one expectation stands. */
    enum OpenResult {
        /** This call inserted the row, its {@code OPENED} event and its keys. */
        OPENED,
        /**
         * A standing row holds the identity ({@code UNIQUE (kind, operation_ref)} or
         * {@code UNIQUE (journal_entry_id, ledger_account_id)}): an earlier opener's or the
         * backfill's. Nothing was written — the winner registered the keys.
         */
        CONVERGED
    }

    /**
     * Opens {@code expectation} with its keys, converging on any standing row. A colliding
     * key is skipped and recorded as an {@code expectation_event(KEY_COLLISION)} — the
     * payment completes; the sweep raises {@code DUPLICATE_INTERNAL} from the records
     * (`P8-TSK-010`).
     */
    OpenResult open(Connection unitOfWork, NewExpectation expectation);

    /**
     * Registers a reference alias — the ARN — under the same per-source unique, in either
     * order with the expectation whose anchor it resolves to (ADR-0067 §5). A collision is
     * recorded and skipped; the first writer stands.
     */
    void registerAlias(Connection unitOfWork, NewAlias alias);

    /** One alias: a foreign reference resolving to an anchor key, scoped per source. */
    record NewAlias(
            UUID sourceId,
            KeyKind kind,
            String value,
            KeyKind anchorKind,
            String anchorValue,
            Actor registeredBy,
            Instant registeredAt,
            CorrelationId correlation) {

        public NewAlias {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(anchorKind, "anchorKind must not be null");
            Objects.requireNonNull(anchorValue, "anchorValue must not be null");
            Objects.requireNonNull(registeredBy, "registeredBy must not be null");
            Objects.requireNonNull(registeredAt, "registeredAt must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (value.isBlank() || anchorValue.isBlank()) {
                throw new IllegalArgumentException("an alias's values must not be blank");
            }
        }
    }
}
