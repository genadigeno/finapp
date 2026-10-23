package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditRiskSignal;
import com.finapp.risk.CustomerRiskProfiles;
import com.finapp.risk.RiskRating;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Credit's risk seam answered by risk - seam version 2 (`P13-TSK-007`; {@code PHASE_13_PLAN.md} section 12.5;
 * {@code INV-RSK-10}, {@code INV-CRD-04}, {@code INV-CRD-01}): the current rating's code and the profile version it is;
 * {@code NOT_ASSESSED} with no version for a party risk has not assessed - no profile, or one enrolled and not yet
 * recomputed - never a default {@code LOW}; and a code from the closed set, never risk's number. The freezer's record of
 * it is {@code CreditRiskSignalDatabaseTest}'s.
 */
@DisplayName("credit's risk seam, version 2 (P13-TSK-007)")
class RiskCreditRiskSignalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            RiskCreditRiskSignalTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final InMemoryCustomerRiskProfileStore store = new InMemoryCustomerRiskProfileStore();
    private final RiskCreditRiskSignal seam = new RiskCreditRiskSignal(new CustomerRiskProfiles(store,
            (unitOfWork, actor) -> Optional.empty(), (unitOfWork, record) -> { }, IDS, CLOCK));

    @Test
    @DisplayName("an assessed party: the rating's code at seam version 2 with the profile version it was read from - the"
            + " newest, as it moves")
    void anAssessedPartyAnswersItsRatingAndVersion() {
        UUID party = IDS.next();
        store.seedDisposition(party, RiskRating.HIGH);
        assertThat(seam.signal(UOW, party)).isEqualTo(new CreditRiskSignal.RiskSignal("HIGH", 2, OptionalLong.of(1)));
        store.seedDisposition(party, RiskRating.MEDIUM);
        assertThat(seam.signal(UOW, party)).isEqualTo(new CreditRiskSignal.RiskSignal("MEDIUM", 2, OptionalLong.of(2)));
        store.seedDisposition(party, RiskRating.PROHIBITED);
        assertThat(seam.signal(UOW, party)).isEqualTo(new CreditRiskSignal.RiskSignal("PROHIBITED", 2, OptionalLong.of(3)));
        assertThat(store.log).as("read with no lock - the head alone").containsOnly("read " + party);
    }

    @Test
    @DisplayName("a party risk has not assessed answers NOT_ASSESSED at seam version 2 with no profile version - no profile"
            + " is not a clean bill of health (DESIGN-007 D1)")
    void anUnassessedPartyIsNotAssessed() {
        UUID party = IDS.next();
        CreditRiskSignal.RiskSignal none = seam.signal(UOW, party);
        assertThat(none).isEqualTo(new CreditRiskSignal.RiskSignal("NOT_ASSESSED", 2));
        assertThat(none.profileVersion()).isEmpty();
        store.pullForward(UOW, party);
        assertThat(seam.signal(UOW, party)).as("enrolled, not yet recomputed")
                .isEqualTo(new CreditRiskSignal.RiskSignal("NOT_ASSESSED", 2));
        assertThat(RiskCreditRiskSignal.SEAM_VERSION).isEqualTo(2);
    }

    @Test
    @DisplayName("every answer is a code from the closed set - a rating's or NOT_ASSESSED - and the record crossing the"
            + " seam has no place for a number (INV-CRD-04)")
    void theAnswerIsACodeNeverANumber() {
        Set<String> closed = Arrays.stream(RiskRating.values()).map(Enum::name).collect(Collectors.toSet());
        for (RiskRating rating : RiskRating.values()) {
            UUID party = IDS.next();
            store.seedDisposition(party, rating);
            assertThat(seam.signal(UOW, party).code()).isIn(closed).isEqualTo(rating.name());
        }
        assertThat(Arrays.stream(CreditRiskSignal.RiskSignal.class.getRecordComponents()).map(RecordComponent::getName))
                .as("the code, the seam's version and the profile's - never a score")
                .containsExactly("code", "seamVersion", "profileVersion");
    }
}
