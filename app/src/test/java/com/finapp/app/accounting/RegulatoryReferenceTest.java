package com.finapp.app.accounting;

import static com.finapp.accounting.testing.RegulatoryWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatoryReportingProvider.Outcome;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The simulated reference adapter and every surface a reader sees (`P14-TSK-018`; ADR-0118 decision 3; owner decision C4): {@code REFERENCE} and {@code SIMULATED} said in what it produces; nothing anywhere claims what it cannot; stateless, so correct with N instances; and the two meters, their tags closed.
 */
@DisplayName("the simulated reference adapter, its surfaces and its meters (P14-TSK-018)")
class RegulatoryReferenceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2096-03-10T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CONTENT = "{\"standing\":\"REFERENCE\"}";
    private static final List<String> CLAIMS = List.of("complian", "statutory", "certified", "regulator approved", "filed with", "legal basis", "officially");

    private final RegulatorySubmissionId id = RegulatorySubmissionId.next(IDS);

    private RegulatoryReportingProvider.Request request(String jurisdiction, String sha256) {
        return new RegulatoryReportingProvider.Request(id, jurisdiction, "REF-TEST-RETURN", 1, "2096-01", CONTENT, sha256);
    }

    private RegulatoryReportingProvider.Request request() {
        return request(SimulatedRegulatoryReportingProvider.JURISDICTION, CanonicalJson.sha256(CONTENT));
    }

    private static SimulatedRegulatoryReportingProvider simulated(SimulatedRegulatoryReportingProvider.Behaviour behaviour) {
        return new SimulatedRegulatoryReportingProvider(behaviour);
    }

    private static String text(Answer answer) {
        return new String(answer.raw(), StandardCharsets.UTF_8);
    }

    private static void claimsNothing(String surface) {
        String lowered = surface.toLowerCase(Locale.ROOT);
        for (String claim : CLAIMS) {
            assertThat(lowered).as(claim).doesNotContain(claim);
        }
    }

    @Test
    @DisplayName("its export says REFERENCE and SIMULATED first, then what was attested, byte for byte; deterministic; a notice, and no claim")
    void theExportSaysWhatItIs() {
        RegulatoryReportingProvider.ReferenceExport export = simulated(SimulatedRegulatoryReportingProvider.Behaviour.ACKNOWLEDGE).export(request());

        assertThat(export.standing()).isEqualTo("REFERENCE");
        assertThat(export.channel()).isEqualTo("SIMULATED");
        assertThat(export.document()).startsWith("standing: REFERENCE\nchannel: SIMULATED\nnotice: ").endsWith("content: " + CONTENT);
        assertThat(export.sha256()).isEqualTo(CanonicalJson.sha256(export.document()));
        assertThat(simulated(SimulatedRegulatoryReportingProvider.Behaviour.REJECT).export(request())).isEqualTo(export);
        assertThat(export.notice()).contains("simulated").contains("asserts nothing");
        claimsNothing(export.document());
        claimsNothing(export.notice());
        assertThat(export.toString()).doesNotContain(CONTENT);
    }

    @Test
    @DisplayName("every answer says REFERENCE and SIMULATED, and no answer or name claims anything")
    void everyAnswerSaysWhatItIs() {
        for (SimulatedRegulatoryReportingProvider.Behaviour behaviour : SimulatedRegulatoryReportingProvider.Behaviour.values()) {
            if (behaviour == SimulatedRegulatoryReportingProvider.Behaviour.UNAVAILABLE) {
                continue;
            }
            for (Answer answer : List.of(simulated(behaviour).submit(request()), simulated(behaviour).inquire(id))) {
                assertThat(text(answer)).as(behaviour.name()).contains("\"channel\":\"SIMULATED\"").contains("\"standing\":\"REFERENCE\"");
                claimsNothing(text(answer));
            }
        }
        claimsNothing(SimulatedRegulatoryReportingProvider.NAME);
        claimsNothing(SimulatedRegulatoryReportingProvider.JURISDICTION);
        claimsNothing(SimulatedRegulatoryReportingProvider.NOTICE);
        claimsNothing(RegulatorySubmissionDesk.NOTICE);
        for (SubmissionStatus status : SubmissionStatus.values()) {
            claimsNothing(status.meaning());
        }
    }

    @Test
    @DisplayName("the one jurisdiction is the placeholder: nothing else is declared, so no row can name an authority")
    void itDeclaresThePlaceholderOnly() {
        SimulatedRegulatoryReportingProvider adapter = simulated(SimulatedRegulatoryReportingProvider.Behaviour.ACKNOWLEDGE);
        assertThat(adapter.name()).isEqualTo("simulated-reference");
        assertThat(adapter.declares("REF")).isTrue();
        assertThat(adapter.declares("EU-REF")).isFalse();
        assertThat(adapter.declares("DE-BAFIN")).isFalse();
    }

    @Test
    @DisplayName("the behaviours: a send and an inquiry answer as the knob says, the same on every instance, its reference derived from the id")
    void theBehaviours() {
        String reference = SimulatedRegulatoryReportingProvider.reference(id);
        Answer acknowledged = simulated(SimulatedRegulatoryReportingProvider.Behaviour.ACKNOWLEDGE).submit(request());
        assertThat(acknowledged.outcome()).isEqualTo(Outcome.ACKNOWLEDGED);
        assertThat(acknowledged.providerReference()).contains(reference);
        assertThat(text(simulated(SimulatedRegulatoryReportingProvider.Behaviour.ACKNOWLEDGE).submit(request()))).as("another instance").isEqualTo(text(acknowledged));

        Answer rejected = simulated(SimulatedRegulatoryReportingProvider.Behaviour.REJECT).submit(request());
        assertThat(rejected.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(rejected.rejectionCode()).contains(SimulatedRegulatoryReportingProvider.REJECTED_BY_SIMULATION);

        assertThat(simulated(SimulatedRegulatoryReportingProvider.Behaviour.UNKNOWN_THEN_ACKNOWLEDGE).submit(request()).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(simulated(SimulatedRegulatoryReportingProvider.Behaviour.UNKNOWN_THEN_ACKNOWLEDGE).inquire(id).outcome()).isEqualTo(Outcome.ACKNOWLEDGED);
        assertThat(simulated(SimulatedRegulatoryReportingProvider.Behaviour.UNKNOWN).inquire(id).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(simulated(SimulatedRegulatoryReportingProvider.Behaviour.NEVER_RECEIVED).submit(request()).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(simulated(SimulatedRegulatoryReportingProvider.Behaviour.NEVER_RECEIVED).inquire(id).outcome()).isEqualTo(Outcome.NOT_RECEIVED);
        assertThatThrownBy(() -> simulated(SimulatedRegulatoryReportingProvider.Behaviour.UNAVAILABLE).submit(request())).isInstanceOf(SimulatedRegulatoryReportingProvider.Unavailable.class);
        assertThatThrownBy(() -> simulated(SimulatedRegulatoryReportingProvider.Behaviour.UNAVAILABLE).inquire(id)).isInstanceOf(SimulatedRegulatoryReportingProvider.Unavailable.class);
    }

    @Test
    @DisplayName("it refuses as a provider would: a jurisdiction it does not serve, a content whose hash is not the one named")
    void itRefusesAsAProviderWould() {
        SimulatedRegulatoryReportingProvider adapter = simulated(SimulatedRegulatoryReportingProvider.Behaviour.ACKNOWLEDGE);
        assertThat(adapter.submit(request("DE-BAFIN", CanonicalJson.sha256(CONTENT))).rejectionCode()).contains(SimulatedRegulatoryReportingProvider.JURISDICTION_UNSUPPORTED);
        assertThat(adapter.submit(request(SimulatedRegulatoryReportingProvider.JURISDICTION, "0".repeat(64))).rejectionCode()).contains(SimulatedRegulatoryReportingProvider.CONTENT_HASH_MISMATCH);
    }

    // ------------------------------------------------------------------ the meters

    @Test
    @DisplayName("finapp.accounting.regulatory.submission{outcome}: every outcome registered at zero, an outcome off the closed list refused, no other tag")
    void theSubmissionMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RegulatorySubmissionMetrics metrics = new RegulatorySubmissionMetrics(registry);

        assertThat(registry.get(RegulatorySubmissionMetrics.SUBMISSION).counters()).hasSize(RegulatorySubmissionMetrics.OUTCOMES.size()).allSatisfy(counter -> {
            assertThat(counter.count()).isZero();
            assertThat(counter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey).containsExactly("outcome");
        });
        metrics.count("rejected");
        assertThat(registry.get(RegulatorySubmissionMetrics.SUBMISSION).tag("outcome", "rejected").counter().count()).isEqualTo(1);
        assertThatThrownBy(() -> metrics.count("anything else")).isInstanceOf(IllegalArgumentException.class);
    }

    private final RegulatoryWorld world = new RegulatoryWorld(CLOCK);

    @Test
    @DisplayName("finapp.accounting.regulatory.attention{kind}: from the rows, every kind registered whatever this instance runs; cached; no value when unreadable, never a zero for a failed read")
    void theAttentionGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        boolean[] reachable = {true};
        int[] opens = {0};
        MovableClock clock = new MovableClock();
        new RegulatoryAttentionMetrics(world.submissions, () -> {
            opens[0]++;
            if (!reachable[0]) {
                throw new SQLException("unreachable");
            }
            return readOnlyConnection();
        }, clock, registry);

        assertThat(registry.get(RegulatoryAttentionMetrics.ATTENTION).meters()).extracting(Meter::getId).extracting(meterId -> meterId.getTag("kind")).containsExactlyInAnyOrderElementsOf(RegulatoryAttentionMetrics.KINDS);
        assertThat(registry.get(RegulatoryAttentionMetrics.ATTENTION).tag("kind", "overdue").gauge().value()).isZero();

        // A return overtaken: the row says so.
        world.closed(RegulatoryWorld.JANUARY);
        world.issued(RegulatoryWorld.JANUARY, RegulatoryWorld.body(1, 1, 1, 1));
        RegulatorySubmissions.Acted sent = world.sent(world.active(), RegulatoryWorld.JANUARY);
        world.reopened(RegulatoryWorld.JANUARY);

        assertThat(registry.get(RegulatoryAttentionMetrics.ATTENTION).tag("kind", "overtaken").gauge().value()).as("cached for 15 seconds").isZero();
        int cachedOpens = opens[0];
        clock.advanceSeconds(16);
        assertThat(registry.get(RegulatoryAttentionMetrics.ATTENTION).tag("kind", "overtaken").gauge().value()).isEqualTo(1);
        assertThat(opens[0]).isEqualTo(cachedOpens + 1);
        assertThat(world.store.find(UOW, sent.id()).orElseThrow().monthMoved()).isTrue();

        reachable[0] = false;
        clock.advanceSeconds(16);
        assertThat(registry.get(RegulatoryAttentionMetrics.ATTENTION).tag("kind", "overtaken").gauge().value()).isNaN();
    }

    private static Connection readOnlyConnection() {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    }

    /** A clock a suite moves. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2096-03-10T09:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
