package com.finapp.app.bnpl;

import com.finapp.app.api.DecimalText;
import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplClosure;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplPurchaseStore;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.TransactionSystemException;

/**
 * The boundary half of the BNPL repayment doors (`P12-TSK-018`; {@code PHASE_12_PLAN.md} section 9, A18; lending's
 * {@code LoanRepaymentDesk}): {@code POST /v1/me/bnpl-agreements/{id}/repayments}, keyed per principal
 * ({@code bnpl.repayment:CUSTOMER:<id>}), run in one transaction under the {@link IdempotentExecutor} claim - a retried key
 * replays the recorded receipt ({@code INV-IDEM-01}: the database committed and the response was lost - the retry answers
 * the same repayment), and a refusal, which writes nothing, rolls the claim back with it; and
 * {@code GET /v1/me/bnpl-agreements/{id}/repayments}, the owner's repayments and their allocations.
 *
 * <p><strong>The platform's conditional step-up</strong> (A18): a customer with an active second factor repays only from a
 * {@code MULTI_FACTOR} session; one without may repay from a password session - money leaves the wallet only onto the
 * customer's own obligation. <strong>No standing check</strong> (a suspended customer may still repay) and <strong>no
 * operator can repay</strong>: the doors are {@code /v1/me}, the agreement the session's own party's (owner-scoped in the
 * lock's statement). The customer names the amount and its currency; the agreement's own currency and scale are the
 * domain's to judge. The meter is counted after the transaction - a replay counts nothing.
 */
@RequiredArgsConstructor
public final class BnplRepaymentDesk {

    static final String REPAYMENT_SCOPE = "bnpl.repayment:";

    @NonNull private final BnplRepayments repayments;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplRepaymentMetrics metrics;
    /** The derived {@code REVERSED} status's read (`P12-TSK-024`). */
    @NonNull private final com.finapp.bnpl.BnplRepaymentReversals reversals;

    /**
     * What a repayment answers - and replays. Amounts are exact decimal strings, the value date ISO; {@code status} is
     * derived ({@code ALLOCATED}; {@code REVERSED} once `P12-TSK-024`'s approved reversal names it); {@code closure} is
     * present when this repayment closed the obligation.
     */
    public record BnplRepaymentView(
            String repaymentId,
            String agreementId,
            String kind,
            String status,
            String amount,
            String currency,
            String valueDate,
            int agreementVersion,
            List<BnplRepaymentAllocationView> allocations,
            BnplRepaymentClosureView closure) {}

    /** One allocation line: what it paid - a billed instalment's component, or the amount held as the credit balance. */
    public record BnplRepaymentAllocationView(String target, String instalmentId, String component, String amount) {}

    /** The closure a repayment brought: its reason and the credit balance refunded to the wallet. */
    public record BnplRepaymentClosureView(String reason, String refunded) {}

    /** The owner's repayments of an agreement, newest first. */
    public record BnplRepaymentListView(List<BnplRepaymentView> repayments) {}

    // ------------------------------------------------------------------ POST

    /** Repays the caller's own agreement from their wallet: the allocation, the posting and any closure, one transaction. */
    public BnplRepaymentView repay(Session current, String rawAgreementId, String idempotencyKey,
            BnplRepaymentController.BnplRepaymentBody body) {
        BnplAgreementId id = agreementId(rawAgreementId);
        Money amount = amount(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(REPAYMENT_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "bnpl.repayment",
                        id.value().toString(), Long.toString(amount.minorUnits()), Integer.toString(amount.scale()),
                        amount.currency().code())
                .getBytes(StandardCharsets.UTF_8));
        BnplRepayments.Request request = new BnplRepayments.Request(idempotencyKey, amount);
        AtomicBoolean converged = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    BnplRepayments.Repaid repaid = repayments.repay(claimed, id, party, request, actor, correlation);
                    converged.set(repaid.replayed());
                    closed.set(repaid.closure().isPresent());
                    return CommandResult.succeeded(stored(view(id, repaid.repayment(), repaid.closure(), false)));
                });
            });
        } catch (BnplRepayments.AgreementNotFound absent) {
            throw notFound();
        } catch (BnplRepayments.RepaymentKeyReused reused) {
            throw new ApiException(PlatformErrorCode.CONFLICT, "The key was already used",
                    "This " + IdempotencyKeyHeader.NAME + " was already used for a different request. Use a new key for"
                            + " a new action, or resend the original request unchanged.");
        } catch (BnplRepayments.AmountInvalid invalid) {
            throw invalid("the amount must be a positive amount in the agreement's currency, at its minor units.");
        } catch (BnplRepayments.ObligationClosed closedAlready) {
            metrics.record(BnplRepaymentMetrics.Kind.CUSTOMER, BnplRepaymentMetrics.Outcome.OBLIGATION_CLOSED);
            throw new ApiException(BnplErrorCode.OBLIGATION_CLOSED, "The BNPL agreement is closed",
                    "the agreement is repaid and closed; nothing was posted.");
        } catch (BnplRepayments.InsufficientFunds insufficient) {
            metrics.record(BnplRepaymentMetrics.Kind.CUSTOMER, BnplRepaymentMetrics.Outcome.INSUFFICIENT_FUNDS);
            throw new ApiException(BnplErrorCode.INSUFFICIENT_FUNDS, "The repayment cannot be funded",
                    "the wallet's available balance cannot fund this repayment now; top up the wallet and repay again;"
                            + " nothing was posted.");
        } catch (AllocationAnomaly anomaly) {
            metrics.anomaly();
            throw anomaly;
        } catch (TransactionSystemException atCommit) {
            // The database's conservation rules are judged at commit (bnpl V010's deferred checks): a refusal there is a
            // refused conservation the domain did not catch - the same defect, counted the same way.
            if (refusedConservation(atCommit)) {
                metrics.anomaly();
            }
            throw atCommit;
        }
        BnplRepaymentView repaid =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a repayment stores its receipt")));
        if (outcome.executed() && !converged.get()) {
            metrics.record(BnplRepaymentMetrics.Kind.CUSTOMER,
                    closed.get() ? BnplRepaymentMetrics.Outcome.CLOSED : BnplRepaymentMetrics.Outcome.REPAID);
        }
        return repaid;
    }

    // ------------------------------------------------------------------ GET

    /** The caller's own agreement's repayments, newest first; another party's agreement, or none, is the uniform 404. */
    public BnplRepaymentListView list(Session current, String rawAgreementId) {
        BnplAgreementId id = agreementId(rawAgreementId);
        return transactions.inSnapshot(unitOfWork -> {
            List<BnplPurchaseStore.Repayment> found = repayments.history(unitOfWork, id, partyOf(unitOfWork, current))
                    .orElseThrow(BnplRepaymentDesk::notFound);
            List<BnplRepaymentView> views = new ArrayList<>();
            // REVERSED is derived from the approved reversal naming the repayment (P12-TSK-024), read in this snapshot.
            java.util.Set<UUID> reversed = reversals.reversedAmong(unitOfWork,
                    found.stream().map(BnplPurchaseStore.Repayment::id).toList());
            for (BnplPurchaseStore.Repayment repayment : found) {
                views.add(view(id, repayment, Optional.empty(), reversed.contains(repayment.id())));
            }
            return new BnplRepaymentListView(List.copyOf(views));
        });
    }

    // ------------------------------------------------------------------ the view and the receipt

    /** A repayment as answered - its status derived: {@code REVERSED} once an approved reversal names it. */
    static BnplRepaymentView view(BnplAgreementId agreement, BnplPurchaseStore.Repayment repayment,
            Optional<BnplClosure> closure, boolean reversed) {
        List<BnplRepaymentAllocationView> allocations = new ArrayList<>();
        for (BnplPurchaseStore.Allocation line : repayment.allocations()) {
            allocations.add(new BnplRepaymentAllocationView(line.target(),
                    line.instalment().map(UUID::toString).orElse(null), line.component().orElse(null),
                    decimal(line.amount())));
        }
        return new BnplRepaymentView(repayment.id().toString(), agreement.value().toString(), repayment.kind(),
                reversed ? "REVERSED" : "ALLOCATED", decimal(repayment.amount()), repayment.amount().currency().code(),
                repayment.valueDate().toString(), repayment.agreementVersion(), List.copyOf(allocations),
                closure.map(closing -> new BnplRepaymentClosureView(closing.reason().name(), decimal(closing.refund())))
                        .orElse(null));
    }

    /** The receipt: the view's fields, '|'-separated, the allocations ';'-separated with ','-separated fields - no key. */
    static StoredResponse stored(BnplRepaymentView view) {
        List<String> lines = new ArrayList<>();
        for (BnplRepaymentAllocationView line : view.allocations()) {
            lines.add(String.join(",", line.target(), nullable(line.instalmentId()), nullable(line.component()),
                    line.amount()));
        }
        String body = String.join("|", "OK", view.repaymentId(), view.agreementId(), view.kind(), view.status(),
                view.amount(), view.currency(), view.valueDate(), Integer.toString(view.agreementVersion()),
                String.join(";", lines),
                view.closure() == null ? "" : view.closure().reason() + "," + view.closure().refunded());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplRepaymentView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        List<BnplRepaymentAllocationView> allocations = new ArrayList<>();
        if (!fields[9].isEmpty()) {
            for (String line : fields[9].split(";", -1)) {
                String[] parts = line.split(",", -1);
                allocations.add(new BnplRepaymentAllocationView(parts[0], blankToNull(parts[1]), blankToNull(parts[2]),
                        parts[3]));
            }
        }
        BnplRepaymentClosureView closure = null;
        if (!fields[10].isEmpty()) {
            String[] parts = fields[10].split(",", -1);
            closure = new BnplRepaymentClosureView(parts[0], parts[1]);
        }
        return new BnplRepaymentView(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
                Integer.parseInt(fields[8]), List.copyOf(allocations), closure);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    // ------------------------------------------------------------------ plumbing

    /** The platform's conditional step-up (A18): an enrolled customer repays from a {@code MULTI_FACTOR} session. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A repayment from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    static boolean refusedConservation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("INV-LND-04")) {
                return true;
            }
        }
        return false;
    }

    /** The body's amount in its named currency, exact at the currency's minor units - the agreement's are the domain's. */
    static Money amount(BnplRepaymentController.BnplRepaymentBody body) {
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

    private static BnplAgreementId agreementId(String raw) {
        try {
            return BnplAgreementId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL agreement matches the requested identifier",
                "no such BNPL agreement.");
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
                .orElseThrow(() -> new IllegalStateException("a BNPL repayment runs inside a correlation scope"))
                .correlationId();
    }
}
