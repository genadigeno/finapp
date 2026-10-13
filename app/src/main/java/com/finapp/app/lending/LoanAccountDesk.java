package com.finapp.app.lending;

import com.finapp.credit.CreditErrorCode;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.lending.AcceptanceEvidence;
import com.finapp.lending.BillingKind;
import com.finapp.lending.CreditLineStatementStore;
import com.finapp.lending.CreditLineStatements;
import com.finapp.lending.InstalmentBilling;
import com.finapp.lending.InstalmentBillingStore;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanAcceptances;
import com.finapp.lending.LoanAgreement;
import com.finapp.lending.LoanApplications;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanOfferId;
import com.finapp.lending.LoanOffers;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanSchedules;
import com.finapp.lending.Loans;
import com.finapp.lending.RepaymentSchedule;
import com.finapp.lending.Schedule;
import com.finapp.lending.SchedulePeriod;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the loan account doors (`P11-TSK-012`; {@code PHASE_11_PLAN.md} section 9): the acceptance - keyed
 * per principal ({@code lending.offer-acceptance:CUSTOMER:<id>}), run in one transaction under the
 * {@link IdempotentExecutor} claim, so a retried key replays the recorded receipt and a refusal, which writes nothing,
 * rolls the claim back with it - and the owner's reads of their loans.
 *
 * <p><strong>The acceptance requires a {@code MULTI_FACTOR} session, always</strong> (A19, R14): it commits exposure and
 * capital, and binds the customer to a contract - the application's rule, enrolled or not. The evidence it records is the
 * session's own: the identity, the session, its assurance level, the client address the platform saw and the agent the
 * client sent - never a value the body could claim. <strong>What the reads show</strong>: the account's state and kind,
 * the agreement version that governs it (the principal or the line's limit, the term, the rate, the day, the hash) and
 * the six balances derived from journal lines in one snapshot. Never the party, a score or a decision.
 */
@RequiredArgsConstructor
public final class LoanAccountDesk {

    static final String ACCEPTANCE_SCOPE = "lending.offer-acceptance:";

    @NonNull private final LoanAcceptances acceptances;
    @NonNull private final Loans loans;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final LoanAcceptanceMetrics metrics;
    @NonNull private final LoanSchedules schedules;
    @NonNull private final CreditLineStatements statements;

    /** What an acceptance answers - and replays: the account opened. */
    public record LoanAcceptedView(
            String loanId,
            String offerId,
            String product,
            String kind,
            String status,
            String agreementId,
            int agreementVersion,
            String termsSha256,
            String acceptedAt) {}

    /**
     * A loan account as its customer sees it. Amounts and the rate are exact decimal strings. {@code payoutStatus} is the
     * external payout's state on path X (`P11-TSK-015`: {@code PENDING}, {@code DISPATCHING}, {@code DISPATCHED},
     * {@code PAID_OUT}, {@code FAILED}, {@code NOT_DISPATCHED}) - null for a wallet loan and a line; never the destination.
     * {@code availableLimit} is a line's (`P11-TSK-021`): its limit less its drawn principal while {@code ACTIVE}, zero
     * once it is not - derived in the read's snapshot, never stored; null for an instalment loan. {@code condition} is the
     * account's delinquency and default (`P11-TSK-024`) - null for an account not in servicing.
     */
    public record LoanView(
            String loanId,
            String offerId,
            String product,
            String kind,
            String status,
            String currency,
            String openedAt,
            String closedAt,
            AgreementView agreement,
            BalancesView balances,
            String payoutStatus,
            String availableLimit,
            ConditionView condition) {}

    /**
     * The account's condition now (`P11-TSK-024`; ADR-0098, {@code PHASE_11_PLAN.md} section 12.7) - derived in the read's
     * snapshot from the billed items on the database clock's business date, never stored: {@code delinquency}
     * {@code CURRENT} or {@code PAST_DUE}; {@code daysPastDue} from the oldest item unpaid at the end of its due date (0
     * when nothing is); {@code bucket} its band ({@code B1_1_29}, {@code B2_30_59}, {@code B3_60_89}, {@code B4_90_PLUS}),
     * null when current; {@code defaulted} set at the pinned threshold (90 days) and cleared at cure; {@code drawsSuspended}
     * an {@code ACTIVE} line's (A25) - null for an instalment loan and a closing line. A condition, never a state: the account's {@code status} is unchanged
     * by it.
     */
    public record ConditionView(
            String delinquency,
            int daysPastDue,
            String bucket,
            boolean defaulted,
            Boolean drawsSuspended) {}

    /** The agreement version that governs the account - a loan's principal, or a line's limit. */
    public record AgreementView(
            String agreementId,
            int version,
            String principal,
            String limit,
            Integer termMonths,
            String nominalAnnualRate,
            int repaymentDay,
            String agreementTemplate,
            int agreementTemplateVersion,
            String termsSha256,
            String effectiveFrom) {}

    /** The six per-loan balances, each on its normal side, derived from journal lines. */
    public record BalancesView(
            String principalNotDue,
            String principalDue,
            String principalOutstanding,
            String interestAccrued,
            String interestDue,
            String feesDue,
            String creditBalance) {}

    /** The caller's loans, newest first. */
    public record LoanListView(List<LoanView> loans) {}

    /**
     * A schedule version as its customer sees it (`P11-TSK-014`): the projection of an agreement version by its pinned
     * engine from the accrual start - amounts exact decimal strings, dates ISO - and beside it the bill (`P11-TSK-017`):
     * each instalment's status and, once billed, what it made due and what has been paid of it; and the post-maturity
     * periods billed after the final due date (interest accrued on principal still outstanding, made due monthly).
     */
    public record ScheduleView(
            String loanId,
            int scheduleVersion,
            int agreementVersion,
            int scheduleEngineVersion,
            String currency,
            String principal,
            String instalment,
            String finalInstalment,
            String totalInterest,
            String totalPayable,
            String startDate,
            int termMonths,
            List<InstalmentView> instalments,
            List<BillingView> postMaturityBillings) {}

    /**
     * One instalment period: its interest runs over {@code [periodStart, dueDate)} on the opening principal - the
     * projection; {@code status} {@code UNBILLED}, {@code DUE}, {@code PARTIALLY_PAID} or {@code PAID}, and the
     * {@code billing} once billed (`P11-TSK-017`) - the bill, which carries interest actually accrued.
     */
    public record InstalmentView(
            int sequence,
            String periodStart,
            String dueDate,
            long days,
            String openingPrincipal,
            String interest,
            String principal,
            String amount,
            String closingPrincipal,
            String status,
            BillingView billing) {}

    /**
     * A billed item (`P11-TSK-017`): what became due - interest actually accrued, the principal portion - what has been
     * paid of each, and what is still due; {@code status} {@code DUE}, {@code PARTIALLY_PAID} or {@code PAID}. Since
     * `P11-TSK-025`: the late fees assessed on the item (less those waived) and what has been paid of them, and the interest
     * waived - the outstanding includes the fees.
     */
    public record BillingView(
            String billingId,
            String kind,
            int sequence,
            String periodStart,
            String dueDate,
            String status,
            String interestBilled,
            String principalBilled,
            String interestPaid,
            String principalPaid,
            String outstanding,
            String feesBilled,
            String feesPaid,
            String interestWaived) {}

    /** A credit line's statements as its customer reads them (`P11-TSK-022`), oldest first. */
    public record StatementListView(String loanId, List<StatementView> statements) {}

    /**
     * One statement (`P11-TSK-022`; A23, A24): its cycle ({@code [cycleStart, statementDate)}), what it billed - the cycle's
     * accrued interest, the minimum payment's principal part, the fees it carries - the minimum payment and its due date,
     * what has been paid of it and what is still due ({@code status} {@code DUE}, {@code PARTIALLY_PAID} or {@code PAID}),
     * and the line's balance - every receivable less any credit balance - at the cycle's opening and close, derived from the
     * ledger, never stored. Amounts exact decimal strings, dates ISO. Since `P11-TSK-025`: the late fees assessed on this
     * statement (less those waived) - owed on it, beside the earlier fees it carries into its minimum - and the interest
     * waived of it.
     */
    public record StatementView(
            String statementId,
            String cycleStart,
            String statementDate,
            String dueDate,
            String status,
            String currency,
            String interestBilled,
            String principalBilled,
            String feesCarried,
            String minimumPayment,
            String paid,
            String outstanding,
            String openingBalance,
            String closingBalance,
            int agreementVersion,
            int statementEngineVersion,
            String feesBilled,
            String interestWaived) {}

    /**
     * Accepts the caller's offer: the agreement, the consumption, the commitment and the account in one transaction.
     */
    public LoanAcceptedView accept(
            Session current,
            String rawOfferId,
            String idempotencyKey,
            LoanOfferController.LoanAcceptanceBody body,
            Optional<String> clientIp,
            Optional<String> userAgent) {
        LoanOfferId id = offerId(rawOfferId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Optional<UUID> payoutMethod = payoutMethod(body.payoutMethodId());
        IdempotencyKey key = new IdempotencyKey(ACCEPTANCE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.offer-acceptance",
                        id.value().toString(), body.termsSha256(), payoutMethod.map(UUID::toString).orElse("-"))
                .getBytes(StandardCharsets.UTF_8));
        AcceptanceEvidence evidence = new AcceptanceEvidence(current.identityId().value(), current.id().value(),
                current.assurance().name(), clientIp, userAgent);
        LoanAcceptances.Acceptance acceptance =
                new LoanAcceptances.Acceptance(body.termsSha256(), payoutMethod, evidence);
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireAssurance(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(acceptances.accept(claimed, id, party, acceptance, actor, correlation))));
            });
        } catch (LoanOffers.OfferNotFound absent) {
            throw notFound("loan offer");
        } catch (LoanOffers.OfferNotAcceptable past) {
            metrics.record(past.product(), LoanAcceptanceMetrics.Outcome.NOT_ACCEPTABLE);
            throw new ApiException(LendingErrorCode.OFFER_NOT_ACCEPTABLE, "The offer can no longer be accepted",
                    past.getMessage() + ".");
        } catch (LoanAcceptances.TermsChanged changed) {
            metrics.record(changed.product(), LoanAcceptanceMetrics.Outcome.TERMS_CHANGED);
            throw new ApiException(LendingErrorCode.TERMS_CHANGED, "The terms accepted are not the offer's",
                    changed.getMessage() + "; read the offer and accept the terms it shows.");
        } catch (LoanAcceptances.PayoutDestinationInvalid invalid) {
            metrics.record(invalid.product(), LoanAcceptanceMetrics.Outcome.DESTINATION_INVALID);
            throw new ApiException(LendingErrorCode.PAYOUT_DESTINATION_INVALID, "The payout destination is not valid here",
                    invalid.getMessage() + ".");
        } catch (LoanAcceptances.CapitalUnavailable unavailable) {
            metrics.record(unavailable.product(), LoanAcceptanceMetrics.Outcome.CAPITAL_UNAVAILABLE);
            throw new ApiException(LendingErrorCode.CAPITAL_UNAVAILABLE, "The loan cannot be funded now",
                    unavailable.getMessage() + ".");
        } catch (LoanApplications.NotInGoodStanding notEligible) {
            throw new ApiException(CreditErrorCode.APPLICANT_NOT_ELIGIBLE, "The applicant is not eligible");
        }
        LoanAcceptedView accepted =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("an acceptance stores its receipt")));
        if (outcome.executed()) {
            metrics.record(LoanProduct.valueOf(accepted.product()), LoanAcceptanceMetrics.Outcome.ACCEPTED);
        }
        return accepted;
    }

    /** The caller's own loan; another party's is the same {@code 404} as an unknown or malformed id. */
    public LoanView read(Session current, String rawId) {
        LoanId id = loanId(rawId);
        return transactions.inSnapshot(unitOfWork -> loans.read(unitOfWork, id, partyOf(unitOfWork, current))
                        .map(LoanAccountDesk::view))
                .orElseThrow(() -> notFound("loan"));
    }

    /** The caller's loans, newest first. */
    public LoanListView list(Session current) {
        return new LoanListView(transactions.inSnapshot(unitOfWork ->
                loans.list(unitOfWork, partyOf(unitOfWork, current)).stream().map(LoanAccountDesk::view).toList()));
    }

    /**
     * The caller's own loan's schedule version {@code version} - its latest when absent; another party's loan, a loan not
     * yet disbursed and a version that does not exist are the same {@code 404} as an unknown or malformed id.
     */
    public ScheduleView schedule(Session current, String rawId, Optional<Integer> version) {
        LoanId id = loanId(rawId);
        return transactions.inSnapshot(unitOfWork -> schedules.read(unitOfWork, id, partyOf(unitOfWork, current), version)
                        .map(LoanAccountDesk::view))
                .orElseThrow(() -> notFound("loan schedule"));
    }

    /**
     * The caller's own credit line's statements, oldest first (`P11-TSK-022`); another party's loan, an instalment loan and an
     * unknown or malformed id are the same {@code 404}. A line not yet stated answers an empty list.
     */
    public StatementListView statements(Session current, String rawId) {
        LoanId id = loanId(rawId);
        return transactions.inSnapshot(unitOfWork -> statements.read(unitOfWork, id, partyOf(unitOfWork, current))
                        .map(reads -> new StatementListView(id.value().toString(),
                                reads.stream().map(LoanAccountDesk::view).toList())))
                .orElseThrow(() -> notFound("credit line"));
    }

    // ------------------------------------------------------------------ the views

    private static StatementView view(CreditLineStatements.StatementRead read) {
        CreditLineStatementStore.StatedItem item = read.item();
        var statement = item.statement();
        var figures = statement.statement();
        Money outstanding = item.outstanding();
        String status = outstanding.isZero() ? "PAID" : item.paid().isZero() ? "DUE" : "PARTIALLY_PAID";
        return new StatementView(
                statement.id().toString(),
                figures.cycle().start().toString(),
                figures.statementDate().toString(),
                figures.dueDate().toString(),
                status,
                figures.interestBilled().currency().code(),
                decimal(figures.interestBilled()),
                decimal(figures.principalBilled()),
                decimal(figures.feesCarried()),
                decimal(figures.minimumPayment()),
                decimal(item.paid()),
                decimal(outstanding),
                decimal(read.openingBalance()),
                decimal(read.closingBalance()),
                statement.agreementVersion(),
                figures.engine().number(),
                decimal(item.feesBilled()),
                decimal(item.interestWaived()));
    }

    static ScheduleView view(LoanSchedules.Read read) {
        RepaymentSchedule recorded = read.schedule();
        Schedule schedule = recorded.schedule();
        Map<Integer, InstalmentBillingStore.BilledItem> billedHere = new HashMap<>();
        List<BillingView> postMaturity = new ArrayList<>();
        for (InstalmentBillingStore.BilledItem item : read.billed()) {
            if (item.billing().kind() == BillingKind.POST_MATURITY) {
                postMaturity.add(view(item));
            } else if (item.billing().scheduleVersion() == recorded.version()) {
                billedHere.put(item.billing().sequence(), item);
            }
        }
        return new ScheduleView(
                recorded.loan().value().toString(),
                recorded.version(),
                recorded.agreementVersion(),
                schedule.engine().number(),
                schedule.currency().code(),
                decimal(schedule.principal()),
                decimal(schedule.instalment()),
                decimal(schedule.finalInstalment()),
                decimal(schedule.totalInterest()),
                decimal(schedule.totalPayable()),
                schedule.start().toString(),
                schedule.termMonths(),
                schedule.periods().stream()
                        .map(period -> view(period, Optional.ofNullable(billedHere.get(period.sequence()))))
                        .toList(),
                postMaturity);
    }

    private static InstalmentView view(SchedulePeriod period, Optional<InstalmentBillingStore.BilledItem> billed) {
        Optional<BillingView> billing = billed.map(LoanAccountDesk::view);
        return new InstalmentView(
                period.sequence(),
                period.periodStart().toString(),
                period.dueDate().toString(),
                period.days(),
                decimal(period.openingBalance()),
                decimal(period.interest()),
                decimal(period.principal()),
                decimal(period.amount()),
                decimal(period.closingBalance()),
                billing.map(BillingView::status).orElse("UNBILLED"),
                billing.orElse(null));
    }

    private static BillingView view(InstalmentBillingStore.BilledItem item) {
        InstalmentBilling billing = item.billing();
        Money outstanding = item.outstanding();
        String status = outstanding.isZero() ? "PAID" : item.paid().isZero() ? "DUE" : "PARTIALLY_PAID";
        return new BillingView(
                billing.id().toString(),
                billing.kind().name(),
                billing.sequence(),
                billing.period().start().toString(),
                billing.dueDate().toString(),
                status,
                decimal(billing.interestDue()),
                decimal(billing.principalDue()),
                decimal(item.interestPaid()),
                decimal(item.principalPaid()),
                decimal(outstanding),
                decimal(item.feesBilled()),
                decimal(item.feesPaid()),
                decimal(item.interestWaived()));
    }

    static LoanView view(Loans.Read read) {
        LoanAgreement agreement = read.owned().agreement();
        var loan = read.owned().loan();
        Loans.Balances balances = read.balances();
        boolean instalment = loan.kind() == LoanKind.INSTALMENT;
        return new LoanView(
                loan.id().value().toString(),
                loan.offer().value().toString(),
                loan.product().name(),
                loan.kind().name(),
                loan.status().name(),
                loan.currency().code(),
                loan.openedAt().toString(),
                loan.closedAt().map(Instant::toString).orElse(null),
                new AgreementView(
                        agreement.id().value().toString(),
                        agreement.version(),
                        instalment ? decimal(agreement.amount()) : null,
                        instalment ? null : decimal(agreement.amount()),
                        agreement.termMonths().orElse(null),
                        agreement.nominalAnnualRate().toPlainString(),
                        agreement.repaymentDay(),
                        agreement.template().id(),
                        agreement.template().version(),
                        agreement.termsSha256Hex(),
                        agreement.effectiveFrom().toString()),
                new BalancesView(
                        decimal(balances.principalNotDue()),
                        decimal(balances.principalDue()),
                        decimal(balances.principalOutstanding()),
                        decimal(balances.interestAccrued()),
                        decimal(balances.interestDue()),
                        decimal(balances.feesDue()),
                        decimal(balances.creditBalance())),
                read.payout().map(Enum::name).orElse(null),
                read.availableLimit().map(LoanAccountDesk::decimal).orElse(null),
                read.condition().map(condition -> new ConditionView(
                        condition.pastDue() ? "PAST_DUE" : "CURRENT",
                        condition.daysPastDue(),
                        condition.bucket().map(Enum::name).orElse(null),
                        condition.defaulted(),
                        read.drawsSuspended().orElse(null))).orElse(null));
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    /** An acceptance's recorded receipt. */
    private static StoredResponse stored(LoanAcceptances.Accepted accepted) {
        String body = String.join("|", "OK", accepted.loan().id().value().toString(),
                accepted.loan().offer().value().toString(), accepted.loan().product().name(),
                accepted.loan().kind().name(), accepted.loan().status().name(),
                accepted.agreement().id().value().toString(), Integer.toString(accepted.agreement().version()),
                accepted.agreement().termsSha256Hex(), accepted.acceptedAt().toString());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static LoanAcceptedView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new LoanAcceptedView(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                Integer.parseInt(fields[7]), fields[8], fields[9]);
    }

    // ------------------------------------------------------------------ plumbing

    /** An acceptance's assurance (A19): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private static void requireAssurance(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A loan offer acceptance requires a MULTI_FACTOR session");
        }
    }

    /** The optional payment method, as an identifier - a malformed one is simply not a verifiable destination. */
    private static Optional<UUID> payoutMethod(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(LendingErrorCode.PAYOUT_DESTINATION_INVALID, "The payout destination is not valid here",
                    "the payout method is not an identifier.");
        }
    }

    private static LoanOfferId offerId(String raw) {
        try {
            return LoanOfferId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound("loan offer");
        }
    }

    private static LoanId loanId(String raw) {
        try {
            return LoanId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound("loan");
        }
    }

    private static ApiException notFound(String what) {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No " + what + " matches the requested identifier",
                "no such " + what + ".");
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a loan command runs inside a correlation scope"))
                .correlationId();
    }
}
