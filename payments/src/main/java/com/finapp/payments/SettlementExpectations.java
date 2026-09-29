package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The expectation-opening seam on the payment appliers' acting branches (`P8-TSK-004`,
 * ADR-0067) — the {@link RailOutcomeObserver} shape, pointed at the money instead of the
 * meters.
 *
 * <h2>Why a port, and why it is required</h2>
 *
 * <p>{@code payments} must not see {@code reconciliation} (ADR-0064): what a completion
 * means for the position proof is the composition root's to record. The parameter is
 * <strong>required</strong> on every applier that calls it, so the wiring is decided at
 * compile time — and unlike {@link RailOutcomeObserver#NONE}, <strong>no do-nothing
 * implementation ships in production code</strong>: a meter left unread loses a count, a
 * completion that opens nothing loses track of money. Tests take the recording double from
 * the test fixtures.
 *
 * <h2>Where it is called</h2>
 *
 * <p>At the applier, past the acting exit, after the posting whose clearing line the
 * expectation copies (it needs the entry id), inside the completing transaction — so the
 * completion, its posting and its expectation commit together or not at all, and a failure
 * inside the implementation rolls the completion back for the redelivery or sweep to
 * complete both (ADR-0067 §6, the accepted coupling). It is called exactly when the stored
 * rail's declared {@code clearingPurpose()} is present: a book completion touches no
 * reconciled position and calls nothing ({@code SettlementModel.NONE}, `INV-SET-01`'s
 * per-rail guarantee).
 *
 * <h2>What crosses</h2>
 *
 * <p>Identifiers, the declared position, the posted clearing account and entry, dates and
 * typed references — <strong>never an amount or a direction</strong>: the implementation
 * derives both from the posted entry's clearing line, so the expectation cannot contradict
 * the ledger (ADR-0067 §3, §4).
 */
public interface SettlementExpectations {

    /**
     * Opens the completion's expectation with its keys — `P8-TSK-004` calls it for the card
     * capture and the card refund; the dispute, push and unmatched completions arrive with
     * `P8-TSK-005` over the same seam.
     */
    void open(Connection unitOfWork, Opening opening);

    /**
     * Registers a reference alias — the ARN — resolving to an anchor key, in either order
     * with the expectation that anchors it (ADR-0067 §5).
     */
    void alias(Connection unitOfWork, AliasRegistration registration);

    /** The completions this module can open expectations for (ADR-0067 §2's table). */
    enum Kind {
        CARD_CAPTURE,
        CARD_REFUND,
        CHARGEBACK,
        CHARGEBACK_REVERSAL,
        DISPUTE_FEE,
        PUSH_PAY_IN,
        UNMATCHED_CONFIRMATION,
        PUSH_WITHDRAWAL,
        PUSH_RETURN
    }

    /** The typed references a counterparty will quote (ADR-0067 §5's list, payments' own). */
    enum ReferenceKind {
        PSP_CAPTURE_REF,
        PSP_REFUND_REF,
        OUR_REF,
        CARD_ATTEMPT,
        ACQUIRER_REF,
        DISPUTE_CB_REF,
        DISPUTE_REV_REF,
        DISPUTE_FEE_REF,
        SCHEME_REF,
        END_TO_END_REF
    }

    /** One typed reference. */
    record Key(ReferenceKind kind, String value) {
        public Key {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(value, "value must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException("a key value must not be blank");
            }
        }
    }

    /**
     * One completion's opening: copies of facts the applier already holds.
     *
     * @param position the stored rail's declared clearing purpose — the same read that
     *     chose the posting's clearing account, made exactly when it is present
     * @param clearingAccount the account that declaration resolved and the posting hit
     * @param settlementCycle the cycle the completion announced, when it announced one — a
     *     matching attribute, never a key
     */
    record Opening(
            Kind kind,
            String operationRef,
            String postingKey,
            AccountPurpose position,
            LedgerAccountId clearingAccount,
            JournalEntryId journalEntryId,
            LocalDate postingDate,
            Optional<String> settlementCycle,
            List<Key> keys,
            Correlation correlation) {

        public Opening {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(operationRef, "operationRef must not be null");
            Objects.requireNonNull(postingKey, "postingKey must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(clearingAccount, "clearingAccount must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(postingDate, "postingDate must not be null");
            Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            keys = List.copyOf(keys);
        }
    }

    /** One alias registration: the foreign reference and the anchor it resolves to. */
    record AliasRegistration(
            AccountPurpose position,
            ReferenceKind kind,
            String value,
            ReferenceKind anchorKind,
            String anchorValue,
            Correlation correlation) {

        public AliasRegistration {
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(anchorKind, "anchorKind must not be null");
            Objects.requireNonNull(anchorValue, "anchorValue must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (value.isBlank() || anchorValue.isBlank()) {
                throw new IllegalArgumentException("an alias's values must not be blank");
            }
        }
    }
}
