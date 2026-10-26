package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.InMemoryScreeningListStore;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-list, hermetic (`P13-TSK-022`; {@code PHASE_13_PLAN.md} sections 7.1, 11, 12.10; {@code RISK_LIFECYCLES.md} section
 * 4.10; {@code INV-RSK-07}, {@code INV-AUD-04}): a list version recorded by a person - the operator the session types
 * {@code CUSTOMER} included - numbered in the list's recording order under its lock, a recorded label converging; activated
 * by a SECOND person, the predecessor retired first and the enqueue left due, nothing enqueued in the activation's
 * transaction; a second activation, a superseded version, the recorder's own activation and the platform each refused.
 * The database's ranks (the four-eyes and persons' {@code CHECK}s, the trigger) are {@code ScreeningListMigrationTest}'s
 * text and {@code RescreeningDatabaseTest}'s live (written, owed - C5).
 */
@DisplayName("T-list: a list version recorded by one person and activated by a second (P13-TSK-022)")
class ScreeningListVersionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = StandInConnections.unused();
    private static final CorrelationId CORRELATION = CorrelationId.of("p13t22-list");

    /** An operator as the platform's session interceptor establishes every one: CUSTOMER-typed. */
    private static final Actor OFFICER = new Actor("officer-1", ActorType.CUSTOMER);
    private static final Actor SECOND = new Actor("officer-2", ActorType.EMPLOYEE);

    private final InMemoryScreeningListStore store = new InMemoryScreeningListStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final ScreeningListVersions lists = new ScreeningListVersions(store,
            (AuditWriter<Connection>) (uow, record) -> audited.add(record), IDS, CLOCK);

    @Test
    @DisplayName("a CUSTOMER-typed operator records a version: under the list's lock, numbered max + 1, born RECORDED with"
            + " nothing enqueued, audited risk.ListVersionRecorded with the reason; the other list is numbered apart")
    void aPersonRecordsAVersion() {
        ScreeningListVersions.Recorded first = record(ScreeningList.SANCTIONS, "2026-10-26", OFFICER);
        assertThat(first.converged()).isFalse();
        assertThat(first.version().status()).isEqualTo(ScreeningListVersionStatus.RECORDED);
        assertThat(first.version().ordinal()).isEqualTo(1);
        assertThat(first.version().recordedBy().type()).as("the operator the session types CUSTOMER")
                .isEqualTo(ActorType.CUSTOMER);
        assertThat(first.version().enqueueNextAt()).isEmpty();
        assertThat(store.log).startsWith("lock list SANCTIONS", "insert SANCTIONS 2026-10-26");
        assertThat(record(ScreeningList.SANCTIONS, "2026-10-27", SECOND).version().ordinal()).isEqualTo(2);
        assertThat(record(ScreeningList.PEP, "2026-10-26", SECOND).version().ordinal()).as("PEP is its own list")
                .isEqualTo(1);
        AuditRecord recorded = audited.getFirst();
        assertThat(recorded.operation()).isEqualTo(RiskAuditAction.LIST_VERSION_RECORDED);
        assertThat(recorded.targetType()).isEqualTo("screening_list_version");
        assertThat(recorded.targetId()).isEqualTo(first.version().id().value().toString());
        assertThat(recorded.reason()).contains("the provider published a new edition");
        assertThat(recorded.changeSummary().orElseThrow()).isEqualTo("list=SANCTIONS, version=2026-10-26, ordinal=1");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
    }

    @Test
    @DisplayName("a label recorded already converges on its version - the fact exists once: nothing written, nothing audited")
    void aRecordedLabelConverges() {
        ScreeningListVersions.Recorded first = record(ScreeningList.PEP, "v42", OFFICER);
        ScreeningListVersions.Recorded again = record(ScreeningList.PEP, "v42", SECOND);
        assertThat(again.converged()).isTrue();
        assertThat(again.version().id()).isEqualTo(first.version().id());
        assertThat(store.all()).hasSize(1);
        assertThat(audited).hasSize(1);
    }

    @Test
    @DisplayName("refused before anything is locked: a label that is text, a missing reason, and the platform, a service"
            + " or a merchant - a list version is a person's fact")
    void refusalsBeforeAnything() {
        assertThatThrownBy(() -> record(ScreeningList.SANCTIONS, "the new list", OFFICER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(ScreeningList.SANCTIONS, "x".repeat(65), OFFICER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lists.record(UOW, ScreeningList.SANCTIONS, "v1", " ", OFFICER, CORRELATION))
                .isInstanceOf(RiskReasons.ReasonRequired.class);
        for (ActorType type : List.of(ActorType.SYSTEM, ActorType.SERVICE, ActorType.MERCHANT)) {
            assertThatThrownBy(() -> record(ScreeningList.SANCTIONS, "v1", new Actor("platform", type)))
                    .as("a %s never records a list version", type).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a person");
        }
        assertThat(store.log).isEmpty();
        assertThat(store.all()).isEmpty();
    }

    @Test
    @DisplayName("a second person activates: the list's lock, the version's row, then the version in force - retired FIRST"
            + " - the version ACTIVE with its enqueue due now and no permit written; audited with the predecessor named")
    void aSecondPersonActivates() {
        ScreeningListVersion v1 = record(ScreeningList.SANCTIONS, "v1", OFFICER).version();
        ScreeningListVersions.Activated first = lists.activate(UOW, v1.id(), "the edition is in force", SECOND, CORRELATION);
        assertThat(first.retired()).isEmpty();
        assertThat(first.version().status()).isEqualTo(ScreeningListVersionStatus.ACTIVE);
        assertThat(first.version().activatedBy().orElseThrow().id()).isEqualTo(SECOND.id());
        assertThat(first.version().enqueueNextAt()).as("the enqueue is the sweep's, due at once").contains(store.now);
        assertThat(first.version().enqueued()).isZero();
        assertThat(first.version().correlationId()).isEqualTo(CORRELATION.value());

        ScreeningListVersion v2 = record(ScreeningList.SANCTIONS, "v2", SECOND).version();
        store.log.clear();
        ScreeningListVersions.Activated second = lists.activate(UOW, v2.id(), "a newer edition", OFFICER, CORRELATION);
        assertThat(store.log).containsExactly("lock list SANCTIONS", "lock " + v2.id().value(), "lock active SANCTIONS",
                "retire " + v1.id().value(), "activate " + v2.id().value());
        assertThat(second.retired()).contains(v1.id());
        assertThat(store.get(v1.id()).status()).isEqualTo(ScreeningListVersionStatus.RETIRED);
        assertThat(store.get(v1.id()).enqueueNextAt()).as("a retired version's enqueue stops").isEmpty();
        assertThat(store.all().stream().filter(v -> v.status() == ScreeningListVersionStatus.ACTIVE)).hasSize(1);
        AuditRecord activated = audited.getLast();
        assertThat(activated.operation()).isEqualTo(RiskAuditAction.LIST_VERSION_ACTIVATED);
        assertThat(activated.reason()).contains("a newer edition");
        assertThat(activated.changeSummary().orElseThrow()).isEqualTo(
                "list=SANCTIONS, version=v2, ordinal=2, retired=#1");
    }

    @Test
    @DisplayName("a second activation of one version is refused ListVersionStale (409 risk.PolicyStale) - by anyone, the"
            + " first activator included - and changes nothing: one rescreening per version")
    void aSecondActivationOfOneVersionIsRefused() {
        ScreeningListVersion v1 = record(ScreeningList.PEP, "v1", OFFICER).version();
        lists.activate(UOW, v1.id(), "in force", SECOND, CORRELATION);
        ScreeningListVersion standing = store.get(v1.id());
        for (Actor anyone : List.of(SECOND, new Actor("officer-3", ActorType.CUSTOMER))) {
            assertThatThrownBy(() -> lists.activate(UOW, v1.id(), "again", anyone, CORRELATION))
                    .isInstanceOf(ScreeningListVersions.ListVersionStale.class).hasMessageContaining("ACTIVE");
        }
        assertThat(store.get(v1.id())).isEqualTo(standing);
        assertThat(audited).hasSize(2);
    }

    @Test
    @DisplayName("four eyes (INV-AUD-04): the recorder's own activation is SelfApprovalRefused before anything moves - by"
            + " identity, whatever the session typed - and the refusal is recordable FAILED naming the version and the act")
    void theRecorderNeverActivates() {
        ScreeningListVersion v1 = record(ScreeningList.SANCTIONS, "v1", OFFICER).version();
        Actor sameIdentityOtherType = new Actor(OFFICER.id(), ActorType.EMPLOYEE);
        for (Actor recorder : List.of(OFFICER, sameIdentityOtherType)) {
            assertThatThrownBy(() -> lists.activate(UOW, v1.id(), "my own", recorder, CORRELATION))
                    .isInstanceOf(ScreeningListVersions.SelfApprovalRefused.class);
        }
        assertThat(store.get(v1.id()).status()).isEqualTo(ScreeningListVersionStatus.RECORDED);
        ScreeningListVersions.SelfApprovalRefused refused = catchRefusal(v1);
        lists.recordRefusal(UOW, refused, OFFICER, CORRELATION);
        AuditRecord record = audited.getLast();
        assertThat(record.operation()).isEqualTo(RiskAuditAction.SELF_APPROVAL_REFUSED);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(record.targetType()).isEqualTo(ScreeningListVersions.TARGET_TYPE);
        assertThat(record.targetId()).isEqualTo(v1.id().value().toString());
        assertThat(record.changeSummary().orElseThrow()).contains("list=SANCTIONS", "ordinal=1", "act=list:activate");
    }

    @Test
    @DisplayName("a version recorded before the one in force is superseded (ListVersionStale): versions only move forward,"
            + " compared by the list's ordinal, never by two instances' clocks")
    void aSupersededVersionIsRefused() {
        ScreeningListVersion older = record(ScreeningList.SANCTIONS, "v1", OFFICER).version();
        ScreeningListVersion newer = record(ScreeningList.SANCTIONS, "v2", OFFICER).version();
        lists.activate(UOW, newer.id(), "the newest", SECOND, CORRELATION);
        assertThatThrownBy(() -> lists.activate(UOW, older.id(), "the older", SECOND, CORRELATION))
                .isInstanceOf(ScreeningListVersions.ListVersionStale.class).hasMessageContaining("superseded");
        assertThat(store.get(newer.id()).status()).isEqualTo(ScreeningListVersionStatus.ACTIVE);
        assertThat(store.get(older.id()).status()).isEqualTo(ScreeningListVersionStatus.RECORDED);
    }

    @Test
    @DisplayName("an unknown version is ListVersionNotFound; the platform never activates")
    void unknownAndThePlatform() {
        assertThatThrownBy(() -> lists.activate(UOW, ScreeningListVersionId.next(IDS), "x", SECOND, CORRELATION))
                .isInstanceOf(ScreeningListVersions.ListVersionNotFound.class);
        ScreeningListVersion v1 = record(ScreeningList.PEP, "v1", OFFICER).version();
        assertThatThrownBy(() -> lists.activate(UOW, v1.id(), "x", new Actor("platform", ActorType.SYSTEM), CORRELATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get(v1.id()).status()).isEqualTo(ScreeningListVersionStatus.RECORDED);
    }

    @Test
    @DisplayName("the value holds its own coherence: a label is a provider's identifier, an activation names its person, a"
            + " retirement its instant, an enqueue only while in force")
    void theValueIsCoherent() {
        assertThat(ScreeningListVersion.label("2026.10-26:EU_1")).isEqualTo("2026.10-26:EU_1");
        for (String text : new String[] {"", " v1", "-v1", "v 1", "v1;DROP", "Ä1"}) {
            assertThatThrownBy(() -> ScreeningListVersion.label(text)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
        ScreeningListVersion recorded = record(ScreeningList.SANCTIONS, "v1", OFFICER).version();
        assertThatThrownBy(() -> new ScreeningListVersion(recorded.id(), recorded.list(), recorded.version(), 1,
                ScreeningListVersionStatus.ACTIVE, recorded.recordedBy(), recorded.recordedAt(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty(), "c"))
                .as("ACTIVE without an activator").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreeningListVersion(recorded.id(), recorded.list(), recorded.version(), 1,
                ScreeningListVersionStatus.RECORDED, recorded.recordedBy(), recorded.recordedAt(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(CLOCK.instant()), Optional.empty(), 0, Optional.empty(),
                "c")).as("an enqueue before the activation").isInstanceOf(IllegalArgumentException.class);
        assertThat(recorded.toString()).doesNotContain(OFFICER.id());
    }

    // -----------------------------------------------------------------

    private ScreeningListVersions.Recorded record(ScreeningList list, String version, Actor actor) {
        return lists.record(UOW, list, version, "the provider published a new edition", actor, CORRELATION);
    }

    private ScreeningListVersions.SelfApprovalRefused catchRefusal(ScreeningListVersion version) {
        try {
            lists.activate(UOW, version.id(), "my own", OFFICER, CORRELATION);
        } catch (ScreeningListVersions.SelfApprovalRefused refused) {
            return refused;
        }
        throw new AssertionError("the recorder's activation was not refused");
    }
}
