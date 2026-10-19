package com.finapp.app.bnpl;

import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplPayoffQuote;
import com.finapp.bnpl.BnplPayoffs;
import com.finapp.bnpl.BnplRepayments;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.TransactionSystemException;

/**
 * The boundary half of the BNPL payoff doors (`P12-TSK-022`; {@code PHASE_12_PLAN.md} sections 9 and 11, A18, A21;
 * lending's {@code LoanPayoffDesk}): {@code POST /v1/me/bnpl-agreements/{id}/payoff-quotes}, keyed per principal
 * ({@code bnpl.payoff-quote:CUSTOMER:<id>}), and {@code POST /v1/me/bnpl-agreements/{id}/payoff}, keyed per principal
 * ({@code bnpl.payoff:CUSTOMER:<id>}) - each run in one transaction under the {@link IdempotentExecutor} claim: a retried
 * key replays the recorded receipt ({@code INV-IDEM-01}: the database committed and the response was lost - the retry
 * answers the same quote or payoff), and a refusal rolls the claim back with everything the transaction wrote.
 *
 * <p><strong>Pay-in-N is accrued first, never inside</strong>: a quote or payoff on a record behind its accrual writes
 * nothing ({@link BnplPayoffs.BehindAccrual}); the desk runs T-acc in its own transaction ({@link BnplAccruals#accrue}, up
 * to {@link #ACCRUAL_ROUNDS} times) and asks again - so neither ever holds the currency's interest income row (the Phase 11
 * gate's A2-F1). <strong>The quote follows the platform's conditional step-up</strong> (A18): it moves no money.
 * <strong>The execution requires a {@code MULTI_FACTOR} session, always</strong> (A18): it moves the wallet's money and
 * ends the agreement for good. <strong>No operator can quote or pay off a customer's agreement</strong>: the doors are
 * {@code /v1/me}, the agreement the session's own party's (owner-scoped in the lock's statement). <strong>No audit
 * record</strong>: the customer's own act is its rows and entries. The meters are counted after the transaction - a replay
 * counts nothing.
 */
@RequiredArgsConstructor
public final class BnplPayoffDesk {

    static final String QUOTE_SCOPE = "bnpl.payoff-quote:";
    static final String PAYOFF_SCOPE = "bnpl.payoff:";

    /** The most T-acc catch-ups one request runs before it answers - each accrues up to 92 dates. */
    static final int ACCRUAL_ROUNDS = 8;

    @NonNull private final BnplPayoffs payoffs;
    @NonNull private final BnplAccruals accruals;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplPayoffMetrics metrics;
    @NonNull private final BnplRepaymentMetrics repaymentMetrics;

    /**
     * What a quote answers - and replays. Amounts are exact decimal strings in {@code currency}, dates ISO: {@code amount}
     * what the payoff takes from the wallet on {@code goodThrough}, {@code refund} what it gives back when the credit balance
     * exceeds everything owed (one of the two is zero), then what they are made of - the receivables and the credit balance
     * on {@code quotedOn} and the interest projected for {@code projectedDays} dates to {@code goodThrough}.
     */
    public record BnplPayoffQuoteView(
            String quoteId,
            String agreementId,
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
     * What a payoff answers - and replays: the execution, its quote, its date and the date every fact of it takes, the
     * payoff's one repayment ({@code repaymentId} and {@code paid}; none and {@code 0} when the credit balance paid
     * everything) and the closure - its reason and the credit balance refunded.
     */
    public record BnplPayoffView(
            String executionId,
            String quoteId,
            String agreementId,
            String businessDate,
            String valueDate,
            String repaymentId,
            String paid,
            String refunded,
            String currency,
            String closureReason,
            String executedAt) {}

    // ------------------------------------------------------------------ the quote

    /** Quotes the payoff of the caller's own agreement - T-acc first when the record is behind - in one transaction. */
    public BnplPayoffQuoteView quote(Session current, String rawAgreementId, String idempotencyKey,
            BnplPayoffController.BnplPayoffQuoteBody body) {
        BnplAgreementId id = agreementId(rawAgreementId);
        Optional<LocalDate> goodThrough = goodThrough(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(QUOTE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "bnpl.payoff-quote",
                id.value().toString(), goodThrough.map(LocalDate::toString).orElse("")).getBytes(StandardCharsets.UTF_8));
        BnplPayoffs.QuoteRequest request = new BnplPayoffs.QuoteRequest(idempotencyKey, goodThrough);
        AtomicReference<BnplPayoffs.Quoted> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = caughtUp(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    BnplPayoffs.Quoted quoted = payoffs.quote(claimed, id, party, request, correlation);
                    acted.set(quoted);
                    return CommandResult.succeeded(stored(view(quoted.quote())));
                });
            });
        } catch (BnplRepayments.AgreementNotFound absent) {
            throw notFound();
        } catch (BnplPayoffs.QuoteKeyReused reused) {
            throw keyReused();
        } catch (BnplRepayments.ObligationClosed closed) {
            metrics.record(closed.product(), BnplPayoffMetrics.Outcome.OBLIGATION_CLOSED);
            throw new ApiException(BnplErrorCode.OBLIGATION_CLOSED, "The BNPL agreement is closed",
                    "the agreement is closed; there is nothing to pay off.");
        } catch (BnplPayoffs.GoodThroughInvalid invalid) {
            throw invalid("the good-through date must be today or up to " + invalid.horizonDays() + " days ahead.");
        } catch (AllocationAnomaly anomaly) {
            repaymentMetrics.anomaly();
            throw anomaly;
        }
        BnplPayoffQuoteView quoted =
                replayedQuote(outcome.body().orElseThrow(() -> new IllegalStateException("a quote stores its receipt")));
        BnplPayoffs.Quoted done = acted.get();
        if (outcome.executed() && done != null && !done.replayed()) {
            metrics.record(done.quote().product(), BnplPayoffMetrics.Outcome.QUOTED);
        }
        return quoted;
    }

    // ------------------------------------------------------------------ the payoff

    /** Pays off the caller's own agreement on its quote: billing, repayment, refund and closure in one transaction. */
    public BnplPayoffView execute(Session current, String rawAgreementId, String idempotencyKey,
            BnplPayoffController.BnplPayoffBody body) {
        BnplAgreementId id = agreementId(rawAgreementId);
        UUID quoteId = quoteId(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(PAYOFF_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "bnpl.payoff",
                id.value().toString(), quoteId.toString()).getBytes(StandardCharsets.UTF_8));
        AtomicReference<BnplPayoffs.Executed> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = caughtUp(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireMultiFactor(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    BnplPayoffs.Executed executed =
                            payoffs.execute(claimed, id, party, idempotencyKey, quoteId, actor, correlation);
                    acted.set(executed);
                    return CommandResult.succeeded(stored(view(executed)));
                });
            });
        } catch (BnplRepayments.AgreementNotFound | BnplPayoffs.QuoteNotFound absent) {
            throw notFound();
        } catch (BnplRepayments.ObligationClosed closed) {
            metrics.record(closed.product(), BnplPayoffMetrics.Outcome.OBLIGATION_CLOSED);
            throw new ApiException(BnplErrorCode.OBLIGATION_CLOSED, "The BNPL agreement is closed",
                    "the agreement is closed - paid off by another quote included; nothing was posted.");
        } catch (BnplPayoffs.PayoffQuoteStale stale) {
            metrics.record(stale.product(), BnplPayoffMetrics.Outcome.STALE);
            throw new ApiException(BnplErrorCode.PAYOFF_QUOTE_STALE, "The payoff quote is stale",
                    stale.reason() == BnplPayoffs.PayoffQuoteStale.Reason.NOT_ITS_DATE
                            ? "a quote is executed only on its good-through date; nothing was posted."
                            : "what the agreement owes changed since the quote was made; nothing was posted - request"
                                    + " a new quote.");
        } catch (BnplRepayments.InsufficientFunds insufficient) {
            metrics.record(insufficient.product(), BnplPayoffMetrics.Outcome.INSUFFICIENT_FUNDS);
            throw new ApiException(BnplErrorCode.INSUFFICIENT_FUNDS, "The payoff cannot be funded",
                    "the wallet's available balance cannot fund this payoff now; top up the wallet and pay off again;"
                            + " nothing was posted.");
        } catch (AllocationAnomaly anomaly) {
            repaymentMetrics.anomaly();
            throw anomaly;
        } catch (TransactionSystemException atCommit) {
            // The database's conservation rules are judged at commit (bnpl V010's deferred checks): the same defect.
            if (BnplRepaymentDesk.refusedConservation(atCommit)) {
                repaymentMetrics.anomaly();
            }
            throw atCommit;
        }
        BnplPayoffView paidOff =
                replayedPayoff(outcome.body().orElseThrow(() -> new IllegalStateException("a payoff stores its receipt")));
        BnplPayoffs.Executed done = acted.get();
        if (outcome.executed() && done != null && !done.replayed()) {
            metrics.record(done.quote().product(), BnplPayoffMetrics.Outcome.PAID_OFF);
            if (done.repayment().isPresent()) {
                repaymentMetrics.record(BnplRepaymentMetrics.Kind.PAYOFF, BnplRepaymentMetrics.Outcome.CLOSED);
            }
        }
        return paidOff;
    }

    /**
     * {@code work} in one transaction; a pay-in-N record behind its accrual writes nothing, so T-acc runs in its own
     * transaction - never inside the payoff's (the A2-F1 rule) - and the work is asked again, at most
     * {@link #ACCRUAL_ROUNDS} times before the caller is told to retry under the same key.
     */
    private IdempotentExecutor.ExecutionOutcome caughtUp(Function<Connection, IdempotentExecutor.ExecutionOutcome> work) {
        for (int round = 0; ; round++) {
            try {
                return transactions.inTransaction(work);
            } catch (BnplPayoffs.BehindAccrual behind) {
                if (round >= ACCRUAL_ROUNDS) {
                    throw new ApiException(PlatformErrorCode.CONFLICT, "The BNPL agreement is being brought up to date",
                            "the agreement's interest is still being accrued; retry under the same key.");
                }
                transactions.inTransaction(uow -> accruals.accrue(uow, behind.obligation()));
            }
        }
    }

    // ------------------------------------------------------------------ the views and the receipts

    static BnplPayoffQuoteView view(BnplPayoffQuote quote) {
        return new BnplPayoffQuoteView(quote.id().toString(), quote.agreement().value().toString(),
                quote.businessDate().toString(), quote.goodThrough().toString(), text(quote.payable()),
                text(quote.refund()), quote.amount().currency().code(), text(quote.principal()),
                text(quote.principalDue()), text(quote.interestAccrued()), text(quote.interestDue()),
                text(quote.feesDue()), text(quote.creditBalance()), text(quote.projectedInterest()),
                quote.projectedDays(), quote.agreementVersion(), quote.createdAt().toString());
    }

    static BnplPayoffView view(BnplPayoffs.Executed executed) {
        Money refunded = executed.closure().refund();
        Money paid = executed.repayment().map(repayment -> repayment.amount()).orElse(refunded.minus(refunded));
        return new BnplPayoffView(executed.execution().id().toString(), executed.quote().id().toString(),
                executed.quote().agreement().value().toString(), executed.execution().businessDate().toString(),
                executed.execution().valueDate().toString(),
                executed.repayment().map(repayment -> repayment.id().toString()).orElse(null), text(paid),
                text(refunded), refunded.currency().code(), executed.closure().reason().name(),
                executed.execution().executedAt().toString());
    }

    /** The quote's receipt: the view's fields, '|'-separated - never the key. */
    static StoredResponse stored(BnplPayoffQuoteView view) {
        String body = String.join("|", "OK", view.quoteId(), view.agreementId(), view.quotedOn(), view.goodThrough(),
                view.amount(), view.refund(), view.currency(), view.principal(), view.principalDue(),
                view.interestAccrued(), view.interestDue(), view.feesDue(), view.creditBalance(), view.projectedInterest(),
                Integer.toString(view.projectedDays()), Integer.toString(view.agreementVersion()), view.createdAt());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplPayoffQuoteView replayedQuote(byte[] body) {
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplPayoffQuoteView(f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11], f[12], f[13],
                f[14], Integer.parseInt(f[15]), Integer.parseInt(f[16]), f[17]);
    }

    /** The payoff's receipt: the view's fields, '|'-separated - never the key. */
    static StoredResponse stored(BnplPayoffView view) {
        String body = String.join("|", "OK", view.executionId(), view.quoteId(), view.agreementId(), view.businessDate(),
                view.valueDate(), view.repaymentId() == null ? "" : view.repaymentId(), view.paid(), view.refunded(),
                view.currency(), view.closureReason(), view.executedAt());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplPayoffView replayedPayoff(byte[] body) {
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplPayoffView(f[1], f[2], f[3], f[4], f[5], f[6].isEmpty() ? null : f[6], f[7], f[8], f[9], f[10],
                f[11]);
    }

    private static String text(Money money) {
        return money.toBigDecimal().toPlainString();
    }

    // ------------------------------------------------------------------ plumbing

    /** The platform's conditional step-up (A18): an enrolled customer quotes from a {@code MULTI_FACTOR} session. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A payoff quote from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    /** A payoff's assurance (A18): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    static void requireMultiFactor(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, "A payoff requires a MULTI_FACTOR session");
        }
    }

    static Optional<LocalDate> goodThrough(BnplPayoffController.BnplPayoffQuoteBody body) {
        if (body.goodThrough() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(body.goodThrough()));
        } catch (DateTimeParseException malformed) {
            throw invalid("the good-through date must be an ISO date (YYYY-MM-DD).");
        }
    }

    static UUID quoteId(BnplPayoffController.BnplPayoffBody body) {
        try {
            return UUID.fromString(body.quoteId());
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static BnplAgreementId agreementId(String raw) {
        try {
            return BnplAgreementId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL agreement or quote matches the requested identifier",
                "no such BNPL agreement or quote.");
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
                .orElseThrow(() -> new IllegalStateException("a BNPL payoff runs inside a correlation scope"))
                .correlationId();
    }
}
