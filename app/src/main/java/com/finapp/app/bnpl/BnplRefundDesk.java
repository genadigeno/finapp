package com.finapp.app.bnpl;

import com.finapp.app.api.DecimalText;
import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplReasons;
import com.finapp.bnpl.BnplRefund;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantApiKeyScope;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.TransactionSystemException;

/**
 * The boundary half of the BNPL refund doors (`P12-TSK-021`; ADR-0105 decision 9, {@code PHASE_12_PLAN.md} section 9):
 * {@code POST /v1/merchant/bnpl-purchases/{id}/refunds} over the merchant's API key carrying the {@code BNPL_REFUND}
 * scope, keyed per merchant ({@code bnpl.refund:MERCHANT:<merchant>}), and the operator's door beside it,
 * {@code POST /v1/operator/bnpl-purchases/{id}/refunds} under {@code PAYMENT_REFUND}, keyed per operator
 * ({@code bnpl.refund:<actor type>:<id>} - {@code CUSTOMER}-typed for an operator session today). Each runs T-ref in
 * one transaction under the {@link IdempotentExecutor} claim: a
 * retried key replays the recorded receipt ({@code INV-IDEM-01}: the database committed and the response was lost - the
 * retry answers the same refund), and a refusal, which writes nothing, rolls the claim back with it.
 *
 * <p><strong>The tenant is the key</strong>: the merchant door's purchase is locked with {@code merchant_id = ?} - another
 * merchant's is the uniform {@code 404 bnpl.NotFound}. A key without the refund scope is {@code 403
 * bnpl.RefundScopeRequired} before anything is read, recorded {@code FAILED} in a transaction of its own; an operator's
 * refund of their own party's purchase is {@code 403 bnpl.SelfDealingRefused}, recorded the same way after the act rolled
 * back. <strong>T-acc first</strong>: a pay-in-N record behind its accrual writes nothing; the door catches it up in its
 * own transaction (no shared income row held across the refund's payable and wallet) and runs the refund again, at most
 * {@link #ACCRUAL_ROUNDS} times. The meter is counted after the transaction - a replay counts nothing.
 *
 * <p><strong>What the merchant sees</strong> is its own refund's purchase-level figures - the amount, the fee returned,
 * the payable's debit and the purchase's status - never which instalments were cancelled, the customer's excess or where
 * it went, or the agreement's closure (the closed view `-014` drew). The operator sees the split.
 */
@RequiredArgsConstructor
public final class BnplRefundDesk {

    static final String REFUND_SCOPE = "bnpl.refund:";

    /** The most T-acc catch-ups one request runs before it answers - each accrues up to 92 dates. */
    static final int ACCRUAL_ROUNDS = 8;

    @NonNull private final BnplRefunds refunds;
    @NonNull private final BnplAccruals accruals;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplRefundMetrics metrics;

    /** A refund as its merchant reads it - purchase-level figures only. Amounts are exact decimal strings. */
    public record BnplMerchantRefundView(
            String refundId,
            String purchaseId,
            String amount,
            String currency,
            String feeReturned,
            String payableDebit,
            String purchaseStatus,
            String valueDate) {}

    /**
     * A refund as the operator reads it: the merchant's figures and the split - the principal cancelled unbilled and billed,
     * the excess and where it went, the interest released, the agreement version in force and any closure's reason.
     */
    public record BnplOperatorRefundView(
            String refundId,
            String purchaseId,
            String amount,
            String currency,
            String feeReturned,
            String payableDebit,
            String purchaseStatus,
            String valueDate,
            String cancelledUnbilled,
            String cancelledBilled,
            String excess,
            String excessTo,
            String interestReleased,
            int agreementVersion,
            String closure) {}

    // ------------------------------------------------------------------ the doors

    /** The merchant refunds its own purchase, over its key carrying the BNPL refund scope. */
    public BnplMerchantRefundView refundAsMerchant(AuthenticatedMerchant merchant, String rawPurchaseId,
            String idempotencyKey, BnplRefundController.BnplRefundBody body) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        if (!merchant.holds(MerchantApiKeyScope.BNPL_REFUND)) {
            // Recorded FAILED in a transaction of its own - nothing else is read or written (fails closed).
            // The target only when the path names a purchase identifier - never a caller's free text in the record.
            String target = parsedOrMalformed(rawPurchaseId);
            transactions.inTransaction(uow -> {
                refunds.recordScopeRefusal(uow, target, merchant.keyId().value().toString(), actor, correlation);
                return null;
            });
            throw new ApiException(BnplErrorCode.REFUND_SCOPE_REQUIRED, "The API key may not refund BNPL purchases",
                    "this key was not issued with the BNPL_REFUND scope; nothing was done.");
        }
        BnplPurchaseId purchase = purchaseId(rawPurchaseId);
        Money amount = amount(body);
        UUID tenant = merchant.merchantId().value();
        BnplRefunds.Request request = new BnplRefunds.Request(idempotencyKey, amount, body.reason(),
                Optional.of(merchant.keyId().value().toString()));
        Keyed keyed = keyed(actor, idempotencyKey, purchase, amount, body,
                uow -> refunds.atMerchant(uow, purchase, tenant, request, actor, correlation),
                refunded -> stored(merchantView(refunded)));
        return keyed.refunded().map(BnplRefundDesk::merchantView)
                .orElseGet(() -> replayedMerchant(keyed.replayed().orElseThrow()));
    }

    /** An operator refunds a purchase on its merchant's behalf - the door beside the merchant's. */
    public BnplOperatorRefundView refundAsOperator(String rawPurchaseId, String idempotencyKey,
            BnplRefundController.BnplRefundBody body) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        BnplPurchaseId purchase = purchaseId(rawPurchaseId);
        Money amount = amount(body);
        BnplRefunds.Request request = new BnplRefunds.Request(idempotencyKey, amount, body.reason(), Optional.empty());
        try {
            Keyed keyed = keyed(actor, idempotencyKey, purchase, amount, body,
                    uow -> refunds.byOperator(uow, purchase, request, actor, correlation),
                    refunded -> stored(operatorView(refunded)));
            return keyed.refunded().map(BnplRefundDesk::operatorView)
                    .orElseGet(() -> replayedOperator(keyed.replayed().orElseThrow()));
        } catch (BnplRefunds.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                refunds.recordSelfDealing(uow, selfDealing, actor, correlation);
                return null;
            });
            metrics.record(BnplRefunds.Kind.PARTIAL, BnplRefundMetrics.Outcome.SELF_DEALING_REFUSED);
            throw new ApiException(BnplErrorCode.SELF_DEALING_REFUSED, "The refund was refused",
                    "an employee never refunds a BNPL purchase of their own party; nothing was done.");
        }
    }

    /** What a keyed refund answered: the refund this request committed (or converged on), or a claim's stored receipt. */
    private record Keyed(Optional<BnplRefunds.Refunded> refunded, Optional<byte[]> replayed) {}

    /**
     * One keyed refund in one transaction, T-acc first when the record is behind: the receipt stored with the claim, the
     * refusals mapped, the meter told after the commit - a replay (the claim's or the arbiter's) counts nothing.
     */
    private Keyed keyed(Actor actor, String idempotencyKey, BnplPurchaseId purchase, Money amount,
            BnplRefundController.BnplRefundBody body, Function<Connection, BnplRefunds.Refunded> act,
            Function<BnplRefunds.Refunded, StoredResponse> receipt) {
        IdempotencyKey key = new IdempotencyKey(REFUND_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "bnpl.refund",
                        purchase.value().toString(), Long.toString(amount.minorUnits()), Integer.toString(amount.scale()),
                        amount.currency().code(), body.reason() == null ? "" : body.reason())
                .getBytes(StandardCharsets.UTF_8));
        AtomicReference<BnplRefunds.Refunded> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome = null;
        for (int round = 0; outcome == null; round++) {
            try {
                outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint,
                        claimed -> {
                            BnplRefunds.Refunded refunded = act.apply(claimed);
                            acted.set(refunded);
                            return CommandResult.succeeded(receipt.apply(refunded));
                        }));
            } catch (BnplRefunds.BehindAccrual behind) {
                if (round >= ACCRUAL_ROUNDS) {
                    throw new ApiException(PlatformErrorCode.CONFLICT, "The BNPL agreement is being brought up to date",
                            "the agreement's interest is still being accrued; retry the refund under the same key.");
                }
                // T-acc in its own transaction - never inside the refund's (the A2-F1 rule) - then the refund again.
                transactions.inTransaction(uow -> accruals.accrue(uow, behind.obligation()));
            } catch (BnplRefunds.PurchaseNotFound absent) {
                throw notFound();
            } catch (BnplRefunds.RefundKeyReused reused) {
                throw new ApiException(PlatformErrorCode.CONFLICT, "The key was already used",
                        "This " + IdempotencyKeyHeader.NAME + " was already used for a different request. Use a new key"
                                + " for a new action, or resend the original request unchanged.");
            } catch (BnplRefunds.AmountInvalid invalid) {
                throw invalid("the amount must be a positive amount in the purchase's currency, at its minor units.");
            } catch (BnplReasons.ReasonRequired reason) {
                throw new ApiException(BnplErrorCode.REASON_REQUIRED, "A reason is required",
                        "a reason of 1 to " + BnplReasons.BOUND + " characters, holding no card or account number, is"
                                + " required.");
            } catch (BnplRefunds.PurchaseFullyRefunded fully) {
                metrics.record(fully.kind(), BnplRefundMetrics.Outcome.FULLY_REFUNDED);
                throw new ApiException(BnplErrorCode.PURCHASE_FULLY_REFUNDED, "The BNPL purchase is fully refunded",
                        "the purchase is already fully refunded; nothing was written.");
            } catch (BnplRefunds.RefundExceedsPurchase exceeds) {
                metrics.record(exceeds.kind(), BnplRefundMetrics.Outcome.EXCEEDS_PURCHASE);
                throw new ApiException(BnplErrorCode.REFUND_EXCEEDS_PURCHASE, "The refund exceeds the BNPL purchase",
                        "the refund exceeds what remains of the purchase; nothing was written.");
            } catch (BnplRefunds.RefundNotPostable notPostable) {
                metrics.record(notPostable.kind(), BnplRefundMetrics.Outcome.NOT_POSTABLE);
                throw new ApiException(BnplErrorCode.REFUND_NOT_POSTABLE, "The BNPL refund cannot be posted now",
                        "the refund cannot be posted now; nothing was written - retry later under the same key.");
            } catch (AllocationAnomaly anomaly) {
                metrics.anomaly();
                throw anomaly;
            } catch (TransactionSystemException atCommit) {
                // bnpl V012's conservation is judged at commit: a refusal there is a conservation the domain did not catch.
                if (refusedConservation(atCommit)) {
                    metrics.anomaly();
                }
                throw atCommit;
            }
        }
        BnplRefunds.Refunded refunded = acted.get();
        if (outcome.executed() && refunded != null && !refunded.replayed()) {
            metrics.record(refunded.kind(), refunded.closure().isPresent() ? BnplRefundMetrics.Outcome.CLOSED
                    : BnplRefundMetrics.Outcome.APPLIED);
        }
        if (!outcome.executed()) {
            return new Keyed(Optional.empty(), Optional.of(outcome.body()
                    .orElseThrow(() -> new IllegalStateException("a refund stores its receipt"))));
        }
        return new Keyed(Optional.of(refunded), Optional.empty());
    }

    // ------------------------------------------------------------------ the views and the receipts

    static BnplMerchantRefundView merchantView(BnplRefunds.Refunded refunded) {
        BnplRefund refund = refunded.refund();
        return new BnplMerchantRefundView(refund.id().toString(), refund.purchase().value().toString(),
                decimal(refund.amount()), refund.amount().currency().code(), decimal(refund.feeReturned()),
                decimal(refund.payableDebit()), purchaseStatus(refunded), refund.valueDate().toString());
    }

    static BnplOperatorRefundView operatorView(BnplRefunds.Refunded refunded) {
        BnplRefund refund = refunded.refund();
        return new BnplOperatorRefundView(refund.id().toString(), refund.purchase().value().toString(),
                decimal(refund.amount()), refund.amount().currency().code(), decimal(refund.feeReturned()),
                decimal(refund.payableDebit()), purchaseStatus(refunded), refund.valueDate().toString(),
                decimal(refund.cancelledUnbilled()), decimal(refund.cancelledBilled()), decimal(refund.excess()),
                refund.excessTo().name(), decimal(refund.interestReleased()), refund.agreementVersion(),
                refunded.closure().map(closure -> closure.reason().name()).orElse(null));
    }

    /** {@code FULLY_REFUNDED} when this refund completed the purchase, else {@code PARTIALLY_REFUNDED} - derived. */
    private static String purchaseStatus(BnplRefunds.Refunded refunded) {
        return refunded.kind() == BnplRefunds.Kind.FULL ? "FULLY_REFUNDED" : "PARTIALLY_REFUNDED";
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    static StoredResponse stored(BnplMerchantRefundView view) {
        return StoredResponse.of(String.join("|", "M", view.refundId(), view.purchaseId(), view.amount(), view.currency(),
                view.feeReturned(), view.payableDebit(), view.purchaseStatus(), view.valueDate())
                .getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static StoredResponse stored(BnplOperatorRefundView view) {
        return StoredResponse.of(String.join("|", "O", view.refundId(), view.purchaseId(), view.amount(), view.currency(),
                view.feeReturned(), view.payableDebit(), view.purchaseStatus(), view.valueDate(),
                view.cancelledUnbilled(), view.cancelledBilled(), view.excess(), view.excessTo(),
                view.interestReleased(), Integer.toString(view.agreementVersion()),
                view.closure() == null ? "" : view.closure()).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplMerchantRefundView replayedMerchant(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplMerchantRefundView(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
                fields[8]);
    }

    static BnplOperatorRefundView replayedOperator(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplOperatorRefundView(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
                fields[8], fields[9], fields[10], fields[11], fields[12], fields[13], Integer.parseInt(fields[14]),
                fields[15].isEmpty() ? null : fields[15]);
    }

    // ------------------------------------------------------------------ plumbing

    static boolean refusedConservation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("INV-BNPL-06") || message.contains("INV-BNPL-07")
                    || message.contains("INV-LND-04"))) {
                return true;
            }
        }
        return false;
    }

    /** The body's amount in its named currency, exact at the currency's minor units - the purchase's are the domain's. */
    static Money amount(BnplRefundController.BnplRefundBody body) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(body.currency());
        } catch (RuntimeException unknown) {
            throw invalid("the currency is not one this platform supports.");
        }
        try {
            return Money.of(DecimalText.parse(body.amount()), currency);
        } catch (RuntimeException malformed) {
            throw invalid("the amount must be an exact decimal at the currency's minor units.");
        }
    }

    /** The path's purchase identifier as a canonical UUID string, or {@code malformed} - for a refusal's audit target. */
    static String parsedOrMalformed(String raw) {
        try {
            return UUID.fromString(raw).toString();
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return "malformed";
        }
    }

    private static BnplPurchaseId purchaseId(String raw) {
        try {
            return BnplPurchaseId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL purchase matches the requested identifier",
                "no such BNPL purchase.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The refund was not valid", detail);
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL refund runs inside a correlation scope"))
                .correlationId();
    }
}
