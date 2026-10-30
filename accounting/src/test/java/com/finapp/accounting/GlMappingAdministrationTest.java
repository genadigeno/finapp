package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GL mapping administration over an in-memory store (`P14-TSK-003`; ADR-0113 decision 3; {@code INV-ACC-06},
 * {@code INV-ACC-07}, {@code INV-AUD-04}): four eyes, persons only, the activation judged total and
 * classification-compatible against the chart the activation reads, never retroactive, the predecessor retired in the
 * same act, replays converging, every act audited and the activation published. Hermetic; the database's ranks are
 * {@code GlMappingDatabaseTest}'s (written and owed, owner decision C5) - the named entry tests have their hermetic twins
 * here, which the probes break.
 */
@DisplayName("the GL mapping administration (P14-TSK-003)")
class GlMappingAdministrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** A unit of work no statement reaches - the store is in memory. */
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);

    private final InMemoryGlMappingStore store = new InMemoryGlMappingStore();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final List<byte[]> payloads = new ArrayList<>();
    private LedgerChart.Chart chart = ReferenceGlMapping.agreeingChart(purpose -> true);
    private final GlMappingAdministration administration = new GlMappingAdministration(store, unitOfWork -> chart,
            (unitOfWork, record) -> audit.add(record),
            (unitOfWork, envelope, payload, mediaType) -> {
                events.add(envelope);
                payloads.add(payload);
            },
            IDS, CLOCK);

    private final Actor accountant = new Actor("accountant-1", ActorType.CUSTOMER);
    private final Actor controller = new Actor("controller-1", ActorType.CUSTOMER);

    @Test
    @DisplayName("a version covering every purpose activates under four eyes - proposed by one person, activated by"
            + " another; audited twice, published once, one ACTIVE")
    void aTotalVersionActivatesUnderFourEyes() {
        GlMappingAdministration.Proposed proposed = propose(ReferenceGlMapping.version());
        assertThat(proposed.version()).isEqualTo(1);
        GlMappingAdministration.Decided decided =
                administration.activate(UOW, proposed.id(), controller, "reviewed", correlation());
        assertThat(decided.status()).isEqualTo(GlMappingVersionStatus.ACTIVE);
        assertThat(decided.retired()).isEmpty();
        assertThat(decided.replayed()).isFalse();
        assertThat(store.row(proposed.id()).status()).isEqualTo(GlMappingVersionStatus.ACTIVE);
        assertThat(audit).extracting(record -> record.operation().code())
                .containsExactly("accounting.MappingProposed", "accounting.MappingActivated");
        assertThat(audit).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.reason()).isPresent();
            assertThat(record.targetId()).isEqualTo(proposed.id().value().toString());
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(GlMappingAdministration.ACTIVATED_EVENT);
            assertThat(event.producer()).isEqualTo("accounting");
        });
        assertThat(EventPayload.fields(payloads.get(0)))
                .containsEntry("version", "1").containsEntry("effectiveFromPeriod", "2026-11")
                .doesNotContainKey("retiredVersion");
        assertThat(store.history()).extracting(GlMappingStore.HistoryRow::to)
                .containsExactly(GlMappingVersionStatus.PROPOSED, GlMappingVersionStatus.ACTIVE);
    }

    @Test
    @DisplayName("the proposer cannot approve: SelfApprovalRefused before anything is written; the refusal is recorded"
            + " FAILED by recordRefusal; the proposal stays PROPOSED")
    void theProposerCannotApprove() {
        GlMappingAdministration.Proposed proposed = propose(ReferenceGlMapping.version());
        assertThatThrownBy(() -> administration.activate(UOW, proposed.id(), accountant, "my own", correlation()))
                .isInstanceOfSatisfying(GlMappingAdministration.SelfApprovalRefused.class, refused -> {
                    assertThat(refused.target()).isEqualTo(proposed.id().value().toString());
                    administration.recordRefusal(UOW, refused, accountant, correlation());
                });
        assertThat(store.row(proposed.id()).status()).isEqualTo(GlMappingVersionStatus.PROPOSED);
        assertThat(events).isEmpty();
        assertThat(audit).extracting(record -> record.operation().code())
                .containsExactly("accounting.MappingProposed", "accounting.SelfApprovalRefused");
        assertThat(audit.get(1).outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(audit.get(1).changeSummary()).hasValue("version=v1, act=mapping:activate");
    }

    @Test
    @DisplayName("a non-total version is refused at activation: a purpose of the chart unmapped - named - and nothing"
            + " moves; the same content proposed whole activates")
    void aNonTotalVersionIsRefused() {
        GlMappingAdministration.Proposed partial = propose(ReferenceGlMapping.without(ReferenceGlMapping.EFFECTIVE_FROM,
                Set.of("SUSPENSE_UNMATCHED")));
        assertThatThrownBy(() -> administration.activate(UOW, partial.id(), controller, "reviewed", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingNotTotal.class)
                .hasMessageContaining("SUSPENSE_UNMATCHED/SUSPENSE");
        assertThat(store.row(partial.id()).status()).isEqualTo(GlMappingVersionStatus.PROPOSED);
        assertThat(events).isEmpty();
        // A purpose the ledger adds after the proposal makes a version that was total non-total at activation.
        GlMappingAdministration.Proposed whole = propose(ReferenceGlMapping.version());
        List<LedgerChart.ChartPurpose> grown = new ArrayList<>(chart.purposes());
        grown.add(new LedgerChart.ChartPurpose("A_PURPOSE_ADDED_LATER", "OPERATIONAL", Set.of("ASSET")));
        chart = new LedgerChart.Chart(grown, chart.counterparties());
        assertThatThrownBy(() -> administration.activate(UOW, whole.id(), controller, "reviewed", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingNotTotal.class)
                .hasMessageContaining("A_PURPOSE_ADDED_LATER/OPERATIONAL");
        assertThat(store.active(UOW)).isEmpty();
    }

    @Test
    @DisplayName("an incompatible classification is refused at activation: an asset purpose mapped to a liability"
            + " account, a rule for a purpose the ledger lacks, a purpose holding two types")
    void anIncompatibleClassificationIsRefused() {
        GlMappingAdministration.Proposed misclassified = propose(ReferenceGlMapping.remapped(
                ReferenceGlMapping.EFFECTIVE_FROM, "CASH_AT_BANK",
                new GlAccount("2999", "Cash shown as a liability", GlClassification.LIABILITY,
                        StatementSection.OTHER_LIABILITIES)));
        assertThatThrownBy(() -> administration.activate(UOW, misclassified.id(), controller, "reviewed", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingIncompatible.class)
                .hasMessageContaining("CASH_AT_BANK/OPERATIONAL is ASSET, mapped to 2999 (LIABILITY)");
        assertThat(store.row(misclassified.id()).status()).isEqualTo(GlMappingVersionStatus.PROPOSED);

        GlMappingAdministration.Proposed whole = propose(ReferenceGlMapping.version());
        chart = ReferenceGlMapping.agreeingChart(purpose -> !purpose.equals("FX_POSITION"));
        List<LedgerChart.ChartPurpose> twoTypes = new ArrayList<>(chart.purposes());
        twoTypes.add(new LedgerChart.ChartPurpose("FX_POSITION", "OPERATIONAL", Set.of("ASSET", "LIABILITY")));
        chart = new LedgerChart.Chart(twoTypes, List.of());
        assertThatThrownBy(() -> administration.activate(UOW, whole.id(), controller, "reviewed", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingIncompatible.class)
                .hasMessageContaining("FX_POSITION/OPERATIONAL is ASSET+LIABILITY");

        chart = ReferenceGlMapping.agreeingChart(purpose -> !purpose.equals("FX_POSITION"));
        assertThatThrownBy(() -> administration.activate(UOW, whole.id(), controller, "reviewed", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingIncompatible.class)
                .hasMessageContaining("no such purpose FX_POSITION/OPERATIONAL");
        assertThat(store.active(UOW)).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("activation retires the version in force in the same act, the predecessor ending where the successor"
            + " begins; an older proposal is superseded; an earlier month is retroactive")
    void activationRetiresThePredecessorAndIsNeverRetroactive() {
        GlMappingAdministration.Proposed first = propose(ReferenceGlMapping.version(YearMonth.of(2026, 11)));
        GlMappingAdministration.Proposed older = propose(ReferenceGlMapping.version(YearMonth.of(2026, 12)));
        GlMappingAdministration.Proposed earlier = propose(ReferenceGlMapping.version(YearMonth.of(2026, 10)));
        GlMappingAdministration.Proposed second = propose(ReferenceGlMapping.version(YearMonth.of(2027, 1)));
        administration.activate(UOW, first.id(), controller, "first", correlation());
        GlMappingAdministration.Decided decided =
                administration.activate(UOW, second.id(), controller, "second", correlation());
        assertThat(decided.retired()).hasValue(first.version());
        assertThat(store.row(first.id()).status()).isEqualTo(GlMappingVersionStatus.RETIRED);
        assertThat(store.row(first.id()).effectiveTo()).hasValue(YearMonth.of(2027, 1));
        assertThat(EventPayload.fields(payloads.get(1))).containsEntry("retiredVersion", "1");
        assertThat(store.history()).extracting(GlMappingStore.HistoryRow::to).containsSubsequence(
                GlMappingVersionStatus.RETIRED, GlMappingVersionStatus.ACTIVE);

        assertThatThrownBy(() -> administration.activate(UOW, older.id(), controller, "late", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingStale.class).hasMessageContaining("superseded");
        GlMappingAdministration.Proposed third = propose(ReferenceGlMapping.version(YearMonth.of(2026, 12)));
        assertThatThrownBy(() -> administration.activate(UOW, third.id(), controller, "back-dated", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingRetroactive.class)
                .hasMessageContaining("2026-12").hasMessageContaining("2027-01");
        assertThat(earlier.version()).isLessThan(second.version());
        assertThat(store.active(UOW).orElseThrow().id()).isEqualTo(second.id());
    }

    @Test
    @DisplayName("ten activations of one proposal leave one ACTIVE: the first decides, the same person's repeats"
            + " converge, every other person's is stale; one event, one activation record")
    void tenActivationsOneActive() {
        GlMappingAdministration.Proposed proposed = propose(ReferenceGlMapping.version());
        int stale = 0;
        int replayed = 0;
        for (int i = 0; i < 10; i++) {
            Actor activator = i % 2 == 0 ? controller : new Actor("controller-" + (i + 10), ActorType.CUSTOMER);
            try {
                if (administration.activate(UOW, proposed.id(), activator, "racing", correlation()).replayed()) {
                    replayed++;
                }
            } catch (GlMappingAdministration.MappingStale expected) {
                stale++;
            }
        }
        assertThat(replayed).isEqualTo(4);
        assertThat(stale).isEqualTo(5);
        assertThat(events).hasSize(1);
        assertThat(audit.stream().filter(r -> r.operation() == AccountingAuditAction.MAPPING_ACTIVATED)).hasSize(1);
        assertThat(store.locks()).as("every act took namespace 14 key mapping first").isEqualTo(11);
    }

    @Test
    @DisplayName("a rejection is terminal and reasoned; its repeat converges; a rejected version never activates")
    void aRejectionIsTerminal() {
        GlMappingAdministration.Proposed proposed = propose(ReferenceGlMapping.version());
        GlMappingAdministration.Decided rejected =
                administration.reject(UOW, proposed.id(), controller, "wrong sections", correlation());
        assertThat(rejected.status()).isEqualTo(GlMappingVersionStatus.REJECTED);
        assertThat(administration.reject(UOW, proposed.id(), controller, "again", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> administration.activate(UOW, proposed.id(), controller, "late", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingStale.class);
        assertThat(audit.get(audit.size() - 1).changeSummary()).hasValue("version=v1, byProposer=false");
    }

    @Test
    @DisplayName("persons only, reasons required, unknown ids refused - before anything is written")
    void personsReasonsAndUnknownIds() {
        for (ActorType type : List.of(ActorType.SYSTEM, ActorType.SERVICE, ActorType.MERCHANT)) {
            assertThatThrownBy(() -> administration.propose(UOW, ReferenceGlMapping.version(), "why",
                    new Actor("x", type), correlation())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(administration.propose(UOW, ReferenceGlMapping.version(), "an employee is a person",
                new Actor("e", ActorType.EMPLOYEE), correlation()).version()).isEqualTo(1);
        assertThatThrownBy(() -> administration.propose(UOW, ReferenceGlMapping.version(), " ", accountant,
                correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> administration.propose(UOW, ReferenceGlMapping.version(),
                "card 4111 1111 1111 1111", accountant, correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> administration.activate(UOW, GlMappingVersionId.next(IDS), controller, "why",
                correlation())).isInstanceOf(GlMappingAdministration.MappingNotFound.class);
        assertThat(store.maxVersion(UOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("the judgement is pure and ordered: total and compatible for the reference; each gap named once")
    void theJudgement() {
        MappingJudgement fine = MappingJudgement.judge(ReferenceGlMapping.version(), chart);
        assertThat(fine.total()).isTrue();
        assertThat(fine.compatible()).isTrue();
        MappingJudgement gaps = MappingJudgement.judge(ReferenceGlMapping.without(ReferenceGlMapping.EFFECTIVE_FROM,
                Set.of("FEE_REVENUE", "CASH_AT_BANK")), chart);
        assertThat(gaps.unmapped()).containsExactly("CASH_AT_BANK/OPERATIONAL", "FEE_REVENUE/OPERATIONAL");
        assertThat(gaps.compatible()).isTrue();
        assertThat(ReferenceGlMapping.ownerKinds()).hasSize(40);
    }

    private GlMappingAdministration.Proposed propose(GlMappingVersion version) {
        return administration.propose(UOW, version, "the reference chart", accountant, correlation());
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
