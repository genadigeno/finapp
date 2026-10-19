package com.finapp.app.bnpl;

import com.finapp.app.api.DecimalText;
import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplDispute;
import com.finapp.bnpl.BnplDisputeId;
import com.finapp.bnpl.BnplDisputeProposal;
import com.finapp.bnpl.BnplDisputeReason;
import com.finapp.bnpl.BnplDisputeStatus;
import com.finapp.bnpl.BnplDisputes;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplReasons;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.merchant.AuthenticatedMerchant;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the BNPL dispute doors (`P12-TSK-023`; ADR-0106; {@code PHASE_12_PLAN.md} section 9): the customer's
 * opening ({@code POST /v1/me/bnpl-agreements/{id}/disputes}, keyed {@code bnpl.dispute:<actor type>:<id>}, the platform's
 * conditional step-up) and withdrawal ({@code POST /v1/me/bnpl-disputes/{id}/withdrawal}); the merchant's list of its open
 * disputes and its response over its API key ({@code POST /v1/merchant/bnpl-disputes/{id}/response}, keyed
 * {@code bnpl.dispute-response:MERCHANT:<merchant>}); the operator's audited read and resolution - with the four-eyes
 * approval and rejection beside it - under {@code BNPL_DISPUTE_RESOLVE}, keyed {@code bnpl.servicing:<actor type>:<id>}.
 * Each act runs in one transaction under the {@link IdempotentExecutor} claim: a retried key replays the recorded receipt,
 * and a refusal rolls the claim back with everything the transaction wrote.
 *
 * <p><strong>T-acc first</strong>: a refund outcome on a pay-in-N record behind its accrual writes nothing; the desk runs
 * T-acc in its own transaction and asks again, at most {@link #ACCRUAL_ROUNDS} times. A self-approval or a self-dealing act
 * is recorded {@code FAILED} in a transaction of its own after the act rolled back. The receipts carry no free text - the
 * reason and the statement are {@code RESTRICTED-PII} and only the operator's audited read shows them. The meter is counted
 * after the transaction; a replay counts nothing.
 */
@RequiredArgsConstructor
public final class BnplDisputeDesk {

    static final String OPEN_SCOPE = "bnpl.dispute:";
    static final String WITHDRAWAL_SCOPE = "bnpl.dispute-withdrawal:";
    static final String RESPONSE_SCOPE = "bnpl.dispute-response:";
    static final String SERVICING_SCOPE = "bnpl.servicing:";

    /** The most T-acc catch-ups one request runs before it answers. */
    static final int ACCRUAL_ROUNDS = 8;

    @NonNull private final BnplDisputes disputes;
    @NonNull private final BnplAccruals accruals;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplDisputeMetrics metrics;
    @NonNull private final BnplRefundMetrics refundMetrics;

    /**
     * A dispute as its customer and an operator's act see it - no free text: its status, category, the amount disputed, the
     * merchant's response deadline, how many instalments it holds, the refund a refund outcome made and any proposal.
     */
    public record BnplDisputeView(
            String disputeId,
            String purchaseId,
            String status,
            String category,
            String amount,
            String currency,
            String responseDeadline,
            int heldInstalments,
            String refundId,
            String proposalId,
            String proposalStatus,
            String proposalOutcome) {}

    /**
     * A dispute as its merchant sees it: the purchase, the category, the amount, the deadline, the status and any refund -
     * never the customer's reason, schedule, repayments or condition (plan section 11).
     */
    public record BnplMerchantDisputeView(
            String disputeId,
            String purchaseId,
            String status,
            String category,
            String amount,
            String currency,
            String responseDeadline,
            String refundId) {}

    /** The merchant's open disputes. */
    public record BnplMerchantDisputeList(List<BnplMerchantDisputeView> disputes) {}

    /**
     * The operator's audited read: the dispute's figures, the customer's reason and the merchant's statement
     * ({@code RESTRICTED-PII}), the held instalments' sequences, any resolution and the live proposal.
     */
    public record BnplOperatorDisputeView(
            BnplDisputeView dispute,
            String reason,
            String merchantStatement,
            List<Integer> heldSequences,
            String resolvedBy,
            String resolvedAt) {}

    // ------------------------------------------------------------------ the customer's doors

    /** The customer opens a dispute of their own agreement's purchase. */
    public BnplDisputeView open(Session current, String rawAgreementId, String idempotencyKey,
            BnplDisputeController.BnplDisputeOpeningBody body) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        Money amount = amount(body.amount(), body.currency());
        BnplDisputeReason category = category(body.category());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        BnplDisputes.OpenRequest request = new BnplDisputes.OpenRequest(idempotencyKey, amount, category, body.reason());
        RequestFingerprint fingerprint = fingerprint("bnpl.dispute", agreement.value().toString(),
                Long.toString(amount.minorUnits()), Integer.toString(amount.scale()), amount.currency().code(),
                category.name(), body.reason() == null ? "" : body.reason());
        return keyed(OPEN_SCOPE, actor, idempotencyKey, fingerprint, unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            requireConditionalAssurance(unitOfWork, current);
            return party;
        }, (unitOfWork, party) -> disputes.open(unitOfWork, agreement, party, request, actor, correlation));
    }

    /** The customer withdraws their own open dispute. */
    public BnplDisputeView withdraw(Session current, String rawDisputeId, String idempotencyKey) {
        BnplDisputeId id = disputeId(rawDisputeId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(WITHDRAWAL_SCOPE, actor, idempotencyKey, fingerprint("bnpl.dispute-withdrawal", id.value().toString()),
                unitOfWork -> partyOf(unitOfWork, current),
                (unitOfWork, party) -> disputes.withdraw(unitOfWork, id, party, actor, correlation));
    }

    // ------------------------------------------------------------------ the merchant's doors

    /** The merchant's open disputes - its own only. */
    public BnplMerchantDisputeList openAtMerchant(AuthenticatedMerchant merchant) {
        UUID tenant = merchant.merchantId().value();
        return new BnplMerchantDisputeList(transactions.inTransaction(unitOfWork -> disputes.openAtMerchant(unitOfWork,
                tenant)).stream().map(BnplDisputeDesk::merchantView).toList());
    }

    /** The merchant answers its purchase's dispute over its key: contests with a statement, or accepts liability. */
    public BnplMerchantDisputeView respond(AuthenticatedMerchant merchant, String rawDisputeId, String idempotencyKey,
            BnplMerchantDisputeController.BnplDisputeResponseBody body) {
        BnplDisputeId id = disputeId(rawDisputeId);
        BnplDisputes.Decision decision = decision(body.decision());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        UUID tenant = merchant.merchantId().value();
        BnplDisputes.Response response = new BnplDisputes.Response(decision, body.statement(),
                merchant.keyId().value().toString());
        BnplDisputeView view = keyed(RESPONSE_SCOPE, actor, idempotencyKey, fingerprint("bnpl.dispute-response",
                        id.value().toString(), decision.name(), body.statement() == null ? "" : body.statement()),
                unitOfWork -> tenant, (unitOfWork, merchantId) -> disputes.respond(unitOfWork, id, merchantId, response,
                        actor, correlation));
        return new BnplMerchantDisputeView(view.disputeId(), view.purchaseId(), view.status(), view.category(),
                view.amount(), view.currency(), view.responseDeadline(), view.refundId());
    }

    // ------------------------------------------------------------------ the operator's doors

    /** The operator's read - recorded every time. */
    public BnplOperatorDisputeView investigate(String rawDisputeId) {
        BnplDisputeId id = disputeId(rawDisputeId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        try {
            BnplDisputes.Investigated read = transactions.inTransaction(unitOfWork -> disputes.investigate(unitOfWork, id,
                    actor, correlation));
            BnplDispute dispute = read.dispute();
            return new BnplOperatorDisputeView(view(dispute, read.proposal().orElse(null)), dispute.reason(),
                    dispute.merchantStatement().orElse(null),
                    dispute.holds().stream().map(BnplDispute.Held::sequence).toList(),
                    dispute.resolvedBy().orElse(null), dispute.resolvedAt().map(Object::toString).orElse(null));
        } catch (BnplDisputes.DisputeNotFound absent) {
            throw notFound();
        }
    }

    /** An operator resolves - at or below the threshold at once, above it a proposal. */
    public BnplDisputeView resolve(String rawDisputeId, String idempotencyKey,
            BnplOperatorDisputeController.BnplDisputeResolutionBody body) {
        BnplDisputeId id = disputeId(rawDisputeId);
        BnplDisputeStatus outcome = outcome(body.outcome());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return operatorKeyed(actor, idempotencyKey, fingerprint("bnpl.dispute-resolution", id.value().toString(),
                        outcome.name(), body.reason() == null ? "" : body.reason()), correlation,
                unitOfWork -> disputes.resolve(unitOfWork, id, outcome, body.reason(), actor, correlation));
    }

    /** A second operator approves a proposal. */
    public BnplDisputeView approve(String rawDisputeId, String idempotencyKey,
            BnplOperatorDisputeController.BnplDisputeDecisionBody body) {
        BnplDisputeId id = disputeId(rawDisputeId);
        UUID proposal = proposalId(body.proposalId());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return operatorKeyed(actor, idempotencyKey, fingerprint("bnpl.dispute-approval", id.value().toString(),
                        proposal.toString(), body.reason() == null ? "" : body.reason()), correlation,
                unitOfWork -> disputes.approve(unitOfWork, id, proposal, body.reason(), actor, correlation));
    }

    /** An operator rejects - or its proposer withdraws - a proposal. */
    public BnplDisputeView reject(String rawDisputeId, String idempotencyKey,
            BnplOperatorDisputeController.BnplDisputeDecisionBody body) {
        BnplDisputeId id = disputeId(rawDisputeId);
        UUID proposal = proposalId(body.proposalId());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return operatorKeyed(actor, idempotencyKey, fingerprint("bnpl.dispute-rejection", id.value().toString(),
                        proposal.toString(), body.reason() == null ? "" : body.reason()), correlation,
                unitOfWork -> disputes.reject(unitOfWork, id, proposal, body.reason(), actor, correlation));
    }

    /** The operator's acts: the self-approval and self-dealing refusals recorded in a transaction of their own. */
    private BnplDisputeView operatorKeyed(Actor actor, String idempotencyKey, RequestFingerprint fingerprint,
            CorrelationId correlation, Function<Connection, BnplDisputes.Acted> act) {
        try {
            return keyed(SERVICING_SCOPE, actor, idempotencyKey, fingerprint, unitOfWork -> actor,
                    (unitOfWork, ignored) -> act.apply(unitOfWork));
        } catch (BnplDisputes.SelfApprovalRefused self) {
            transactions.inTransaction(uow -> {
                disputes.recordSelfApproval(uow, self, actor, correlation);
                return null;
            });
            metrics.record(BnplDisputeMetrics.Outcome.SELF_APPROVAL_REFUSED);
            throw new ApiException(BnplErrorCode.SELF_APPROVAL_REFUSED, "The approval was refused",
                    "an operator never approves their own resolution proposal; another operator decides it.");
        } catch (BnplDisputes.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                disputes.recordSelfDealing(uow, selfDealing, actor, correlation);
                return null;
            });
            metrics.record(BnplDisputeMetrics.Outcome.SELF_DEALING_REFUSED);
            throw new ApiException(BnplErrorCode.SELF_DEALING_REFUSED, "The resolution was refused",
                    "an employee never resolves a BNPL dispute of their own party; nothing was done.");
        }
    }

    // ------------------------------------------------------------------ one keyed act

    /** The work's own prelude (the party, the tenant, the assurance) and the act, under one claim. */
    @FunctionalInterface
    private interface Act<P> {
        BnplDisputes.Acted apply(Connection unitOfWork, P prelude);
    }

    /**
     * One keyed act in one transaction - T-acc first when a refund outcome finds its record behind: the receipt stored with
     * the claim, the refusals mapped, the meter told after the commit.
     */
    private <P> BnplDisputeView keyed(String scope, Actor actor, String idempotencyKey, RequestFingerprint fingerprint,
            Function<Connection, P> prelude, Act<P> act) {
        IdempotencyKey key = new IdempotencyKey(scope + actor.type().name() + ":" + actor.id(), idempotencyKey);
        AtomicReference<BnplDisputes.Acted> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome = null;
        for (int round = 0; outcome == null; round++) {
            try {
                outcome = transactions.inTransaction(unitOfWork -> {
                    P before = prelude.apply(unitOfWork);
                    return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                        BnplDisputes.Acted done = act.apply(claimed, before);
                        acted.set(done);
                        return CommandResult.succeeded(stored(view(done)));
                    });
                });
            } catch (BnplRefunds.BehindAccrual behind) {
                if (round >= ACCRUAL_ROUNDS) {
                    throw new ApiException(PlatformErrorCode.CONFLICT, "The BNPL agreement is being brought up to date",
                            "the agreement's interest is still being accrued; retry under the same key.");
                }
                transactions.inTransaction(uow -> accruals.accrue(uow, behind.obligation()));
            } catch (BnplDisputes.DisputeNotFound absent) {
                throw notFound();
            } catch (BnplDisputes.DisputeKeyReused reused) {
                throw keyReused();
            } catch (BnplDisputes.AmountInvalid invalid) {
                throw invalid("the amount must be a positive amount in the purchase's currency, at its minor units.");
            } catch (BnplReasons.ReasonRequired reason) {
                throw new ApiException(BnplErrorCode.REASON_REQUIRED, "A reason is required",
                        "a text of 1 to " + BnplReasons.BOUND + " characters, holding no card or account number, is"
                                + " required.");
            } catch (BnplDisputes.DisputeOpen open) {
                metrics.record(BnplDisputeMetrics.Outcome.DISPUTE_OPEN);
                throw new ApiException(BnplErrorCode.DISPUTE_OPEN, "The BNPL purchase has an open dispute",
                        "the purchase already has an open dispute; nothing was written.");
            } catch (BnplDisputes.DisputeNotActionable notActionable) {
                metrics.record(BnplDisputeMetrics.Outcome.NOT_ACTIONABLE);
                throw new ApiException(BnplErrorCode.DISPUTE_NOT_ACTIONABLE, "The BNPL dispute is not open to this act",
                        "the dispute was already answered, resolved or withdrawn, or the merchant's response deadline"
                                + " has passed; nothing was written.");
            } catch (BnplDisputes.ProposalPending pending) {
                throw new ApiException(BnplErrorCode.PROPOSAL_PENDING, "A resolution proposal awaits a decision",
                        "another operator's proposal awaits approval or rejection; nothing was written.");
            } catch (BnplRefunds.PurchaseFullyRefunded fully) {
                metrics.record(BnplDisputeMetrics.Outcome.FULLY_REFUNDED);
                throw new ApiException(BnplErrorCode.PURCHASE_FULLY_REFUNDED, "The BNPL purchase is fully refunded",
                        "the purchase is already fully refunded; there is nothing left to dispute.");
            } catch (BnplRefunds.RefundExceedsPurchase exceeds) {
                metrics.record(BnplDisputeMetrics.Outcome.EXCEEDS_PURCHASE);
                throw new ApiException(BnplErrorCode.REFUND_EXCEEDS_PURCHASE, "The dispute exceeds the BNPL purchase",
                        "the amount exceeds what remains of the purchase; nothing was written.");
            } catch (BnplRefunds.RefundNotPostable notPostable) {
                metrics.record(BnplDisputeMetrics.Outcome.NOT_POSTABLE);
                throw new ApiException(BnplErrorCode.REFUND_NOT_POSTABLE, "The dispute's refund cannot be posted now",
                        "the refund outcome cannot be posted now; nothing was written - retry later under the same key.");
            } catch (AllocationAnomaly anomaly) {
                refundMetrics.anomaly();
                throw anomaly;
            }
        }
        BnplDisputes.Acted done = acted.get();
        if (outcome.executed() && done != null && !done.replayed()) {
            metrics.record(BnplDisputeMetrics.Outcome.of(done.outcome()));
            done.refund().ifPresent(refunded -> refundMetrics.record(refunded.kind(), refunded.closure().isPresent()
                    ? BnplRefundMetrics.Outcome.CLOSED : BnplRefundMetrics.Outcome.APPLIED));
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a dispute act stores its receipt")));
    }

    // ------------------------------------------------------------------ the views and the receipts

    static BnplDisputeView view(BnplDisputes.Acted acted) {
        return view(acted.dispute(), acted.proposal().orElse(null));
    }

    static BnplDisputeView view(BnplDispute dispute, BnplDisputeProposal proposal) {
        return new BnplDisputeView(dispute.id().value().toString(), dispute.purchase().value().toString(),
                dispute.status().name(), dispute.category().name(), decimal(dispute.amount()),
                dispute.amount().currency().code(), dispute.responseDeadline().map(Object::toString).orElse(null),
                dispute.holds().size(), dispute.refund().map(UUID::toString).orElse(null),
                proposal == null ? null : proposal.id().toString(), proposal == null ? null : proposal.status().name(),
                proposal == null ? null : proposal.outcome().name());
    }

    static BnplMerchantDisputeView merchantView(BnplDispute dispute) {
        return new BnplMerchantDisputeView(dispute.id().value().toString(), dispute.purchase().value().toString(),
                dispute.status().name(), dispute.category().name(), decimal(dispute.amount()),
                dispute.amount().currency().code(), dispute.responseDeadline().map(Object::toString).orElse(null),
                dispute.refund().map(UUID::toString).orElse(null));
    }

    /** The receipt: the view's fields, '|'-separated - no free text, never the key. */
    static StoredResponse stored(BnplDisputeView view) {
        return StoredResponse.of(String.join("|", "D", view.disputeId(), view.purchaseId(), view.status(),
                view.category(), view.amount(), view.currency(), blank(view.responseDeadline()),
                Integer.toString(view.heldInstalments()), blank(view.refundId()), blank(view.proposalId()),
                blank(view.proposalStatus()), blank(view.proposalOutcome())).getBytes(StandardCharsets.UTF_8),
                "text/plain");
    }

    static BnplDisputeView replayed(byte[] body) {
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplDisputeView(f[1], f[2], f[3], f[4], f[5], f[6], nullable(f[7]), Integer.parseInt(f[8]),
                nullable(f[9]), nullable(f[10]), nullable(f[11]), nullable(f[12]));
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String nullable(String field) {
        return field.isEmpty() ? null : field;
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    // ------------------------------------------------------------------ plumbing

    private static RequestFingerprint fingerprint(String... parts) {
        return RequestFingerprint.sha256(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }

    /** The platform's conditional step-up (A18): an enrolled customer disputes from a {@code MULTI_FACTOR} session. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A dispute from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    static Money amount(String amount, String currencyCode) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(currencyCode);
        } catch (RuntimeException unknown) {
            throw invalid("the currency is not one this platform supports.");
        }
        try {
            return Money.of(DecimalText.parse(amount), currency);
        } catch (RuntimeException malformed) {
            throw invalid("the amount must be an exact decimal at the currency's minor units.");
        }
    }

    static BnplDisputeReason category(String raw) {
        try {
            return BnplDisputeReason.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw invalid("the category is one of NOT_RECEIVED, NOT_AS_DESCRIBED, RETURNED_NOT_REFUNDED, OTHER.");
        }
    }

    static BnplDisputes.Decision decision(String raw) {
        try {
            return BnplDisputes.Decision.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw invalid("the decision is CONTEST or ACCEPT.");
        }
    }

    static BnplDisputeStatus outcome(String raw) {
        if ("REFUND".equalsIgnoreCase(raw)) {
            return BnplDisputeStatus.RESOLVED_REFUND;
        }
        if ("UPHOLD".equalsIgnoreCase(raw)) {
            return BnplDisputeStatus.RESOLVED_UPHELD;
        }
        throw invalid("the outcome is REFUND or UPHOLD.");
    }

    private static BnplAgreementId agreementId(String raw) {
        try {
            return BnplAgreementId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    static BnplDisputeId disputeId(String raw) {
        try {
            return BnplDisputeId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static UUID proposalId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL dispute or agreement matches the requested identifier",
                "no such BNPL dispute or agreement.");
    }

    private static ApiException keyReused() {
        return new ApiException(PlatformErrorCode.CONFLICT, "The key was already used",
                "This " + IdempotencyKeyHeader.NAME + " was already used for a different request. Use a new key for a new"
                        + " action, or resend the original request unchanged.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The dispute request was not valid", detail);
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL dispute runs inside a correlation scope"))
                .correlationId();
    }
}
