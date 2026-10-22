package com.finapp.app.bnpl;

import com.finapp.merchant.BnplEnablement;
import com.finapp.merchant.BnplEnablementId;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeCurrencyMismatchException;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.MerchantBnplProduct;
import com.finapp.merchant.MerchantClosedException;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.UnknownFeeScheduleException;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.platform.api.ApiException;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the merchant BNPL doors (`P12-TSK-006`; {@code BnplTermsDesk}'s shape): parse the identifiers
 * (malformed is absent - one {@code 404}), key every enablement act per principal
 * ({@code merchant.bnpl-enablement:<type>:<id>}), run each in one transaction, translate merchant's refusals onto the
 * error contract, and record a refused self-approval {@code FAILED} in a transaction of its own after the act rolled back
 * (the Phase 11 gate's R4). The four-eyes rule, the machine and the pricing rules are merchant's and `V010`'s; nothing
 * here decides them.
 *
 * <p><strong>The key's type</strong> is the session actor's - {@code CUSTOMER} for an operator session today (the
 * platform does not yet issue {@code EMPLOYEE} actors to operator sessions; {@code BnplTermsDesk}'s note), so the
 * backlog's {@code merchant.bnpl-enablement:EMPLOYEE:<id>} reads {@code ...:CUSTOMER:<id>} until that debt is repaid, with
 * no change here. The fee schedule assignment carries no key: it converges through state, the existing
 * {@code .../fee-schedule} door's idiom.
 */
@RequiredArgsConstructor
public final class MerchantBnplDesk {

    static final String SCOPE = "merchant.bnpl-enablement:";

    @NonNull private final BnplEnablements enablements;
    @NonNull private final BnplPricing pricing;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;

    /** An enablement act's receipt: the enablement, its merchant and its status. Never a reason or a person. */
    public record BnplEnablementReceipt(String id, String merchantId, String status) {}

    /** Which schedule prices a merchant's BNPL product. */
    public record BnplFeeScheduleView(String merchantId, String product, String feeScheduleId) {}

    // ------------------------------------------------------------------ the enablement

    public BnplEnablementReceipt propose(String idempotencyKey, String rawMerchant, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "propose|" + merchant.value() + "|" + reason,
                uow -> receipt(enablements.propose(uow, merchant, reason, actor, correlation)));
    }

    public BnplEnablementReceipt approve(String idempotencyKey, String rawMerchant, String rawId, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        BnplEnablementId id = enablementOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "approve|" + merchant.value() + "|" + id.value() + "|" + reason,
                uow -> receipt(enablements.approve(uow, merchant, id, reason, actor, correlation).enablement()));
    }

    public BnplEnablementReceipt reject(String idempotencyKey, String rawMerchant, String rawId, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        BnplEnablementId id = enablementOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "reject|" + merchant.value() + "|" + id.value() + "|" + reason,
                uow -> receipt(enablements.reject(uow, merchant, id, reason, actor, correlation).enablement()));
    }

    public BnplEnablementReceipt withdraw(String idempotencyKey, String rawMerchant, String rawId, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        BnplEnablementId id = enablementOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "withdraw|" + merchant.value() + "|" + id.value() + "|" + reason,
                uow -> receipt(enablements.withdraw(uow, merchant, id, reason, actor, correlation).enablement()));
    }

    // ------------------------------------------------------------------ the BNPL fee schedule

    /** Points the merchant's BNPL product at a schedule, reasoned; converges when it is already there. */
    public BnplFeeScheduleView assignFeeSchedule(String rawMerchant, String rawProduct, UUID feeScheduleId,
            String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        MerchantBnplProduct product = productOrAbsent(rawProduct);
        Objects.requireNonNull(feeScheduleId, "feeScheduleId must not be null");
        FeeScheduleId schedule;
        try {
            schedule = FeeScheduleId.of(feeScheduleId);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        FeeScheduleId assigned = guarded(() -> transactions.inTransaction(
                uow -> pricing.assign(uow, merchant, product, schedule, reason, actor, correlation)));
        return new BnplFeeScheduleView(merchant.value().toString(), product.name(), assigned.value().toString());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing; a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private BnplEnablementReceipt keyed(Actor actor, String idempotencyKey, String fingerprinted,
            Function<Connection, BnplEnablementReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    BnplEnablementReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.merchantId() + "|" + receipt.status();
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8),
                            "text/plain"));
                }))));
        String[] stored = new String(outcome.body()
                        .orElseThrow(() -> new IllegalStateException("an enablement act stores its receipt")),
                StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplEnablementReceipt(stored[0], stored[1], stored[2]);
    }

    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (BnplEnablements.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                enablements.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** merchant's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (UnknownMerchantException | BnplEnablements.Unknown | UnknownFeeScheduleException unknown) {
            throw notFound();
        } catch (BnplEnablements.SelfApprovalRefused self) {
            throw new ApiException(MerchantErrorCode.SELF_APPROVAL_REFUSED,
                    "A merchant BNPL enablement's proposer tried to approve it", self.getMessage());
        } catch (BnplEnablements.Pending pending) {
            throw new ApiException(MerchantErrorCode.BNPL_ENABLEMENT_PENDING,
                    "A merchant BNPL enablement is already live", pending.getMessage());
        } catch (BnplEnablements.NotOpen notOpen) {
            throw new ApiException(MerchantErrorCode.BNPL_ENABLEMENT_NOT_OPEN,
                    "A merchant BNPL enablement decision was refused by the machine", notOpen.getMessage());
        } catch (MerchantClosedException closed) {
            throw new ApiException(MerchantErrorCode.ILLEGAL_TRANSITION,
                    "A BNPL enablement was asked of a closed merchant",
                    "the merchant's current status does not permit this change.");
        } catch (BnplPricing.ScheduleNotReturning retained) {
            throw new ApiException(MerchantErrorCode.BNPL_SCHEDULE_NOT_RETURNING,
                    "A fee schedule that retains its fee was named for BNPL", retained.getMessage());
        } catch (FeeCurrencyMismatchException mismatch) {
            throw new ApiException(MerchantErrorCode.FEE_CURRENCY_MISMATCH,
                    "A fee schedule was used for a currency it does not price",
                    "the fee schedule's currency does not match.");
        } catch (com.finapp.merchant.BnplEnablement.ReasonRefused reason) {
            // The Phase 12 -> 13 gate's A5-F5: a reason the bean bounds admit but the instrument screen refuses - 422,
            // never a 5xx; the detail never repeats the reason (INV-AUD-02).
            throw new ApiException(com.finapp.platform.api.PlatformErrorCode.VALIDATION_FAILED, "A reason was refused",
                    "a reason of 1 to " + com.finapp.merchant.BnplEnablement.MAX_REASON_LENGTH + " characters, holding"
                            + " no card or account number, is required.");
        }
    }

    private static BnplEnablementReceipt receipt(BnplEnablement enablement) {
        return new BnplEnablementReceipt(enablement.id().value().toString(),
                enablement.merchantId().value().toString(), enablement.status().name());
    }

    private static MerchantId merchantOrAbsent(String raw) {
        try {
            return MerchantId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static BnplEnablementId enablementOrAbsent(String raw) {
        try {
            return BnplEnablementId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static MerchantBnplProduct productOrAbsent(String raw) {
        try {
            return MerchantBnplProduct.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "A merchant BNPL read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a merchant BNPL command runs inside a correlation scope"))
                .correlationId();
    }
}
