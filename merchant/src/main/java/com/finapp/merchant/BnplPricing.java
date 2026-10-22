package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A merchant's BNPL standing and pricing (`P12-TSK-006`; OQ-8 - a per-merchant schedule, ADR-0103 decision 7, ADR-0105
 * decision 3, PHASE_12_PLAN.md section 12.5; {@code INV-MER-03}, {@code INV-MER-04}, {@code INV-BNPL-04}): which fee
 * schedule prices each BNPL product a merchant offers, whether the merchant may offer one at all, the fee pinned on each
 * purchase and the fee returned on each refund - <strong>one fee arithmetic</strong>, {@link FeeCalculation}'s, for the
 * card capture and for BNPL alike.
 *
 * <h2>Why every BNPL schedule returns its fee</h2>
 *
 * <p>A BNPL refund returns the merchant's fee pro rata by the cumulative method (ADR-0105 decision 3) -
 * {@link RefundFeePolicy#RETURNED}'s arithmetic. So a schedule with any {@code RETAINED} version is refused for BNPL at
 * the assignment ({@link ScheduleNotReturning}; `V010`'s trigger for every writer); and since versions are added later
 * without a lock (V004 - a schedule row cannot be locked), a {@code RETAINED} version that becomes effective afterwards is
 * caught where it would price: the standing answers {@link Standing#NOT_PRICED}, the pin refuses, and `V010`'s pin
 * trigger refuses for every writer. Never a {@code RETAINED} BNPL fee; at worst the product stops being offered.
 *
 * <h2>The standing is read under the rows it depends on</h2>
 *
 * <p>The merchant row {@code FOR SHARE} - as {@code CheckoutSessions.requireTrading} reads it, so a suspension or close
 * (which write it {@code FOR UPDATE}) and a BNPL assignment (which locks it too) serialise against the purchase - then
 * the merchant's {@code ACTIVE} enablement {@code FOR SHARE}, so a withdrawal waits for the purchase; the assignment and
 * the immutable versions after them, unlocked. Every lock is a share lock on a row the purchase never writes, taken
 * after the purchase's own rows (PHASE_12_PLAN.md section 7.2's standing step) and before any ledger row.
 *
 * <h2>The pin and the split from one version, read once</h2>
 *
 * <p>{@link #pin} resolves the version in force <em>after</em> those locks, on the database's clock read there
 * ({@code clock_timestamp()} - <em>corrected by the Phase 12 -> 13 gate's A3-M1: it read the application clock, so an
 * instance whose clock ran ahead priced with a version not yet in force and stamped the pin with its own instant; a
 * version's {@code effective_from} is a declared instant, judged on the one clock every instance shares</em>), prices the
 * gross with it, refuses a sale that does not cover its fee (ADR-0058,
 * {@link SaleBelowFeeException}) and records the pin - all before anything else is written, in the purchase's own
 * transaction, so a rollback leaves no pin. The primary key refuses a second pin for the purchase ({@link AlreadyPinned}),
 * never a second version.
 */
@RequiredArgsConstructor
public final class BnplPricing {

    static final String ASSIGNMENT_TARGET_TYPE = "merchant";

    @NonNull private final BnplPricingStore<Connection> pricing;
    @NonNull private final BnplEnablementStore<Connection> enablements;
    @NonNull private final MerchantStore<Connection> merchants;
    @NonNull private final FeeScheduleStore<Connection> schedules;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Whether a merchant may offer a BNPL product now - the first failing condition, or {@link #ENABLED}. */
    public enum Standing {

        /** {@code ACTIVE}, an {@code ACTIVE} enablement, and the product priced by a returning version in force. */
        ENABLED,

        /** Unknown, suspended or closed. */
        NOT_TRADING,

        /** Trading, but with no {@code ACTIVE} BNPL enablement. */
        NOT_ENABLED,

        /** Enabled, but the product has no BNPL schedule, no version in force, or a version that retains its fee. */
        NOT_PRICED
    }

    // ------------------------------------------------------------------ refusals

    /** The schedule has a version that retains its fee ({@code 422 merchant.BnplScheduleNotReturning}). */
    public static final class ScheduleNotReturning extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ScheduleNotReturning() {
            super("a BNPL fee schedule returns its fee on refund; this one retains it");
        }
    }

    /** The merchant may not offer the product now: the pin is refused before anything is written. */
    public static final class NotOffered extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final Standing standing;

        NotOffered(Standing standing) {
            super("the merchant may not offer this BNPL product: " + standing.name());
            this.standing = standing;
        }

        public Standing standing() {
            return standing;
        }
    }

    /** The purchase already has a fee pin - never a second version for one purchase. */
    public static final class AlreadyPinned extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AlreadyPinned() {
            super("the BNPL purchase is already priced");
        }
    }

    /** No fee pin names the purchase. */
    public static final class NotPinned extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotPinned() {
            super("no BNPL fee pin names the purchase");
        }
    }

    // ------------------------------------------------------------------ the assignment

    /**
     * Points the merchant's {@code product} at {@code scheduleId}, reasoned - converging silently when it is already
     * there (the {@link FeeSchedules#assign} idiom: nothing happened, nothing is recorded). Under the merchant row's
     * {@code FOR UPDATE}, so an assignment and a standing read serialise.
     *
     * @throws UnknownMerchantException no merchant answers to the identifier
     * @throws UnknownFeeScheduleException no schedule does
     * @throws FeeCurrencyMismatchException the schedule does not price the merchant's settlement currency
     * @throws ScheduleNotReturning a version of the schedule retains its fee
     * @throws IllegalArgumentException for a missing, blank or unbounded reason
     */
    public FeeScheduleId assign(Connection unitOfWork, MerchantId merchantId, MerchantBnplProduct product,
            FeeScheduleId scheduleId, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplEnablement.reasoned(reason);
        Merchant merchant = merchants.findByIdForUpdate(unitOfWork, merchantId)
                .orElseThrow(UnknownMerchantException::new);
        FeeSchedule schedule = schedules.findSchedule(unitOfWork, scheduleId)
                .orElseThrow(UnknownFeeScheduleException::new);
        if (!schedule.currency().equals(merchant.settlementCurrency())) {
            throw new FeeCurrencyMismatchException(schedule.currency(), merchant.settlementCurrency());
        }
        if (schedules.listVersions(unitOfWork, scheduleId).stream()
                .anyMatch(version -> version.refundFeePolicy() != RefundFeePolicy.RETURNED)) {
            throw new ScheduleNotReturning();
        }
        Optional<FeeScheduleId> previous = pricing.findAssignment(unitOfWork, merchantId, product);
        if (previous.filter(scheduleId::equals).isPresent()) {
            return scheduleId;
        }
        Instant at = clock.instant();
        pricing.assign(unitOfWork, merchantId, product, previous, scheduleId, reasoned, actor.id(),
                actor.type().name(), at);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, at,
                MerchantAuditAction.MERCHANT_BNPL_FEE_SCHEDULE_ASSIGNED, ASSIGNMENT_TARGET_TYPE,
                merchantId.value().toString(), Optional.of(reasoned), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("merchant=" + merchantId + ", product=" + product.name() + ", from="
                        + previous.map(Object::toString).orElse("none") + ", to=" + scheduleId)));
        return scheduleId;
    }

    /** Which schedule prices the merchant's product, or empty. */
    public Optional<FeeScheduleId> assignmentOf(
            Connection unitOfWork, MerchantId merchantId, MerchantBnplProduct product) {
        return pricing.findAssignment(unitOfWork, merchantId, product);
    }

    // ------------------------------------------------------------------ the standing, the pin, the return

    /**
     * Whether the merchant may offer {@code product} now, its rows share-locked to the caller's commit (the class
     * javadoc's order). An unknown merchant is {@link Standing#NOT_TRADING} - one answer, no oracle.
     */
    public Standing standing(Connection unitOfWork, MerchantId merchantId, MerchantBnplProduct product) {
        return judged(unitOfWork, merchantId, product).standing();
    }

    /**
     * Prices one BNPL purchase and pins the version that priced it, in the purchase's transaction: the standing judged
     * under its locks, the version in force read once after them, {@code fee = round(G x rate) + fixed} through
     * {@link FeeCalculation#assess}, {@code net = G - fee} by subtraction, the sale refused unless it covers its fee, the
     * pin inserted. Every refusal comes before any write.
     *
     * @throws NotOffered the merchant may not offer the product now (not trading, not enabled, not priced or retaining)
     * @throws FeeCurrencyMismatchException the gross is not in the schedule's currency
     * @throws SaleBelowFeeException the fee meets or exceeds the gross (ADR-0058)
     * @throws AlreadyPinned the purchase is already priced
     */
    public FeeAssessment pin(Connection unitOfWork, MerchantId merchantId, MerchantBnplProduct product,
            UUID purchaseRef, Money gross, Actor actor) {
        Objects.requireNonNull(purchaseRef, "purchaseRef must not be null");
        Objects.requireNonNull(gross, "gross must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Judged judged = judged(unitOfWork, merchantId, product);
        if (judged.standing() != Standing.ENABLED) {
            throw new NotOffered(judged.standing());
        }
        FeeScheduleVersion version = judged.version().orElseThrow();
        FeeAssessment assessment = FeeCalculation.assess(gross, version);
        if (!assessment.net().isPositive()) {
            throw new SaleBelowFeeException();
        }
        if (!pricing.insertPin(unitOfWork, new BnplFeePin(purchaseRef, merchantId, product, version.id(), gross,
                judged.at(), actor.id()))) {
            throw new AlreadyPinned();
        }
        return assessment;
    }

    /**
     * The fee a refund of {@code refunded} returns, {@code refundedBefore} having been refunded already - under the
     * purchase's pinned version, by {@link FeeCalculation#returnedFee}: {@code cum(before + refunded) - cum(before)} with
     * {@code cum(x) = round(f x x / G)}. Exact, never {@code leastReturnedFee}'s conservative share: a BNPL refund is
     * synchronous, its {@code before} the committed sum read under the purchase's lock (ADR-0105 decision 3), so any
     * sequence of refunds summing to G returns exactly f, and none returns more in any order.
     *
     * @throws NotPinned no pin names the purchase
     * @throws IllegalArgumentException the refunds would exceed the gross
     */
    public Money returnedFee(Connection unitOfWork, UUID purchaseRef, Money refundedBefore, Money refunded) {
        BnplFeePin pin = pricing.findPin(unitOfWork, purchaseRef).orElseThrow(NotPinned::new);
        FeeScheduleVersion version = schedules.findVersion(unitOfWork, pin.versionId())
                .orElseThrow(() -> new MerchantStorageException(
                        "a BNPL fee pin names version " + pin.versionId() + ", which is not there"));
        return FeeCalculation.returnedFee(FeeCalculation.assess(pin.gross(), version), version, refundedBefore,
                refunded);
    }

    /**
     * Whether a sale of {@code gross} covers the merchant's fee for {@code product} now (`P12-TSK-010`, the BNPL
     * application's eligibility read): judged exactly as {@link #pin} judges it - the standing under the same share locks,
     * the version in force read once after them, {@code fee = round(G x rate) + fixed} through
     * {@link FeeCalculation#assess} and the net required positive (ADR-0058) - with <strong>nothing written</strong>: no
     * pin, no audit record. {@code false} when the merchant may not offer the product now, or when the gross is not in
     * the schedule's currency. A reading, never a reservation: the purchase's own {@link #pin} re-judges under its locks.
     */
    public boolean coversFee(Connection unitOfWork, MerchantId merchantId, MerchantBnplProduct product, Money gross) {
        Objects.requireNonNull(gross, "gross must not be null");
        Judged judged = judged(unitOfWork, merchantId, product);
        if (judged.standing() != Standing.ENABLED) {
            return false;
        }
        try {
            return FeeCalculation.assess(gross, judged.version().orElseThrow()).net().isPositive();
        } catch (FeeCurrencyMismatchException mismatch) {
            return false;
        }
    }

    /** The purchase's pin, or empty - the provenance read for the three-way proof's fee arm (`P12-TSK-025`). */
    public Optional<BnplFeePin> pinFor(Connection unitOfWork, UUID purchaseRef) {
        return pricing.findPin(unitOfWork, purchaseRef);
    }

    // ------------------------------------------------------------------ plumbing

    private record Judged(Standing standing, Optional<FeeScheduleVersion> version, Instant at) {}

    private Judged judged(Connection unitOfWork, MerchantId merchantId, MerchantBnplProduct product) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(product, "product must not be null");
        boolean trading = merchants.findByIdForShare(unitOfWork, merchantId)
                .filter(merchant -> merchant.status() == MerchantStatus.ACTIVE)
                .isPresent();
        if (!trading) {
            return new Judged(Standing.NOT_TRADING, Optional.empty(), clock.instant());
        }
        if (enablements.activeForShare(unitOfWork, merchantId).isEmpty()) {
            return new Judged(Standing.NOT_ENABLED, Optional.empty(), clock.instant());
        }
        Optional<FeeScheduleId> schedule = pricing.findAssignment(unitOfWork, merchantId, product);
        // The instant the version is resolved at and the pin stamped with: the DATABASE's clock_timestamp(), read after
        // both share locks were granted - never this instance's clock (the Phase 12 -> 13 gate's A3-M1). Ten instances
        // with ten skewed clocks resolve one version for one instant; `V012`'s pin trigger refuses an instant outside the
        // pinning transaction's own span on that clock, for every writer.
        Instant at = pricing.databaseNow(unitOfWork);
        Optional<FeeScheduleVersion> version = schedule.flatMap(id -> schedules.findEffectiveVersion(unitOfWork, id, at))
                .filter(inForce -> inForce.refundFeePolicy() == RefundFeePolicy.RETURNED);
        return version.isPresent()
                ? new Judged(Standing.ENABLED, version, at)
                : new Judged(Standing.NOT_PRICED, Optional.empty(), at);
    }
}
