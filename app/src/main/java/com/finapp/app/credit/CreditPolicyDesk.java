package com.finapp.app.credit;

import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.CreditPolicyVersionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.TransactionRunner;
import com.finapp.credit.UnavailableFallback;
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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the credit policy doors (`P10-TSK-012`): parse the policy exactly into the domain's words - an
 * unknown product is {@code credit.ProductNotOffered}, every other defect the policy's own
 * ({@code credit.PolicyIncomplete}) - key every act per principal ({@code credit.policy:<type>:<id>}), run each in one
 * transaction, and translate the domain's refusals onto the error contract. The four-eyes rule, the machine, the frozen
 * rules and the never-approving fallback are the domain's and {@code credit V008}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class CreditPolicyDesk {

    static final String SCOPE = "credit.policy:";

    @NonNull private final CreditPolicyAdministration administration;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;

    /** A version's receipt: its id, number and status, and the predecessor an activation retired. */
    public record CreditPolicyReceipt(String id, int version, String status, String retiredId) {}

    /** A policy version as the operator reads it - the whole policy, its thresholds included (CONFIDENTIAL). */
    public record CreditPolicyView(
            String id,
            String product,
            int version,
            String status,
            String effectiveFrom,
            String effectiveTo,
            String currency,
            int scale,
            int assessmentRateBps,
            long minimumDisposableMinor,
            int minimumPaymentRatioBps,
            long maximumExposureMinor,
            List<CreditPolicySourceView> sources,
            String unavailableFallback,
            long autoApprovalCeilingMinor,
            List<CreditPolicyRuleView> rules) {}

    /** A source kind the policy reads and its maximum data age. */
    public record CreditPolicySourceView(String kind, long maximumDataAgeSeconds) {}

    /** One rule, in its stored order. */
    public record CreditPolicyRuleView(
            int ordinal,
            String ruleCode,
            String subjectKind,
            String subject,
            String operator,
            Long integerOperand,
            Long moneyOperandMinor,
            Boolean booleanOperand,
            List<String> codesOperand,
            String effect,
            Long capMinor,
            String reasonCode) {}

    public CreditPolicyReceipt propose(String idempotencyKey, CreditPolicyController.PolicyProposalRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CreditPolicy policy = guarded(() -> policy(request));
        return keyed(actor, idempotencyKey, "propose|" + request, uow -> {
            CreditPolicyAdministration.Proposed proposed =
                    administration.propose(uow, policy, request.reason(), actor, correlation);
            return new CreditPolicyReceipt(proposed.id().value().toString(), proposed.version(), "PROPOSED", null);
        });
    }

    public CreditPolicyReceipt approve(String idempotencyKey, String rawId, String reason) {
        CreditPolicyVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "approve|" + id.value() + "|" + reason,
                uow -> receipt(administration.approve(uow, id, actor, reason, correlation)));
    }

    public CreditPolicyReceipt reject(String idempotencyKey, String rawId, String reason) {
        CreditPolicyVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "reject|" + id.value() + "|" + reason,
                uow -> receipt(administration.reject(uow, id, actor, reason, correlation)));
    }

    /** The product's policy at {@code rawAt}, or {@code ACTIVE} now - a read, unkeyed, in one transaction. */
    public CreditPolicyView activeAt(String rawProduct, String rawAt) {
        CreditProduct product = product(rawProduct);
        Optional<Instant> at = instant(rawAt);
        return guarded(() -> transactions.inTransaction(uow -> view(administration.activeAt(uow, product, at))));
    }

    // ------------------------------------------------------------------ the policy in the domain's words

    private static CreditPolicy policy(CreditPolicyController.PolicyProposalRequest request) {
        CreditProduct product = product(request.product());
        CurrencyCode currency = product.currency();
        if (!currency.code().equals(request.currency())) {
            throw new CreditPolicy.PolicyIncomplete("the policy's amounts are in the product's currency");
        }
        Map<CreditSourceKind, Duration> ages = new EnumMap<>(CreditSourceKind.class);
        for (CreditPolicyController.CreditPolicySourceRequest source : request.sources()) {
            CreditSourceKind kind = named(CreditSourceKind.class, source.kind(), "a source kind");
            if (ages.put(kind, Duration.ofSeconds(source.maximumDataAgeSeconds())) != null) {
                throw new CreditPolicy.PolicyIncomplete("a source kind is declared once");
            }
        }
        List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
        for (CreditPolicyController.CreditPolicyRuleRequest rule : request.rules()) {
            rules.add(rule(rule, currency));
        }
        return new CreditPolicy(
                product,
                request.assessmentRateBps(),
                Money.ofMinorUnits(request.minimumDisposableMinor(), currency),
                request.minimumPaymentRatioBps(),
                Money.ofMinorUnits(request.maximumExposureMinor(), currency),
                ages,
                named(UnavailableFallback.class, request.unavailableFallback(), "an unavailable-source fallback - REFER or DECLINE"),
                Money.ofMinorUnits(request.autoApprovalCeilingMinor(), currency),
                rules);
    }

    private static CreditPolicy.PolicyRule rule(CreditPolicyController.CreditPolicyRuleRequest rule, CurrencyCode currency) {
        long operands = java.util.stream.Stream.of(rule.integerOperand(), rule.moneyOperandMinor(), rule.booleanOperand(),
                        rule.codesOperand())
                .filter(Objects::nonNull)
                .count();
        if (operands > 1) {
            throw new CreditPolicy.PolicyIncomplete("rule " + rule.ruleCode() + " carries at most one operand");
        }
        CreditPolicy.Operand operand = rule.integerOperand() != null
                ? new CreditPolicy.Operand.IntegerOperand(rule.integerOperand())
                : rule.moneyOperandMinor() != null
                        ? new CreditPolicy.Operand.MoneyOperand(Money.ofMinorUnits(rule.moneyOperandMinor(), currency))
                        : rule.booleanOperand() != null
                                ? new CreditPolicy.Operand.BooleanOperand(rule.booleanOperand())
                                : rule.codesOperand() != null
                                        ? new CreditPolicy.Operand.CodesOperand(rule.codesOperand())
                                        : new CreditPolicy.Operand.None();
        return new CreditPolicy.PolicyRule(
                rule.ruleCode(),
                CreditPolicy.subject(rule.subjectKind(), rule.subject()),
                named(PolicyOperator.class, rule.operator(), "an operator"),
                operand,
                named(PolicyEffect.class, rule.effect(), "an effect"),
                Optional.ofNullable(rule.capMinor()).map(minor -> Money.ofMinorUnits(minor, currency)),
                CreditPolicy.reasonCode(rule.reasonCode()));
    }

    private static <E extends Enum<E>> E named(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new CreditPolicy.PolicyIncomplete("the policy names something that is not " + what);
        }
    }

    private static CreditProduct product(String raw) {
        try {
            return CreditProduct.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ApiException(CreditErrorCode.PRODUCT_NOT_OFFERED, "A credit policy names a product that is not offered",
                    "the product is not one the platform offers.");
        }
    }

    private static Optional<Instant> instant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(raw));
        } catch (DateTimeParseException malformed) {
            throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
                    "A credit policy was read at an instant that is not one", "'at' is an ISO-8601 instant.");
        }
    }

    // ------------------------------------------------------------------ the view

    private static CreditPolicyView view(CreditPolicyStore.PolicyVersion version) {
        CreditPolicy policy = version.policy();
        List<CreditPolicySourceView> sources = new ArrayList<>();
        policy.sourceKinds().forEach(kind ->
                sources.add(new CreditPolicySourceView(kind.name(), policy.maximumDataAge().get(kind).toSeconds())));
        List<CreditPolicyRuleView> rules = new ArrayList<>();
        int ordinal = 1;
        for (CreditPolicy.PolicyRule rule : policy.rules()) {
            Long integer = rule.operand() instanceof CreditPolicy.Operand.IntegerOperand value ? value.value() : null;
            Long money = rule.operand() instanceof CreditPolicy.Operand.MoneyOperand value ? value.value().minorUnits() : null;
            Boolean bool = rule.operand() instanceof CreditPolicy.Operand.BooleanOperand value ? value.value() : null;
            List<String> codes = rule.operand() instanceof CreditPolicy.Operand.CodesOperand value ? value.codes() : null;
            rules.add(new CreditPolicyRuleView(ordinal++, rule.ruleCode(),
                    rule.subject() instanceof CreditPolicy.Subject.Figure ? "FIGURE" : "ATTRIBUTE", rule.subject().name(),
                    rule.operator().name(), integer, money, bool, codes, rule.effect().name(),
                    rule.cap().map(Money::minorUnits).orElse(null), rule.reason().code()));
        }
        return new CreditPolicyView(
                version.row().id().value().toString(),
                version.row().product().name(),
                version.row().version(),
                version.row().status().name(),
                version.effectiveFrom().map(Instant::toString).orElse(null),
                version.effectiveTo().map(Instant::toString).orElse(null),
                policy.product().currency().code(),
                policy.product().currency().minorUnits(),
                policy.assessmentRateBps(),
                policy.minimumDisposable().minorUnits(),
                policy.minimumPaymentRatioBps(),
                policy.maximumExposure().minorUnits(),
                sources,
                policy.unavailableFallback().name(),
                policy.autoApprovalCeiling().minorUnits(),
                rules);
    }

    // ------------------------------------------------------------------ plumbing

    /** One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing. */
    private CreditPolicyReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<java.sql.Connection, CreditPolicyReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.inTransaction(
                unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    CreditPolicyReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.version() + "|" + receipt.status() + "|"
                            + (receipt.retiredId() == null ? "" : receipt.retiredId());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                })));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a policy act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new CreditPolicyReceipt(stored[0], Integer.parseInt(stored[1]), stored[2], stored[3].isEmpty() ? null : stored[3]);
    }

    private static CreditPolicyReceipt receipt(CreditPolicyAdministration.Decided decided) {
        return new CreditPolicyReceipt(decided.id().value().toString(), decided.version(), decided.status().name(),
                decided.retired().map(id -> id.value().toString()).orElse(null));
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (CreditPolicyAdministration.PolicyNotFound unknown) {
            throw notFound();
        } catch (CreditPolicyAdministration.SelfApprovalRefused self) {
            throw refused(CreditErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (CreditPolicyAdministration.PolicyStale stale) {
            throw refused(CreditErrorCode.POLICY_STALE, stale);
        } catch (CreditPolicyAdministration.ProposalPending pending) {
            throw refused(CreditErrorCode.PROPOSAL_PENDING, pending);
        } catch (CreditPolicyAdministration.ReasonRequired reason) {
            throw refused(CreditErrorCode.REASON_REQUIRED, reason);
        } catch (CreditPolicy.PolicyIncomplete incomplete) {
            throw refused(CreditErrorCode.POLICY_INCOMPLETE, incomplete);
        }
    }

    private static ApiException refused(CreditErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A credit policy administrator's command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(CreditErrorCode.NOT_FOUND, "No credit policy version answers the request",
                "no such record.");
    }

    private static CreditPolicyVersionId versionId(String raw) {
        try {
            return CreditPolicyVersionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a credit policy command runs inside a correlation scope"))
                .correlationId();
    }
}
