package com.finapp.lending;

import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The platform's own money committed to lending, under four eyes (`P11-TSK-004`; ADR-0096 section 4, PHASE_11_PLAN.md
 * sections 7.1 (T-cap), 11 and 12.9; {@code INV-LND-13}, {@code INV-AUD-04}): a holder of
 * {@code LENDING_CAPITAL_ADMINISTER} proposes a contribution - currency, amount, the bank reference its transfer will
 * carry, a reason; a DIFFERENT holder approves it, opening its {@code LENDING_CAPITAL_CONTRIBUTION} expectation through
 * reconciliation's opener in the same transaction; anyone, the proposer included, may reject it. The machine is
 * {@code PROPOSED -> APPROVED | REJECTED} and nothing else.
 *
 * <p><strong>An approval is not capital.</strong> Nothing here posts. {@code LENDING_CAPITAL} is credited only when the
 * corporate transfer appears on the settlement bank's statement and bank recognition matches the line to the
 * expectation (rule 0); a contribution whose money never arrives yields nothing, its expectation lapsing on the database
 * clock. Recognition is read, never stored here ({@link CapitalRecognitions}).
 *
 * <p><strong>Ten proposers, one proposal; ten approvers, one approval.</strong> Every act takes the currency's
 * administration lock first (advisory namespace {@code 11}, {@code hashtext(currency)}), then reads the contribution's
 * state - a decision under the lock sees every committed decision. One contribution per currency awaits a decision at a
 * time ({@link ProposalPending}); a decided contribution refuses another decision ({@link ProposalNotPending}), and the
 * decided-once partial unique is the arbiter beneath for every writer. The same person's retry of their own decision
 * converges. The tables are INSERT-only (`lending V002`): the proposal is born once, the decision is a history row.
 */
@RequiredArgsConstructor
public final class LendingCapitalAdministration {

    /** The audit record's target type. */
    static final String TARGET_TYPE = "capital_contribution";

    /** A reason's bound - `lending V002`'s. */
    public static final int REASON_BOUND = 1000;

    /**
     * The reference family: {@code LCAP-} and 4 to 12 digits - claimed by no settlement source's remittance pattern
     * (the families are {@code PSP-REM-}, {@code SCH-REM-}, {@code PAY-REM-}, {@code FX?-}, {@code XB?-}), within the
     * statement format's reference class, and never a digit run of card length.
     */
    public static final Pattern REFERENCE = Pattern.compile("LCAP-[0-9]{4,12}");

    @NonNull private final CapitalContributionStore store;
    @NonNull private final CapitalExpectations expectations;
    @NonNull private final LendingCapitalHeadroom headroom;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome. */
    public record Proposed(CapitalContributionId id) {}

    /** A decision's outcome; {@code replayed} when the same person's retry converged. */
    public record Decided(
            CapitalContributionId id, CapitalContributionStatus status, Optional<String> expectationRef, boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No contribution has this id. */
    public static final class ContributionNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContributionNotFound() {
            super("no capital contribution answers this request");
        }
    }

    /** A contribution of the currency already awaits a decision. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("a capital contribution of this currency already awaits a decision");
        }
    }

    /** The contribution is no longer {@code PROPOSED} - decided by somebody else, or differently. */
    public static final class ProposalNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(CapitalContributionStatus status) {
            super("the capital contribution is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to approve their own contribution ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(CapitalContributionId id) {
            super("a capital contribution is approved by someone other than its proposer; the proposer may reject it");
            this.target = id.value().toString();
            this.summary = "contribution=" + id.value() + ", act=capital:approve";
        }

        /** The proposal the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the proposal and the act - never an amount. */
        public String summary() {
            return summary;
        }
    }

    /** A proposal or decision carries no reason, one past its bound, or one holding an instrument shape. */
    public static final class ReasonRequired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRequired(String detail) {
            super(detail);
        }
    }

    /** The contribution is not well formed; the detail names the defect, never a value. */
    public static final class ContributionInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContributionInvalid(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a contribution of {@code amountMinor} minor units of {@code currency}, to arrive under
     * {@code reference}: the currency locked, one pending per currency, the reference never used before, born
     * {@code PROPOSED} with its history row and the reasoned audit record.
     */
    public Proposed propose(
            Connection unitOfWork,
            CurrencyCode currency,
            long amountMinor,
            String reference,
            String reason,
            Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        if (!SupportedCurrencies.ALL.contains(currency)) {
            throw new ContributionInvalid("lending capital is held only in a supported currency");
        }
        if (amountMinor <= 0) {
            throw new ContributionInvalid("a contribution's amount is positive");
        }
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new ContributionInvalid("a contribution's reference is LCAP- and 4 to 12 digits");
        }
        Money amount = Money.ofMinorUnits(amountMinor, currency);
        store.lockCurrency(unitOfWork, currency);
        if (store.proposalPending(unitOfWork, currency)) {
            throw new ProposalPending();
        }
        if (store.referenceTaken(unitOfWork, reference)) {
            throw new ContributionInvalid("the reference already names a contribution: one reference, one transfer");
        }
        CapitalContributionId id = CapitalContributionId.next(ids);
        store.insertProposal(
                unitOfWork, new CapitalContributionStore.NewProposal(id, amount, reference, actor, reasoned, correlation, ids.next()));
        audit(unitOfWork, actor, LendingAuditAction.CAPITAL_CONTRIBUTION_PROPOSED, id, reasoned,
                "currency=" + currency.code(), correlation);
        return new Proposed(id);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves a pending contribution - a different person's act. Under the currency's lock: the contribution must be
     * {@code PROPOSED} and not the approver's own; its expectation is opened through reconciliation's opener, dated
     * from the database's date; the {@code APPROVED} row names it; the reasoned audit record commits with them.
     */
    public Decided approve(
            Connection unitOfWork, CapitalContributionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        CapitalContributionStore.ContributionRow row = locked(unitOfWork, id);
        if (row.status() == CapitalContributionStatus.APPROVED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.status(), row.expectationRef(), true);
        }
        if (row.status() != CapitalContributionStatus.PROPOSED) {
            throw new ProposalNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row.id());
        }
        Instant now = store.transactionTime(unitOfWork);
        LedgerAccount capital = headroom.capitalAccount(unitOfWork, row.currency());
        String expectationRef =
                expectations.open(
                        unitOfWork,
                        new CapitalExpectations.Opening(
                                row.id(),
                                row.amount(),
                                row.reference(),
                                capital,
                                LocalDate.ofInstant(now, ZoneOffset.UTC),
                                actor,
                                now,
                                correlation));
        store.decide(
                        unitOfWork,
                        new CapitalContributionStore.Decision(
                                ids.next(), row.id(), CapitalContributionStatus.APPROVED, actor, row.proposedBy(), reasoned,
                                Optional.of(expectationRef), correlation))
                .orElseThrow(() -> new IllegalStateException(
                        "a decision under the currency's lock found another standing: the lock protocol was bypassed"));
        audit(unitOfWork, actor, LendingAuditAction.CAPITAL_CONTRIBUTION_APPROVED, row.id(), reasoned,
                "currency=" + row.currency().code() + ", expectation=" + expectationRef, correlation);
        return new Decided(row.id(), CapitalContributionStatus.APPROVED, Optional.of(expectationRef), false);
    }

    // ------------------------------------------------------------------ reject

    /** Rejects a pending contribution - any holder, the proposer's withdrawal included. Terminal; opens nothing. */
    public Decided reject(
            Connection unitOfWork, CapitalContributionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        CapitalContributionStore.ContributionRow row = locked(unitOfWork, id);
        if (row.status() == CapitalContributionStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.status(), Optional.empty(), true);
        }
        if (row.status() != CapitalContributionStatus.PROPOSED) {
            throw new ProposalNotPending(row.status());
        }
        store.decide(
                        unitOfWork,
                        new CapitalContributionStore.Decision(
                                ids.next(), row.id(), CapitalContributionStatus.REJECTED, actor, row.proposedBy(), reasoned,
                                Optional.empty(), correlation))
                .orElseThrow(() -> new IllegalStateException(
                        "a decision under the currency's lock found another standing: the lock protocol was bypassed"));
        audit(unitOfWork, actor, LendingAuditAction.CAPITAL_CONTRIBUTION_REJECTED, row.id(), reasoned,
                "currency=" + row.currency().code() + ", byProposer=" + row.proposedBy().equals(actor.id()), correlation);
        return new Decided(row.id(), CapitalContributionStatus.REJECTED, Optional.empty(), false);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link LendingAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept (the Phase 11
     * gate's R4).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The contribution read under its currency's lock: read once to learn the currency (immutable), the lock taken,
     * read again - the state the decision acts on is the one every committed decision left.
     */
    private CapitalContributionStore.ContributionRow locked(Connection unitOfWork, CapitalContributionId id) {
        CapitalContributionStore.ContributionRow first = store.find(unitOfWork, id).orElseThrow(ContributionNotFound::new);
        store.lockCurrency(unitOfWork, first.currency());
        return store.find(unitOfWork, id).orElseThrow(ContributionNotFound::new);
    }

    /** The reason screen ({@code INV-AUD-02}; the R2 precedent) - `lending V002`'s twin refuses a bypass beneath. */
    static String reasoned(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > REASON_BOUND) {
            throw new ReasonRequired("a reason of 1 to " + REASON_BOUND + " characters is required");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw new ReasonRequired("a reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            LendingAuditAction action,
            CapitalContributionId id,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
