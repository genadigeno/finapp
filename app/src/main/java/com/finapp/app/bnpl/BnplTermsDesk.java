package com.finapp.app.bnpl;

import com.finapp.app.api.DecimalText;
import com.finapp.bnpl.BnplAgreementTemplate;
import com.finapp.bnpl.BnplEngine;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplReasons;
import com.finapp.bnpl.BnplTerms;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplTermsVersion;
import com.finapp.bnpl.BnplTermsVersionId;
import com.finapp.bnpl.InstalmentFrequency;
import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationSequence;
import com.finapp.creditmath.DayCountConvention;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.LendingEngine;
import com.finapp.creditmath.OverpaymentTreatment;
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
 * The boundary half of the BNPL terms doors (`P12-TSK-004`; lending's {@code LoanTermsDesk}): parse the proposal exactly
 * into the domain's words - an unknown product, convention or engine, an unregistered template, a missing
 * product-specific term, each {@code bnpl.TermsInvalid} - key every act per principal ({@code bnpl.terms:<type>:<id>}),
 * run each in one transaction, and translate the domain's refusals onto the error contract; a refused self-approval is
 * recorded {@code FAILED} in a transaction of its own. The four-eyes rule, the machine, the frozen term set and the bounds
 * are the domain's and {@code bnpl V002}'s; nothing here decides them.
 *
 * <p><strong>The key's type</strong> is the session actor's: {@code CUSTOMER} for an operator session too, today
 * ({@code LENDING_LIFECYCLES.md} section 4.1's A5-04 note - the platform does not yet issue {@code EMPLOYEE} actors to
 * operator sessions), so the plan's {@code bnpl.terms:EMPLOYEE:<id>} reads {@code bnpl.terms:CUSTOMER:<id>} until that
 * platform debt is repaid, with no change here.
 */
@RequiredArgsConstructor
public final class BnplTermsDesk {

    static final String SCOPE = "bnpl.terms:";

    @NonNull private final BnplTermsAdministration administration;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;

    /** A version's receipt: its id, product, number and status, and the predecessor an activation retired. */
    public record BnplTermsReceipt(String id, String product, int version, String status, String retiredId) {}

    /**
     * A terms version as the operator reads it - its machine row and its whole term set (RESTRICTED-FINANCIAL): every
     * amount in minor units of {@code currency} at {@code scale}, the rate a decimal fraction string. No reason, no person.
     */
    public record BnplTermsView(
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
            String frequency,
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
            int collectionRetryDays,
            String instalmentRounding,
            String interestRounding,
            String dayCount,
            String servicingZone,
            Integer instalmentCount,
            Integer intervalDays,
            List<Integer> allowedTerms,
            Integer minFirstPeriodDays,
            int offerValidityMinutes,
            int disputeResponseDays,
            long disputeFourEyesThresholdMinor,
            int payoffHorizonDays,
            String scheduleEngine,
            String accrualEngine,
            String allocationEngine,
            String refundAllocationEngine,
            String agreementTemplateId,
            int agreementTemplateVersion) {}

    public BnplTermsReceipt propose(String idempotencyKey, BnplTermsController.BnplTermsProposalRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        BnplTerms terms = guarded(() -> terms(request));
        return keyed(actor, idempotencyKey, "propose|" + request, uow -> {
            BnplTermsAdministration.Proposed proposed =
                    administration.propose(uow, terms, request.reason(), actor, correlation);
            return new BnplTermsReceipt(proposed.id().value().toString(), proposed.product().name(), proposed.version(),
                    "PROPOSED", null);
        });
    }

    public BnplTermsReceipt approve(String idempotencyKey, String rawId, String reason) {
        BnplTermsVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "approve|" + id.value() + "|" + reason,
                uow -> receipt(administration.approve(uow, id, actor, reason, correlation)));
    }

    public BnplTermsReceipt reject(String idempotencyKey, String rawId, String reason) {
        BnplTermsVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "reject|" + id.value() + "|" + reason,
                uow -> receipt(administration.reject(uow, id, actor, reason, correlation)));
    }

    /** The product's terms at {@code rawAt}, or {@code ACTIVE} now - a read, unkeyed, in one transaction. */
    public BnplTermsView activeAt(String rawProduct, String rawAt) {
        Optional<Instant> at = instant(rawAt);
        BnplProduct product;
        try {
            product = BnplProduct.valueOf(rawProduct);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw notFound();
        }
        return guarded(() -> transactions.inTransaction(uow -> view(administration.activeAt(uow, product, at))));
    }

    /** One version by id, in any state - a read, unkeyed, in one transaction. */
    public BnplTermsView version(String rawId) {
        BnplTermsVersionId id = versionId(rawId);
        return guarded(() -> transactions.inTransaction(uow -> view(administration.version(uow, id))));
    }

    // ------------------------------------------------------------------ the terms in the domain's words

    static BnplTerms terms(BnplTermsController.BnplTermsProposalRequest request) {
        BnplProduct product = named(BnplProduct.class, request.product(), "a BNPL product");
        CurrencyCode currency = product.currency();
        if (!currency.code().equals(request.currency())) {
            throw new BnplTerms.TermsInvalid("the terms' amounts are in the product's currency");
        }
        BigDecimal rate;
        try {
            rate = DecimalText.parse(request.nominalAnnualRate());
        } catch (NumberFormatException malformed) {
            throw new BnplTerms.TermsInvalid("the nominal annual rate is a decimal fraction, 0.05 for 5 %");
        }
        List<AllocationComponent> order = new ArrayList<>();
        for (String component : request.allocationOrder()) {
            order.add(named(AllocationComponent.class, component, "an allocation component"));
        }
        Optional<BnplTerms.PayIn4Terms> payIn4 = Optional.empty();
        if (request.instalmentCount() != null || request.intervalDays() != null) {
            payIn4 = Optional.of(new BnplTerms.PayIn4Terms(
                    present(request.instalmentCount(), "pay-in-4's instalment count"),
                    present(request.intervalDays(), "pay-in-4's days between instalments"),
                    bnplEngine(request.scheduleEngine())));
        }
        Optional<BnplTerms.PayInNTerms> payInN = Optional.empty();
        if (request.allowedTerms() != null || request.minFirstPeriodDays() != null || request.interestRounding() != null
                || request.dayCount() != null || request.accrualEngine() != null) {
            payInN = Optional.of(new BnplTerms.PayInNTerms(
                    present(request.allowedTerms(), "pay-in-N's plans"),
                    present(request.minFirstPeriodDays(), "pay-in-N's first-period floor"),
                    named(DayCountConvention.class, present(request.dayCount(), "a day count"),
                            "a day count an engine implements"),
                    named(RoundingMode.class, present(request.interestRounding(), "an interest rounding mode"),
                            "a rounding mode"),
                    engine(LendingEngine.SCHEDULE, request.scheduleEngine()),
                    engine(LendingEngine.ACCRUAL, present(request.accrualEngine(), "an accrual engine version"))));
        }
        return BnplTerms.builder()
                .product(product)
                .frequency(named(InstalmentFrequency.class, request.frequency(), "an instalment frequency"))
                .nominalAnnualRate(rate)
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
                .collectionRetryDays(request.collectionRetryDays())
                .instalmentRounding(named(RoundingMode.class, request.instalmentRounding(), "a rounding mode"))
                .servicingZone(request.servicingZone())
                .offerValidityMinutes(request.offerValidityMinutes())
                .disputeResponseDays(request.disputeResponseDays())
                .disputeFourEyesThreshold(Money.ofMinorUnits(request.disputeFourEyesThresholdMinor(), currency))
                .payoffHorizonDays(request.payoffHorizonDays())
                .allocationEngine(engine(LendingEngine.ALLOCATION, request.allocationEngine()))
                .refundAllocationEngine(bnplEngine(request.refundAllocationEngine()))
                .payIn4(payIn4)
                .payInN(payInN)
                .agreementTemplate(BnplAgreementTemplate.of(request.agreementTemplateId(), request.agreementTemplateVersion())
                        .orElseThrow(() -> new BnplTerms.TermsInvalid("the terms name an agreement template this build"
                                + " does not register")))
                .build();
    }

    /** A creditmath engine version by name - one this build holds, of {@code engine}. */
    private static EngineVersion engine(LendingEngine engine, String name) {
        for (EngineVersion version : EngineVersion.values()) {
            if (version.name().equals(name) && version.engine() == engine) {
                return version;
            }
        }
        throw new BnplTerms.TermsInvalid("the terms name a " + engine.name().toLowerCase(java.util.Locale.ROOT)
                + " engine version this build does not hold");
    }

    /** A bnpl engine version by name - one this build holds. */
    private static BnplEngine bnplEngine(String name) {
        for (BnplEngine version : BnplEngine.values()) {
            if (version.name().equals(name)) {
                return version;
            }
        }
        throw new BnplTerms.TermsInvalid("the terms name a bnpl engine version this build does not hold");
    }

    private static <T> T present(T value, String what) {
        if (value == null) {
            throw new BnplTerms.TermsInvalid("the terms name " + what);
        }
        return value;
    }

    private static <E extends Enum<E>> E named(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new BnplTerms.TermsInvalid("the terms name something that is not " + what);
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
                    "BNPL terms were read at an instant that is not one", "'at' is an ISO-8601 instant.");
        }
    }

    // ------------------------------------------------------------------ the view

    static BnplTermsView view(BnplTermsVersion version) {
        BnplTermsStore.VersionRow row = version.row();
        BnplTerms terms = version.terms();
        Optional<BnplTerms.PayIn4Terms> payIn4 = terms.payIn4();
        Optional<BnplTerms.PayInNTerms> payInN = terms.payInN();
        return new BnplTermsView(
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
                terms.frequency().name(),
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
                terms.collectionRetryDays(),
                terms.instalmentRounding().name(),
                payInN.map(own -> own.interestRounding().name()).orElse(null),
                payInN.map(own -> own.dayCount().name()).orElse(null),
                terms.servicingZone(),
                payIn4.map(BnplTerms.PayIn4Terms::instalments).orElse(null),
                payIn4.map(BnplTerms.PayIn4Terms::intervalDays).orElse(null),
                payInN.map(BnplTerms.PayInNTerms::allowedTerms).orElse(null),
                payInN.map(BnplTerms.PayInNTerms::minFirstPeriodDays).orElse(null),
                terms.offerValidityMinutes(),
                terms.disputeResponseDays(),
                terms.disputeFourEyesThreshold().minorUnits(),
                terms.payoffHorizonDays(),
                payIn4.map(own -> own.scheduleEngine().name()).orElseGet(() -> payInN.orElseThrow().scheduleEngine().name()),
                payInN.map(own -> own.accrualEngine().name()).orElse(null),
                terms.allocationEngine().name(),
                terms.refundAllocationEngine().name(),
                terms.agreementTemplate().id(),
                terms.agreementTemplate().version());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private BnplTermsReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, BnplTermsReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    BnplTermsReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.product() + "|" + receipt.version() + "|"
                            + receipt.status() + "|" + (receipt.retiredId() == null ? "" : receipt.retiredId());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a terms act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new BnplTermsReceipt(stored[0], stored[1], Integer.parseInt(stored[2]), stored[3],
                stored[4].isEmpty() ? null : stored[4]);
    }

    private static BnplTermsReceipt receipt(BnplTermsAdministration.Decided decided) {
        return new BnplTermsReceipt(decided.id().value().toString(), decided.product().name(), decided.version(),
                decided.status().name(), decided.retired().map(id -> id.value().toString()).orElse(null));
    }

    /**
     * {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own after the act rolled back
     * (the Phase 11 gate's R4, from bnpl's first door), then refused as before.
     */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (BnplTermsAdministration.SelfApprovalRefused self) {
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
        } catch (BnplTermsAdministration.TermsNotFound unknown) {
            throw notFound();
        } catch (BnplTermsAdministration.SelfApprovalRefused self) {
            throw refused(BnplErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (BnplTermsAdministration.TermsStale stale) {
            throw refused(BnplErrorCode.TERMS_STALE, stale);
        } catch (BnplTermsAdministration.ProposalPending pending) {
            throw refused(BnplErrorCode.PROPOSAL_PENDING, pending);
        } catch (BnplReasons.ReasonRequired reason) {
            throw refused(BnplErrorCode.REASON_REQUIRED, reason);
        } catch (BnplTerms.TermsInvalid invalid) {
            throw refused(BnplErrorCode.TERMS_INVALID, invalid);
        } catch (BnplTermsAdministration.TermsNotAmortising notAmortising) {
            throw refused(BnplErrorCode.TERMS_NOT_AMORTISING, notAmortising);
        }
    }

    private static ApiException refused(BnplErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A BNPL terms command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL terms version answers the request", "no such record.");
    }

    private static BnplTermsVersionId versionId(String raw) {
        try {
            return BnplTermsVersionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL terms command runs inside a correlation scope"))
                .correlationId();
    }
}
