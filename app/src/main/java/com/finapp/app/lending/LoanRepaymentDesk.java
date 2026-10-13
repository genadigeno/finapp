package com.finapp.app.lending;

import com.finapp.app.api.DecimalText;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.lending.AllocationAnomaly;
import com.finapp.lending.AllocationLine;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanClosure;
import com.finapp.lending.LoanId;
import com.finapp.lending.Repayment;
import com.finapp.lending.RepaymentAllocation;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.TransactionSystemException;

/**
 * The boundary half of the repayment doors (`P11-TSK-018`; {@code PHASE_11_PLAN.md} sections 9 and 11, A19, ADR-0095
 * point 3): {@code POST /v1/me/loans/{id}/repayments}, keyed per principal ({@code lending.repayment:CUSTOMER:<id>}), run
 * in one transaction under the {@link IdempotentExecutor} claim - a retried key replays the recorded receipt, and a
 * refusal, which writes nothing, rolls the claim back with it; and {@code GET /v1/me/loans/{id}/repayments}, the owner's
 * repayments and their allocations.
 *
 * <p><strong>The platform's conditional step-up</strong> (A19): a customer with an active second factor repays only from a
 * {@code MULTI_FACTOR} session; one without may repay from a password session - money leaves the wallet only onto the
 * customer's own loan. <strong>No standing check</strong> (a suspended customer may still repay) and <strong>no operator
 * can repay</strong>: the doors are {@code /v1/me}, the loan the session's own party's (owner-scoped in the lock's
 * statement). The customer names the amount and its currency; the loan's own currency and scale are the domain's to judge.
 */
@RequiredArgsConstructor
public final class LoanRepaymentDesk {

    static final String REPAYMENT_SCOPE = "lending.repayment:";

    @NonNull private final Repayments repayments;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final RepaymentMetrics metrics;
    @NonNull private final com.finapp.lending.RepaymentReversalStore reversals;

    /**
     * What a repayment answers - and replays. Amounts are exact decimal strings, the value date ISO; {@code status} is
     * derived ({@code ALLOCATED}; {@code REVERSED} on the history read once an approved reversal names it, `P11-TSK-020` - a
     * receipt replays what was answered); {@code closure} is present when this
     * repayment closed the loan.
     */
    public record RepaymentView(
            String repaymentId,
            String loanId,
            String kind,
            String status,
            String amount,
            String currency,
            String valueDate,
            int agreementVersion,
            String receivedAt,
            List<RepaymentAllocationView> allocations,
            RepaymentClosureView closure) {}

    /**
     * One allocation line: what it paid - a billed item's component, a credit line's principal not yet due
     * ({@code NOT_DUE_PRINCIPAL}, `P11-TSK-023`), or the amount held as the credit balance.
     */
    public record RepaymentAllocationView(String target, String billingId, String component, String amount) {}

    /** The closure a repayment brought: its reason and the credit balance refunded to the wallet. */
    public record RepaymentClosureView(String reason, String refunded) {}

    /** The owner's repayments of a loan, newest first. */
    public record RepaymentListView(List<RepaymentView> repayments) {}

    // ------------------------------------------------------------------ POST

    /** Repays the caller's own loan from their wallet: the allocation, the posting and any closure in one transaction. */
    public RepaymentView repay(Session current, String rawLoanId, String idempotencyKey,
            LoanController.LoanRepaymentBody body) {
        LoanId id = loanId(rawLoanId);
        Money amount = amount(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(REPAYMENT_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.repayment",
                        id.value().toString(), Long.toString(amount.minorUnits()), Integer.toString(amount.scale()),
                        amount.currency().code())
                .getBytes(StandardCharsets.UTF_8));
        Repayments.Request request = new Repayments.Request(idempotencyKey, amount);
        AtomicBoolean converged = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean paidDown = new AtomicBoolean();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    Repayments.Repaid repaid = repayments.repay(claimed, id, party, request, actor, correlation);
                    converged.set(repaid.replayed());
                    closed.set(repaid.closure().isPresent());
                    paidDown.set(repaid.repayment().to(AllocationLine.Target.NOT_DUE_PRINCIPAL).isPositive());
                    return CommandResult.succeeded(stored(view(repaid.repayment(), repaid.closure())));
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
            metrics.record(RepaymentKind.CUSTOMER, RepaymentMetrics.Outcome.NOT_REPAYABLE);
            throw new ApiException(LendingErrorCode.LOAN_NOT_REPAYABLE, "The loan cannot be repaid",
                    "the loan is closed, cancelled or not yet disbursed; nothing was posted.");
        } catch (Repayments.InsufficientFunds insufficient) {
            metrics.record(RepaymentKind.CUSTOMER, RepaymentMetrics.Outcome.INSUFFICIENT_FUNDS);
            throw new ApiException(LendingErrorCode.INSUFFICIENT_FUNDS, "The repayment cannot be funded",
                    "the wallet's available balance cannot fund this repayment now; nothing was posted.");
        } catch (AllocationAnomaly anomaly) {
            metrics.anomaly();
            throw anomaly;
        } catch (TransactionSystemException atCommit) {
            // The database's conservation rules are judged at commit (lending V014's deferred checks): a refusal there is
            // a refused conservation the domain did not catch - the same defect, counted the same way.
            if (refusedConservation(atCommit)) {
                metrics.anomaly();
            }
            throw atCommit;
        }
        RepaymentView repaid =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a repayment stores its receipt")));
        if (outcome.executed() && !converged.get()) {
            metrics.record(RepaymentKind.CUSTOMER, closed.get() ? RepaymentMetrics.Outcome.CLOSED
                    : paidDown.get() ? RepaymentMetrics.Outcome.PAID_DOWN : RepaymentMetrics.Outcome.REPAID);
        }
        return repaid;
    }

    // ------------------------------------------------------------------ GET

    /** The caller's own loan's repayments, newest first; another party's loan, or none, is the uniform 404. */
    public RepaymentListView list(Session current, String rawLoanId) {
        LoanId id = loanId(rawLoanId);
        return transactions.inTransaction(unitOfWork -> {
            List<Repayment> found = repayments.history(unitOfWork, id, partyOf(unitOfWork, current))
                    .orElseThrow(LoanRepaymentDesk::notFound);
            // P11-TSK-020: REVERSED is derived from the approved reversal naming the repayment, read in the same transaction.
            Set<UUID> reversed = reversals.reversedAmong(unitOfWork, found.stream().map(Repayment::id).toList());
            List<RepaymentView> views = new ArrayList<>();
            for (Repayment repayment : found) {
                views.add(view(repayment, Optional.empty(), reversed.contains(repayment.id())));
            }
            return new RepaymentListView(List.copyOf(views));
        });
    }

    // ------------------------------------------------------------------ the view and the receipt

    private static RepaymentView view(Repayment repayment, Optional<LoanClosure> closure) {
        return view(repayment, closure, false);
    }

    /**
     * A repayment as answered - its status derived: {@code REVERSED} once an approved reversal names it (`P11-TSK-020`),
     * {@code ALLOCATED} otherwise. The operator's loan read shows the same view.
     */
    static RepaymentView view(Repayment repayment, Optional<LoanClosure> closure, boolean reversed) {
        List<RepaymentAllocationView> allocations = new ArrayList<>();
        for (RepaymentAllocation allocation : repayment.allocations()) {
            AllocationLine line = allocation.line();
            allocations.add(new RepaymentAllocationView(line.target().name(),
                    line.item().map(item -> item.value().toString()).orElse(null),
                    line.component().map(Enum::name).orElse(null), line.amount().toBigDecimal().toPlainString()));
        }
        return new RepaymentView(repayment.id().toString(), repayment.loan().value().toString(), repayment.kind().name(),
                reversed ? "REVERSED" : "ALLOCATED", repayment.amount().toBigDecimal().toPlainString(),
                repayment.amount().currency().code(),
                repayment.valueDate().toString(), repayment.agreementVersion(), repayment.receivedAt().toString(),
                List.copyOf(allocations),
                closure.map(closing -> new RepaymentClosureView(closing.reason().name(),
                        closing.refund().toBigDecimal().toPlainString())).orElse(null));
    }

    /** The receipt: the view's fields, '|'-separated, the allocations ';'-separated with ','-separated fields. */
    private static StoredResponse stored(RepaymentView view) {
        List<String> lines = new ArrayList<>();
        for (RepaymentAllocationView line : view.allocations()) {
            lines.add(String.join(",", line.target(), nullable(line.billingId()), nullable(line.component()),
                    line.amount()));
        }
        String body = String.join("|", "OK", view.repaymentId(), view.loanId(), view.kind(), view.status(),
                view.amount(), view.currency(), view.valueDate(), Integer.toString(view.agreementVersion()),
                view.receivedAt(), String.join(";", lines),
                view.closure() == null ? "" : view.closure().reason() + "," + view.closure().refunded());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static RepaymentView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        List<RepaymentAllocationView> allocations = new ArrayList<>();
        if (!fields[10].isEmpty()) {
            for (String line : fields[10].split(";", -1)) {
                String[] parts = line.split(",", -1);
                allocations.add(new RepaymentAllocationView(parts[0], blankToNull(parts[1]), blankToNull(parts[2]), parts[3]));
            }
        }
        RepaymentClosureView closure = null;
        if (!fields[11].isEmpty()) {
            String[] parts = fields[11].split(",", -1);
            closure = new RepaymentClosureView(parts[0], parts[1]);
        }
        return new RepaymentView(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
                Integer.parseInt(fields[8]), fields[9], List.copyOf(allocations), closure);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------ plumbing

    /** The platform's conditional step-up (A19): an enrolled customer repays from a {@code MULTI_FACTOR} session. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A repayment from an MFA-enrolled identity requires a MULTI_FACTOR session");
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
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The repayment was not valid", detail);
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
