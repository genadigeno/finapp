package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.FailSafeBand;
import com.finapp.risk.ParameterValue;
import com.finapp.risk.RatingBand;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEngine;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskPolicyVersion;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.ScoreBand;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.WindowKind;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the risk policy doors (`P13-TSK-002`; bnpl's {@code BnplTermsDesk}): parse the proposal exactly
 * into the domain's words - an unknown operation, engine, kind, decision, window, dimension, assurance or rating, a
 * parameter the kind does not declare, a value not of its type - each {@code risk.PolicyInvalid} before anything is
 * keyed; key every act per principal ({@code risk.policy:<type>:<id>}), run each in one transaction under its span,
 * and translate the domain's refusals onto the error contract; a refused self-approval is recorded {@code FAILED} in a
 * transaction of its own. The four-eyes rule, the machine, the frozen content and the bands' judgement are the
 * domain's and {@code risk V002}'s; nothing here decides them.
 *
 * <p><strong>The key's type</strong> is the session actor's (bnpl's reading of the plan's {@code risk.policy:<actor>}).
 */
@RequiredArgsConstructor
public final class RiskPolicyDesk {

    static final String SCOPE = "risk.policy:";

    @NonNull private final RiskPolicyAdministration administration;
    @NonNull private final RiskPolicies policies;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final RiskTransactions transactions;
    @NonNull private final RiskFlowScope flow;

    /** A version's receipt: its id, operation, number and status, and the predecessor an activation retired. */
    public record RiskPolicyReceipt(String id, String operation, int version, String status, String retiredId) {}

    /** One rule as the operator reads it: its kind, parameters by name, weight and hard decision. */
    public record RiskRuleView(int ordinal, String kind, Map<String, Object> parameters, Integer weight, String hardDecision) {}

    /** A score or rating band. */
    public record RiskBandView(int from, int to, String decision, String rating) {}

    /** A fail-safe band; {@code toMinor} null for the open-ended last. */
    public record RiskFailSafeBandView(long fromMinor, Long toMinor, String decision) {}

    /**
     * A policy version as the operator reads it - its machine row and its whole content (CONFIDENTIAL; the amounts
     * RESTRICTED-FINANCIAL): every amount in minor units of {@code currency} at {@code scale}. No reason, no person.
     */
    public record RiskPolicyView(
            String id,
            String operation,
            int version,
            String engine,
            String status,
            String proposedAt,
            String decidedAt,
            String effectiveFrom,
            String effectiveTo,
            String currency,
            int scale,
            List<RiskRuleView> rules,
            List<RiskBandView> scoreBands,
            List<RiskBandView> ratingBands,
            List<RiskFailSafeBandView> failSafeBands,
            String challengeAssurance,
            Long overrideThresholdMinor,
            Integer holdValidityHours) {}

    public RiskPolicyReceipt propose(String idempotencyKey, RiskPolicyController.RiskPolicyProposalRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RiskPolicy policy = guarded(() -> policy(request));
        return flow.door(RiskSpans.POLICY_PROPOSE, () -> keyed(actor, idempotencyKey, "propose|" + request, uow -> {
            RiskPolicyAdministration.Proposed proposed =
                    administration.propose(uow, policy, request.reason(), actor, correlation);
            return new RiskPolicyReceipt(proposed.id().value().toString(), proposed.operation().name(),
                    proposed.version(), "PROPOSED", null);
        }));
    }

    public RiskPolicyReceipt activate(String idempotencyKey, String rawId, String reason) {
        RiskPolicyVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(RiskSpans.POLICY_ACTIVATE, () -> keyed(actor, idempotencyKey,
                "activate|" + id.value() + "|" + reason,
                uow -> receipt(administration.activate(uow, id, actor, reason, correlation))));
    }

    public RiskPolicyReceipt reject(String idempotencyKey, String rawId, String reason) {
        RiskPolicyVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(RiskSpans.POLICY_REJECT, () -> keyed(actor, idempotencyKey,
                "reject|" + id.value() + "|" + reason,
                uow -> receipt(administration.reject(uow, id, actor, reason, correlation))));
    }

    /** The operation's version ACTIVE now - a read, unkeyed, in one transaction; none active is {@code risk.PolicyNotActive}. */
    public RiskPolicyView inForce(String rawOperation) {
        RiskOperation operation;
        try {
            operation = RiskOperation.valueOf(rawOperation);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw notFound();
        }
        return guarded(() -> flow.door(RiskSpans.POLICY_READ,
                () -> transactions.inTransaction(uow -> view(policies.inForce(uow, operation)))));
    }

    /** One version by id, in any state - a read, unkeyed, in one transaction. */
    public RiskPolicyView version(String rawId) {
        RiskPolicyVersionId id = versionId(rawId);
        return guarded(() -> flow.door(RiskSpans.POLICY_READ,
                () -> transactions.inTransaction(uow -> view(policies.version(uow, id)))));
    }

    // ------------------------------------------------------------------ the policy in the domain's words

    static RiskPolicy policy(RiskPolicyController.RiskPolicyProposalRequest request) {
        RiskOperation operation = named(RiskOperation.class, request.operation(), "an operation");
        RiskEngine engine = engine(request.engine());
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(request.currency());
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new RiskPolicy.PolicyInvalid("the policy's currency is an ISO 4217 code");
        }
        List<RiskRule> rules = new ArrayList<>();
        int ordinal = 1;
        for (RiskPolicyController.RiskRuleRequest rule : request.rules()) {
            RuleKind kind = named(RuleKind.class, rule.kind(), "a rule kind");
            rules.add(new RiskRule(ordinal++, kind, parameters(kind, rule.parameters()),
                    rule.weight() == null ? OptionalInt.empty() : OptionalInt.of(rule.weight()),
                    rule.hardDecision() == null ? Optional.empty()
                            : Optional.of(named(RiskDecision.class, rule.hardDecision(), "a decision"))));
        }
        List<ScoreBand> scoreBands = new ArrayList<>();
        for (RiskPolicyController.RiskScoreBandRequest band : orEmpty(request.scoreBands())) {
            scoreBands.add(new ScoreBand(band.from(), band.to(), named(RiskDecision.class, band.decision(), "a decision")));
        }
        List<RatingBand> ratingBands = new ArrayList<>();
        for (RiskPolicyController.RiskRatingBandRequest band : orEmpty(request.ratingBands())) {
            ratingBands.add(new RatingBand(band.from(), band.to(), named(RiskRating.class, band.rating(), "a rating")));
        }
        List<FailSafeBand> failSafeBands = new ArrayList<>();
        for (RiskPolicyController.RiskFailSafeBandRequest band : orEmpty(request.failSafeBands())) {
            failSafeBands.add(new FailSafeBand(band.fromMinor(),
                    band.toMinor() == null ? OptionalLong.empty() : OptionalLong.of(band.toMinor()),
                    named(RiskDecision.class, band.decision(), "a decision")));
        }
        Optional<RiskAssurance> assurance = request.challengeAssurance() == null ? Optional.empty()
                : Optional.of(named(RiskAssurance.class, request.challengeAssurance(), "an assurance"));
        Optional<Money> threshold = request.overrideThresholdMinor() == null ? Optional.empty()
                : Optional.of(Money.ofMinorUnits(request.overrideThresholdMinor(), currency));
        Optional<Duration> hold;
        if (request.holdValidityHours() != null) {
            hold = Optional.of(Duration.ofHours(request.holdValidityHours()));
        } else if (RiskPolicy.namesHoldValidity(operation, rules, scoreBands)) {
            // The plan's default (section 5): a version that can decide HOLD and names no validity takes 72 h - a
            // withdrawal's always, a card payment's when a band or a rule holds (P13-TSK-010); a card version that
            // never holds names none.
            hold = Optional.of(RiskPolicy.DEFAULT_HOLD_VALIDITY);
        } else {
            hold = Optional.empty();
        }
        return new RiskPolicy(operation, engine, currency, rules, scoreBands, ratingBands, failSafeBands, assurance,
                threshold, hold);
    }

    /** {@code raw}'s entries parsed by the kind's declared types; an undeclared name refused before any parse. */
    static RuleParameters parameters(RuleKind kind, Map<String, Object> raw) {
        Map<String, Object> given = raw == null ? Map.of() : raw;
        Set<String> declared = new TreeSet<>();
        for (RuleKind.Parameter parameter : kind.parameters()) {
            declared.add(parameter.name());
        }
        for (String name : new TreeSet<>(given.keySet())) {
            if (!declared.contains(name)) {
                throw new RiskPolicy.PolicyInvalid(kind.name() + " declares no parameter '" + name + "'");
            }
        }
        Map<String, ParameterValue> values = new LinkedHashMap<>();
        for (RuleKind.Parameter parameter : kind.parameters()) {
            Object value = given.get(parameter.name());
            if (value == null) {
                throw new RiskPolicy.PolicyInvalid(
                        kind.name() + " requires the parameter '" + parameter.name() + "' (" + parameter.type() + ")");
            }
            values.put(parameter.name(), value(kind, parameter, value));
        }
        return RuleParameters.of(kind, values);
    }

    private static ParameterValue value(RuleKind kind, RuleKind.Parameter parameter, Object raw) {
        String where = kind.name() + "'s parameter '" + parameter.name() + "'";
        return switch (parameter.type()) {
            case COUNT -> new ParameterValue.Count(integral(raw, where));
            case MINOR_AMOUNT -> new ParameterValue.MinorAmount(integral(raw, where));
            case BASIS_POINTS -> new ParameterValue.BasisPoints(Math.toIntExact(bounded(integral(raw, where), where)));
            case DAYS -> new ParameterValue.Days(Math.toIntExact(bounded(integral(raw, where), where)));
            case HOURS -> new ParameterValue.Hours(Math.toIntExact(bounded(integral(raw, where), where)));
            case WINDOW -> new ParameterValue.Window(named(WindowKind.class, text(raw, where), "a window"));
            case VELOCITY_DIMENSION -> new ParameterValue.Dimension(
                    named(VelocityDimension.class, text(raw, where), "a dimension"));
            case ASSURANCE -> new ParameterValue.Assurance(named(RiskAssurance.class, text(raw, where), "an assurance"));
            case RATING -> new ParameterValue.Rating(named(RiskRating.class, text(raw, where), "a rating"));
            case CORRIDOR_SET -> {
                if (!(raw instanceof Collection<?> items)) {
                    throw new RiskPolicy.PolicyInvalid(where + " is a list of corridor codes");
                }
                Set<String> codes = new TreeSet<>();
                for (Object item : items) {
                    codes.add(text(item, where));
                }
                yield new ParameterValue.Corridors(codes);
            }
        };
    }

    /**
     * A JSON integer or digit string read exactly as a whole number - never a fraction: a JSON number with a fraction
     * or an exponent arrives as a floating-point value and is refused outright, so no float's rounding and no unbounded
     * decimal (the Phase 9 → 10 transition's {@code DecimalText} rule) ever reaches a threshold.
     */
    private static long integral(Object raw, String where) {
        try {
            if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
                return ((Number) raw).longValue();
            }
            if (raw instanceof BigInteger big) {
                return big.longValueExact();
            }
            if (raw instanceof BigDecimal decimal) {
                return decimal.toBigIntegerExact().longValueExact();
            }
            if (raw instanceof String text && text.matches("-?[0-9]{1,18}")) {
                return Long.parseLong(text);
            }
        } catch (ArithmeticException notWhole) {
            throw new RiskPolicy.PolicyInvalid(where + " is a whole number");
        }
        throw new RiskPolicy.PolicyInvalid(where + " is a whole number");
    }

    private static long bounded(long value, String where) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new RiskPolicy.PolicyInvalid(where + " is out of range");
        }
        return value;
    }

    private static String text(Object raw, String where) {
        if (raw instanceof String text) {
            return text;
        }
        throw new RiskPolicy.PolicyInvalid(where + " is a name");
    }

    private static RiskEngine engine(String name) {
        for (RiskEngine version : RiskEngine.values()) {
            if (version.name().equals(name)) {
                return version;
            }
        }
        throw new RiskPolicy.PolicyInvalid("the policy names an engine version this build does not hold");
    }

    private static <E extends Enum<E>> E named(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new RiskPolicy.PolicyInvalid("the policy names something that is not " + what);
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ------------------------------------------------------------------ the view

    static RiskPolicyView view(RiskPolicyVersion version) {
        RiskPolicyStore.VersionRow row = version.row();
        RiskPolicy policy = version.policy();
        List<RiskRuleView> rules = new ArrayList<>();
        for (RiskRule rule : policy.rules()) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (RuleKind.Parameter parameter : rule.kind().parameters()) {
                parameters.put(parameter.name(), raw(rule.parameters().value(parameter.name())));
            }
            rules.add(new RiskRuleView(rule.ordinal(), rule.kind().name(), parameters,
                    rule.weight().isPresent() ? rule.weight().getAsInt() : null,
                    rule.hardDecision().map(Enum::name).orElse(null)));
        }
        return new RiskPolicyView(
                row.id().value().toString(),
                row.operation().name(),
                row.version(),
                row.engine(),
                row.status().name(),
                row.proposedAt().toString(),
                row.decidedAt().map(Instant::toString).orElse(null),
                row.effectiveFrom().map(Instant::toString).orElse(null),
                row.effectiveTo().map(Instant::toString).orElse(null),
                policy.currency().code(),
                policy.currency().minorUnits(),
                rules,
                policy.scoreBands().stream().map(b -> new RiskBandView(b.from(), b.to(), b.decision().name(), null)).toList(),
                policy.ratingBands().stream().map(b -> new RiskBandView(b.from(), b.to(), null, b.rating().name())).toList(),
                policy.failSafeBands().stream().map(b -> new RiskFailSafeBandView(b.fromMinor(),
                        b.toMinor().isPresent() ? b.toMinor().getAsLong() : null, b.decision().name())).toList(),
                policy.challengeAssurance().map(Enum::name).orElse(null),
                policy.overrideThreshold().map(Money::minorUnits).orElse(null),
                policy.holdValidity().map(d -> (int) d.toHours()).orElse(null));
    }

    private static Object raw(ParameterValue value) {
        return switch (value) {
            case ParameterValue.Count count -> count.value();
            case ParameterValue.MinorAmount amount -> amount.value();
            case ParameterValue.BasisPoints bps -> bps.value();
            case ParameterValue.Days days -> days.value();
            case ParameterValue.Hours hours -> hours.value();
            case ParameterValue.Window window -> window.value().name();
            case ParameterValue.Dimension dimension -> dimension.value().name();
            case ParameterValue.Corridors corridors -> List.copyOf(corridors.sorted());
            case ParameterValue.Assurance assurance -> assurance.value().name();
            case ParameterValue.Rating rating -> rating.value().name();
        };
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private RiskPolicyReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, RiskPolicyReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    RiskPolicyReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.operation() + "|" + receipt.version() + "|"
                            + receipt.status() + "|" + (receipt.retiredId() == null ? "" : receipt.retiredId());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a policy act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new RiskPolicyReceipt(stored[0], stored[1], Integer.parseInt(stored[2]), stored[3],
                stored[4].isEmpty() ? null : stored[4]);
    }

    private static RiskPolicyReceipt receipt(RiskPolicyAdministration.Decided decided) {
        return new RiskPolicyReceipt(decided.id().value().toString(), decided.operation().name(), decided.version(),
                decided.status().name(), decided.retired().map(id -> id.value().toString()).orElse(null));
    }

    /**
     * {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own after the act rolled back
     * ({@code INV-RSK-07}), then refused as before.
     */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (RiskPolicyAdministration.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                administration.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (RiskPolicyAdministration.PolicyNotFound unknown) {
            throw notFound();
        } catch (RiskPolicyAdministration.SelfApprovalRefused self) {
            throw refused(RiskErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RiskPolicyAdministration.PolicyStale stale) {
            throw refused(RiskErrorCode.POLICY_STALE, stale);
        } catch (RiskPolicies.PolicyNotActive none) {
            throw refused(RiskErrorCode.POLICY_NOT_ACTIVE, none);
        } catch (RiskReasons.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        } catch (RiskPolicy.PolicyInvalid invalid) {
            throw refused(RiskErrorCode.POLICY_INVALID, invalid);
        }
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A risk policy command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk policy version answers the request", "no such record.");
    }

    private static RiskPolicyVersionId versionId(String raw) {
        try {
            return RiskPolicyVersionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk policy command runs inside a correlation scope"))
                .correlationId();
    }
}
