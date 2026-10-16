package com.finapp.app.lending;

import com.finapp.app.api.DecimalText;
import com.finapp.lending.AgreementTemplate;
import com.finapp.lending.AllocationComponent;
import com.finapp.lending.AllocationSequence;
import com.finapp.lending.DayCountConvention;
import com.finapp.lending.EngineVersion;
import com.finapp.lending.LendingCapitalAdministration;
import com.finapp.lending.LendingEngine;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTerms;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.lending.LoanTermsVersionId;
import com.finapp.lending.OriginationFeeTreatment;
import com.finapp.lending.OverpaymentTreatment;
import com.finapp.lending.RateType;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the terms doors (`P11-TSK-005`): parse the proposal exactly into the domain's words - an unknown
 * product, an unknown or unimplemented convention, an unregistered engine version or template, a missing kind-specific
 * term, each {@code lending.TermsInvalid} - key every act per principal ({@code lending.terms:<type>:<id>}), run each in
 * one transaction, and translate the domain's refusals onto the error contract. The four-eyes rule, the machine, the
 * frozen term set and the bounds are the domain's and {@code lending V003}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class LoanTermsDesk {

    static final String SCOPE = "lending.terms:";

    @NonNull private final LoanTermsAdministration administration;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;

    /** A version's receipt: its id, product, number and status, and the predecessor an activation retired. */
    public record LoanTermsReceipt(String id, String product, int version, String status, String retiredId) {}

    /**
     * A terms version as the operator reads it - its machine row and its whole term set (RESTRICTED-FINANCIAL): every
     * amount in minor units of {@code currency} at {@code scale}, the rate a decimal fraction string.
     */
    public record LoanTermsView(
            String id,
            String product,
            int version,
            String status,
            String proposedAt,
            String decidedAt,
            String effectiveFrom,
            String effectiveTo,
            String currency,
            int scale,
            String nominalAnnualRate,
            String rateType,
            Long originationFeeMinor,
            Integer originationFeeBps,
            String originationFeeTreatment,
            long lateFeeMinor,
            int lateFeeDay,
            long lateFeeCapMinor,
            int graceDays,
            List<Integer> delinquencyBounds,
            int defaultThresholdDays,
            String allocationSequence,
            List<String> allocationOrder,
            String overpaymentTreatment,
            boolean autoCollection,
            boolean partialCollection,
            String instalmentRounding,
            String interestRounding,
            String dayCount,
            String servicingZone,
            List<Integer> allowedDays,
            Integer minFirstPeriodDays,
            int offerValidityDays,
            int payoffHorizonDays,
            Integer disbursementDeadlineHours,
            Long minimumPaymentFloorMinor,
            Integer minimumPaymentRatioBps,
            Integer paymentDueDays,
            Integer scheduleEngineVersion,
            int accrualEngineVersion,
            int allocationEngineVersion,
            Integer statementEngineVersion,
            String agreementTemplateId,
            int agreementTemplateVersion) {}

    public LoanTermsReceipt propose(String idempotencyKey, LoanTermsController.LoanTermsProposalRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        LoanTerms terms = guarded(() -> terms(request));
        return keyed(actor, idempotencyKey, "propose|" + request, uow -> {
            LoanTermsAdministration.Proposed proposed = administration.propose(uow, terms, request.reason(), actor, correlation);
            return new LoanTermsReceipt(proposed.id().value().toString(), proposed.product().name(), proposed.version(),
                    "PROPOSED", null);
        });
    }

    public LoanTermsReceipt approve(String idempotencyKey, String rawId, String reason) {
        LoanTermsVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "approve|" + id.value() + "|" + reason,
                uow -> receipt(administration.approve(uow, id, actor, reason, correlation)));
    }

    public LoanTermsReceipt reject(String idempotencyKey, String rawId, String reason) {
        LoanTermsVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "reject|" + id.value() + "|" + reason,
                uow -> receipt(administration.reject(uow, id, actor, reason, correlation)));
    }

    /** The product's terms at {@code rawAt}, or {@code ACTIVE} now - a read, unkeyed, in one transaction. */
    public LoanTermsView activeAt(String rawProduct, String rawAt) {
        Optional<Instant> at = instant(rawAt);
        LoanProduct product;
        try {
            product = LoanProduct.valueOf(rawProduct);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw notFound();
        }
        return guarded(() -> transactions.inTransaction(uow -> view(administration.activeAt(uow, product, at))));
    }

    // ------------------------------------------------------------------ the terms in the domain's words

    private static LoanTerms terms(LoanTermsController.LoanTermsProposalRequest request) {
        LoanProduct product = named(LoanProduct.class, request.product(), "a product lending offers");
        CurrencyCode currency = product.currency();
        if (!currency.code().equals(request.currency())) {
            throw new LoanTerms.TermsInvalid("the terms' amounts are in the product's currency");
        }
        BigDecimal rate;
        try {
            rate = DecimalText.parse(request.nominalAnnualRate());
        } catch (NumberFormatException malformed) {
            throw new LoanTerms.TermsInvalid("the nominal annual rate is a decimal fraction, 0.05 for 5 %");
        }
        if (request.originationFeeMinor() != null && request.originationFeeBps() != null) {
            throw new LoanTerms.TermsInvalid("the origination fee is an amount or basis points, exactly one");
        }
        LoanTerms.OriginationFee fee = request.originationFeeBps() != null
                ? LoanTerms.OriginationFee.ofBasisPoints(request.originationFeeBps())
                : request.originationFeeMinor() != null
                        ? LoanTerms.OriginationFee.ofAmount(Money.ofMinorUnits(request.originationFeeMinor(), currency))
                        : new LoanTerms.OriginationFee(Optional.empty(), Optional.empty());
        List<AllocationComponent> order = new ArrayList<>();
        for (String component : request.allocationOrder()) {
            order.add(named(AllocationComponent.class, component, "an allocation component"));
        }
        Optional<LoanTerms.InstalmentTerms> instalment = Optional.empty();
        if (request.minFirstPeriodDays() != null || request.disbursementDeadlineHours() != null
                || request.scheduleEngineVersion() != null) {
            instalment = Optional.of(new LoanTerms.InstalmentTerms(
                    present(request.minFirstPeriodDays(), "an instalment loan's shortest first period"),
                    present(request.disbursementDeadlineHours(), "an instalment loan's disbursement deadline"),
                    engine(LendingEngine.SCHEDULE, present(request.scheduleEngineVersion(), "a schedule engine version"))));
        }
        Optional<LoanTerms.RevolvingTerms> revolving = Optional.empty();
        if (request.minimumPaymentFloorMinor() != null || request.minimumPaymentRatioBps() != null
                || request.paymentDueDays() != null || request.statementEngineVersion() != null) {
            revolving = Optional.of(new LoanTerms.RevolvingTerms(
                    Money.ofMinorUnits(present(request.minimumPaymentFloorMinor(), "a minimum payment floor"), currency),
                    present(request.minimumPaymentRatioBps(), "a minimum payment ratio"),
                    present(request.paymentDueDays(), "a statement's payment-due days"),
                    engine(LendingEngine.STATEMENT, present(request.statementEngineVersion(), "a statement engine version"))));
        }
        return LoanTerms.builder()
                .product(product)
                .nominalAnnualRate(rate)
                .rateType(named(RateType.class, request.rateType(), "a rate type an engine implements"))
                .originationFee(fee)
                .originationFeeTreatment(named(OriginationFeeTreatment.class, request.originationFeeTreatment(),
                        "an origination fee treatment"))
                .lateFee(Money.ofMinorUnits(request.lateFeeMinor(), currency))
                .lateFeeDay(request.lateFeeDay())
                .lateFeeCap(Money.ofMinorUnits(request.lateFeeCapMinor(), currency))
                .graceDays(request.graceDays())
                .delinquencyBounds(request.delinquencyBounds())
                .defaultThresholdDays(request.defaultThresholdDays())
                .allocationSequence(named(AllocationSequence.class, request.allocationSequence(), "an allocation sequence"))
                .allocationOrder(order)
                .overpaymentTreatment(named(OverpaymentTreatment.class, request.overpaymentTreatment(),
                        "an overpayment treatment"))
                .autoCollection(request.autoCollection())
                .partialCollection(request.partialCollection())
                .instalmentRounding(named(RoundingMode.class, request.instalmentRounding(), "a rounding mode"))
                .interestRounding(named(RoundingMode.class, request.interestRounding(), "a rounding mode"))
                .dayCount(named(DayCountConvention.class, request.dayCount(), "a day count an engine implements"))
                .servicingZone(request.servicingZone())
                .allowedDays(request.allowedDays())
                .offerValidityDays(request.offerValidityDays())
                .payoffHorizonDays(request.payoffHorizonDays())
                .accrualEngine(engine(LendingEngine.ACCRUAL, request.accrualEngineVersion()))
                .allocationEngine(engine(LendingEngine.ALLOCATION, request.allocationEngineVersion()))
                .instalment(instalment)
                .revolving(revolving)
                .agreementTemplate(AgreementTemplate.of(request.agreementTemplateId(), request.agreementTemplateVersion())
                        .orElseThrow(() -> new LoanTerms.TermsInvalid("the terms name an agreement template this build"
                                + " does not register")))
                .build();
    }

    private static EngineVersion engine(LendingEngine engine, int number) {
        return EngineVersion.of(engine, number)
                .orElseThrow(() -> new LoanTerms.TermsInvalid("the terms name a " + engine.name().toLowerCase(java.util.Locale.ROOT)
                        + " engine version this build does not hold"));
    }

    private static <T> T present(T value, String what) {
        if (value == null) {
            throw new LoanTerms.TermsInvalid("the terms name " + what);
        }
        return value;
    }

    private static <E extends Enum<E>> E named(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new LoanTerms.TermsInvalid("the terms name something that is not " + what);
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
                    "Loan terms were read at an instant that is not one", "'at' is an ISO-8601 instant.");
        }
    }

    // ------------------------------------------------------------------ the view

    private static LoanTermsView view(LoanTermsStore.LoanTermsVersion version) {
        LoanTermsStore.VersionRow row = version.row();
        LoanTerms terms = version.terms();
        return new LoanTermsView(
                row.id().value().toString(),
                row.product().name(),
                row.version(),
                row.status().name(),
                row.proposedAt().toString(),
                row.decidedAt().map(Instant::toString).orElse(null),
                row.effectiveFrom().map(Instant::toString).orElse(null),
                row.effectiveTo().map(Instant::toString).orElse(null),
                terms.currency().code(),
                terms.currency().minorUnits(),
                terms.nominalAnnualRate().toPlainString(),
                terms.rateType().name(),
                terms.originationFee().amount().map(Money::minorUnits).orElse(null),
                terms.originationFee().basisPoints().orElse(null),
                terms.originationFeeTreatment().name(),
                terms.lateFee().minorUnits(),
                terms.lateFeeDay(),
                terms.lateFeeCap().minorUnits(),
                terms.graceDays(),
                terms.delinquencyBounds(),
                terms.defaultThresholdDays(),
                terms.allocationSequence().name(),
                terms.allocationOrder().stream().map(Enum::name).toList(),
                terms.overpaymentTreatment().name(),
                terms.autoCollection(),
                terms.partialCollection(),
                terms.instalmentRounding().name(),
                terms.interestRounding().name(),
                terms.dayCount().name(),
                terms.servicingZone(),
                terms.allowedDays(),
                terms.instalment().map(LoanTerms.InstalmentTerms::minFirstPeriodDays).orElse(null),
                terms.offerValidityDays(),
                terms.payoffHorizonDays(),
                terms.instalment().map(LoanTerms.InstalmentTerms::disbursementDeadlineHours).orElse(null),
                terms.revolving().map(own -> own.minimumPaymentFloor().minorUnits()).orElse(null),
                terms.revolving().map(LoanTerms.RevolvingTerms::minimumPaymentRatioBps).orElse(null),
                terms.revolving().map(LoanTerms.RevolvingTerms::paymentDueDays).orElse(null),
                terms.instalment().map(own -> own.scheduleEngine().number()).orElse(null),
                terms.accrualEngine().number(),
                terms.allocationEngine().number(),
                terms.revolving().map(own -> own.statementEngine().number()).orElse(null),
                terms.agreementTemplate().id(),
                terms.agreementTemplate().version());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private LoanTermsReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, LoanTermsReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    LoanTermsReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.product() + "|" + receipt.version() + "|"
                            + receipt.status() + "|" + (receipt.retiredId() == null ? "" : receipt.retiredId());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a terms act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new LoanTermsReceipt(stored[0], stored[1], Integer.parseInt(stored[2]), stored[3],
                stored[4].isEmpty() ? null : stored[4]);
    }

    private static LoanTermsReceipt receipt(LoanTermsAdministration.Decided decided) {
        return new LoanTermsReceipt(decided.id().value().toString(), decided.product().name(), decided.version(),
                decided.status().name(), decided.retired().map(id -> id.value().toString()).orElse(null));
    }

    /**
     * {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own after the act rolled back
     * (the Phase 11 gate's R4), then refused as before.
     */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (LoanTermsAdministration.SelfApprovalRefused self) {
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
        } catch (LoanTermsAdministration.TermsNotFound unknown) {
            throw notFound();
        } catch (LoanTermsAdministration.SelfApprovalRefused self) {
            throw refused(LendingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (LoanTermsAdministration.TermsStale stale) {
            throw refused(LendingErrorCode.TERMS_STALE, stale);
        } catch (LoanTermsAdministration.ProposalPending pending) {
            throw refused(LendingErrorCode.PROPOSAL_PENDING, pending);
        } catch (LendingCapitalAdministration.ReasonRequired reason) {
            throw refused(LendingErrorCode.REASON_REQUIRED, reason);
        } catch (LoanTerms.TermsInvalid invalid) {
            throw refused(LendingErrorCode.TERMS_INVALID, invalid);
        } catch (LoanTermsAdministration.TermsNotAmortising notAmortising) {
            throw refused(LendingErrorCode.TERMS_NOT_AMORTISING, notAmortising);
        }
    }

    private static ApiException refused(LendingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A loan terms command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan terms version answers the request", "no such record.");
    }

    private static LoanTermsVersionId versionId(String raw) {
        try {
            return LoanTermsVersionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a loan terms command runs inside a correlation scope"))
                .correlationId();
    }
}
