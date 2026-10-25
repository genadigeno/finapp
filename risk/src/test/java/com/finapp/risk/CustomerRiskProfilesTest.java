package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The customer risk profile's reads (`P13-TSK-007`; {@code INV-RSK-10}, {@code INV-RSK-07}, {@code INV-RSK-08}): the
 * current rating credit's seam and `-004` read - empty while unassessed, never a default - and the analyst's audited
 * history behind the self-dealing judgement, fail closed. The door's HTTP shape is {@code ProfileApiTest}'s.
 */
@DisplayName("the customer risk profile's reads (P13-TSK-007)")
class CustomerRiskProfilesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId FLOW = CorrelationId.of("read-flow");
    private static final Actor ANALYST = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            CustomerRiskProfilesTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final InMemoryCustomerRiskProfileStore store = new InMemoryCustomerRiskProfileStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final AuditWriter<Connection> audit = (unitOfWork, record) -> audited.add(record);
    private final UUID analystsParty = IDS.next();
    private final CustomerRiskProfiles profiles = new CustomerRiskProfiles(store,
            (unitOfWork, actor) -> actor.equals(ANALYST) ? Optional.of(analystsParty) : Optional.empty(), audit, IDS,
            CLOCK);

    @Test
    @DisplayName("current: empty with no profile and with one enrolled but not yet assessed; the newest version's rating"
            + " and number once assessed")
    void currentIsTheNewestVersionOrNothing() {
        UUID party = IDS.next();
        assertThat(profiles.current(UOW, party)).as("no profile").isEmpty();
        store.pullForward(UOW, party);
        assertThat(profiles.current(UOW, party)).as("enrolled, unassessed - not a clean bill of health").isEmpty();

        assess(party, false);
        assertThat(profiles.current(UOW, party)).contains(new CustomerRiskProfiles.CurrentRating(party, 1, RiskRating.LOW));
        assess(party, true);
        assertThat(profiles.current(UOW, party))
                .contains(new CustomerRiskProfiles.CurrentRating(party, 2, RiskRating.MEDIUM));
        assertThat(audited).as("the seam's read is not an operator's: no audit").isEmpty();
    }

    @Test
    @DisplayName("history: every version oldest first and the current rating, recorded risk.ProfileRead SUCCEEDED -"
            + " the summary a count, never a rating")
    void historyIsServedAndRecorded() {
        UUID party = IDS.next();
        assess(party, false);
        assess(party, true);
        CustomerRiskProfiles.History history = profiles.history(UOW, party, ANALYST, FLOW);
        assertThat(history.versions()).extracting(CustomerRiskProfileVersion::version).containsExactly(1, 2);
        assertThat(history.current()).contains(new CustomerRiskProfiles.CurrentRating(party, 2, RiskRating.MEDIUM));
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(RiskAuditAction.PROFILE_READ);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetType()).isEqualTo("customer_risk_profile");
            assertThat(record.targetId()).isEqualTo(party.toString());
            assertThat(record.changeSummary()).contains("versions=2");
            assertThat(record.correlationId()).isEqualTo(FLOW);
        });

        UUID enrolled = IDS.next();
        store.pullForward(UOW, enrolled);
        assertThat(profiles.history(UOW, enrolled, ANALYST, FLOW).current()).as("enrolled, not assessed").isEmpty();
    }

    @Test
    @DisplayName("self-dealing, fail closed: the analyst's own party, and an analyst placed in no party, are refused"
            + " before anything is read; the refusal is recordable FAILED, never naming the party in its summary")
    void selfDealingIsRefusedBeforeAnyRead() {
        assess(analystsParty, false);
        store.log.clear();
        assertThatExceptionOfType(CustomerRiskProfiles.SelfDealingRefused.class)
                .isThrownBy(() -> profiles.history(UOW, analystsParty, ANALYST, FLOW))
                .satisfies(refused -> {
                    assertThat(refused.refusal()).isEqualTo(CustomerRiskProfiles.SelfDealingRefused.Cause.OWN_PARTY);
                    profiles.recordRefusal(UOW, analystsParty, refused, ANALYST, FLOW);
                });
        Actor stranger = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        assertThatExceptionOfType(CustomerRiskProfiles.SelfDealingRefused.class)
                .isThrownBy(() -> profiles.history(UOW, IDS.next(), stranger, FLOW))
                .satisfies(refused -> assertThat(refused.refusal())
                        .isEqualTo(CustomerRiskProfiles.SelfDealingRefused.Cause.UNPLACEABLE));
        assertThat(store.log).as("nothing read").isEmpty();
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(RiskAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).contains("profile-read", "OWN_PARTY")
                    .doesNotContain(analystsParty.toString());
        });
    }

    @Test
    @DisplayName("a party with no profile is ProfileNotFound - and nothing is recorded as served")
    void noProfileIsNotFound() {
        assertThatExceptionOfType(CustomerRiskProfiles.ProfileNotFound.class)
                .isThrownBy(() -> profiles.history(UOW, IDS.next(), ANALYST, FLOW));
        assertThat(audited).isEmpty();
    }

    // -----------------------------------------------------------------

    private void assess(UUID party, boolean screeningHit) {
        CustomerRiskProfileTest.PolicyStore policies = new CustomerRiskProfileTest.PolicyStore();
        policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        CustomerRiskProfileTest.Screening screening = new CustomerRiskProfileTest.Screening();
        screening.hit = Optional.of(screeningHit);
        new ProfileRecompute(new RiskPolicies(policies), store, CustomerRiskProfileTest.inputs(screening),
                AlertRaiser.NONE, new RiskSignalsTest.RecordingOutbox(), IDS, CLOCK, Duration.ofDays(1)).recompute(UOW, party, FLOW);
    }
}
