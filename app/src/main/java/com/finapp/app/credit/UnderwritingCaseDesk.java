package com.finapp.app.credit;

import com.finapp.app.api.DecimalText;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.TransactionRunner;
import com.finapp.credit.UnderwritingCase;
import com.finapp.credit.UnderwritingCaseId;
import com.finapp.credit.UnderwritingCaseStatus;
import com.finapp.credit.UnderwritingCases;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the review-case doors (`P10-TSK-018`; ADR-0089 point 8): parse each act exactly, key it per
 * principal ({@code credit.review:<type>:<id>}), run it in one transaction, tell the decision's meters after the commit,
 * and translate the domain's refusals onto the error contract. What a person may decide, the four eyes, the locks and the
 * exposure bound are the domain's and {@code credit V013}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class UnderwritingCaseDesk {

    static final String SCOPE = "credit.review:";

    @NonNull private final UnderwritingCases cases;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;

    /** An act's receipt: the case, its status now, the decision it recorded, and why it closed. */
    public record ReviewCaseReceipt(String caseId, String status, String decisionId, String closureReason) {}

    /** One queued case with its basis - the snapshot's normalised attributes and the rules, never raw evidence. */
    public record ReviewCaseView(
            String caseId,
            String requestId,
            String product,
            String status,
            String currency,
            String requestedAmount,
            String approvableAmount,
            String fourEyesThreshold,
            List<String> referralReasonCodes,
            String assignee,
            String firstOutcome,
            String firstApprovedAmount,
            List<String> firstReasonCodes,
            String firstReason,
            String firstDecidedBy,
            String secondDecidedBy,
            String closureReason,
            String openedAt,
            String requestExpiresAt,
            List<ReviewBasisAttributeView> basisAttributes,
            List<ReviewBasisRuleView> basisRules) {}

    /** One normalised attribute of the basis snapshot, with its provenance's kind. */
    public record ReviewBasisAttributeView(String code, String value, String provenance) {}

    /** One rule of the pinned policy and what it did in the basis evaluation. */
    public record ReviewBasisRuleView(int ordinal, String ruleCode, String effect, String reasonCode, String state) {}

    // ------------------------------------------------------------------ the queue

    public List<ReviewCaseView> queue(String rawStatus) {
        Optional<UnderwritingCaseStatus> status = Optional.ofNullable(rawStatus).filter(text -> !text.isBlank())
                .map(UnderwritingCaseDesk::status);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return transactions.inTransaction(uow -> cases.queue(uow, status, actor, correlation)).stream()
                .map(UnderwritingCaseDesk::view)
                .toList();
    }

    // ------------------------------------------------------------------ the acts

    public ReviewCaseReceipt assign(String idempotencyKey, String rawId) {
        UnderwritingCaseId id = caseId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, "assign|" + id.value(),
                uow -> cases.assign(uow, id, actor, correlation));
    }

    public ReviewCaseReceipt release(String idempotencyKey, String rawId) {
        UnderwritingCaseId id = caseId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, "release|" + id.value(),
                uow -> cases.release(uow, id, actor, correlation));
    }

    public ReviewCaseReceipt decide(String idempotencyKey, String rawId, UnderwritingCaseController.ReviewDecisionBody body) {
        UnderwritingCaseId id = caseId(rawId);
        UnderwritingCases.Judgement judgement = judgement(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String fingerprinted = String.join("|", "decide", id.value().toString(), judgement.outcome().name(),
                judgement.approved().map(amount -> amount.currency().code() + ":" + amount.minorUnits()).orElse("-"),
                String.join(",", judgement.reasons().stream().map(ReasonCode::code).toList()),
                String.valueOf(judgement.reason()));
        return keyed(actor, correlation, idempotencyKey, fingerprinted,
                uow -> cases.decide(uow, id, judgement, actor, correlation));
    }

    public ReviewCaseReceipt secondApproval(
            String idempotencyKey, String rawId, UnderwritingCaseController.SecondApprovalBody body) {
        UnderwritingCaseId id = caseId(rawId);
        boolean approve = switch (body.decision()) {
            case "APPROVE" -> true;
            case "REFUSE" -> false;
            default -> throw invalid("decision must be APPROVE or REFUSE");
        };
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String fingerprinted = String.join("|", "second", id.value().toString(), body.decision(),
                String.valueOf(body.reason()));
        return keyed(actor, correlation, idempotencyKey, fingerprinted, uow -> approve
                ? cases.approveSecond(uow, id, Optional.ofNullable(body.reason()), actor, correlation)
                : cases.refuseSecond(uow, id, body.reason(), actor, correlation));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing and rolls
     * everything back. The decision's meters are told after the commit, once. A self-dealing attempt (the Phase 10 to 11
     * transition) is the one refusal recorded: {@code credit.ReviewOwnCaseRefused} {@code FAILED}, in a transaction of
     * its own after the act's rolled back - so the attempt survives, and a retry is recorded as the attempt it is.
     */
    private ReviewCaseReceipt keyed(
            Actor actor,
            CorrelationId correlation,
            String idempotencyKey,
            String fingerprinted,
            Function<Connection, UnderwritingCases.Acted> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<UnderwritingCases.Acted> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = guarded(() -> transactions.inTransaction(
                    unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                        UnderwritingCases.Acted done = act.apply(uow);
                        acted.set(done);
                        UnderwritingCase reviewCase = done.reviewCase();
                        String stored = String.join("|", reviewCase.id().value().toString(), reviewCase.status().name(),
                                done.decision().map(decision -> decision.id().value().toString()).orElse(""),
                                reviewCase.closureReason().orElse(""));
                        return CommandResult.succeeded(
                                StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                    })));
        } catch (UnderwritingCases.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                cases.recordRefusal(uow, selfDealing, actor, correlation);
                return null;
            });
            throw refused(CreditErrorCode.SELF_DEALING_REFUSED, selfDealing);
        }
        if (acted.get() != null) {
            cases.observe(acted.get());
        }
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a review act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new ReviewCaseReceipt(stored[0], stored[1], stored[2].isEmpty() ? null : stored[2],
                stored[3].isEmpty() ? null : stored[3]);
    }

    private static UnderwritingCases.Judgement judgement(UnderwritingCaseController.ReviewDecisionBody body) {
        DecisionOutcome outcome;
        try {
            outcome = DecisionOutcome.valueOf(body.outcome());
        } catch (IllegalArgumentException unknown) {
            throw invalid("outcome must be APPROVED or DECLINED");
        }
        Optional<Money> approved = Optional.empty();
        if (body.approvedAmount() != null) {
            if (body.currency() == null) {
                throw invalid("an approved amount names its currency");
            }
            CurrencyCode currency;
            try {
                currency = CurrencyCode.of(body.currency());
            } catch (IllegalArgumentException unknown) {
                throw invalid("currency must be an ISO 4217 code");
            }
            try {
                // The shape first: an exponent never reaches Money.of's rescale.
                approved = Optional.of(Money.of(DecimalText.parse(body.approvedAmount()), currency));
            } catch (RuntimeException malformed) {
                throw invalid("approvedAmount must be an exact decimal at the currency's minor units");
            }
        }
        List<ReasonCode> reasons = new ArrayList<>();
        for (String code : body.reasonCodes() == null ? List.<String>of() : body.reasonCodes()) {
            reasons.add(reasonCode(code));
        }
        return new UnderwritingCases.Judgement(outcome, approved, reasons, body.reason());
    }

    private static ReasonCode reasonCode(String code) {
        for (ReasonCode candidate : ReasonCode.values()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        throw new ApiException(CreditErrorCode.REASON_REQUIRED, "A review decision was refused",
                "a reason code is not in the catalogue.");
    }

    private static ReviewCaseView view(UnderwritingCases.Queued queued) {
        UnderwritingCase reviewCase = queued.reviewCase();
        Optional<UnderwritingCase.FirstDecision> first = reviewCase.first();
        return new ReviewCaseView(
                reviewCase.id().value().toString(),
                reviewCase.decisionRequest().value().toString(),
                reviewCase.product().name(),
                reviewCase.status().name(),
                reviewCase.requested().currency().code(),
                plain(reviewCase.requested()),
                plain(reviewCase.approvable()),
                plain(reviewCase.fourEyesThreshold()),
                reviewCase.referralReasons().stream().map(ReasonCode::code).toList(),
                reviewCase.assignee().orElse(null),
                first.map(decision -> decision.outcome().name()).orElse(null),
                first.flatMap(UnderwritingCase.FirstDecision::approved).map(UnderwritingCaseDesk::plain).orElse(null),
                first.map(decision -> decision.reasons().stream().map(ReasonCode::code).toList()).orElse(null),
                first.map(UnderwritingCase.FirstDecision::reason).orElse(null),
                first.map(UnderwritingCase.FirstDecision::decidedBy).orElse(null),
                reviewCase.secondDecidedBy().orElse(null),
                reviewCase.closureReason().orElse(null),
                reviewCase.openedAt().toString(),
                reviewCase.requestExpiresAt().toString(),
                queued.attributes().stream().map(attribute -> new ReviewBasisAttributeView(attribute.code().name(),
                        CreditInvestigationDesk.value(attribute.value()),
                        CreditInvestigationDesk.provenance(attribute.provenance()))).toList(),
                queued.rules().stream().map(rule -> new ReviewBasisRuleView(rule.ordinal(), rule.ruleCode(),
                        rule.effect().name(), rule.reason().code(), rule.state().name())).toList());
    }

    private static String plain(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (UnderwritingCases.CaseNotFound unknown) {
            throw notFound();
        } catch (UnderwritingCases.CaseTaken taken) {
            throw refused(CreditErrorCode.CASE_TAKEN, taken);
        } catch (UnderwritingCases.SelfApprovalRefused self) {
            throw refused(CreditErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (UnderwritingCases.HardDeclineNotOverridable hard) {
            throw refused(CreditErrorCode.HARD_DECLINE_NOT_OVERRIDABLE, hard);
        } catch (UnderwritingCases.ExposureLimitExceeded exceeded) {
            throw refused(CreditErrorCode.EXPOSURE_LIMIT_EXCEEDED, exceeded);
        } catch (UnderwritingCases.ExposureUnassessable unassessable) {
            throw refused(CreditErrorCode.EXPOSURE_UNASSESSABLE, unassessable);
        } catch (UnderwritingCases.DataStale stale) {
            throw refused(CreditErrorCode.DATA_STALE, stale);
        } catch (UnderwritingCases.ReasonRequired reason) {
            throw refused(CreditErrorCode.REASON_REQUIRED, reason);
        } catch (UnderwritingCases.JudgementInvalid invalid) {
            throw invalid(invalid.getMessage());
        }
    }

    private static ApiException refused(CreditErrorCode code, RuntimeException cause) {
        return new ApiException(code, "An underwriter's command was refused", cause.getMessage());
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The review command was not valid", detail);
    }

    private static ApiException notFound() {
        return new ApiException(CreditErrorCode.NOT_FOUND, "No review case matches the requested identifier",
                "no such review case.");
    }

    private static UnderwritingCaseStatus status(String raw) {
        try {
            return UnderwritingCaseStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw invalid("status must be OPEN, ASSIGNED, AWAITING_SECOND, DECIDED or CLOSED");
        }
    }

    private static UnderwritingCaseId caseId(String raw) {
        try {
            return UnderwritingCaseId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a review command runs inside a correlation scope"))
                .correlationId();
    }
}
