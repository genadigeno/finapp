package com.finapp.app.payments;

import com.finapp.payments.BackdatedRoutingPolicyVersionException;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.PaymentDirection;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentsErrorCode;
import com.finapp.payments.RailAvailability;
import com.finapp.payments.RailId;
import com.finapp.payments.RoutingAdministration;
import com.finapp.payments.RoutingDecision;
import com.finapp.payments.RoutingPolicyVersion;
import com.finapp.payments.RoutingRule;
import com.finapp.payments.RoutingStep;
import com.finapp.payments.UnknownPaymentException;
import com.finapp.payments.UnknownRailException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's routing surface behind the controller (`P7-TSK-003`) — the
 * {@code FeeScheduleOperations} shape: parse and refuse at the boundary, one transaction per
 * command, domain refusals translated to the registered codes.
 *
 * <p><strong>The explanation renders the judged amount.</strong> It is an operator-only read
 * under {@code PAYMENT_ROUTING_ADMINISTER}, audited per read, and the amount is a judged
 * INPUT — an explanation that hid it could not explain a ceiling rejection. The
 * classification stays {@code RESTRICTED-FINANCIAL} at the column; this surface is one of
 * its permission-gated readers.
 */
@RequiredArgsConstructor
public class RoutingPolicyOperations {

    /** One rule as declared, ceilings in minor units of the rule's currency. */
    public record RuleView(
            int ruleIndex,
            String direction,
            String instrumentKind,
            String currency,
            Long ceilingAmountMinor,
            List<String> rails) {}

    /** An immutable version, rules in judgement order. */
    public record VersionView(
            String routingPolicyVersionId,
            int version,
            String effectiveFrom,
            String createdBy,
            List<RuleView> rules) {}

    /**
     * The recorded availability fact, as every instance will read it. Since `P17-TSK-032` it names who recorded it - an
     * identity's id, never a name - absent with the instant for a rail never switched.
     */
    public record AvailabilityView(
            String rail, boolean available, String reason, String changedAt, String changedBy) {}

    /** One step of the trail: the candidate, the verdict, what it was judged from. */
    public record StepView(
            int stepIndex,
            String rail,
            String verdict,
            String rejection,
            boolean railAvailable,
            Integer descriptorVersion) {}

    /** The explanation: the pinned version, the judged inputs, the trail. */
    public record ExplanationView(
            String paymentIntentId,
            String routingDecisionId,
            int policyVersion,
            String direction,
            String instrumentKind,
            String amount,
            String currency,
            Integer matchedRuleIndex,
            String chosenRail,
            String decidedAt,
            List<StepView> steps) {}

    @NonNull private final RoutingAdministration administration;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** The version list's read (`P17-TSK-032`) - last, so no positional argument moved. */
    @NonNull private final com.finapp.payments.PaymentCollections collections;

    /** The availability facts every instance routes by (`P17-TSK-032`, the rails' read). */
    @NonNull private final com.finapp.payments.RoutingStore<Connection> routingStore;

    /** The declared rails (`P17-TSK-032`): the rails' read lists each, a never-switched one available. */
    @NonNull private final com.finapp.payments.PaymentRails rails;

    static final String VERSIONS = "payments.routing-versions";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    /** A page of routing policy versions, newest number first: {@code nextCursor} absent on the last one. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record VersionPage(List<VersionView> items, String nextCursor) {}

    /**
     * Every declared rail's availability as every instance routes by it: the newest act's fact, or - for a rail never
     * switched - available, with no reason and no instant (ADR-0060 section 4: an absent row means available).
     */
    public record RailList(List<AvailabilityView> items) {}

    /** Creates an immutable version, effective forward; a replay answers the original. */
    public VersionView createVersion(CreateRoutingPolicyVersionRequest body, String key) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(key, "key must not be null");
        List<RoutingPolicyVersion.NewRule> rules =
                body.rules().stream().map(RoutingPolicyOperations::parsedRule).toList();
        RoutingAdministration.NewVersion request =
                new RoutingAdministration.NewVersion(
                        rules, Optional.ofNullable(body.effectiveFrom()), body.reason());
        try {
            return renderVersion(
                    inOneTransaction(
                                    unitOfWork ->
                                            administration.createVersion(
                                                    unitOfWork, request, key))
                            .version());
        } catch (UnknownRailException unknown) {
            throw unknownRail();
        } catch (BackdatedRoutingPolicyVersionException backdated) {
            throw new ApiException(
                    PaymentsErrorCode.ROUTING_POLICY_NOT_FORWARD,
                    "A routing policy version would have taken effect in the past",
                    "a routing policy version takes effect forward; it cannot be backdated.");
        } catch (IllegalArgumentException malformed) {
            // The domain's own construction bounds, restated by the request annotations;
            // reachable only if the two ever disagree, and a 422 is honest either way.
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A routing policy version carried rules the domain refuses",
                    "the routing rules are not valid.");
        }
    }

    /**
     * Records the rail's availability — the newest act wins, every act is audited. Keyed since `P17-TSK-032` (finapp-admin-panel
     * ADR-C135 decision 2): a retried key answers the original act and records nothing; the key with another body is the 409.
     */
    public AvailabilityView setAvailability(String rawRail, SetRailAvailabilityRequest body, String idempotencyKey) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        RailId rail = parsedRail(rawRail);
        try {
            RailAvailability fact =
                    inOneTransaction(
                                    unitOfWork ->
                                            administration.setAvailability(
                                                    unitOfWork, rail, body.available(), body.reason(), idempotencyKey))
                            .fact();
            return availabilityView(fact);
        } catch (UnknownRailException unknown) {
            throw unknownRail();
        }
    }

    /** Every declared rail's availability, ordered by the rail's name (`P17-TSK-032`, gap G-47). Unrecorded: configuration. */
    public RailList rails() {
        java.util.Map<RailId, RailAvailability> facts = inOneTransaction(routingStore::availabilityByRail);
        return new RailList(rails.declaredIds().stream()
                .sorted(java.util.Comparator.comparing(RailId::value))
                .map(rail -> Optional.ofNullable(facts.get(rail))
                        .map(RoutingPolicyOperations::availabilityView)
                        .orElse(new AvailabilityView(rail.value(), true, null, null, null)))
                .toList());
    }

    /**
     * A page of the routing policy versions, newest number first (`P17-TSK-032`, gap G-46) - each with its rules, as its
     * creation answered it. Unrecorded: configuration, as the fee schedules' reads are.
     */
    public VersionPage versions(String cursor, String limit) {
        int size = com.finapp.app.api.OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<Integer> before = versionCursor(cursor);
        List<RoutingPolicyVersion> rows = inOneTransaction(uow -> collections.routingVersions(uow, before, size + 1));
        List<RoutingPolicyVersion> served = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size
                ? com.finapp.app.api.OperatorCollection.cursor(VERSIONS, Integer.toString(served.get(served.size() - 1).version()))
                : null;
        return new VersionPage(served.stream().map(RoutingPolicyOperations::renderVersion).toList(), next);
    }

    static Optional<Integer> versionCursor(String cursor) {
        return com.finapp.app.api.OperatorCollection.key(VERSIONS, cursor, 1).map(key -> {
            int version;
            try {
                version = Integer.parseInt(key.get(0));
            } catch (NumberFormatException unreadable) {
                throw com.finapp.app.api.OperatorCollection.invalidCursor();
            }
            if (version < 1) {
                throw com.finapp.app.api.OperatorCollection.invalidCursor();
            }
            return version;
        });
    }

    /** One version by its number - what a payment's routing explanation names. Unknown and malformed are one 404. */
    public VersionView version(String rawVersion) {
        int number;
        try {
            number = Integer.parseInt(rawVersion);
        } catch (NumberFormatException | NullPointerException malformed) {
            throw versionNotFound();
        }
        if (number < 1) {
            throw versionNotFound();
        }
        return inOneTransaction(uow -> collections.routingVersion(uow, number))
                .map(RoutingPolicyOperations::renderVersion)
                .orElseThrow(RoutingPolicyOperations::versionNotFound);
    }

    private static AvailabilityView availabilityView(RailAvailability fact) {
        return new AvailabilityView(
                fact.rail().value(),
                fact.available(),
                fact.reason(),
                fact.changedAt().toString(),
                fact.changedBy());
    }

    private static ApiException versionNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A routing policy version read found nothing at the number",
                "the requested resource does not exist.");
    }

    /** The payment's routing explanation — audited; unknown and unrouted are one 404. */
    public ExplanationView explain(String rawIntentId) {
        PaymentIntentId intentId = parsedIntentOrAbsent(rawIntentId);
        try {
            RoutingAdministration.Explanation explanation =
                    inOneTransaction(unitOfWork -> administration.explain(unitOfWork, intentId));
            return renderExplanation(explanation);
        } catch (UnknownPaymentException unknown) {
            throw routingNotFound();
        }
    }

    // -----------------------------------------------------------------

    private static RoutingPolicyVersion.NewRule parsedRule(
            CreateRoutingPolicyVersionRequest.RuleBody body) {
        PaymentDirection direction = parsedDirection(body.direction());
        InstrumentKind kind = parsedKind(body.instrumentKind());
        Optional<CurrencyCode> currency;
        try {
            currency = Optional.ofNullable(body.currency()).map(CurrencyCode::of);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A routing rule named a malformed currency",
                    "the currency is not a valid ISO 4217 code.");
        }
        Optional<Money> ceiling;
        if (body.ceilingAmountMinor() == null) {
            ceiling = Optional.empty();
        } else if (currency.isEmpty()) {
            // The domain refuses this too; refused here so the message can say which field.
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A routing rule carried a ceiling without a currency",
                    "a ceiling is minor units of the rule's currency; name one.");
        } else {
            ceiling = Optional.of(
                    Money.ofMinorUnits(body.ceilingAmountMinor(), currency.orElseThrow()));
        }
        List<RailId> rails = body.rails().stream()
                .map(RoutingPolicyOperations::parsedRail)
                .toList();
        // P9-TSK-019: an optional matcher - absent is false, so every older body means what it meant.
        return new RoutingPolicyVersion.NewRule(direction, kind, currency, ceiling, rails,
                Boolean.TRUE.equals(body.requiresDestinationCountry()));
    }

    // Two small parsers rather than one <E extends Enum<E>> helper: the self-referential
    // bound sends the floating-point signature scanner into unbounded recursion
    // (NoFloatingPointMoneyRulesTest), and two enums do not earn a generic anyway.
    private static PaymentDirection parsedDirection(String raw) {
        try {
            return PaymentDirection.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A routing rule named an unknown direction",
                    "the direction is not one this platform defines.");
        }
    }

    private static InstrumentKind parsedKind(String raw) {
        try {
            return InstrumentKind.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A routing rule named an unknown instrument kind",
                    "the instrument kind is not one this platform defines.");
        }
    }

    private static RailId parsedRail(String raw) {
        try {
            return RailId.of(raw);
        } catch (IllegalArgumentException malformed) {
            // Malformed folds into unknown (malformed-equals-absent): both answer the same
            // 422, so the shape rule leaks nothing the refusal would not say anyway.
            throw unknownRail();
        }
    }

    private static PaymentIntentId parsedIntentOrAbsent(String raw) {
        try {
            return PaymentIntentId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw routingNotFound();
        }
    }

    private static VersionView renderVersion(RoutingPolicyVersion version) {
        return new VersionView(
                version.id().value().toString(),
                version.version(),
                version.effectiveFrom().toString(),
                version.createdBy(),
                version.rules().stream()
                        .map(RoutingPolicyOperations::renderRule)
                        .toList());
    }

    private static RuleView renderRule(RoutingRule rule) {
        return new RuleView(
                rule.ruleIndex(),
                rule.direction().name(),
                rule.instrumentKind().name(),
                rule.currency().map(CurrencyCode::code).orElse(null),
                rule.ceiling().map(Money::minorUnits).orElse(null),
                rule.rails().stream().map(RailId::value).toList());
    }

    private static ExplanationView renderExplanation(
            RoutingAdministration.Explanation explanation) {
        RoutingDecision decision = explanation.decision();
        return new ExplanationView(
                decision.intentId().value().toString(),
                decision.id().value().toString(),
                explanation.version().version(),
                decision.direction().name(),
                decision.instrumentKind().name(),
                decision.amount().toBigDecimal().toPlainString(),
                decision.amount().currency().code(),
                decision.matchedRuleIndex().orElse(null),
                decision.chosenRail().map(RailId::value).orElse(null),
                decision.createdAt().toString(),
                decision.steps().stream()
                        .map(RoutingPolicyOperations::renderStep)
                        .toList());
    }

    private static StepView renderStep(RoutingStep step) {
        return new StepView(
                step.stepIndex(),
                step.rail().value(),
                step.verdict().name(),
                step.rejection().map(Enum::name).orElse(null),
                step.railAvailable(),
                step.descriptorVersion().orElse(null));
    }

    private static ApiException unknownRail() {
        return new ApiException(
                PaymentsErrorCode.UNKNOWN_RAIL,
                "The named payment rail is not declared by this platform",
                "no such rail is declared.");
    }

    private static ApiException routingNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No routing decision matches the requested payment",
                "no such payment routing.");
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
