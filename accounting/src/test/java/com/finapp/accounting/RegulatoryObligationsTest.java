package com.finapp.accounting;

import static com.finapp.accounting.testing.RegulatoryWorld.APPROVER;
import static com.finapp.accounting.testing.RegulatoryWorld.PREPARER;
import static com.finapp.accounting.testing.RegulatoryWorld.REASON;
import static com.finapp.accounting.testing.RegulatoryWorld.SOURCE;
import static com.finapp.accounting.testing.RegulatoryWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.OperationalReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.ReportStatement;
import com.finapp.accounting.testing.InMemoryRegulatoryObligationStore;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regulatory obligation versions - configuration as data, under four eyes (`P14-TSK-018`; ADR-0118 decision 1; {@code INV-AUD-04}): nothing is seeded, every row is {@code REFERENCE}, a proposal is held to what this build holds, the activator names what they read, a later version retires the one in force. {@code
 * RegulatorySubmissionDatabaseTest} is the live half (owed, C5).
 */
@DisplayName("regulatory obligation versions (P14-TSK-018)")
class RegulatoryObligationsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2096-03-10T09:00:00Z"), ZoneOffset.UTC);

    private final RegulatoryWorld world = new RegulatoryWorld(CLOCK);
    private final RegulatoryObligations machine = world.obligations;

    private RegulatoryObligations.Proposal proposal(int version) {
        return new RegulatoryObligations.Proposal(ScriptedRegulatoryProvider.JURISDICTION, RegulatoryWorld.FORM, version, ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration());
    }

    private RegulatoryObligations.Acted proposed(int version) {
        return machine.propose(UOW, proposal(version), REASON, PREPARER, world.correlation());
    }

    private RegulatoryObligationStore.Row row(RegulatoryObligations.Acted acted) {
        return world.obligationStore.find(UOW, acted.id()).orElseThrow();
    }

    @Test
    @DisplayName("nothing is seeded: a store starts empty, and every row a proposal births is REFERENCE, a month's, PROPOSED, its configuration canonical and hashed")
    void aProposalIsBornReferenceAndHashed() {
        assertThat(new InMemoryRegulatoryObligationStore(CLOCK).all()).isEmpty();
        RegulatoryObligations.Acted acted = proposed(1);

        RegulatoryObligationStore.Row row = row(acted);
        assertThat(row.standing()).isEqualTo("REFERENCE");
        assertThat(row.periodType()).isEqualTo("MONTH");
        assertThat(row.status()).isEqualTo(RegulatoryObligationStatus.PROPOSED);
        assertThat(row.configurationSha256()).isEqualTo(CanonicalJson.sha256(row.configuration()));
        assertThat(RegulatoryObligationConfiguration.parse(row.configuration())).isEqualTo(RegulatoryWorld.configuration());
        AuditRecord record = world.audit.get(0);
        assertThat(record.operation().code()).isEqualTo("regulatory.ObligationProposed");
        assertThat(record.reason()).contains(REASON);
        assertThat(record.changeSummary().orElseThrow()).doesNotContain(REASON);
    }

    @Test
    @DisplayName("a proposal is held to the build: an adapter not held, a jurisdiction it does not file with, a source not held at that version, a code out of shape; a form version held twice")
    void aProposalIsHeldToTheBuild() {
        String jurisdiction = ScriptedRegulatoryProvider.JURISDICTION;
        String adapter = ScriptedRegulatoryProvider.NAME;
        RegulatoryObligationConfiguration configuration = RegulatoryWorld.configuration();
        for (RegulatoryObligations.Proposal bad : List.of(
                new RegulatoryObligations.Proposal(jurisdiction, RegulatoryWorld.FORM, 1, "no-such-adapter", configuration),
                new RegulatoryObligations.Proposal("DE-BAFIN", RegulatoryWorld.FORM, 1, adapter, configuration),
                new RegulatoryObligations.Proposal(jurisdiction, "not a form", 1, adapter, configuration),
                new RegulatoryObligations.Proposal(jurisdiction, RegulatoryWorld.FORM, 0, adapter, configuration),
                new RegulatoryObligations.Proposal(jurisdiction, RegulatoryWorld.FORM, 1, adapter, new RegulatoryObligationConfiguration(List.of(new RegulatoryObligationConfiguration.Source(SOURCE, 2)),
                        List.of(new RegulatoryObligationConfiguration.Line("A", "A line", SOURCE, "body.x.y")), List.of(new RegulatoryObligationConfiguration.Rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of())))))) {
            assertThatThrownBy(() -> machine.propose(UOW, bad, REASON, PREPARER, world.correlation())).isInstanceOf(RegulatoryObligations.ObligationInvalid.class);
        }
        proposed(1);
        assertThatThrownBy(() -> proposed(1)).isInstanceOf(RegulatoryObligations.ObligationStale.class);
        assertThatThrownBy(() -> machine.propose(UOW, proposal(2), " ", PREPARER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.propose(UOW, proposal(2), REASON, new Actor("svc", ActorType.SERVICE), world.correlation())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an operational report is never a run, so never a source: refused at the proposal")
    void anOperationalDefinitionCannotBeASource() {
        ReportStatement statement = new ReportStatement("the suite's own source", "the suite's own rule", "the suite's own dates", "the suite's own currencies", "the suite's own freshness");
        ReportDefinitions held = ReportDefinitions.of(new OperationalReportDefinition() {
            @Override
            public String code() {
                return "CLOSE_STATUS";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public ReportStatement statement() {
                return statement;
            }

            @Override
            public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
                return CanonicalJson.object();
            }
        });
        RegulatoryObligations operational = new RegulatoryObligations(world.obligationStore, new RegulatoryAdapters(List.of(world.provider)), held, (uow, record) -> { }, world.ids, CLOCK);
        RegulatoryObligationConfiguration configuration = new RegulatoryObligationConfiguration(List.of(new RegulatoryObligationConfiguration.Source("CLOSE_STATUS", 1)),
                List.of(new RegulatoryObligationConfiguration.Line("A", "A line", "CLOSE_STATUS", "body.x.y")), List.of(new RegulatoryObligationConfiguration.Rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of())));

        assertThatThrownBy(() -> operational.propose(UOW, new RegulatoryObligations.Proposal(ScriptedRegulatoryProvider.JURISDICTION, RegulatoryWorld.FORM, 1, ScriptedRegulatoryProvider.NAME, configuration), REASON, PREPARER, world.correlation()))
                .isInstanceOf(RegulatoryObligations.ObligationInvalid.class).hasMessageContaining("never a run");
    }

    @Test
    @DisplayName("the proposer cannot activate; a second person names the hash they read; a differing one is stale; a repeat converges; each decision audited")
    void theProposerCannotActivate() {
        RegulatoryObligations.Acted acted = proposed(1);

        assertThatThrownBy(() -> machine.activate(UOW, acted.id(), acted.configurationSha256(), REASON, PREPARER, world.correlation())).isInstanceOf(RegulatoryObligations.SelfApprovalRefused.class)
                .satisfies(refused -> machine.recordRefusal(UOW, (RegulatoryObligations.SelfApprovalRefused) refused, PREPARER, world.correlation()));
        AuditRecord refusal = world.audit.get(world.audit.size() - 1);
        assertThat(refusal.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
        assertThat(refusal.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThatThrownBy(() -> world.obligationStore.markActive(UOW, acted.id(), PREPARER, REASON)).isInstanceOf(IllegalStateException.class).hasMessageContaining("regulatory_obligation_four_eyes");

        assertThatThrownBy(() -> machine.activate(UOW, acted.id(), "0".repeat(64), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatoryObligations.ObligationStale.class);
        assertThat(row(acted).status()).isEqualTo(RegulatoryObligationStatus.PROPOSED);

        RegulatoryObligations.Acted active = machine.activate(UOW, acted.id(), acted.configurationSha256(), REASON, APPROVER, world.correlation());
        assertThat(active.status()).isEqualTo(RegulatoryObligationStatus.ACTIVE);
        assertThat(machine.activate(UOW, acted.id(), acted.configurationSha256(), REASON, APPROVER, world.correlation()).replayed()).isTrue();
        assertThat(world.audit.stream().map(record -> record.operation().code())).contains("regulatory.ObligationActivated");
        assertThatThrownBy(() -> machine.activate(UOW, acted.id(), acted.configurationSha256(), REASON, new Actor("controller-2", ActorType.CUSTOMER), world.correlation()))
                .isInstanceOf(RegulatoryObligations.ObligationStale.class);
    }

    @Test
    @DisplayName("a later form version retires the one in force, in the same act; never backwards, never one in force beside another")
    void aLaterVersionRetiresTheOneInForce() {
        RegulatoryObligations.Acted second = proposed(2);
        machine.activate(UOW, second.id(), second.configurationSha256(), REASON, APPROVER, world.correlation());
        RegulatoryObligations.Acted earlier = proposed(1);
        assertThatThrownBy(() -> machine.activate(UOW, earlier.id(), earlier.configurationSha256(), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatoryObligations.ObligationStale.class);

        RegulatoryObligations.Acted third = proposed(3);
        machine.activate(UOW, third.id(), third.configurationSha256(), REASON, APPROVER, world.correlation());
        assertThat(row(second).status()).isEqualTo(RegulatoryObligationStatus.RETIRED);
        assertThat(row(second).retiredAt()).isPresent();
        assertThat(row(third).status()).isEqualTo(RegulatoryObligationStatus.ACTIVE);
    }

    @Test
    @DisplayName("a rejection is final, may be the proposer's, and is reasoned")
    void aRejectionIsFinal() {
        RegulatoryObligations.Acted acted = proposed(1);
        assertThat(machine.reject(UOW, acted.id(), REASON, PREPARER, world.correlation()).status()).isEqualTo(RegulatoryObligationStatus.REJECTED);
        assertThat(machine.reject(UOW, acted.id(), REASON, PREPARER, world.correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> machine.reject(UOW, acted.id(), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatoryObligations.ObligationStale.class);
        assertThatThrownBy(() -> machine.activate(UOW, acted.id(), acted.configurationSha256(), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatoryObligations.ObligationStale.class);
        assertThatThrownBy(() -> machine.reject(UOW, RegulatoryObligationId.next(world.ids), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatoryObligations.ObligationNotFound.class);
    }

    @Test
    @DisplayName("a controller's list excludes their own, in the statement; a person's only; a reader sees whether the build still holds the adapter")
    void aControllersListExcludesTheirOwn() {
        RegulatoryObligations.Acted acted = proposed(1);
        assertThat(machine.awaitingActivation(UOW, APPROVER)).extracting(RegulatoryObligationStore.Row::id).containsExactly(acted.id());
        assertThat(machine.awaitingActivation(UOW, PREPARER)).isEmpty();
        assertThatThrownBy(() -> machine.awaitingActivation(UOW, Actor.SYSTEM)).isInstanceOf(IllegalArgumentException.class);
        assertThat(machine.recent(UOW)).hasSize(1);
        assertThat(machine.view(UOW, acted.id()).adapterHeld()).isTrue();
        assertThat(machine.view(UOW, acted.id()).configuration()).isEqualTo(RegulatoryWorld.configuration());
        assertThatThrownBy(() -> machine.view(UOW, RegulatoryObligationId.next(world.ids))).isInstanceOf(RegulatoryObligations.ObligationNotFound.class);
        List<AuditRecord> reads = new ArrayList<>(world.audit);
        machine.recordRead(UOW, acted.id().value().toString(), "door=preparer", PREPARER, world.correlation());
        assertThat(world.audit).hasSize(reads.size() + 1);
    }
}
