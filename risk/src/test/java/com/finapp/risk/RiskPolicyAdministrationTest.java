package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The risk policy administration, hermetic (`P13-TSK-002`; ADR-0109 decisions 5, 6; {@code RISK_LIFECYCLES.md} section
 * 4.1; {@code INV-RSK-05}, {@code INV-RSK-07}, {@code INV-AUD-04}, {@code INV-AUD-02}): the machine, four eyes at the
 * domain's rank and the refused self-approval's {@code FAILED} record, several proposals numbered without a gap, the
 * lock order A0 then A1 then A2, the predecessor retired before its successor activates, a superseded proposal refused,
 * the same person's retry converging, the engine drift refusal, and the event and audit records naming no threshold or
 * weight. The database's ranks are {@code RiskPolicyDatabaseTest}'s (written, owed - C5).
 */
@DisplayName("the risk policy administration: four eyes, A0-A2, retire beside the successor (P13-TSK-002)")
class RiskPolicyAdministrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor ALICE = new Actor("alice", ActorType.EMPLOYEE);
    private static final Actor BOB = new Actor("bob", ActorType.EMPLOYEE);
    private static final CorrelationId CORRELATION = CorrelationId.of("p13t2-unit");
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            RiskPolicyAdministrationTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final FakeRiskPolicyStore store = new FakeRiskPolicyStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<EventEnvelope> published = new ArrayList<>();
    private final List<String> payloads = new ArrayList<>();
    private final RiskPolicyAdministration administration = new RiskPolicyAdministration(store,
            (AuditWriter<Connection>) (uow, record) -> audited.add(record),
            (OutboxWriter<Connection>) (uow, envelope, payload, mediaType) -> {
                published.add(envelope);
                payloads.add(new String(payload, StandardCharsets.UTF_8));
            },
            IDS, CLOCK);

    @Test
    @DisplayName("a proposal takes the operation's lock before it reads anything and is numbered max + 1; ten proposals"
            + " leave ten versions numbered without a gap, and the other operation is independent")
    void proposalsAreNumberedUnderTheLock() {
        RiskPolicyAdministration.Proposed first = propose(RiskOperation.TRANSFER, ALICE);
        assertThat(first.version()).isEqualTo(1);
        assertThat(store.log).startsWith("A0 TRANSFER", "max TRANSFER", "insert TRANSFER v1");
        for (int i = 2; i <= 10; i++) {
            assertThat(propose(RiskOperation.TRANSFER, BOB).version()).isEqualTo(i);
        }
        assertThat(propose(RiskOperation.WITHDRAWAL, BOB).version()).as("the withdrawal is its own operation").isEqualTo(1);
        administration.reject(UOW, first.id(), ALICE, "withdrawn by its proposer", CORRELATION);
        assertThat(propose(RiskOperation.TRANSFER, BOB).version()).as("numbers are never reused").isEqualTo(11);
        assertThat(audited).extracting(AuditRecord::operation).contains(RiskAuditAction.POLICY_PROPOSED,
                RiskAuditAction.POLICY_REJECTED);
        assertThat(audited.get(0).changeSummary().orElseThrow()).contains("operation=TRANSFER", "version=v1")
                .doesNotContain("50000");
    }

    @Test
    @DisplayName("the proposer never activates (INV-AUD-04, INV-RSK-07) and the refusal is recordable FAILED, naming the"
            + " proposal and the act; the proposer may withdraw; a decided version refuses another decision, the same"
            + " person's retry converging")
    void theProposerCannotActivate() {
        RiskPolicyAdministration.Proposed proposed = propose(RiskOperation.TRANSFER, ALICE);
        RiskPolicyAdministration.SelfApprovalRefused refused = (RiskPolicyAdministration.SelfApprovalRefused)
                catchThrowable(() -> administration.activate(UOW, proposed.id(), ALICE, "my own", CORRELATION));
        assertThat(refused).isNotNull();
        assertThat(refused.target()).isEqualTo(proposed.id().value().toString());
        assertThat(refused.summary()).isEqualTo("operation=TRANSFER, version=v1, act=policy:activate");
        assertThat(store.status(proposed.id())).isEqualTo(RiskPolicyVersionStatus.PROPOSED);
        assertThat(store.log).contains("A0 TRANSFER", "A1 " + proposed.id().value()).doesNotContain("A2 TRANSFER");

        administration.recordRefusal(UOW, refused, ALICE, CORRELATION);
        AuditRecord record = audited.get(audited.size() - 1);
        assertThat(record.operation()).isEqualTo(RiskAuditAction.SELF_APPROVAL_REFUSED);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(record.targetId()).isEqualTo(proposed.id().value().toString());
        assertThat(record.changeSummary().orElseThrow()).contains("act=policy:activate", "refused");

        RiskPolicyAdministration.Decided activated = administration.activate(UOW, proposed.id(), BOB, "reviewed",
                CORRELATION);
        assertThat(activated.status()).isEqualTo(RiskPolicyVersionStatus.ACTIVE);
        assertThat(activated.retired()).isEmpty();
        assertThat(administration.activate(UOW, proposed.id(), BOB, "again", CORRELATION).replayed())
                .as("the same person's retry converges").isTrue();
        assertThatThrownBy(() -> administration.activate(UOW, proposed.id(), ALICE, "late", CORRELATION))
                .isInstanceOf(RiskPolicyAdministration.PolicyStale.class);
        assertThatThrownBy(() -> administration.reject(UOW, proposed.id(), BOB, "late", CORRELATION))
                .isInstanceOf(RiskPolicyAdministration.PolicyStale.class);

        RiskPolicyAdministration.Proposed withdrawn = propose(RiskOperation.TRANSFER, ALICE);
        RiskPolicyAdministration.Decided rejected = administration.reject(UOW, withdrawn.id(), ALICE, "withdrawn",
                CORRELATION);
        assertThat(rejected.status()).isEqualTo(RiskPolicyVersionStatus.REJECTED);
        assertThat(administration.reject(UOW, withdrawn.id(), ALICE, "withdrawn", CORRELATION).replayed()).isTrue();
        assertThat(audited.stream().filter(a -> a.operation() == RiskAuditAction.POLICY_REJECTED).findFirst()
                .orElseThrow().changeSummary().orElseThrow()).contains("byProposer=true");
    }

    @Test
    @DisplayName("an activation locks A0, A1, then the ACTIVE row (A2), retires the predecessor FIRST and activates the"
            + " successor; both history rows, the retirement's audit in the activator's name and the event - operation"
            + " and version only - commit with it")
    void activationRetiresThePredecessor() {
        RiskPolicyAdministration.Proposed v1 = propose(RiskOperation.WITHDRAWAL, ALICE);
        administration.activate(UOW, v1.id(), BOB, "first", CORRELATION);
        RiskPolicyAdministration.Proposed v2 = propose(RiskOperation.WITHDRAWAL, BOB);
        store.log.clear();
        RiskPolicyAdministration.Decided decided = administration.activate(UOW, v2.id(), ALICE, "second", CORRELATION);
        assertThat(decided.retired()).contains(v1.id());
        assertThat(store.log).containsSubsequence("find " + v2.id().value(), "A0 WITHDRAWAL", "A1 " + v2.id().value(),
                "A2 WITHDRAWAL", "retire " + v1.id().value(), "decide " + v2.id().value() + " ACTIVE",
                "event " + v1.id().value() + " ACTIVE -> RETIRED", "event " + v2.id().value() + " PROPOSED -> ACTIVE");
        assertThat(store.status(v1.id())).isEqualTo(RiskPolicyVersionStatus.RETIRED);
        assertThat(store.status(v2.id())).isEqualTo(RiskPolicyVersionStatus.ACTIVE);
        List<AuditRecord> ofThis = audited.subList(audited.size() - 2, audited.size());
        assertThat(ofThis).extracting(AuditRecord::operation)
                .containsExactly(RiskAuditAction.POLICY_RETIRED, RiskAuditAction.POLICY_ACTIVATED);
        assertThat(ofThis.get(0).actor()).isEqualTo(ALICE);
        assertThat(ofThis.get(0).reason()).isEmpty();
        assertThat(ofThis.get(1).changeSummary().orElseThrow()).isEqualTo("operation=WITHDRAWAL, activated=v2, retired=v1");
        assertThat(published).hasSize(2);
        EventEnvelope event = published.get(1);
        assertThat(event.eventType()).isEqualTo("risk.RiskPolicyActivated");
        assertThat(event.aggregateType()).isEqualTo("risk_policy_version");
        assertThat(event.producer()).isEqualTo("risk");
        assertThat(payloads.get(1)).contains("\"operation\":\"WITHDRAWAL\"", "\"version\":\"2\"")
                .doesNotContain("250000").doesNotContain("alice").doesNotContain("bob");
    }

    @Test
    @DisplayName("activation is monotonic in version number: a proposal numbered below the version in force is"
            + " PolicyStale (superseded) and nothing is retired")
    void anOlderProposalIsSuperseded() {
        RiskPolicyAdministration.Proposed v1 = propose(RiskOperation.TRANSFER, ALICE);
        RiskPolicyAdministration.Proposed v2 = propose(RiskOperation.TRANSFER, ALICE);
        administration.activate(UOW, v2.id(), BOB, "the later first", CORRELATION);
        assertThatThrownBy(() -> administration.activate(UOW, v1.id(), BOB, "the earlier after", CORRELATION))
                .isInstanceOf(RiskPolicyAdministration.PolicyStale.class).hasMessageContaining("superseded");
        assertThat(store.status(v2.id())).isEqualTo(RiskPolicyVersionStatus.ACTIVE);
        assertThat(store.status(v1.id())).isEqualTo(RiskPolicyVersionStatus.PROPOSED);
        administration.reject(UOW, v1.id(), BOB, "superseded", CORRELATION);
    }

    @Test
    @DisplayName("a version written for an engine version this build no longer holds is refused at activation,"
            + " PolicyInvalid; an unknown id is PolicyNotFound; a missing reason is ReasonRequired, before any lock")
    void driftAndTheRefusals() {
        RiskPolicyAdministration.Proposed proposed = propose(RiskOperation.TRANSFER, ALICE);
        store.engineOf(proposed.id(), "RISK_ENGINE_V0");
        assertThatThrownBy(() -> administration.activate(UOW, proposed.id(), BOB, "drifted", CORRELATION))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("RISK_ENGINE_V0");
        assertThat(store.status(proposed.id())).isEqualTo(RiskPolicyVersionStatus.PROPOSED);
        assertThatThrownBy(() -> administration.activate(UOW, RiskPolicyVersionId.next(IDS), BOB, "none", CORRELATION))
                .isInstanceOf(RiskPolicyAdministration.PolicyNotFound.class);
        store.log.clear();
        assertThatThrownBy(() -> administration.propose(UOW, RiskPolicyFixtures.v1(RiskOperation.TRANSFER), " ", ALICE,
                CORRELATION)).isInstanceOf(RiskReasons.ReasonRequired.class);
        assertThatThrownBy(() -> administration.activate(UOW, proposed.id(), BOB, "card 4111 1111 1111 1111", CORRELATION))
                .isInstanceOf(RiskReasons.ReasonRequired.class);
        assertThat(store.log).as("a refused reason locks nothing").isEmpty();
    }

    // -----------------------------------------------------------------

    private RiskPolicyAdministration.Proposed propose(RiskOperation operation, Actor actor) {
        return administration.propose(UOW, RiskPolicyFixtures.v1(operation), "v1 for " + operation, actor, CORRELATION);
    }

    /** The store as a map, every call logged - the lock order read from the log. */
    static class FakeRiskPolicyStore implements RiskPolicyStore {
        final List<String> log = new ArrayList<>();
        private final Map<RiskPolicyVersionId, VersionRow> rows = new java.util.LinkedHashMap<>();
        private final Map<RiskPolicyVersionId, RiskPolicy> policies = new java.util.HashMap<>();

        RiskPolicyVersionStatus status(RiskPolicyVersionId id) {
            return rows.get(id).status();
        }

        void engineOf(RiskPolicyVersionId id, String engine) {
            VersionRow row = rows.get(id);
            rows.put(id, new VersionRow(row.id(), row.operation(), row.version(), engine, row.status(), row.proposedBy(),
                    row.proposedAt(), row.decidedBy(), row.decidedAt(), row.effectiveFrom(), row.effectiveTo()));
        }

        @Override
        public void lockOperation(Connection unitOfWork, RiskOperation operation) {
            log.add("A0 " + operation);
        }

        @Override
        public void lockOperationShared(Connection unitOfWork, RiskOperation operation) {
            log.add("A0-shared " + operation);
        }

        @Override
        public int maxVersion(Connection unitOfWork, RiskOperation operation) {
            log.add("max " + operation);
            return rows.values().stream().filter(r -> r.operation() == operation).mapToInt(VersionRow::version).max()
                    .orElse(0);
        }

        @Override
        public void insertProposal(Connection unitOfWork, NewProposal proposal) {
            log.add("insert " + proposal.policy().operation() + " v" + proposal.version());
            rows.put(proposal.id(), new VersionRow(proposal.id(), proposal.policy().operation(), proposal.version(),
                    proposal.policy().engine().name(), RiskPolicyVersionStatus.PROPOSED, proposal.proposer().id(),
                    CLOCK.instant(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            policies.put(proposal.id(), proposal.policy());
        }

        @Override
        public Optional<VersionRow> find(Connection unitOfWork, RiskPolicyVersionId id) {
            log.add("find " + id.value());
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<VersionRow> lock(Connection unitOfWork, RiskPolicyVersionId id) {
            log.add("A1 " + id.value());
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<VersionRow> lockActive(Connection unitOfWork, RiskOperation operation) {
            log.add("A2 " + operation);
            return rows.values().stream()
                    .filter(r -> r.operation() == operation && r.status() == RiskPolicyVersionStatus.ACTIVE).findFirst();
        }

        @Override
        public boolean retire(Connection unitOfWork, RiskPolicyVersionId id) {
            log.add("retire " + id.value());
            VersionRow row = rows.get(id);
            if (row.status() != RiskPolicyVersionStatus.ACTIVE) {
                return false;
            }
            rows.put(id, new VersionRow(row.id(), row.operation(), row.version(), row.engine(),
                    RiskPolicyVersionStatus.RETIRED, row.proposedBy(), row.proposedAt(), row.decidedBy(), row.decidedAt(),
                    row.effectiveFrom(), Optional.of(CLOCK.instant())));
            return true;
        }

        @Override
        public Optional<DecisionStamps> decide(Connection unitOfWork, RiskPolicyVersionId id,
                RiskPolicyVersionStatus status, Actor actor, String reason) {
            log.add("decide " + id.value() + " " + status);
            VersionRow row = rows.get(id);
            if (row.status() != RiskPolicyVersionStatus.PROPOSED) {
                return Optional.empty();
            }
            Optional<Instant> effective = status == RiskPolicyVersionStatus.ACTIVE ? Optional.of(CLOCK.instant())
                    : Optional.empty();
            rows.put(id, new VersionRow(row.id(), row.operation(), row.version(), row.engine(), status, row.proposedBy(),
                    row.proposedAt(), Optional.of(actor.id()), Optional.of(CLOCK.instant()), effective, Optional.empty()));
            return Optional.of(new DecisionStamps(CLOCK.instant(), effective));
        }

        @Override
        public void appendEvent(Connection unitOfWork, HistoryRow row) {
            log.add("event " + row.version().value() + " " + row.from().map(Enum::name).orElse("-") + " -> " + row.to());
        }

        @Override
        public Optional<RiskPolicyVersion> version(Connection unitOfWork, RiskPolicyVersionId id) {
            log.add("version " + id.value());
            return Optional.ofNullable(rows.get(id)).map(row -> new RiskPolicyVersion(row, policies.get(id)));
        }

        @Override
        public Optional<RiskPolicyVersionId> active(Connection unitOfWork, RiskOperation operation) {
            log.add("active " + operation);
            return lockActive(unitOfWork, operation).map(VersionRow::id);
        }

        @Override
        public Optional<RiskPolicyVersionId> shareActiveOnce(Connection unitOfWork, RiskOperation operation) {
            log.add("share " + operation);
            return rows.values().stream()
                    .filter(r -> r.operation() == operation && r.status() == RiskPolicyVersionStatus.ACTIVE)
                    .map(VersionRow::id).findFirst();
        }

        @Override
        public Map<RiskOperation, Integer> activeVersions(Connection unitOfWork) {
            Map<RiskOperation, Integer> versions = new java.util.EnumMap<>(RiskOperation.class);
            rows.values().stream().filter(r -> r.status() == RiskPolicyVersionStatus.ACTIVE)
                    .forEach(r -> versions.put(r.operation(), r.version()));
            return versions;
        }
    }
}
