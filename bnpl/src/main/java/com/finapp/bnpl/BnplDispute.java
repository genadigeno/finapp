package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL dispute as recorded (`P12-TSK-023`; {@code bnpl V015}'s {@code bnpl_dispute}, its claim and its hold; ADR-0106;
 * {@code BNPL_LIFECYCLES.md} section 4.10): a customer's contest of a BNPL purchase with the platform - no card network, no
 * chargeback. {@link BnplDisputeStatus} is its machine; the amount {@code D} it disputes, {@code 0 < D <= G - refunded}
 * judged under the purchase's lock (B5), and the {@link Held} instalments a refund of {@code D} would cancel at the opening,
 * latest first, judged under the obligation's (B4). Every instant and business date is the database's (empty until the row
 * is born or the edge committed).
 *
 * <p>The reason, the merchant's statement and the resolution reason are {@code RESTRICTED-PII}: {@link #toString()} names the
 * identifiers and the status only.
 *
 * @param requestKey the customer's key - {@code UNIQUE (party_id, request_key)}, the arbiter behind a lapsed claim
 * @param refundedBefore Σ the purchase's refunds at the opening, read under B5
 * @param resolvedBy {@code SYSTEM}, {@code MERCHANT:<merchant>}, {@code CUSTOMER:<party>} or {@code OPERATOR:<identity>}
 * @param refund the {@link BnplRefund} of door {@code DISPUTE} a {@code RESOLVED_REFUND} executed
 * @param proposal the approved four-eyes proposal an operator's resolution above the threshold names
 */
public record BnplDispute(
        BnplDisputeId id,
        BnplPurchaseId purchase,
        BnplObligationId obligation,
        UUID merchant,
        UUID party,
        BnplAgreementId agreement,
        BnplTermsVersionId termsVersion,
        BnplDisputeStatus status,
        BnplDisputeReason category,
        String reason,
        String requestKey,
        Money amount,
        Money refundedBefore,
        Optional<Instant> openedAt,
        Optional<LocalDate> openedOn,
        Optional<Instant> responseDeadline,
        Optional<String> merchantStatement,
        Optional<Instant> merchantRespondedAt,
        Optional<String> resolvedBy,
        Optional<String> resolutionReason,
        Optional<Instant> resolvedAt,
        Optional<LocalDate> resolvedOn,
        Optional<UUID> refund,
        Optional<UUID> proposal,
        CorrelationId correlation,
        List<Held> holds) {

    /** The system's resolver: the deadline sweep (OQ-10). */
    public static final String SYSTEM = "SYSTEM";

    /** One instalment the dispute holds: the principal a refund of {@code D} would cancel on it, at the opening. */
    public record Held(UUID id, int sequence, UUID instalment, LocalDate dueDate, RefundAllocation.Cancellation.Kind kind,
            Money principal) {

        public Held {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(instalment, "instalment must not be null");
            Objects.requireNonNull(dueDate, "dueDate must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(principal, "principal must not be null");
            if (sequence < 1 || !principal.isPositive()) {
                throw new IllegalArgumentException("a held instalment has a sequence and a positive principal");
            }
        }

        @Override
        public String toString() {
            return "Held[" + sequence + ", " + kind + "]";
        }
    }

    public BnplDispute {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(purchase, "purchase must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(termsVersion, "termsVersion must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(refundedBefore, "refundedBefore must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        Objects.requireNonNull(openedOn, "openedOn must not be null");
        Objects.requireNonNull(responseDeadline, "responseDeadline must not be null");
        Objects.requireNonNull(merchantStatement, "merchantStatement must not be null");
        Objects.requireNonNull(merchantRespondedAt, "merchantRespondedAt must not be null");
        Objects.requireNonNull(resolvedBy, "resolvedBy must not be null");
        Objects.requireNonNull(resolutionReason, "resolutionReason must not be null");
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        Objects.requireNonNull(resolvedOn, "resolvedOn must not be null");
        Objects.requireNonNull(refund, "refund must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        holds = List.copyOf(Objects.requireNonNull(holds, "holds must not be null"));
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a dispute disputes a positive amount");
        }
        if ((status == BnplDisputeStatus.RESOLVED_REFUND) != refund.isPresent()) {
            throw new IllegalArgumentException("a dispute names a refund exactly when it resolved as one (INV-BNPL-08)");
        }
        boolean holding = status == BnplDisputeStatus.OPEN || status == BnplDisputeStatus.MERCHANT_RESPONDED;
        if (holding == resolvedBy.isPresent()) {
            throw new IllegalArgumentException("a dispute is resolved by someone exactly when it is terminal");
        }
        Money held = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (Held each : holds) {
            held = held.plus(each.principal());
        }
        if (held.compareTo(amount) > 0) {
            throw new IllegalArgumentException("a dispute holds at most the principal it disputes");
        }
    }

    /** Whether it still holds - {@code OPEN} or {@code MERCHANT_RESPONDED}. */
    public boolean open() {
        return status == BnplDisputeStatus.OPEN || status == BnplDisputeStatus.MERCHANT_RESPONDED;
    }

    /** The merchant's answer, contesting it: {@code MERCHANT_RESPONDED} with {@code statement} - the database stamps when. */
    BnplDispute responded(String statement) {
        return new BnplDispute(id, purchase, obligation, merchant, party, agreement, termsVersion,
                BnplDisputeStatus.MERCHANT_RESPONDED, category, reason, requestKey, amount, refundedBefore, openedAt,
                openedOn, responseDeadline, Optional.of(statement), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), correlation, holds);
    }

    /**
     * A terminal edge to {@code to} by {@code by} - the merchant's acceptance carrying its {@code statement}; the database
     * stamps the instant and the business date.
     */
    BnplDispute resolved(BnplDisputeStatus to, String by, Optional<String> statement, Optional<String> why,
            Optional<UUID> refunded, Optional<UUID> approved) {
        return new BnplDispute(id, purchase, obligation, merchant, party, agreement, termsVersion, to, category, reason,
                requestKey, amount, refundedBefore, openedAt, openedOn, responseDeadline,
                statement.isPresent() ? statement : merchantStatement, merchantRespondedAt, Optional.of(by), why,
                Optional.empty(), Optional.empty(), refunded, approved, correlation, holds);
    }

    /** Never the reason, the statement or an amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplDispute[" + id.value() + ", " + status + "]";
    }
}
