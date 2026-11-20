package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lending's operator collections (`P17-TSK-035`, gaps G-82, G-84 and G-85; finapp-admin-panel ADR-C133): the loan applications,
 * the loans, the servicing proposals awaiting a decision and the terms versions as an operator browses them - keyset pages,
 * each strictly beyond the cursor in its declared total order, one beyond the page read so the caller knows whether more
 * remain. Reads only, on the caller's unit of work; recording what was served is the caller's (ADR-C132 decision 5).
 *
 * <p><strong>The reader's own party is withheld by the statement</strong> from every list of a party's records (the
 * applications, the loans, the servicing queue) - the write-off queue's rule ({@code LoanWriteOffStore.oldestPending}): an
 * operator never browses their own party's accounts, and never approves a correction on them.
 */
public interface LendingOperatorLists {

    /** The last row a page served: its order's stamp and its id, the tie-breaker. */
    record Cursor(Instant at, UUID id) {

        public Cursor {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(id, "id");
        }
    }

    /** A loan application's head: whose, which product, where it stands, the request and terms it names, its stamps. */
    record ApplicationHead(
            LoanApplicationId id,
            UUID party,
            LoanProduct product,
            LoanApplicationStatus status,
            UUID decisionRequest,
            LoanTermsVersionId termsVersion,
            Instant submittedAt,
            Optional<Instant> closedAt) {}

    /**
     * An account's condition as the daily servicing step last recorded it ({@code lending V020}'s
     * {@code loan_condition_event}'s latest rows): the delinquency bucket - {@code CURRENT} when no change was ever recorded -
     * and the default flag.
     */
    record RecordedCondition(String bucket, boolean defaulted) {}

    /** A loan's head: whose, which product and kind, its state, currency and stamps, and its recorded condition in servicing. */
    record LoanHead(
            LoanId id,
            UUID party,
            LoanProduct product,
            LoanKind kind,
            LoanStatus status,
            CurrencyCode currency,
            Instant openedAt,
            Optional<Instant> closedAt,
            Optional<RecordedCondition> condition) {}

    /**
     * What a loan search narrows to: one party, one state, one recorded bucket ({@code CURRENT} or a
     * {@link DelinquencyBucket}) - a bucket narrows to the accounts in servicing, {@code ACTIVE} and {@code CLOSING}.
     */
    record LoanFilter(Optional<UUID> party, Optional<LoanStatus> status, Optional<String> bucket) {

        public LoanFilter {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(bucket, "bucket");
        }
    }

    /** The kinds of servicing proposal an approver decides (ADR-0099; {@code LOAN_SERVICE_APPROVE}). */
    enum ServicingKind { WAIVER, AMENDMENT, REPAYMENT_REVERSAL }

    /**
     * A servicing proposal awaiting a decision: its id and kind, the loan it corrects, its proposer, stamp and reason, and -
     * for a repayment reversal, whose doors are addressed by the repayment - the repayment it would reverse.
     */
    record ServicingRequest(
            UUID id,
            ServicingKind kind,
            LoanId loan,
            String proposedBy,
            Instant proposedAt,
            String reason,
            Optional<UUID> repayment) {}

    /**
     * A terms version's head: its product, number and status, who proposed it, when and why, the decision's stamp and reason
     * once decided - never who decided - and its period in force.
     */
    record TermsVersionHead(
            LoanTermsVersionId id,
            LoanProduct product,
            int version,
            LoanTermsVersionStatus status,
            String proposedBy,
            Instant proposedAt,
            String reason,
            Optional<Instant> decidedAt,
            Optional<String> decisionReason,
            Optional<Instant> effectiveFrom,
            Optional<Instant> effectiveTo) {}

    /** The loan applications, submitted newest first and the id breaking the tie, of one status when named. */
    List<ApplicationHead> applications(Connection unitOfWork, Optional<LoanApplicationStatus> status, UUID withheldParty,
            Optional<Cursor> after, int limit);

    /** The loans, opened newest first and the id breaking the tie, narrowed by {@code filter}. */
    List<LoanHead> loans(Connection unitOfWork, LoanFilter filter, UUID withheldParty, Optional<Cursor> after, int limit);

    /**
     * The waivers, amendments and repayment reversals awaiting a decision - no decision row yet, a reversal still
     * {@code PROPOSED} - proposed oldest first and the id breaking the tie: the approver's queue.
     */
    List<ServicingRequest> pendingServicing(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after, int limit);

    /** The terms versions, proposed newest first and the id breaking the tie, of one product and one status when named. */
    List<TermsVersionHead> termsVersions(Connection unitOfWork, Optional<LoanProduct> product,
            Optional<LoanTermsVersionStatus> status, Optional<Cursor> after, int limit);

    /**
     * The terms versions awaiting a decision ({@code PROPOSED}), proposed oldest first and the id breaking the tie, strictly
     * after {@code after}: the approver's pending-decision queue (`P17-TSK-040`).
     */
    List<TermsVersionHead> pendingTermsVersions(Connection unitOfWork, Optional<Cursor> after, int limit);

    /** A capital contribution awaiting a decision: its id, its proposer's identity id, stamp and reason, and its currency. */
    record PendingContribution(CapitalContributionId id, String proposedBy, Instant proposedAt, String reason,
            CurrencyCode currency) {}

    /**
     * The capital contributions awaiting a decision - no decision event yet ({@code lending V002}'s machine) - proposed oldest
     * first and the id breaking the tie, strictly after {@code after}: the approver's pending-decision queue (`P17-TSK-040`).
     */
    List<PendingContribution> pendingCapitalContributions(Connection unitOfWork, Optional<Cursor> after, int limit);
}
