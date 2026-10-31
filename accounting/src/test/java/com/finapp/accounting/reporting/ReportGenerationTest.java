package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.testing.TestReportDefinition;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One run's content, generated in a snapshot (`P14-TSK-008`; ADR-0115 decision 2; {@code INV-ACC-04}): the header binds
 * every pinned input to the answer, a FINAL run reads only under the lock it was claimed in, the same inputs give the same
 * bytes, and a content the form refuses fails as {@code CONTENT_INVALID}. Hermetic: the snapshot is a connection nothing
 * touches and the period's state a stand-in; the snapshot's own read-only-ness is {@code JdbcReportSnapshotsTest}'s and
 * the database's ({@code ReportRunDatabaseTest#aWriteInsideGenerationIsRefused}, owed).
 */
@DisplayName("a report run's generation (P14-TSK-008)")
class ReportGenerationTest {

    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            ReportGenerationTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the stand-ins never touch the snapshot");
            });
    private static final AccountingPeriodId PERIOD = AccountingPeriodId.of(UUID.fromString(
            "0190f0c4-1d2e-7a00-8000-0000000000aa"));
    private static final Instant LOCKED_AT = Instant.parse("2026-10-03T09:00:00.123456Z");
    private static final GlMappingVersionId MAPPING = GlMappingVersionId.of(UUID.fromString(
            "0190f0c4-1d2e-7a00-8000-0000000000bb"));

    @Test
    @DisplayName("the content is the definition's body inside a header naming every pinned input - fixed bytes, fixed"
            + " hash; a FINAL run's cut never shows")
    void theHeaderBindsTheQuestionToTheAnswer() {
        ReportGeneration generation = new ReportGeneration(locked(LOCKED_AT));
        ReportGeneration.Generated generated = generation.generate(SNAPSHOT, TestReportDefinition.echo(), finalInputs());
        assertThat(generated.content()).isEqualTo("{\"basis\":\"FINAL\",\"body\":{\"echo\":{\"probe\":\"p-1\"},"
                + "\"from\":\"2026-09-01\",\"through\":\"2026-09-30\",\"total\":{\"currency\":\"EUR\",\"minor\":\"0\","
                + "\"scale\":\"2\"}},\"cut\":null,\"definition\":\"TEST_PROBE\",\"definitionVersion\":\"1\","
                + "\"mappingVersion\":\"3\",\"parameters\":{\"probe\":\"p-1\"},\"period\":\"2026-09\","
                + "\"periodRevision\":\"1\",\"valuationRateSet\":null}");
        assertThat(generated.sha256()).isEqualTo(CanonicalJson.sha256(generated.content())).hasSize(64);
        // A FINAL run cut at another instant is the same answer: the cutoff made its range immutable.
        ReportInputs later = new ReportInputs(PERIOD, YearMonth.of(2026, 9), 1, RunBasis.FINAL, Optional.of(LOCKED_AT),
                Optional.of(MAPPING), Optional.of(3), Instant.parse("2026-10-20T00:00:00Z"), finalInputs().parameters());
        assertThat(generation.generate(SNAPSHOT, TestReportDefinition.echo(), later)).isEqualTo(generated);
    }

    @Test
    @DisplayName("a PROVISIONAL run's header names its cut, and no lock is read")
    void aProvisionalRunNamesItsCut() {
        ReportGeneration generation = new ReportGeneration((snapshot, id) -> {
            throw new AssertionError("a provisional run reads no lock");
        });
        ReportInputs inputs = new ReportInputs(PERIOD, YearMonth.of(2026, 10), 1, RunBasis.PROVISIONAL, Optional.empty(),
                Optional.empty(), Optional.empty(), Instant.parse("2026-10-20T10:15:30.000001Z"), RunParameters.none());
        assertThat(generation.generate(SNAPSHOT, TestReportDefinition.echo(), inputs).content())
                .contains("\"basis\":\"PROVISIONAL\"").contains("\"cut\":\"2026-10-20T10:15:30.000001Z\"")
                .contains("\"mappingVersion\":null");
    }

    @Test
    @DisplayName("a FINAL run reads only under the lock it was claimed in: unlocked, or unlocked and locked again, is"
            + " PeriodMoved - the definition never runs")
    void aMovedPeriodIsRefused() {
        TestReportDefinition never = TestReportDefinition.of(1, (snapshot, inputs) -> {
            throw new AssertionError("the definition never runs over a moved period");
        });
        assertThatThrownBy(() -> new ReportGeneration((snapshot, id) -> Optional.of(
                new ReportGeneration.PeriodState(PeriodStatus.OPEN, Optional.empty())))
                .generate(SNAPSHOT, never, finalInputs())).isInstanceOf(ReportGeneration.PeriodMoved.class);
        assertThatThrownBy(() -> new ReportGeneration(locked(LOCKED_AT.plusSeconds(60)))
                .generate(SNAPSHOT, never, finalInputs())).isInstanceOf(ReportGeneration.PeriodMoved.class);
        assertThatThrownBy(() -> new ReportGeneration((snapshot, id) -> Optional.empty())
                .generate(SNAPSHOT, never, finalInputs())).isInstanceOf(ReportGeneration.PeriodMoved.class);
        // CLOSED under the same lock is still the lock it was claimed in.
        assertThat(new ReportGeneration((snapshot, id) -> Optional.of(new ReportGeneration.PeriodState(
                PeriodStatus.CLOSED, Optional.of(LOCKED_AT)))).generate(SNAPSHOT, TestReportDefinition.echo(),
                finalInputs()).content()).contains("\"basis\":\"FINAL\"");
    }

    @Test
    @DisplayName("a body past the stored bound is ContentInvalid; a body never collides with the header; a definition's own"
            + " failure propagates as itself")
    void anOversizedBodyIsContentInvalid() {
        ReportGeneration generation = new ReportGeneration(locked(LOCKED_AT));
        TestReportDefinition oversized = TestReportDefinition.of(1, (snapshot, inputs) -> CanonicalJson.object()
                .put("filler", "x".repeat(ReportGeneration.MAX_CONTENT_BYTES)));
        assertThatThrownBy(() -> generation.generate(SNAPSHOT, oversized, finalInputs()))
                .isInstanceOf(ReportGeneration.ContentInvalid.class);
        TestReportDefinition headerKey = TestReportDefinition.of(1, (snapshot, inputs) -> CanonicalJson.object()
                .put("definition", "a header key inside the body"));
        // The body is nested under "body", so its own keys never collide with the header's.
        assertThat(generation.generate(SNAPSHOT, headerKey, finalInputs()).content())
                .contains("\"body\":{\"definition\":\"a header key inside the body\"}");
        TestReportDefinition throwing = TestReportDefinition.of(1, (snapshot, inputs) -> {
            throw new IllegalStateException("a read the definition could not make");
        });
        assertThatThrownBy(() -> generation.generate(SNAPSHOT, throwing, finalInputs()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not make");
    }

    @Test
    @DisplayName("the pinned inputs are coherent: a FINAL run names its lock and only a FINAL run does; a mapping pin names"
            + " its id and number together")
    void theInputsAreCoherent() {
        assertThatThrownBy(() -> new ReportInputs(PERIOD, YearMonth.of(2026, 9), 1, RunBasis.FINAL, Optional.empty(),
                Optional.empty(), Optional.empty(), LOCKED_AT, RunParameters.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportInputs(PERIOD, YearMonth.of(2026, 9), 1, RunBasis.PROVISIONAL,
                Optional.of(LOCKED_AT), Optional.empty(), Optional.empty(), LOCKED_AT, RunParameters.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportInputs(PERIOD, YearMonth.of(2026, 9), 1, RunBasis.PROVISIONAL,
                Optional.empty(), Optional.of(MAPPING), Optional.empty(), LOCKED_AT, RunParameters.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the registry: the highest version is current, every version is kept, a code and version are held once")
    void theRegistryKeepsEveryVersion() {
        TestReportDefinition one = TestReportDefinition.echo();
        TestReportDefinition two = TestReportDefinition.of(2, TestReportDefinition::echoOf);
        ReportDefinitions definitions = ReportDefinitions.of(two, one);
        assertThat(definitions.current(TestReportDefinition.CODE)).containsSame(two);
        assertThat(definitions.find(TestReportDefinition.CODE, 1)).containsSame(one);
        assertThat(definitions.find(TestReportDefinition.CODE, 3)).isEmpty();
        assertThat(definitions.current("TRIAL_BALANCE")).isEmpty();
        assertThat(definitions.codes()).containsExactly(TestReportDefinition.CODE);
        assertThatThrownBy(() -> ReportDefinitions.of(one, TestReportDefinition.echo()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("held once");
        assertThatThrownBy(() -> ReportDefinitions.of(new TestReportDefinition("lower", 1, java.util.Set.of(), false,
                TestReportDefinition::echoOf))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportDefinitions.of(TestReportDefinition.of(0, TestReportDefinition::echoOf)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static ReportInputs finalInputs() {
        return new ReportInputs(PERIOD, YearMonth.of(2026, 9), 1, RunBasis.FINAL, Optional.of(LOCKED_AT),
                Optional.of(MAPPING), Optional.of(3), Instant.parse("2026-10-05T00:00:00Z"),
                RunParameters.of(Map.of("probe", "p-1"), Optional.empty()));
    }

    private static ReportGeneration.PeriodStates locked(Instant at) {
        return (snapshot, id) -> Optional.of(new ReportGeneration.PeriodState(PeriodStatus.LOCKED, Optional.of(at)));
    }
}
