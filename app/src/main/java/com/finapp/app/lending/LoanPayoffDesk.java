package com.finapp.app.lending;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.lending.AllocationAnomaly;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanId;
import com.finapp.lending.PayoffQuote;
import com.finapp.lending.Payoffs;
import com.finapp.lending.RepaymentKind;
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
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.TransactionSystemException;

/**
 * The boundary half of the payoff doors (`P11-TSK-026`; {@code PHASE_11_PLAN.md} sections 9, 11 and 12.5, A19, ADR-0095
 * point 8): {@code POST /v1/me/loans/{id}/payoff-quotes}, keyed per principal ({@code lending.payoff-quote:CUSTOMER:<id>}),
 * and {@code POST /v1/me/loans/{id}/payoff}, keyed per principal ({@code lending.payoff:CUSTOMER:<id>}) - each run in one
 * transaction under the {@link IdempotentExecutor} claim: a retried key replays the recorded receipt, and a refusal rolls
 * the claim back with everything the transaction wrote.
 *
 * <p><strong>The quote follows the platform's conditional step-up</strong> (A19): it moves no money, but its catch-up
 * accrual posts - the repayment door's rule. <strong>The execution requires a {@code MULTI_FACTOR} session, always</strong>
 * (A19): it moves the wallet's money and ends the account for good. <strong>No operator can quote or pay off a customer's
 * account</strong>: the doors are {@code /v1/me}, the account the session's own party's (owner-scoped in the lock's
 * statement). <strong>No audit record</strong>: the customer's own act is its rows and entries.
 */
@RequiredArgsConstructor
public final class LoanPayoffDesk {

    static final String QUOTE_SCOPE = "lending.payoff-quote:";
    static final String PAYOFF_SCOPE = "lending.payoff:";

    @NonNull private final Payoffs payoffs;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final PayoffMetrics metrics;
    @NonNull private final RepaymentMetrics repaymentMetrics;

    /**
     * What a quote answers - and replays. Amounts are exact decimal strings in {@code currency}, dates ISO: {@code amount}
     * what the payoff takes from the wallet on {@code goodThrough}, {@code refund} what it gives back when the credit
     * balance exceeds everything owed (one of the two is zero), then what they are made of - the receivables and the credit
     * balance on {@code quotedOn} and the interest projected for {@code projectedDays} dates to {@code goodThrough}.
     */
    public record PayoffQuoteView(
            String quoteId,
            String loanId,
            String quotedOn,
            String goodThrough,
            String amount,
            String refund,
            String currency,
            String principal,
            String principalDue,
            String interestAccrued,
            String interestDue,
            String feesDue,
            String creditBalance,
            String projectedInterest,
            int projectedDays,
            int agreementVersion,
            String createdAt) {}

    /**
     * What a payoff answers - and replays: the execution, its quote, the payoff's one repayment ({@code repaymentId} and
     * {@code paid}; none and {@code 0} when the credit balance paid everything) and the closure - its reason and the credit
     * balance refunded. {@code paid - refunded} is the quote's amount less its refund.
     */
    public record PayoffView(
            String executionId,
            String quoteId,
            String loanId,
            String businessDate,
            String repaymentId,
            String paid,
            String refunded,
            String currency,
            String closureReason,
            String executedAt) {}

    // ------------------------------------------------------------------ the quote

    /** Quotes the payoff of the caller's own account: its catch-up accrual and the quote in one transaction. */
    public PayoffQuoteView quote(Session current, String rawLoanId, String idempotencyKey,
            LoanController.PayoffQuoteBody body) {
        LoanId id = loanId(rawLoanId);
        Optional<LocalDate> goodThrough = goodThrough(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(QUOTE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.payoff-quote",
                id.value().toString(), goodThrough.map(LocalDate::toString).orElse("")).getBytes(StandardCharsets.UTF_8));
        Payoffs.QuoteRequest request = new Payoffs.QuoteRequest(idempotencyKey, goodThrough);
        AtomicBoolean converged = new AtomicBoolean();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    Payoffs.Quoted quoted = payoffs.quote(claimed, id, party, request, correlation);
                    converged.set(quoted.replayed());
                    return CommandResult.succeeded(stored(view(quoted.quote())));
                });
            });
        } catch (Repayments.LoanNotFound absent) {
            throw notFound();
        } catch (Payoffs.QuoteKeyReused reused) {
            throw keyReused();
        } catch (Repayments.LoanNotRepayable notRepayable) {
            metrics.record(PayoffMetrics.Outcome.NOT_REPAYABLE);
            throw new ApiException(LendingErrorCode.LOAN_NOT_REPAYABLE, "The account cannot be paid off",
                    "the account is closed, cancelled, not yet disbursed or awaiting its payout; nothing was done.");
        } catch (Payoffs.GoodThroughInvalid invalid) {
            throw invalid("the good-through date must be today or up to " + invalid.horizonDays() + " days ahead.");
        }
        PayoffQuoteView quoted =
                replayedQuote(outcome.body().orElseThrow(() -> new IllegalStateException("a quote stores its receipt")));
        if (outcome.executed() && !converged.get()) {
            metrics.record(PayoffMetrics.Outcome.QUOTED);
        }
        return quoted;
    }

    // ------------------------------------------------------------------ the payoff

    /** Pays off the caller's own account on its quote: accrual, billing, repayment, refund and closure in one transaction. */
    public PayoffView execute(Session current, String rawLoanId, String idempotencyKey, LoanController.PayoffBody body) {
        LoanId id = loanId(rawLoanId);
        UUID quoteId = quoteId(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(PAYOFF_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.payoff",
                id.value().toString(), quoteId.toString()).getBytes(StandardCharsets.UTF_8));
        AtomicBoolean converged = new AtomicBoolean();
        AtomicBoolean repaid = new AtomicBoolean();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireMultiFactor(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    Payoffs.Executed executed =
                            payoffs.execute(claimed, id, party, idempotencyKey, quoteId, actor, correlation);
                    converged.set(executed.replayed());
                    repaid.set(executed.repayment().isPresent());
                    return CommandResult.succeeded(stored(view(executed)));
                });
            });
        } catch (Repayments.LoanNotFound | Payoffs.QuoteNotFound absent) {
            throw notFound();
        } catch (Repayments.LoanNotRepayable notRepayable) {
            metrics.record(PayoffMetrics.Outcome.NOT_REPAYABLE);
            throw new ApiException(LendingErrorCode.LOAN_NOT_REPAYABLE, "The account cannot be paid off",
                    "the account is closed or cancelled; nothing was posted.");
        } catch (Payoffs.PayoffQuoteStale stale) {
            metrics.record(PayoffMetrics.Outcome.STALE);
            throw new ApiException(LendingErrorCode.PAYOFF_QUOTE_STALE, "The payoff quote is stale",
                    stale.reason() == Payoffs.PayoffQuoteStale.Reason.NOT_ITS_DATE
                            ? "a quote is executed only on its good-through date; nothing was posted."
                            : "what the account owes changed since the quote was made; nothing was posted - request a"
                                    + " new quote.");
        } catch (Repayments.InsufficientFunds insufficient) {
            metrics.record(PayoffMetrics.Outcome.INSUFFICIENT_FUNDS);
            throw new ApiException(LendingErrorCode.INSUFFICIENT_FUNDS, "The payoff cannot be funded",
                    "the wallet's available balance cannot fund this payoff now; nothing was posted.");
        } catch (AllocationAnomaly anomaly) {
            repaymentMetrics.anomaly();
            throw anomaly;
        } catch (TransactionSystemException atCommit) {
            // The database's conservation rules are judged at commit (lending V014's deferred checks): the same defect.
            if (refusedConservation(atCommit)) {
                repaymentMetrics.anomaly();
            }
            throw atCommit;
        }
        PayoffView paidOff =
                replayedPayoff(outcome.body().orElseThrow(() -> new IllegalStateException("a payoff stores its receipt")));
        if (outcome.executed() && !converged.get()) {
            metrics.record(PayoffMetrics.Outcome.PAID_OFF);
            if (repaid.get()) {
                repaymentMetrics.record(RepaymentKind.PAYOFF, RepaymentMetrics.Outcome.CLOSED);
            }
        }
        return paidOff;
    }

    // ------------------------------------------------------------------ the views and the receipts

    private static PayoffQuoteView view(PayoffQuote quote) {
        return new PayoffQuoteView(quote.id().toString(), quote.loan().value().toString(),
                quote.businessDate().toString(), quote.goodThrough().toString(), text(quote.amount()),
                text(quote.refund()), quote.outstanding().currency().code(), text(quote.principal()),
                text(quote.principalDue()), text(quote.interestAccrued()), text(quote.interestDue()),
                text(quote.feesDue()), text(quote.creditBalance()), text(quote.projectedInterest()),
                quote.projectedDays(), quote.agreementVersion(), quote.createdAt().toString());
    }

    private static PayoffView view(Payoffs.Executed executed) {
        Money refunded = executed.closure().refund();
        Money paid = executed.repayment().map(repayment -> repayment.amount()).orElse(refunded.minus(refunded));
        return new PayoffView(executed.execution().id().toString(), executed.quote().id().toString(),
                executed.execution().loan().value().toString(), executed.execution().businessDate().toString(),
                executed.repayment().map(repayment -> repayment.id().toString()).orElse(null), text(paid),
                text(refunded), refunded.currency().code(), executed.closure().reason().name(),
                executed.execution().executedAt().toString());
    }

    private static String text(Money money) {
        return money.toBigDecimal().toPlainString();
    }

    private static StoredResponse stored(PayoffQuoteView view) {
        String body = String.join("|", "OK", view.quoteId(), view.loanId(), view.quotedOn(), view.goodThrough(),
                view.amount(), view.refund(), view.currency(), view.principal(), view.principalDue(),
                view.interestAccrued(), view.interestDue(), view.feesDue(), view.creditBalance(), view.projectedInterest(),
                Integer.toString(view.projectedDays()), Integer.toString(view.agreementVersion()), view.createdAt());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static PayoffQuoteView replayedQuote(byte[] body) {
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new PayoffQuoteView(f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11], f[12], f[13],
                f[14], Integer.parseInt(f[15]), Integer.parseInt(f[16]), f[17]);
    }

    private static StoredResponse stored(PayoffView view) {
        String body = String.join("|", "OK", view.executionId(), view.quoteId(), view.loanId(), view.businessDate(),
                view.repaymentId() == null ? "" : view.repaymentId(), view.paid(), view.refunded(), view.currency(),
                view.closureReason(), view.executedAt());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static PayoffView replayedPayoff(byte[] body) {
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new PayoffView(f[1], f[2], f[3], f[4], f[5].isEmpty() ? null : f[5], f[6], f[7], f[8], f[9], f[10]);
    }

    // ------------------------------------------------------------------ plumbing

    /** The platform's conditional step-up (A19): an enrolled customer quotes from a {@code MULTI_FACTOR} session. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A payoff quote from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    /** A payoff's assurance (A19): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private static void requireMultiFactor(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, "A payoff requires a MULTI_FACTOR session");
        }
    }

    private static boolean refusedConservation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("INV-LND-04")) {
                return true;
            }
        }
        return false;
    }

    private static Optional<LocalDate> goodThrough(LoanController.PayoffQuoteBody body) {
        if (body.goodThrough() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(body.goodThrough()));
        } catch (DateTimeParseException malformed) {
            throw invalid("the good-through date must be an ISO date (YYYY-MM-DD).");
        }
    }

    private static UUID quoteId(LoanController.PayoffBody body) {
        try {
            return UUID.fromString(body.quoteId());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(LendingErrorCode.NOT_FOUND, "No payoff quote matches the requested identifier",
                    "no such quote.");
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
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan or quote matches the requested identifier",
                "no such loan or quote.");
    }

    private static ApiException keyReused() {
        return new ApiException(PlatformErrorCode.CONFLICT, "The key was already used",
                "This " + IdempotencyKeyHeader.NAME + " was already used for a different request. Use a new key for a new"
                        + " action, or resend the original request unchanged.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The payoff quote was not valid", detail);
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
