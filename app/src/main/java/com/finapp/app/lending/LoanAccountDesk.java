package com.finapp.app.lending;

import com.finapp.credit.CreditErrorCode;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.lending.AcceptanceEvidence;
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
import java.util.List;
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
            String payoutStatus) {}

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
     * engine from the accrual start - amounts exact decimal strings, dates ISO. The projection, not the bill: billed and
     * paid status arrive with billing and repayment (`P11-TSK-017`, `-018`).
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
            List<InstalmentView> instalments) {}

    /** One instalment period: its interest runs over {@code [periodStart, dueDate)} on the opening principal. */
    public record InstalmentView(
            int sequence,
            String periodStart,
            String dueDate,
            long days,
            String openingPrincipal,
            String interest,
            String principal,
            String amount,
            String closingPrincipal) {}

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

    // ------------------------------------------------------------------ the views

    static ScheduleView view(RepaymentSchedule recorded) {
        Schedule schedule = recorded.schedule();
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
                schedule.periods().stream().map(LoanAccountDesk::view).toList());
    }

    private static InstalmentView view(SchedulePeriod period) {
        return new InstalmentView(
                period.sequence(),
                period.periodStart().toString(),
                period.dueDate().toString(),
                period.days(),
                decimal(period.openingBalance()),
                decimal(period.interest()),
                decimal(period.principal()),
                decimal(period.amount()),
                decimal(period.closingBalance()));
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
                read.payout().map(Enum::name).orElse(null));
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
