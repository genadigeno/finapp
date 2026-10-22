package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A merchant's BNPL standing, its BNPL fee schedule assignment, the fee pin and the returned fee in the domain rank
 * (`P12-TSK-006`; {@code INV-MER-03}, {@code INV-MER-04}, ADR-0058, ADR-0105 decision 3) - over in-memory stores
 * ({@link BnplFakes}). The every-writer ranks and the ten pinners are {@code MerchantBnplEnablementDatabaseTest}'s.
 */
@DisplayName("merchant BNPL pricing (P12-TSK-006)")
class BnplPricingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = BnplFakes.CONNECTION;
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-006-test");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Actor ANNA = new Actor("operator-anna", ActorType.EMPLOYEE);
    private static final Actor BORIS = new Actor("operator-boris", ActorType.EMPLOYEE);
    private static final Actor CUSTOMER = new Actor("customer-1", ActorType.CUSTOMER);

    private BnplFakes.Enablements enablementStore;
    private BnplFakes.Merchants merchants;
    private BnplFakes.Pricing pricingStore;
    private BnplFakes.Schedules schedules;
    private BnplFakes.Audit audit;
    private BnplEnablements enablements;
    private BnplPricing pricing;
    private Merchant merchant;
    private FeeSchedule returning;

    @BeforeEach
    void setUp() {
        enablementStore = new BnplFakes.Enablements(CLOCK);
        merchants = new BnplFakes.Merchants();
        pricingStore = new BnplFakes.Pricing(CLOCK);
        schedules = new BnplFakes.Schedules();
        audit = new BnplFakes.Audit();
        enablements = new BnplEnablements(enablementStore, merchants, audit, IDS, CLOCK);
        pricing = new BnplPricing(pricingStore, enablementStore, merchants, schedules, audit, IDS, CLOCK);
        merchant = Merchant.onboard(IDS, CLOCK, IDS.next(), "Shop GmbH", "Shop", EUR);
        merchants.insert(UOW, merchant);
        returning = schedule("BNPL standard", RefundFeePolicy.RETURNED, "0.0299", 0);
    }

    @Test
    @DisplayName("the standing names the first failing condition: enablement, then the product's priced schedule")
    void theStandingNamesTheFirstFailingCondition() {
        assertThat(standing()).isEqualTo(BnplPricing.Standing.NOT_ENABLED);
        enable();
        assertThat(standing()).isEqualTo(BnplPricing.Standing.NOT_PRICED);
        pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, returning.id(), "contract", ANNA,
                CORRELATION);
        assertThat(standing()).isEqualTo(BnplPricing.Standing.ENABLED);
        assertThat(pricing.standing(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_N))
                .as("a schedule is per product").isEqualTo(BnplPricing.Standing.NOT_PRICED);
    }

    @Test
    @DisplayName("a suspended merchant is not enabled, whatever its enablement says")
    void aSuspendedMerchantIsNotEnabled() {
        priced();
        merchants.insert(UOW, merchant.suspend(CLOCK));
        assertThat(standing()).isEqualTo(BnplPricing.Standing.NOT_TRADING);
        assertThatThrownBy(() -> pin(UUID.randomUUID(), 10_000))
                .isInstanceOfSatisfying(BnplPricing.NotOffered.class,
                        refused -> assertThat(refused.standing()).isEqualTo(BnplPricing.Standing.NOT_TRADING));
        assertThat(pricingStore.pins).isEmpty();
    }

    @Test
    @DisplayName("a withdrawn enablement offers nothing: the pin is refused before anything is written")
    void aWithdrawnEnablementOffersNothing() {
        BnplEnablement active = priced();
        enablements.withdraw(UOW, merchant.id(), active.id(), "stop", ANNA, CORRELATION);
        assertThat(standing()).isEqualTo(BnplPricing.Standing.NOT_ENABLED);
        assertThatThrownBy(() -> pin(UUID.randomUUID(), 10_000)).isInstanceOf(BnplPricing.NotOffered.class);
        assertThat(pricingStore.pins).isEmpty();
    }

    @Test
    @DisplayName("a RETAINED schedule is refused for BNPL at the assignment, and a RETAINED version later prices nothing")
    void aRetainedScheduleIsRefusedForBnpl() {
        enable();
        FeeSchedule retained = schedule("card standard", RefundFeePolicy.RETAINED, "0.0290", 30);
        assertThatThrownBy(() -> pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, retained.id(),
                "wrong schedule", ANNA, CORRELATION)).isInstanceOf(BnplPricing.ScheduleNotReturning.class);
        assertThat(pricingStore.assignments).isEmpty();

        pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, returning.id(), "contract", ANNA,
                CORRELATION);
        // A RETAINED version added to the BNPL schedule afterwards, in force from a later instant.
        schedules.insertVersionIfNumberIsFree(UOW, FeeScheduleVersion.rehydrate(FeeScheduleVersionId.next(IDS),
                returning.id(), 2, FeeRate.of(new BigDecimal("0.0100")), Money.ofMinorUnits(0, EUR),
                RoundingPolicy.HALF_EVEN, RefundFeePolicy.RETAINED, CLOCK.instant(), CLOCK.instant(), "operator"));
        assertThat(standing()).isEqualTo(BnplPricing.Standing.NOT_PRICED);
        assertThatThrownBy(() -> pin(UUID.randomUUID(), 10_000))
                .isInstanceOfSatisfying(BnplPricing.NotOffered.class,
                        refused -> assertThat(refused.standing()).isEqualTo(BnplPricing.Standing.NOT_PRICED));
    }

    @Test
    @DisplayName("the pin conserves the gross, names the version in force, and is born once per purchase")
    void theFeePinIsBornOncePerPurchase() {
        priced();
        UUID purchase = UUID.randomUUID();

        FeeAssessment split = pin(purchase, 10_000);

        assertThat(split.fee()).isEqualTo(Money.ofMinorUnits(299, EUR));
        assertThat(split.fee().plus(split.net())).isEqualTo(split.gross());
        BnplFeePin pinned = pricingStore.pins.get(purchase);
        assertThat(pinned.versionId()).isEqualTo(split.version());
        assertThat(pinned.pinnedBy()).isEqualTo(CUSTOMER.id());
        assertThatThrownBy(() -> pin(purchase, 10_000)).isInstanceOf(BnplPricing.AlreadyPinned.class);
        assertThat(pricingStore.pins).hasSize(1);
    }

    @Test
    @DisplayName("the Phase 12 -> 13 gate's A3-M1: an instance whose clock runs past a version's effective_from still pins"
            + " the version in force on the DATABASE's clock, stamped with the database's instant - never its own")
    void theVersionInForceIsTheDatabaseClocksNotTheInstances() {
        priced();
        // Version 2 of the BNPL schedule, in force an hour after the database's now.
        Instant later = CLOCK.instant().plusSeconds(3_600);
        schedules.insertVersionIfNumberIsFree(UOW, FeeScheduleVersion.rehydrate(FeeScheduleVersionId.next(IDS),
                returning.id(), 2, FeeRate.of(new BigDecimal("0.0100")), Money.ofMinorUnits(0, EUR),
                RoundingPolicy.HALF_EVEN, RefundFeePolicy.RETURNED, later, CLOCK.instant(), "operator"));
        FeeScheduleVersionId inForce = schedules.findEffectiveVersion(UOW, returning.id(), CLOCK.instant())
                .orElseThrow().id();
        // This instance's clock two hours ahead of the database's - past version 2's effective_from.
        Clock skewed = Clock.offset(CLOCK, java.time.Duration.ofHours(2));
        BnplPricing onASkewedInstance = new BnplPricing(pricingStore, enablementStore, merchants, schedules, audit, IDS,
                skewed);
        UUID purchase = UUID.randomUUID();

        FeeAssessment split = onASkewedInstance.pin(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, purchase,
                Money.ofMinorUnits(10_000, EUR), CUSTOMER);

        assertThat(split.version()).as("the version in force on the database's clock").isEqualTo(inForce);
        assertThat(split.fee()).isEqualTo(Money.ofMinorUnits(299, EUR));
        assertThat(pricingStore.pins.get(purchase).pinnedAt()).as("the database's instant").isEqualTo(CLOCK.instant());
    }

    @Test
    @DisplayName("A3-M1's other half: a version created to take effect IMMEDIATELY on an instance whose clock runs ahead of"
            + " the database's is in force from its commit - 'immediately' never waits for the database to catch up -"
            + " and the pin is still stamped with the database's instant")
    void anImmediateVersionStampedAheadIsInForceAtOnce() {
        priced();
        // Version 2, immediate (effective_from = created_at), stamped by an instance an hour ahead of the database.
        Instant ahead = CLOCK.instant().plusSeconds(3_600);
        FeeScheduleVersionId immediate = FeeScheduleVersionId.next(IDS);
        schedules.insertVersionIfNumberIsFree(UOW, FeeScheduleVersion.rehydrate(immediate, returning.id(), 2,
                FeeRate.of(new BigDecimal("0.0100")), Money.ofMinorUnits(0, EUR), RoundingPolicy.HALF_EVEN,
                RefundFeePolicy.RETURNED, ahead, ahead, "operator"));
        UUID purchase = UUID.randomUUID();

        FeeAssessment split = pin(purchase, 10_000);

        assertThat(split.version()).as("the immediate version, in force from its commit").isEqualTo(immediate);
        assertThat(split.fee()).isEqualTo(Money.ofMinorUnits(100, EUR));
        assertThat(pricingStore.pins.get(purchase).pinnedAt()).as("the database's instant").isEqualTo(CLOCK.instant());
    }

    @Test
    @DisplayName("a sale that does not cover its fee is refused before the pin (ADR-0058)")
    void aSaleBelowItsFeeIsRefused() {
        enable();
        FeeSchedule fixedHeavy = schedule("fixed heavy", RefundFeePolicy.RETURNED, "0", 100);
        pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, fixedHeavy.id(), "c", ANNA, CORRELATION);
        assertThatThrownBy(() -> pin(UUID.randomUUID(), 100)).isInstanceOf(SaleBelowFeeException.class);
        assertThat(pricingStore.pins).isEmpty();
        assertThat(pin(UUID.randomUUID(), 101).net()).isEqualTo(Money.ofMinorUnits(1, EUR));
    }

    @Test
    @DisplayName("the fee coverage read judges as the pin does - the standing first, then the net positive - and writes"
            + " nothing (P12-TSK-010)")
    void theFeeCoverageReadJudgesAsThePinAndWritesNothing() {
        assertThat(covers(10_000)).as("not enabled covers nothing").isFalse();
        enable();
        FeeSchedule fixedHeavy = schedule("fixed heavy", RefundFeePolicy.RETURNED, "0", 100);
        assertThat(covers(10_000)).as("not priced covers nothing").isFalse();
        pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, fixedHeavy.id(), "c", ANNA, CORRELATION);
        int audited = audit.records.size();
        assertThat(covers(100)).as("a fee meeting the gross").isFalse();
        assertThat(covers(101)).as("one cent of net").isTrue();
        assertThat(pricing.coversFee(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4,
                Money.ofMinorUnits(10_000, CurrencyCode.of("USD")))).as("another currency").isFalse();
        assertThat(pricingStore.pins).as("a reading pins nothing").isEmpty();
        assertThat(audit.records).as("a reading audits nothing").hasSize(audited);
        merchants.insert(UOW, merchant.suspend(CLOCK));
        assertThat(covers(10_000)).as("a suspended merchant covers nothing").isFalse();
    }

    private boolean covers(long grossMinor) {
        return pricing.coversFee(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4,
                Money.ofMinorUnits(grossMinor, EUR));
    }

    @Test
    @DisplayName("the returned fee telescopes under the pin: two halves return exactly f")
    void theReturnedFeeTelescopes() {
        priced();
        UUID purchase = UUID.randomUUID();
        FeeAssessment split = pin(purchase, 10_001);
        Money first = pricing.returnedFee(UOW, purchase, Money.ofMinorUnits(0, EUR), Money.ofMinorUnits(5_000, EUR));
        Money second = pricing.returnedFee(UOW, purchase, Money.ofMinorUnits(5_000, EUR),
                Money.ofMinorUnits(5_001, EUR));
        assertThat(first.plus(second)).isEqualTo(split.fee());

        // The shape that over-returns when each refund rounds its own share: an odd fee refunded in halves.
        // 100.00 at 2.99 % is a fee of 3 (2.99 rounded once); each half's own share is 1.5, which HALF_EVEN rounds to 2,
        // so the naive arithmetic returns 4 - more than was ever charged. The cumulative difference returns 2 then 1.
        UUID odd = UUID.randomUUID();
        FeeAssessment oddSplit = pin(odd, 100);
        assertThat(oddSplit.fee()).isEqualTo(Money.ofMinorUnits(3, EUR));
        Money half = pricing.returnedFee(UOW, odd, Money.ofMinorUnits(0, EUR), Money.ofMinorUnits(50, EUR));
        Money otherHalf = pricing.returnedFee(UOW, odd, Money.ofMinorUnits(50, EUR), Money.ofMinorUnits(50, EUR));
        assertThat(half.plus(otherHalf)).as("exactly f, never a minor unit more").isEqualTo(oddSplit.fee());
        assertThatThrownBy(() -> pricing.returnedFee(UOW, UUID.randomUUID(), Money.ofMinorUnits(0, EUR),
                Money.ofMinorUnits(1, EUR))).isInstanceOf(BnplPricing.NotPinned.class);
    }

    @Test
    @DisplayName("the assignment converges, records a move with its reason, and refuses a foreign currency")
    void theAssignmentConverges() {
        pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, returning.id(), "c", ANNA, CORRELATION);
        pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, returning.id(), "c", BORIS, CORRELATION);
        assertThat(pricingStore.history).hasSize(1);
        assertThat(audit.records).extracting(record -> record.operation())
                .containsExactly(MerchantAuditAction.MERCHANT_BNPL_FEE_SCHEDULE_ASSIGNED);
        FeeSchedule dollars = FeeSchedule.create(IDS, "usd", CurrencyCode.of("USD"), "operator", CLOCK);
        schedules.insertSchedule(UOW, dollars);
        assertThatThrownBy(() -> pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_N, dollars.id(),
                "c", ANNA, CORRELATION)).isInstanceOf(FeeCurrencyMismatchException.class);
        assertThatThrownBy(() -> pricing.assign(UOW, MerchantId.next(IDS), MerchantBnplProduct.BNPL_PAY_IN_N,
                returning.id(), "c", ANNA, CORRELATION)).isInstanceOf(UnknownMerchantException.class);
    }

    // ------------------------------------------------------------------ fixtures

    private BnplPricing.Standing standing() {
        return pricing.standing(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4);
    }

    private FeeAssessment pin(UUID purchase, long grossMinor) {
        return pricing.pin(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, purchase,
                Money.ofMinorUnits(grossMinor, EUR), CUSTOMER);
    }

    private BnplEnablement enable() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "contract", ANNA, CORRELATION);
        return enablements.approve(UOW, merchant.id(), proposed.id(), "ok", BORIS, CORRELATION).enablement();
    }

    private BnplEnablement priced() {
        BnplEnablement active = enable();
        pricing.assign(UOW, merchant.id(), MerchantBnplProduct.BNPL_PAY_IN_4, returning.id(), "contract", ANNA,
                CORRELATION);
        return active;
    }

    private FeeSchedule schedule(String name, RefundFeePolicy policy, String rate, long fixedMinor) {
        FeeSchedule schedule = FeeSchedule.create(IDS, name, EUR, "operator", CLOCK);
        schedules.insertSchedule(UOW, schedule);
        schedules.insertVersionIfNumberIsFree(UOW, FeeScheduleVersion.create(IDS, schedule, 1,
                FeeRate.of(new BigDecimal(rate)), Money.ofMinorUnits(fixedMinor, EUR), RoundingPolicy.HALF_EVEN,
                policy, Optional.empty(), "operator", CLOCK));
        return schedule;
    }
}
