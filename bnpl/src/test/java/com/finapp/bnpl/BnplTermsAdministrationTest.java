package com.finapp.bnpl;

import static com.finapp.bnpl.BnplTermsFixtures.euros;
import static com.finapp.bnpl.BnplTermsFixtures.payIn4;
import static com.finapp.bnpl.BnplTermsFixtures.payInN;
import static com.finapp.bnpl.BnplTermsFixtures.payInNPart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.ScheduleEngineV1;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL terms administration, hermetic (`P12-TSK-004`; ADR-0102 decision 4, {@code BNPL_LIFECYCLES.md} section 4.1;
 * {@code INV-AUD-04}, {@code INV-AUD-02}, {@code INV-HIST-04}): the machine, four eyes at the domain's rank and the refused
 * self-approval's {@code FAILED} record, one pending version per product, the lock order A0 then A1 then A2, the
 * predecessor retired before its successor activates, the same person's retry converging, the judgement against credit's
 * bounds before anything is locked (pay-in-4's own, pay-in-N's by {@code SCHEDULE_ENGINE_V1}'s margined door), the pin
 * answering {@code ProductNotOffered}, and the event and audit records naming no rate or fee. The database's ranks are
 * {@code BnplTermsDatabaseTest}'s.
 */
@DisplayName("the BNPL terms administration: four eyes, one pending, A0-A2, judged against credit (P12-TSK-004)")
class BnplTermsAdministrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor ALICE = new Actor("alice", ActorType.EMPLOYEE);
    private static final Actor BOB = new Actor("bob", ActorType.EMPLOYEE);
    private static final Actor CAROL = new Actor("carol", ActorType.EMPLOYEE);
    private static final CorrelationId CORRELATION = CorrelationId.of("p12t4-unit");
    private static final Connection UOW =
            (Connection) Proxy.newProxyInstance(
                    BnplTermsAdministrationTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("the fakes never touch the connection");
                    });

    private final FakeStore store = new FakeStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> published = new ArrayList<>();
    private final BnplTermsAdministration administration = administration(BnplTermsFixtures.BOUNDS);

    @Test
    @DisplayName("a proposal takes the product's lock before it reads anything, is numbered max + 1, and leaves one"
            + " pending per product - the other product independent")
    void oneProposalPerProductUnderTheLock() {
        BnplTermsAdministration.Proposed first = propose(payInN("0.1999").build(), ALICE);
        assertThat(first.version()).isEqualTo(1);
        assertThat(store.log).startsWith("A0 BNPL_PAY_IN_N", "pending BNPL_PAY_IN_N");
        assertThatThrownBy(() -> propose(payInN("0.2099").build(), BOB))
                .isInstanceOf(BnplTermsAdministration.ProposalPending.class);
        assertThat(propose(payIn4().build(), BOB).version()).as("pay-in-4 is its own product").isEqualTo(1);
        administration.reject(UOW, first.id(), ALICE, "withdrawn by its proposer", CORRELATION);
        assertThat(propose(payInN("0.2099").build(), BOB).version()).as("numbers are never reused").isEqualTo(2);
        assertThat(audited).extracting(AuditRecord::operation).containsExactly(BnplAuditAction.TERMS_VERSION_PROPOSED,
                BnplAuditAction.TERMS_VERSION_PROPOSED, BnplAuditAction.TERMS_VERSION_REJECTED,
                BnplAuditAction.TERMS_VERSION_PROPOSED);
    }

    @Test
    @DisplayName("the proposer never activates (INV-AUD-04) and the refusal is recordable FAILED, naming the proposal and"
            + " the act; the proposer may withdraw; a decided version refuses another decision, the same person's retry"
            + " converging")
    void theProposerCannotApprove() {
        BnplTermsAdministration.Proposed proposed = propose(payIn4().build(), ALICE);
        BnplTermsAdministration.SelfApprovalRefused refused = (BnplTermsAdministration.SelfApprovalRefused)
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> administration.approve(UOW, proposed.id(), ALICE, "my own", CORRELATION));
        assertThat(refused).isNotNull();
        assertThat(store.rows.get(proposed.id()).status()).isEqualTo(BnplTermsVersionStatus.PROPOSED);
        assertThat(published).as("nothing published").isEmpty();
        administration.recordRefusal(UOW, refused, ALICE, CORRELATION);
        AuditRecord record = audited.get(audited.size() - 1);
        assertThat(record.operation()).isEqualTo(BnplAuditAction.SELF_APPROVAL_REFUSED);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(record.targetType()).isEqualTo(BnplTermsAdministration.TARGET_TYPE);
        assertThat(record.targetId()).isEqualTo(proposed.id().value().toString());
        assertThat(record.changeSummary().orElseThrow()).contains("BNPL_PAY_IN_4", "v1", "act=terms:activate");

        BnplTermsAdministration.Decided activated = administration.approve(UOW, proposed.id(), BOB, "reviewed", CORRELATION);
        assertThat(activated.status()).isEqualTo(BnplTermsVersionStatus.ACTIVE);
        assertThat(activated.replayed()).isFalse();
        assertThat(administration.approve(UOW, proposed.id(), BOB, "reviewed", CORRELATION).replayed())
                .as("the approver's retry converges").isTrue();
        assertThatThrownBy(() -> administration.approve(UOW, proposed.id(), CAROL, "late", CORRELATION))
                .isInstanceOf(BnplTermsAdministration.TermsStale.class);
        assertThatThrownBy(() -> administration.reject(UOW, proposed.id(), CAROL, "late", CORRELATION))
                .isInstanceOf(BnplTermsAdministration.TermsStale.class);
        assertThat(published).as("one activation, one event").hasSize(1);
    }

    @Test
    @DisplayName("an approval and a rejection, in either order, leave exactly one decision - the loser stale")
    void approvalAndRejectionLeaveExactlyOne() {
        BnplTermsAdministration.Proposed first = propose(payInN("0.15").build(), ALICE);
        administration.reject(UOW, first.id(), CAROL, "not warranted", CORRELATION);
        assertThatThrownBy(() -> administration.approve(UOW, first.id(), BOB, "too late", CORRELATION))
                .isInstanceOf(BnplTermsAdministration.TermsStale.class);
        assertThat(store.rows.get(first.id()).status()).isEqualTo(BnplTermsVersionStatus.REJECTED);
        BnplTermsAdministration.Proposed second = propose(payInN("0.16").build(), ALICE);
        administration.approve(UOW, second.id(), BOB, "reviewed", CORRELATION);
        assertThatThrownBy(() -> administration.reject(UOW, second.id(), CAROL, "too late", CORRELATION))
                .isInstanceOf(BnplTermsAdministration.TermsStale.class);
        assertThat(store.rows.get(second.id()).status()).isEqualTo(BnplTermsVersionStatus.ACTIVE);
        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName("an activation locks A0, the proposal (A1), then the active row (A2), and retires the predecessor before"
            + " the successor activates; the event and audit name the product and versions, never a rate or a fee")
    void activationRetiresThePredecessorAtomically() {
        BnplTermsVersionId first = activate(payInN("0.1811").build());
        BnplTermsAdministration.Proposed second = propose(payInN("0.1922").build(), ALICE);
        store.log.clear();
        BnplTermsAdministration.Decided decided = administration.approve(UOW, second.id(), BOB, "v2", CORRELATION);
        assertThat(decided.retired()).contains(first);
        assertThat(store.log).containsExactly("find " + second.id().value(), "A0 BNPL_PAY_IN_N",
                "A1 " + second.id().value(), "A2 BNPL_PAY_IN_N", "retire " + first.value(),
                "decide " + second.id().value() + " ACTIVE", "event " + first.value() + " RETIRED",
                "event " + second.id().value() + " ACTIVE");
        assertThat(published.get(published.size() - 1))
                .startsWith(BnplTermsAdministration.ACTIVATED_EVENT)
                .contains("BNPL_PAY_IN_N", first.value().toString())
                .doesNotContain("0.1922").doesNotContain("lateFee").doesNotContain("reason");
        AuditRecord activation = audited.get(audited.size() - 1);
        assertThat(activation.operation()).isEqualTo(BnplAuditAction.TERMS_VERSION_ACTIVATED);
        assertThat(activation.changeSummary().orElseThrow()).contains("activated=v2", "retired=v1");
        assertThat(audited).allSatisfy(record -> assertThat(record.changeSummary().orElse(""))
                .doesNotContain("0.18").doesNotContain("0.19"));
    }

    @Test
    @DisplayName("pay-in-4 is judged against credit's smallest purchase - an instalment of nothing refused before the"
            + " product is locked; credit's EUR 50.00 schedules")
    void aPayIn4SetIsJudgedAgainstCreditsSmallestPurchase() {
        BnplTermsAdministration tiny = administration(product -> new BnplProductBounds.Bounds(euros(3), euros(1_000_00),
                List.of()));
        assertThatThrownBy(() -> tiny.propose(UOW, payIn4().build(), "a proposal", ALICE, CORRELATION))
                .isInstanceOf(BnplTermsAdministration.TermsNotAmortising.class)
                .hasMessageContaining("instalment of nothing");
        assertThat(store.log).as("judged before A0").isEmpty();
        assertThat(administration(product -> new BnplProductBounds.Bounds(euros(4), euros(1_000_00), List.of()))
                .propose(UOW, payIn4().build(), "a proposal", ALICE, CORRELATION).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("a pay-in-N set that cannot amortise is refused with the margin, before anything is locked or written:"
            + " the door's verdict is SCHEDULE_ENGINE_V1's margined judgement over every purchase day, credit's smallest"
            + " amount and the longest plan offered")
    void aPayInNSetThatCannotAmortiseIsRefusedWithTheMargin() {
        BnplTerms steep = payInN("0.85").payInN(Optional.of(payInNPart(java.util.List.of(3, 6, 12), 28))).build();
        assertThatThrownBy(() -> propose(steep, ALICE))
                .isInstanceOf(BnplTermsAdministration.TermsNotAmortising.class)
                .hasMessageContaining("12-month")
                .hasMessageNotContaining("0.85");
        assertThat(store.log).as("judged before A0").isEmpty();
        assertThat(store.rows).isEmpty();
        assertThat(audited).isEmpty();
        assertThat(propose(steep.toBuilder().payInN(Optional.of(payInNPart(List.of(3, 6), 28))).build(), ALICE).version())
                .as("the longest plan is the judged one: without 12 months the same rate amortises").isEqualTo(1);

        List<Integer> everyDay = IntStream.rangeClosed(1, 31).boxed().toList();
        for (int floor : new int[] {1, 15, 28}) {
            for (String rate : new String[] {"0", "0.2", "0.5", "0.8", "0.85", "0.9", "1"}) {
                BnplTerms terms = payInN(rate).payInN(Optional.of(payInNPart(List.of(3, 6, 12), floor))).build();
                boolean engineRefuses = ScheduleEngineV1.judgeAmortisation(terms.scheduleTerms().orElseThrow(), everyDay,
                        euros(200_00), 12).isPresent();
                boolean doorRefuses;
                try {
                    administration.schedulable(terms);
                    doorRefuses = false;
                } catch (BnplTermsAdministration.TermsNotAmortising refused) {
                    doorRefuses = true;
                }
                assertThat(doorRefuses).as("rate %s, floor %d: the door is the engine's judgement", rate, floor)
                        .isEqualTo(engineRefuses);
            }
        }

        BnplTermsAdministration atASmallerMinimum = administration(product -> new BnplProductBounds.Bounds(euros(1),
                euros(5_000_00), List.of(3, 6, 12)));
        assertThatThrownBy(() -> atASmallerMinimum.schedulable(payInN("0.2").build()))
                .as("the smallest purchase credit admits is the judged one - one cent cannot repay 12 instalments")
                .isInstanceOf(BnplTermsAdministration.TermsNotAmortising.class);
    }

    @Test
    @DisplayName("a pay-in-N set offering a plan credit cannot decide is refused whole")
    void aPlanCreditCannotDecideIsRefused() {
        BnplTermsAdministration narrower = administration(product -> new BnplProductBounds.Bounds(euros(200_00),
                euros(5_000_00), List.of(3, 6)));
        assertThatThrownBy(() -> narrower.propose(UOW, payInN("0.2").build(), "a proposal", ALICE, CORRELATION))
                .isInstanceOf(BnplTerms.TermsInvalid.class)
                .hasMessageContaining("credit cannot decide");
        assertThat(store.rows).isEmpty();
    }

    @Test
    @DisplayName("every act is reasoned and screened for instrument shapes (INV-AUD-02); a refusal writes nothing; an"
            + " unknown version is not found")
    void everyActIsReasonedAndScreened() {
        for (String reason : java.util.Arrays.asList(null, "", " ", "x".repeat(1001), "card 4111 1111 1111 1111")) {
            assertThatThrownBy(() -> administration.propose(UOW, payIn4().build(), reason, ALICE, CORRELATION))
                    .isInstanceOf(BnplReasons.ReasonRequired.class);
        }
        assertThat(store.rows).isEmpty();
        BnplTermsAdministration.Proposed proposed = propose(payIn4().build(), ALICE);
        assertThatThrownBy(() -> administration.approve(UOW, proposed.id(), BOB, "DE89 3704 0044 0532 0130 00",
                CORRELATION)).isInstanceOf(BnplReasons.ReasonRequired.class);
        assertThat(audited).hasSize(1);
        assertThatThrownBy(() -> administration.approve(UOW, BnplTermsVersionId.next(IDS), BOB, "who", CORRELATION))
                .isInstanceOf(BnplTermsAdministration.TermsNotFound.class);
        assertThatThrownBy(() -> administration.version(UOW, BnplTermsVersionId.next(IDS)))
                .isInstanceOf(BnplTermsAdministration.TermsNotFound.class);
    }

    @Test
    @DisplayName("without an ACTIVE version the product is not offered: the pin answers ProductNotOffered; once a second"
            + " person activates one, the pin holds it FOR SHARE and returns its whole term set")
    void withoutAnActiveVersionTheProductIsNotOffered() {
        assertThatThrownBy(() -> administration.pinActive(UOW, BnplProduct.BNPL_PAY_IN_4))
                .isInstanceOf(BnplTermsAdministration.ProductNotOffered.class);
        BnplTermsAdministration.Proposed proposed = propose(payIn4().build(), ALICE);
        assertThatThrownBy(() -> administration.pinActive(UOW, BnplProduct.BNPL_PAY_IN_4))
                .as("a proposal offers nothing").isInstanceOf(BnplTermsAdministration.ProductNotOffered.class);
        administration.approve(UOW, proposed.id(), BOB, "reviewed", CORRELATION);
        store.log.clear();
        BnplTermsVersion pinned = administration.pinActive(UOW, BnplProduct.BNPL_PAY_IN_4);
        assertThat(pinned.id()).isEqualTo(proposed.id());
        assertThat(pinned.terms().payIn4()).isPresent();
        assertThat(store.log).containsExactly("share BNPL_PAY_IN_4");
        assertThatThrownBy(() -> administration.pinActive(UOW, BnplProduct.BNPL_PAY_IN_N))
                .as("the other product is still closed").isInstanceOf(BnplTermsAdministration.ProductNotOffered.class);
    }

    // ------------------------------------------------------------------ plumbing

    private BnplTermsAdministration administration(BnplProductBounds bounds) {
        return new BnplTermsAdministration(
                store,
                (AuditWriter<Connection>) (uow, record) -> audited.add(record),
                (OutboxWriter<Connection>) (uow, envelope, payload, media) ->
                        published.add(envelope.eventType() + "|" + new String(payload, StandardCharsets.UTF_8)),
                IDS,
                CLOCK,
                bounds);
    }

    private BnplTermsAdministration.Proposed propose(BnplTerms terms, Actor proposer) {
        return administration.propose(UOW, terms, "a case's proposal", proposer, CORRELATION);
    }

    private BnplTermsVersionId activate(BnplTerms terms) {
        BnplTermsAdministration.Proposed proposed = propose(terms, CAROL);
        administration.approve(UOW, proposed.id(), BOB, "a case's activation", CORRELATION);
        return proposed.id();
    }

    /** The store in memory, logging every lock, read and write in order. */
    private static final class FakeStore implements BnplTermsStore {

        final Map<BnplTermsVersionId, VersionRow> rows = new LinkedHashMap<>();
        final Map<BnplTermsVersionId, BnplTerms> terms = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();

        @Override
        public void lockProduct(Connection unitOfWork, BnplProduct product) {
            log.add("A0 " + product);
        }

        @Override
        public boolean proposalPending(Connection unitOfWork, BnplProduct product) {
            log.add("pending " + product);
            return rows.values().stream()
                    .anyMatch(row -> row.product() == product && row.status() == BnplTermsVersionStatus.PROPOSED);
        }

        @Override
        public int maxVersion(Connection unitOfWork, BnplProduct product) {
            return rows.values().stream().filter(row -> row.product() == product).mapToInt(VersionRow::version).max()
                    .orElse(0);
        }

        @Override
        public void insertProposal(Connection unitOfWork, NewProposal proposal) {
            rows.put(proposal.id(), new VersionRow(proposal.id(), proposal.terms().product(), proposal.version(),
                    BnplTermsVersionStatus.PROPOSED, proposal.proposer().id(), CLOCK.instant(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            terms.put(proposal.id(), proposal.terms());
        }

        @Override
        public Optional<VersionRow> find(Connection unitOfWork, BnplTermsVersionId id) {
            log.add("find " + id.value());
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<VersionRow> lock(Connection unitOfWork, BnplTermsVersionId id) {
            log.add("A1 " + id.value());
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<VersionRow> lockActive(Connection unitOfWork, BnplProduct product) {
            log.add("A2 " + product);
            return active(product);
        }

        @Override
        public boolean retire(Connection unitOfWork, BnplTermsVersionId id) {
            log.add("retire " + id.value());
            VersionRow row = rows.get(id);
            rows.put(id, moved(row, BnplTermsVersionStatus.RETIRED, row.decidedBy()));
            return true;
        }

        @Override
        public Optional<DecisionStamps> decide(
                Connection unitOfWork, BnplTermsVersionId id, BnplTermsVersionStatus status, Actor actor, String reason) {
            log.add("decide " + id.value() + " " + status);
            VersionRow row = rows.get(id);
            if (row.status() != BnplTermsVersionStatus.PROPOSED) {
                return Optional.empty();
            }
            boolean active = status == BnplTermsVersionStatus.ACTIVE;
            if (active && active(row.product()).isPresent()) {
                throw new IllegalStateException("two ACTIVE: the partial unique would refuse this");
            }
            rows.put(id, moved(row, status, Optional.of(actor.id())));
            return Optional.of(new DecisionStamps(CLOCK.instant(), active ? Optional.of(CLOCK.instant()) : Optional.empty()));
        }

        @Override
        public void appendEvent(Connection unitOfWork, HistoryRow row) {
            if (row.from().isPresent()) {
                log.add("event " + row.version().value() + " " + row.to());
            }
        }

        @Override
        public Optional<BnplTermsVersion> version(Connection unitOfWork, BnplTermsVersionId id) {
            return Optional.ofNullable(rows.get(id)).map(row -> new BnplTermsVersion(row, terms.get(id)));
        }

        @Override
        public Optional<BnplTermsVersionId> activeAt(Connection unitOfWork, BnplProduct product, Instant instant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BnplTermsVersionId> active(Connection unitOfWork, BnplProduct product) {
            return active(product).map(VersionRow::id);
        }

        @Override
        public Optional<BnplTermsVersionId> shareActive(Connection unitOfWork, BnplProduct product) {
            log.add("share " + product);
            return active(product).map(VersionRow::id);
        }

        @Override
        public Map<BnplProduct, Integer> activeVersions(Connection unitOfWork) {
            return new EnumMap<>(BnplProduct.class);
        }

        private Optional<VersionRow> active(BnplProduct product) {
            return rows.values().stream()
                    .filter(row -> row.product() == product && row.status() == BnplTermsVersionStatus.ACTIVE)
                    .findFirst();
        }

        private static VersionRow moved(VersionRow row, BnplTermsVersionStatus status, Optional<String> decidedBy) {
            return new VersionRow(row.id(), row.product(), row.version(), status, row.proposedBy(), row.proposedAt(),
                    decidedBy, Optional.of(CLOCK.instant()), row.effectiveFrom(), row.effectiveTo());
        }
    }
}
