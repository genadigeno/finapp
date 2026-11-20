package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BNPL's operator lists (`P17-TSK-035`, gaps G-83 and G-84; finapp-admin-panel ADR-C133; {@code API_CONVENTIONS.md} §7's
 * collections - not {@link BnplCollections}, the wallet's auto-collection): the applications, the agreements, one purchase's
 * refunds and the servicing proposals awaiting a decision as an operator browses them - keyset pages, each strictly beyond the
 * cursor in its declared total order, one beyond the page read so the caller knows whether more remain. Reads only, on the
 * caller's unit of work; recording what was served is the caller's (ADR-C132 decision 5).
 *
 * <p><strong>The reader's own party is withheld by the statement</strong> from every list of a party's records - the
 * write-off and dispute queues' rule ({@code JdbcBnplWriteOffStore}, {@code JdbcBnplDisputeStore.queue}): an operator never
 * browses their own party's obligations, and never approves a correction on them.
 */
public interface BnplOperatorLists {

    /** The last row a page served: its order's stamp and its id, the tie-breaker. */
    record Cursor(Instant at, UUID id) {

        public Cursor {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * A BNPL application's head: whose, which product and plan, where it stands and - closed by the platform - why, the
     * merchant it was made at, the decision request and terms version it names, its stamps.
     */
    record ApplicationHead(
            BnplApplicationId id,
            UUID party,
            String product,
            String plan,
            BnplApplicationStatus status,
            Optional<String> closureReason,
            UUID merchant,
            UUID decisionRequest,
            BnplTermsVersionId termsVersion,
            Instant submittedAt,
            Optional<Instant> closedAt) {}

    /** A BNPL agreement's head: its obligation and purchase, whose and at which merchant, the product and plan, its state. */
    record AgreementHead(
            BnplAgreementId agreement,
            BnplObligationId obligation,
            BnplPurchaseId purchase,
            UUID party,
            UUID merchant,
            String product,
            String plan,
            CurrencyCode currency,
            BnplObligationStatus status,
            Instant openedAt,
            Optional<Instant> closedAt,
            Optional<String> closureReason) {}

    /**
     * One applied refund as it was recorded ({@code bnpl V012}'s {@code bnpl_refund}): which door, who asked, the amount and
     * what was refunded before it, the fee returned and the payable debited, what it cancelled - unbilled and billed - and any
     * excess and where it went, the interest released, the agreement and schedule versions it wrote, its reason, entry and
     * value date. Never the request's key.
     */
    record RefundHead(
            UUID id,
            BnplPurchaseId purchase,
            String door,
            String requestedBy,
            Money amount,
            Money refundedBefore,
            Money feeReturned,
            Money payableDebit,
            Money cancelledUnbilled,
            Money cancelledBilled,
            Money excess,
            String excessTo,
            Money interestAccruedReleased,
            Money interestDueReleased,
            BnplAgreementId agreement,
            int agreementVersion,
            int scheduleVersion,
            String reason,
            UUID journalEntry,
            LocalDate valueDate,
            Instant createdAt) {}

    /** The kinds of servicing proposal an approver decides ({@code BNPL_SERVICE_APPROVE}). */
    enum ServicingKind { WAIVER, REPAYMENT_REVERSAL }

    /**
     * A servicing proposal awaiting a decision: its id and kind, the agreement its doors are addressed by, its proposer, stamp
     * and reason, and - for a repayment reversal, whose doors are addressed by the repayment - the repayment it would reverse.
     */
    record ServicingRequest(
            UUID id,
            ServicingKind kind,
            BnplAgreementId agreement,
            String proposedBy,
            Instant proposedAt,
            String reason,
            Optional<UUID> repayment) {}

    /** The applications, submitted newest first and the id breaking the tie, of one status when named. */
    List<ApplicationHead> applications(Connection unitOfWork, Optional<BnplApplicationStatus> status, UUID withheldParty,
            Optional<Cursor> after, int limit);

    /** The agreements, their obligations opened newest first and the obligation's id breaking the tie, narrowed when named. */
    List<AgreementHead> agreements(Connection unitOfWork, Optional<BnplObligationStatus> status, Optional<UUID> merchant,
            UUID withheldParty, Optional<Cursor> after, int limit);

    /** Whose purchase {@code purchase} is - empty when there is no such purchase. */
    Optional<UUID> purchaseParty(Connection unitOfWork, BnplPurchaseId purchase);

    /** {@code purchase}'s refunds, oldest first and the id breaking the tie. */
    List<RefundHead> refunds(Connection unitOfWork, BnplPurchaseId purchase, Optional<Cursor> after, int limit);

    /**
     * The waivers and repayment reversals awaiting a decision - no decision row yet, a reversal still {@code PROPOSED} -
     * proposed oldest first and the id breaking the tie: the approver's queue.
     */
    List<ServicingRequest> pendingServicing(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after, int limit);
}
