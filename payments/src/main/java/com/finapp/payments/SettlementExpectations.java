package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
 * <p>Identifiers, the declared position, the posted clearing account and entry and typed
 * references — <strong>never an amount, a direction or a date</strong>: the implementation
 * derives all three from the posted entry and its clearing line, so the expectation cannot
 * contradict the ledger (ADR-0067 §3, §4; the posting date "copied from the entry and never
 * re-read from the clock" — `P8-TSK-005` removed the applier's own copy of it).
 */
public interface SettlementExpectations {

    /**
     * Opens the completion's expectation with its keys — the card capture and the card refund
     * (`P8-TSK-004`); the dispute stages, the push execution, withdrawal and return and the
     * unmatched confirmation (`P8-TSK-005`), each in its applier's acting branch.
     */
    void open(Connection unitOfWork, Opening opening);

    /**
     * Registers a reference alias — the ARN — resolving to an anchor key, in either order
     * with the expectation that anchors it (ADR-0067 §5).
     */
    void alias(Connection unitOfWork, AliasRegistration registration);

    /**
     * Gives a parking's value its owner (`P8-TSK-020`, ADR-0070 §2's
     * {@code UNMATCHED_CONFIRMATION} row): the CREDIT suspense item for the line the parking
     * put into {@code SUSPENSE_UNMATCHED}, and the break that owns it, in the parking's own
     * transaction ({@code INV-REC-09}). Called by the claim's winner only, after the parking row
     * it names exists; the amount, side and date are read off the posted entry, never passed.
     */
    void parked(Connection unitOfWork, ParkedValue parked);

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
        PUSH_RETURN,
        /** A cross-border outbound credit's completion: OUTBOUND on its corridor's clearing (`P9-TSK-020`). */
        CROSSBORDER_PAYOUT
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
        END_TO_END_REF,
        /** A corridor provider's own reference for a payout (`P9-TSK-020`). */
        PAYOUT_PROVIDER_REF
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
     * @param operationRef the identifier the posting key names — the key's suffix after its
     *     prefix, so the expectation and its entry name one operation
     * @param clearingAccount the account that declaration resolved and the posting hit
     * @param settlementCycle the cycle the completion announced, when it announced one — a
     *     matching attribute, never a key
     * @param counterparty the counterparty owning the clearing position, when the position is
     *     counterparty-scoped (a corridor's: its rail, `P9-TSK-020`) - the source that discharges it
     */
    record Opening(
            Kind kind,
            String operationRef,
            String postingKey,
            AccountPurpose position,
            LedgerAccountId clearingAccount,
            JournalEntryId journalEntryId,
            Optional<String> settlementCycle,
            List<Key> keys,
            Correlation correlation,
            Optional<String> counterparty) {

        /** An opening on a platform-scoped position (every opening before `P9-TSK-020`). */
        public Opening(
                Kind kind,
                String operationRef,
                String postingKey,
                AccountPurpose position,
                LedgerAccountId clearingAccount,
                JournalEntryId journalEntryId,
                Optional<String> settlementCycle,
                List<Key> keys,
                Correlation correlation) {
            this(kind, operationRef, postingKey, position, clearingAccount, journalEntryId, settlementCycle, keys,
                    correlation, Optional.empty());
        }

        public Opening {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(operationRef, "operationRef must not be null");
            Objects.requireNonNull(postingKey, "postingKey must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(clearingAccount, "clearingAccount must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            Objects.requireNonNull(counterparty, "counterparty must not be null");
            keys = List.copyOf(keys);
        }
    }

    /**
     * One parking's value to own: identifiers and the parking's stored attribution - never an
     * amount, a side or a date (the implementation reads them off the posted entry's line on
     * {@code suspenseAccount}).
     *
     * @param parkingId the parking row - the suspense item's {@code origin_ref}, its one arbiter
     * @param position the rail's declared clearing position the parking debited - the source
     *     whose evidence reaches the value is the one that discharges it
     * @param cause what the statement named, as the parking stored it (payments `V023`)
     * @param attempt the named attempt, exactly when the parking is attributed
     * @param explainedBy the claim's standing subject when payments `V023`'s backfill left this
     *     parking unclaimed - one scheme execution a credit, a withdrawal or a return already
     *     explains (ADR-0070 point 8). Only the opening-position backfill passes it: a live
     *     parking is always its claim's winner
     */
    record ParkedValue(
            UUID parkingId,
            AccountPurpose position,
            LedgerAccountId suspenseAccount,
            JournalEntryId journalEntryId,
            UnmatchedConfirmation.Cause cause,
            Optional<PaymentAttemptId> attempt,
            Optional<String> explainedBy,
            Correlation correlation) {

        public ParkedValue {
            Objects.requireNonNull(parkingId, "parkingId must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(suspenseAccount, "suspenseAccount must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(attempt, "attempt must not be null");
            Objects.requireNonNull(explainedBy, "explainedBy must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if ((cause == UnmatchedConfirmation.Cause.UNATTRIBUTED) != attempt.isEmpty()) {
                throw new IllegalArgumentException(
                        "a parking names an attempt exactly when it is attributed (V023)");
            }
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
