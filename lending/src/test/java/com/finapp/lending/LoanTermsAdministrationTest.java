package com.finapp.lending;

import static com.finapp.lending.LoanTermsFixtures.creditLine;
import static com.finapp.lending.LoanTermsFixtures.personalLoan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The terms administration, hermetic (`P11-TSK-005`; ADR-0092 section 1, {@code LENDING_LIFECYCLES.md} section 4.1;
 * {@code INV-AUD-04}, {@code INV-AUD-02}): the machine, four eyes at the domain's rank, one pending version per product,
 * the lock order A0 then A1 then A2, the predecessor retired before its successor activates, the same person's retry
 * converging, and the event and audit records naming no rate or fee. The database's ranks are
 * {@code LoanTermsDatabaseTest}'s.
 */
@DisplayName("the loan terms administration: four eyes, one pending, A0-A2 (P11-TSK-005)")
class LoanTermsAdministrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor ALICE = new Actor("alice", ActorType.EMPLOYEE);
    private static final Actor BOB = new Actor("bob", ActorType.EMPLOYEE);
    private static final Actor CAROL = new Actor("carol", ActorType.EMPLOYEE);
    private static final CorrelationId CORRELATION = CorrelationId.of("p11t5-unit");
    private static final Connection UOW =
            (Connection) Proxy.newProxyInstance(
                    LoanTermsAdministrationTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("the fakes never touch the connection");
                    });

    private final FakeStore store = new FakeStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> published = new ArrayList<>();
    private final LoanTermsAdministration administration =
            new LoanTermsAdministration(
                    store,
                    (AuditWriter<Connection>) (uow, record) -> audited.add(record),
                    (OutboxWriter<Connection>) (uow, envelope, payload, media) ->
                            published.add(envelope.eventType() + "|" + new String(payload, StandardCharsets.UTF_8)),
                    IDS,
                    CLOCK,
                    LoanTermsFixtures.BOUNDS);

    @Test
    @DisplayName("a proposal takes the product's lock before it reads anything, is numbered max + 1, and leaves one"
            + " pending per product - another product independent")
    void oneProposalPerProductUnderTheLock() {
        LoanTermsAdministration.Proposed first = propose(personalLoan("0.08").build(), ALICE);
        assertThat(first.version()).isEqualTo(1);
        assertThat(store.log).startsWith("A0 PERSONAL_LOAN", "pending PERSONAL_LOAN");
        assertThatThrownBy(() -> propose(personalLoan("0.09").build(), BOB))
                .isInstanceOf(LoanTermsAdministration.ProposalPending.class);
        assertThat(propose(creditLine("0.15").build(), BOB).version()).as("the line is its own product").isEqualTo(1);
        administration.reject(UOW, first.id(), ALICE, "withdrawn by its proposer", CORRELATION);
        assertThat(propose(personalLoan("0.09").build(), BOB).version()).as("numbers are never reused").isEqualTo(2);
    }

    @Test
    @DisplayName("the proposer never activates (INV-AUD-04) but may withdraw; a decided version refuses another"
            + " decision, the same person's retry converging")
    void fourEyesAtTheDomainsRank() {
        LoanTermsAdministration.Proposed proposed = propose(personalLoan("0.08").build(), ALICE);
        assertThatThrownBy(() -> administration.approve(UOW, proposed.id(), ALICE, "my own", CORRELATION))
                .isInstanceOf(LoanTermsAdministration.SelfApprovalRefused.class);
        assertThat(store.rows.get(proposed.id()).status()).isEqualTo(LoanTermsVersionStatus.PROPOSED);
        LoanTermsAdministration.Decided activated =
                administration.approve(UOW, proposed.id(), BOB, "reviewed", CORRELATION);
        assertThat(activated.status()).isEqualTo(LoanTermsVersionStatus.ACTIVE);
        assertThat(activated.replayed()).isFalse();
        assertThat(administration.approve(UOW, proposed.id(), BOB, "reviewed", CORRELATION).replayed())
                .as("the approver's retry converges").isTrue();
        assertThatThrownBy(() -> administration.approve(UOW, proposed.id(), CAROL, "late", CORRELATION))
                .isInstanceOf(LoanTermsAdministration.TermsStale.class);
        assertThatThrownBy(() -> administration.reject(UOW, proposed.id(), CAROL, "late", CORRELATION))
                .isInstanceOf(LoanTermsAdministration.TermsStale.class);
        assertThat(published).as("one activation, one event").hasSize(1);
    }

    @Test
    @DisplayName("an activation locks A0, the proposal (A1), then the active row (A2), and retires the predecessor"
            + " before the successor activates; the event and audit name the product and versions, never a rate")
    void anActivationRetiresItsPredecessorFirst() {
        LoanTermsVersionId first = activate(personalLoan("0.0811").build());
        LoanTermsAdministration.Proposed second = propose(personalLoan("0.0922").build(), ALICE);
        store.log.clear();
        LoanTermsAdministration.Decided decided = administration.approve(UOW, second.id(), BOB, "v2", CORRELATION);
        assertThat(decided.retired()).contains(first);
        assertThat(store.log).containsExactly("find " + second.id().value(), "A0 PERSONAL_LOAN", "A1 " + second.id().value(),
                "A2 PERSONAL_LOAN", "retire " + first.value(), "decide " + second.id().value() + " ACTIVE",
                "event " + first.value() + " RETIRED", "event " + second.id().value() + " ACTIVE");
        assertThat(published.get(published.size() - 1))
                .startsWith(LoanTermsAdministration.ACTIVATED_EVENT)
                .contains("PERSONAL_LOAN", first.value().toString())
                .doesNotContain("0.0922").doesNotContain("1500");
        AuditRecord activation = audited.get(audited.size() - 1);
        assertThat(activation.operation()).isEqualTo(LendingAuditAction.TERMS_VERSION_ACTIVATED);
        assertThat(activation.changeSummary().orElseThrow()).contains("activated=v2", "retired=v1");
        assertThat(audited).allSatisfy(record -> assertThat(record.changeSummary().orElse(""))
                .doesNotContain("0.08").doesNotContain("0.09"));
    }

    @Test
    @DisplayName("terms that cannot amortise across the product's bounds are refused before the product is locked or"
            + " anything written (P11-TSK-006); a line's terms have no schedule to judge")
    void termsThatCannotAmortiseAreRefusedFirst() {
        assertThatThrownBy(() -> propose(personalLoan("0.30").build(), ALICE))
                .isInstanceOf(LoanTermsAdministration.TermsNotAmortising.class)
                .hasMessageContaining("60-month")
                .hasMessageNotContaining("0.3");
        assertThat(store.log).as("judged before A0").isEmpty();
        assertThat(store.rows).isEmpty();
        assertThat(audited).isEmpty();
        assertThat(propose(personalLoan("0.2309").build(), ALICE).version()).as("23.09 % amortises").isEqualTo(1);
        assertThat(propose(creditLine("0.30").build(), BOB).version()).as("a line is not scheduled").isEqualTo(1);
    }

    @Test
    @DisplayName("every act is reasoned and screened for instrument shapes (INV-AUD-02); a refusal writes nothing")
    void everyActIsReasonedAndScreened() {
        for (String reason : java.util.Arrays.asList(null, "", " ", "x".repeat(1001), "card 4111 1111 1111 1111")) {
            assertThatThrownBy(() -> administration.propose(UOW, personalLoan("0.08").build(), reason, ALICE, CORRELATION))
                    .isInstanceOf(LendingCapitalAdministration.ReasonRequired.class);
        }
        assertThat(store.rows).isEmpty();
        LoanTermsAdministration.Proposed proposed = propose(personalLoan("0.08").build(), ALICE);
        assertThatThrownBy(() -> administration.approve(UOW, proposed.id(), BOB, "DE89 3704 0044 0532 0130 00", CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.ReasonRequired.class);
        assertThat(audited).hasSize(1);
        assertThatThrownBy(() -> administration.approve(UOW, LoanTermsVersionId.next(IDS), BOB, "who", CORRELATION))
                .isInstanceOf(LoanTermsAdministration.TermsNotFound.class);
    }

    // ------------------------------------------------------------------ plumbing

    private LoanTermsAdministration.Proposed propose(LoanTerms terms, Actor proposer) {
        return administration.propose(UOW, terms, "a case's proposal", proposer, CORRELATION);
    }

    private LoanTermsVersionId activate(LoanTerms terms) {
        LoanTermsAdministration.Proposed proposed = propose(terms, CAROL);
        administration.approve(UOW, proposed.id(), BOB, "a case's activation", CORRELATION);
        return proposed.id();
    }

    /** The store in memory, logging every lock, read and write in order. */
    private static final class FakeStore implements LoanTermsStore {

        final Map<LoanTermsVersionId, VersionRow> rows = new LinkedHashMap<>();
        final Map<LoanTermsVersionId, LoanTerms> terms = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();

        @Override
        public void lockProduct(Connection unitOfWork, LoanProduct product) {
            log.add("A0 " + product);
        }

        @Override
        public boolean proposalPending(Connection unitOfWork, LoanProduct product) {
            log.add("pending " + product);
            return rows.values().stream()
                    .anyMatch(row -> row.product() == product && row.status() == LoanTermsVersionStatus.PROPOSED);
        }

        @Override
        public int maxVersion(Connection unitOfWork, LoanProduct product) {
            return rows.values().stream().filter(row -> row.product() == product).mapToInt(VersionRow::version).max()
                    .orElse(0);
        }

        @Override
        public void insertProposal(Connection unitOfWork, NewProposal proposal) {
            rows.put(proposal.id(), new VersionRow(proposal.id(), proposal.terms().product(), proposal.version(),
                    LoanTermsVersionStatus.PROPOSED, proposal.proposer().id(), CLOCK.instant(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            terms.put(proposal.id(), proposal.terms());
        }

        @Override
        public Optional<VersionRow> find(Connection unitOfWork, LoanTermsVersionId id) {
            log.add("find " + id.value());
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<VersionRow> lock(Connection unitOfWork, LoanTermsVersionId id) {
            log.add("A1 " + id.value());
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<VersionRow> lockActive(Connection unitOfWork, LoanProduct product) {
            log.add("A2 " + product);
            return rows.values().stream()
                    .filter(row -> row.product() == product && row.status() == LoanTermsVersionStatus.ACTIVE)
                    .findFirst();
        }

        @Override
        public boolean retire(Connection unitOfWork, LoanTermsVersionId id) {
            log.add("retire " + id.value());
            VersionRow row = rows.get(id);
            rows.put(id, moved(row, LoanTermsVersionStatus.RETIRED, row.decidedBy()));
            return true;
        }

        @Override
        public Optional<DecisionStamps> decide(
                Connection unitOfWork, LoanTermsVersionId id, LoanTermsVersionStatus status, Actor actor, String reason) {
            log.add("decide " + id.value() + " " + status);
            VersionRow row = rows.get(id);
            if (row.status() != LoanTermsVersionStatus.PROPOSED) {
                return Optional.empty();
            }
            boolean active = status == LoanTermsVersionStatus.ACTIVE;
            if (active && rows.values().stream().anyMatch(other -> other.product() == row.product()
                    && other.status() == LoanTermsVersionStatus.ACTIVE)) {
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
        public Optional<LoanTermsVersion> version(Connection unitOfWork, LoanTermsVersionId id) {
            return Optional.ofNullable(rows.get(id)).map(row -> new LoanTermsVersion(row, terms.get(id)));
        }

        @Override
        public Optional<LoanTermsVersionId> activeAt(Connection unitOfWork, LoanProduct product, Instant instant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LoanTermsVersionId> active(Connection unitOfWork, LoanProduct product) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LoanTermsVersionId> shareActive(Connection unitOfWork, LoanProduct product) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<LoanProduct, Integer> activeVersions(Connection unitOfWork) {
            return new EnumMap<>(LoanProduct.class);
        }

        private static VersionRow moved(VersionRow row, LoanTermsVersionStatus status, Optional<String> decidedBy) {
            return new VersionRow(row.id(), row.product(), row.version(), status, row.proposedBy(), row.proposedAt(),
                    decidedBy, Optional.of(CLOCK.instant()), row.effectiveFrom(), row.effectiveTo());
        }
    }
}
