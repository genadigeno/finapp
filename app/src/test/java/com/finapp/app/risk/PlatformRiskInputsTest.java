package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.CommandRef;
import com.finapp.risk.Reading;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskInputs;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RuleKind;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.SessionAssurance;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlatformRiskInputs} over scripted owning-module reads (`P13-TSK-004`): each reading answered from its source
 * only when a judged kind needs it; a source that knows nothing is {@code ABSENT}, never a default; the session's
 * assurance only through a wired {@link SessionAssurance}; the account payee and the authentication failures
 * {@code ABSENT} - no source exists.
 */
@DisplayName("RiskInputs over kyc, party, merchant and the session: answered where a source exists, ABSENT where none"
        + " does (P13-TSK-004)")
class PlatformRiskInputsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = stub(Connection.class, (method, args) -> {
        throw new UnsupportedOperationException(method);
    });
    private static final Set<RuleKind> EVERY_KIND = EnumSet.allOf(RuleKind.class);

    private final List<String> reads = new ArrayList<>();
    private final UUID party = IDS.next();
    private final UUID customer = IDS.next();
    private final UUID merchant = IDS.next();

    @Test
    @DisplayName("every source answering: the session's assurance, the screening hit, KYC approved, the merchant"
            + " suspended - and the account payee and the authentication failures ABSENT")
    void everySourceAnswers() {
        PlatformRiskInputs inputs = inputs(Optional.of((unitOfWork, actor) -> Optional.of(RiskAssurance.MULTI_FACTOR)),
                Optional.of(new ScreeningClearances.Clearance(IDS.next(), true, CLOCK.instant())),
                Optional.of(KycCaseStatus.APPROVED), Optional.of(MerchantStatus.SUSPENDED));
        RiskInputs.Readings readings = inputs.read(UOW, request(), EVERY_KIND, CLOCK.instant());
        assertThat(readings.sessionAssurance()).isEqualTo(Reading.of(RiskAssurance.MULTI_FACTOR));
        assertThat(readings.screeningHit()).isEqualTo(Reading.of(true));
        assertThat(readings.kycApproved()).isEqualTo(Reading.of(true));
        assertThat(readings.merchantSuspended()).isEqualTo(Reading.of(true));
        assertThat(readings.accountPayeeFirstSeen().isPresent()).isFalse();
        assertThat(readings.authFailures()).isEmpty();
    }

    @Test
    @DisplayName("a source that knows nothing is ABSENT, never a clear: no session port, never screened, no KYC case, an"
            + " unknown merchant; a pending KYC case is not approved; a closed merchant is suspended for judging")
    void nothingKnownIsAbsent() {
        RiskInputs.Readings unknown = inputs(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
                .read(UOW, request(), EVERY_KIND, CLOCK.instant());
        assertThat(List.of(unknown.sessionAssurance(), unknown.screeningHit(), unknown.kycApproved(),
                unknown.merchantSuspended())).allMatch(reading -> !reading.isPresent());

        RiskInputs.Readings pending = inputs(Optional.empty(), Optional.empty(), Optional.of(KycCaseStatus.IN_REVIEW),
                Optional.of(MerchantStatus.CLOSED)).read(UOW, request(), EVERY_KIND, CLOCK.instant());
        assertThat(pending.kycApproved()).isEqualTo(Reading.of(false));
        assertThat(pending.merchantSuspended()).isEqualTo(Reading.of(true));
    }

    @Test
    @DisplayName("only what a judged kind needs is read: a version with no screening, KYC or merchant rule reads none of"
            + " them - every read is paid inside the caller's money lock")
    void onlyWhatIsJudgedIsRead() {
        PlatformRiskInputs inputs = inputs(Optional.empty(),
                Optional.of(new ScreeningClearances.Clearance(IDS.next(), false, CLOCK.instant())),
                Optional.of(KycCaseStatus.APPROVED), Optional.of(MerchantStatus.ACTIVE));
        RiskInputs.Readings readings = inputs.read(UOW, request(), EnumSet.of(RuleKind.AMOUNT_BAND), CLOCK.instant());
        assertThat(reads).isEmpty();
        assertThat(readings.screeningHit().isPresent()).isFalse();
        inputs.read(UOW, request(), EVERY_KIND, CLOCK.instant());
        assertThat(reads).containsExactly("clearance", "customer", "case", "merchant");
    }

    // ------------------------------------------------------------------

    private RiskEvaluationRequest request() {
        return RiskEvaluationRequest.builder(RiskOperation.CARD_PAYMENT, CommandRef.of(IDS.next()),
                Money.ofMinorUnits(20_00, CurrencyCode.of("EUR")), party, CorrelationId.of("flow"),
                CausationId.of("cause")).actor(new Actor("customer-1", ActorType.CUSTOMER)).merchant(merchant)
                .sourceAccount(IDS.next()).build();
    }

    private PlatformRiskInputs inputs(Optional<SessionAssurance> session,
            Optional<ScreeningClearances.Clearance> clearance, Optional<KycCaseStatus> kyc,
            Optional<MerchantStatus> merchantStatus) {
        ScreeningClearances clearances = (unitOfWork, partyId) -> {
            reads.add("clearance");
            return partyId.equals(party) ? clearance : Optional.empty();
        };
        @SuppressWarnings("unchecked")
        PartyStore<Connection> parties = stub(PartyStore.class, (method, args) -> {
            reads.add("customer");
            return ((PartyId) args[1]).value().equals(party)
                    ? Optional.of(Customer.rehydrate(CustomerId.of(customer), PartyId.of(party), CustomerStatus.ACTIVE,
                            CLOCK.instant(), CLOCK.instant()))
                    : Optional.empty();
        });
        @SuppressWarnings("unchecked")
        KycCaseStore<Connection> cases = stub(KycCaseStore.class, (method, args) -> {
            reads.add("case");
            return kyc.map(status -> KycCase.rehydrate(KycCaseId.next(IDS), customer, KycCaseKind.KYC, status,
                    KycPolicyVersion.CURRENT, CLOCK.instant(), CLOCK.instant()));
        });
        @SuppressWarnings("unchecked")
        MerchantStore<Connection> merchants = stub(MerchantStore.class, (method, args) -> {
            reads.add("merchant");
            return merchantStatus.map(status -> Merchant.rehydrate(MerchantId.of(merchant), IDS.next(), "Legal",
                    "Display", CurrencyCode.of("EUR"), status, CLOCK.instant(), CLOCK.instant()));
        });
        return new PlatformRiskInputs(session, clearances, parties, cases, merchants);
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args) throws Throwable;
    }

    /** A store that answers its one read and refuses anything else by the method's name. */
    private static <T> T stub(Class<T> type, Answer answer) {
        Map<String, String> reads = Map.of(
                "PartyStore", "findLiveCustomerFor",
                "KycCaseStore", "findLatestFor",
                "MerchantStore", "findById");
        return type.cast(Proxy.newProxyInstance(PlatformRiskInputsTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return type.getSimpleName();
                    }
                    String allowed = reads.get(type.getSimpleName());
                    if (allowed != null && !method.getName().equals(allowed)) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return answer.answer(method.getName(), args);
                }));
    }
}
