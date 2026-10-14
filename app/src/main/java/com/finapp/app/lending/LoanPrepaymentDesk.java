package com.finapp.app.lending;

import com.finapp.app.api.DecimalText;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.lending.AllocationAnomaly;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanId;
import com.finapp.lending.Prepayments;
import com.finapp.lending.RepaymentKind;
import com.finapp.lending.RepaymentStore;
import com.finapp.lending.Repayments;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.TransactionSystemException;

/**
 * The boundary half of the prepayment door (`P11-TSK-028`; {@code PHASE_11_PLAN.md} sections 9, 11 and 12.5, A19, ADR-0099
 * point 6): {@code POST /v1/me/loans/{id}/prepayments}, keyed per principal ({@code lending.prepayment:CUSTOMER:<id>}), run
 * in one transaction under the {@link IdempotentExecutor} claim - a retried key replays the recorded receipt, and a
 * refusal, which writes nothing, rolls the claim back with it.
 *
 * <p><strong>A {@code MULTI_FACTOR} session, always</strong> (A19's step-up): a prepayment moves the wallet's money early
 * AND makes agreement version n+1 on the customer's word alone - a payoff's sibling (a partial payoff) and an
 * acceptance's (a new version of the contract), both of which require it - so, unlike a repayment, it is not the
 * platform's conditional step-up. <strong>No standing check</strong> (a suspended customer may still repay) and <strong>no
 * operator can prepay</strong>: the door is {@code /v1/me}, the loan the session's own party's (owner-scoped in the lock's
 * statement). The customer names the amount and its currency; the loan's own currency and scale are the domain's to judge.
 */
@RequiredArgsConstructor
public final class LoanPrepaymentDesk {

    static final String PREPAYMENT_SCOPE = "lending.prepayment:";

    @NonNull private final Prepayments prepayments;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final RepaymentMetrics metrics;

    /**
     * What a prepayment answers - and replays: the repayment (its view, kind {@code PREPAYMENT}, one
     * {@code NOT_DUE_PRINCIPAL} line) and the re-schedule it made - agreement and schedule versions, the remaining
     * instalments, the level instalment (unchanged) and the final one, the next due date. Amounts are exact decimal
     * strings, dates ISO.
     */
    public record PrepaymentView(
            LoanRepaymentDesk.RepaymentView repayment,
            int agreementVersion,
            int scheduleVersion,
            int remainingInstalments,
            String instalment,
            String finalInstalment,
            String nextDueDate) {}

    /** Prepays the caller's own loan from their wallet: the posting and the re-schedule in one transaction. */
    public PrepaymentView prepay(Session current, String rawLoanId, String idempotencyKey,
            LoanController.LoanRepaymentBody body) {
        LoanId id = loanId(rawLoanId);
        Money amount = amount(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(PREPAYMENT_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.prepayment",
                        id.value().toString(), Long.toString(amount.minorUnits()), Integer.toString(amount.scale()),
                        amount.currency().code())
                .getBytes(StandardCharsets.UTF_8));
        Prepayments.Request request = new Prepayments.Request(idempotencyKey, amount);
        AtomicBoolean converged = new AtomicBoolean();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireMultiFactor(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    Prepayments.Prepaid prepaid = prepayments.prepay(claimed, id, party, request, actor, correlation);
                    converged.set(prepaid.replayed());
                    return CommandResult.succeeded(stored(view(prepaid)));
                });
            });
        } catch (Repayments.LoanNotFound absent) {
            throw notFound();
        } catch (Repayments.RepaymentKeyReused reused) {
            throw new ApiException(PlatformErrorCode.CONFLICT, "The key was already used",
                    "This " + IdempotencyKeyHeader.NAME + " was already used for a different request. Use a new key for"
                            + " a new action, or resend the original request unchanged.");
        } catch (Repayments.AmountInvalid invalid) {
            throw invalid("the amount must be a positive amount in the loan's currency, at its minor units.");
        } catch (Repayments.LoanNotRepayable notRepayable) {
            metrics.record(RepaymentKind.PREPAYMENT, RepaymentMetrics.Outcome.NOT_REPAYABLE);
            throw new ApiException(LendingErrorCode.LOAN_NOT_REPAYABLE, "The loan cannot be prepaid",
                    "only an active, scheduled instalment loan is prepaid - a credit line's repayment already pays its"
                            + " principal down; nothing was posted.");
        } catch (Prepayments.ArrearsFirst arrears) {
            metrics.record(RepaymentKind.PREPAYMENT, RepaymentMetrics.Outcome.ARREARS_FIRST);
            throw new ApiException(LendingErrorCode.ARREARS_FIRST, "Something billed is still owing",
                    "repay what is due first, then prepay; nothing was posted.");
        } catch (Prepayments.PrepaymentExceedsPrincipal exceeds) {
            metrics.record(RepaymentKind.PREPAYMENT, RepaymentMetrics.Outcome.EXCEEDS_PRINCIPAL);
            throw new ApiException(LendingErrorCode.PREPAYMENT_EXCEEDS_PRINCIPAL, "The prepayment is not partial",
                    "a prepayment is less than the principal not yet due; the payoff settles the whole. Nothing was"
                            + " posted.");
        } catch (Repayments.InsufficientFunds insufficient) {
            metrics.record(RepaymentKind.PREPAYMENT, RepaymentMetrics.Outcome.INSUFFICIENT_FUNDS);
            throw new ApiException(LendingErrorCode.INSUFFICIENT_FUNDS, "The prepayment cannot be funded",
                    "the wallet's available balance cannot fund this prepayment now; nothing was posted.");
        } catch (AllocationAnomaly anomaly) {
            metrics.anomaly();
            throw anomaly;
        } catch (TransactionSystemException atCommit) {
            // The database's conservation rules are judged at commit (lending V014's and V024's deferred checks): a
            // refusal there is a refused conservation the domain did not catch - the same defect, counted the same way.
            if (refusedConservation(atCommit)) {
                metrics.anomaly();
            }
            throw atCommit;
        }
        PrepaymentView prepaid =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a prepayment stores its receipt")));
        if (outcome.executed() && !converged.get()) {
            metrics.record(RepaymentKind.PREPAYMENT, RepaymentMetrics.Outcome.PREPAID);
        }
        return prepaid;
    }

    // ------------------------------------------------------------------ the view and the receipt

    private static PrepaymentView view(Prepayments.Prepaid prepaid) {
        RepaymentStore.Rescheduled rescheduled = prepaid.rescheduled();
        return new PrepaymentView(LoanRepaymentDesk.view(prepaid.repayment(), Optional.empty(), false),
                rescheduled.agreementVersion(), rescheduled.scheduleVersion(), rescheduled.termMonths(),
                rescheduled.instalment().toBigDecimal().toPlainString(),
                rescheduled.finalInstalment().toBigDecimal().toPlainString(), rescheduled.firstDueDate().toString());
    }

    /** The receipt: the repayment's own receipt, then '#' and the re-schedule's fields, '|'-separated. */
    private static StoredResponse stored(PrepaymentView view) {
        String body = new String(LoanRepaymentDesk.stored(view.repayment()).body(), StandardCharsets.UTF_8) + "#"
                + String.join("|", Integer.toString(view.agreementVersion()), Integer.toString(view.scheduleVersion()),
                        Integer.toString(view.remainingInstalments()), view.instalment(), view.finalInstalment(),
                        view.nextDueDate());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static PrepaymentView replayed(byte[] body) {
        String[] halves = new String(body, StandardCharsets.UTF_8).split("#", -1);
        String[] fields = halves[1].split("\\|", -1);
        return new PrepaymentView(LoanRepaymentDesk.replayed(halves[0].getBytes(StandardCharsets.UTF_8)),
                Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), fields[3], fields[4],
                fields[5]);
    }

    // ------------------------------------------------------------------ plumbing

    /** A prepayment's assurance (A19): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private static void requireMultiFactor(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, "A prepayment requires a MULTI_FACTOR session");
        }
    }

    private static boolean refusedConservation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("INV-LND-04") || message.contains("INV-LND-03"))) {
                return true;
            }
        }
        return false;
    }

    /** The body's amount in its named currency, exact at the currency's minor units - the loan's are the domain's. */
    private static Money amount(LoanController.LoanRepaymentBody body) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(body.currency());
        } catch (RuntimeException unknown) {
            throw invalid("the currency is not one this platform supports.");
        }
        try {
            // The shape first: an exponent never reaches Money.of's rescale.
            return Money.of(DecimalText.parse(body.amount()), currency);
        } catch (RuntimeException malformed) {
            throw invalid("the amount must be an exact decimal at the currency's minor units.");
        }
    }

    private static LoanId loanId(String raw) {
        try {
            return LoanId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan matches the requested identifier", "no such loan.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The prepayment was not valid", detail);
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
