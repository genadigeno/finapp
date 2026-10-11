package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The capital contribution's administration, hermetic (`P11-TSK-004`; ADR-0096 section 4; {@code INV-AUD-04},
 * {@code INV-AUD-02}, {@code INV-LND-13}): the machine {@code PROPOSED -> APPROVED | REJECTED}, four eyes at the
 * domain's rank, one pending contribution per currency, the reason screen, the reference family, the currency lock
 * taken before any state is read, and the approval - never the proposal - opening the expectation, once. The database's
 * ranks (the four-eyes {@code CHECK}, the decided-once unique) are {@code LendingCapitalDatabaseTest}'s.
 */
@DisplayName("the lending capital administration: four eyes, one pending, one decision (P11-TSK-004)")
class LendingCapitalAdministrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final Actor ALICE = new Actor("alice", ActorType.EMPLOYEE);
    private static final Actor BOB = new Actor("bob", ActorType.EMPLOYEE);
    private static final Actor CAROL = new Actor("carol", ActorType.EMPLOYEE);
    private static final CorrelationId CORRELATION = CorrelationId.of("p11t4-unit");
    private static final Connection UOW =
            (Connection) Proxy.newProxyInstance(
                    LendingCapitalAdministrationTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("the fakes never touch the connection");
                    });

    private final FakeStore store = new FakeStore();
    private final List<CapitalExpectations.Opening> opened = new ArrayList<>();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final LendingCapitalAdministration administration =
            new LendingCapitalAdministration(
                    store,
                    (uow, opening) -> {
                        opened.add(opening);
                        return opening.contribution().value().toString();
                    },
                    new LendingCapitalHeadroom(capitalChart(), unusedBalances(), new JdbcLoanStore()),
                    (AuditWriter<Connection>) (uow, record) -> audited.add(record),
                    IDS,
                    CLOCK);

    @Test
    @DisplayName("a malformed contribution is refused before the currency is locked: an unsupported currency, a"
            + " non-positive amount, a reference outside LCAP- and 4 to 12 digits")
    void malformedContributionsAreRefused() {
        assertThatThrownBy(() -> propose(CurrencyCode.of("CHF"), 100_00, "LCAP-1001", ALICE))
                .isInstanceOf(LendingCapitalAdministration.ContributionInvalid.class);
        assertThatThrownBy(() -> propose(EUR, 0, "LCAP-1001", ALICE))
                .isInstanceOf(LendingCapitalAdministration.ContributionInvalid.class);
        for (String reference : List.of("LCAP-123", "LCAP-1234567890123", "PSP-REM-1234", "lcap-1234", "LCAP-12A4")) {
            assertThatThrownBy(() -> propose(EUR, 100_00, reference, ALICE))
                    .as(reference)
                    .isInstanceOf(LendingCapitalAdministration.ContributionInvalid.class);
        }
        assertThat(store.locks).as("nothing malformed reaches the lock").isEmpty();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("every act is reasoned, and no reason holds a card-number or bank-account shape (INV-AUD-02)")
    void everyActIsReasonedAndScreened() {
        for (String reason : java.util.Arrays.asList(null, "", "   ", "x".repeat(1001),
                "fund from 4111 1111 1111 1111", "from DE89 3704 0044 0532 0130 00")) {
            assertThatThrownBy(() -> administration.propose(UOW, EUR, 100_00, "LCAP-1001", reason, ALICE, CORRELATION))
                    .isInstanceOf(LendingCapitalAdministration.ReasonRequired.class);
        }
        CapitalContributionId id = propose(EUR, 100_00, "LCAP-1001", ALICE);
        assertThatThrownBy(() -> administration.approve(UOW, id, BOB, "card 4111111111111111", CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.ReasonRequired.class);
        assertThatThrownBy(() -> administration.reject(UOW, id, BOB, " ", CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.ReasonRequired.class);
        assertThat(store.find(UOW, id).orElseThrow().status()).isEqualTo(CapitalContributionStatus.PROPOSED);
    }

    @Test
    @DisplayName("one contribution per currency awaits a decision; another currency is independent; a reference names"
            + " one contribution for all time")
    void onePendingPerCurrencyAndOneReferencePerTransfer() {
        CapitalContributionId first = propose(EUR, 100_00, "LCAP-2001", ALICE);
        assertThatThrownBy(() -> propose(EUR, 200_00, "LCAP-2002", BOB))
                .isInstanceOf(LendingCapitalAdministration.ProposalPending.class);
        propose(GBP, 300_00, "LCAP-2003", BOB);
        administration.reject(UOW, first, ALICE, "withdrawn by its proposer", CORRELATION);
        assertThatThrownBy(() -> propose(EUR, 100_00, "LCAP-2001", ALICE))
                .as("a rejected contribution's reference is still spent")
                .isInstanceOf(LendingCapitalAdministration.ContributionInvalid.class);
        propose(EUR, 100_00, "LCAP-2004", ALICE);
        assertThat(store.locks).as("every act locked its currency first").containsOnly(EUR, GBP);
    }

    @Test
    @DisplayName("four eyes at the domain: the proposer cannot approve, and may reject (a withdrawal)")
    void theProposerNeverApproves() {
        CapitalContributionId id = propose(EUR, 100_00, "LCAP-3001", ALICE);
        assertThatThrownBy(() -> administration.approve(UOW, id, ALICE, "my own", CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.SelfApprovalRefused.class);
        assertThat(opened).isEmpty();
        LendingCapitalAdministration.Decided withdrawn =
                administration.reject(UOW, id, ALICE, "withdrawn: wrong amount", CORRELATION);
        assertThat(withdrawn.status()).isEqualTo(CapitalContributionStatus.REJECTED);
        assertThat(withdrawn.expectationRef()).isEmpty();
        assertThat(opened).as("a rejection opens nothing").isEmpty();
    }

    @Test
    @DisplayName("the approval - a second person's - opens the expectation once, for the contribution's amount and"
            + " reference on its currency's capital account; the proposal opened nothing")
    void theApprovalOpensTheExpectationOnce() {
        CapitalContributionId id = propose(EUR, 1_000_00, "LCAP-4001", ALICE);
        assertThat(opened).as("a proposal is not an expectation").isEmpty();
        LendingCapitalAdministration.Decided approved = administration.approve(UOW, id, BOB, "board minute 7", CORRELATION);
        assertThat(approved.status()).isEqualTo(CapitalContributionStatus.APPROVED);
        assertThat(approved.expectationRef()).contains(id.value().toString());
        assertThat(opened).singleElement().satisfies(opening -> {
            assertThat(opening.amount()).isEqualTo(Money.ofMinorUnits(1_000_00, EUR));
            assertThat(opening.reference()).isEqualTo("LCAP-4001");
            assertThat(opening.capitalAccount().purpose()).isEqualTo(AccountPurpose.LENDING_CAPITAL);
            assertThat(opening.approver()).isEqualTo(BOB);
        });
        assertThat(store.find(UOW, id).orElseThrow().expectationRef()).contains(id.value().toString());
        assertThat(audited).extracting(record -> record.operation().code())
                .containsExactly("lending.CapitalContributionProposed", "lending.CapitalContributionApproved");
        assertThat(audited).allSatisfy(record -> assertThat(record.reason()).isPresent());
        assertThat(audited).allSatisfy(record -> assertThat(record.changeSummary().orElseThrow())
                .doesNotContain("1000").doesNotContain("LCAP-4001"));
    }

    @Test
    @DisplayName("decided once: another approver or a late rejection meets ProposalNotPending; the approver's own"
            + " retry converges without a second expectation")
    void decidedOnce() {
        CapitalContributionId id = propose(EUR, 500_00, "LCAP-5001", ALICE);
        administration.approve(UOW, id, BOB, "approved", CORRELATION);
        assertThatThrownBy(() -> administration.approve(UOW, id, CAROL, "me too", CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.ProposalNotPending.class);
        assertThatThrownBy(() -> administration.reject(UOW, id, CAROL, "too late", CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.ProposalNotPending.class);
        LendingCapitalAdministration.Decided retry = administration.approve(UOW, id, BOB, "approved", CORRELATION);
        assertThat(retry.replayed()).isTrue();
        assertThat(opened).hasSize(1);
        assertThatThrownBy(() -> administration.approve(UOW, CapitalContributionId.next(IDS), BOB, "none", CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.ContributionNotFound.class);
    }

    @Test
    @DisplayName("a contribution's recognition is derived: recognised while its recognition stands, lapsed once the"
            + " database's date passes its window, awaiting funds before")
    void recognitionIsDerived() {
        java.time.LocalDate expectedBy = java.time.LocalDate.parse("2026-10-13");
        assertThat(CapitalRecognitionStatus.derive(false, expectedBy, expectedBy)).isEqualTo(CapitalRecognitionStatus.AWAITING_FUNDS);
        assertThat(CapitalRecognitionStatus.derive(false, expectedBy, expectedBy.plusDays(1))).isEqualTo(CapitalRecognitionStatus.LAPSED);
        assertThat(CapitalRecognitionStatus.derive(true, expectedBy, expectedBy.plusDays(9))).isEqualTo(CapitalRecognitionStatus.RECOGNISED);
        CapitalRecognitions.ExpectationReading repudiated =
                new CapitalRecognitions.ExpectationReading(expectedBy, expectedBy.plusDays(1), false,
                        Optional.of(com.finapp.ledger.JournalEntryId.of(IDS.next())), Optional.of(expectedBy));
        assertThat(repudiated.status())
                .as("a recognition whose expectation reopened (a repudiated statement) is no longer capital")
                .isEqualTo(CapitalRecognitionStatus.LAPSED);
        assertThat(CapitalContributionStatus.PROPOSED.permittedTransitions())
                .containsExactlyInAnyOrder(CapitalContributionStatus.APPROVED, CapitalContributionStatus.REJECTED);
        assertThat(CapitalContributionStatus.APPROVED.isTerminal()).isTrue();
        assertThat(CapitalContributionStatus.REJECTED.isTerminal()).isTrue();
    }

    // -----------------------------------------------------------------

    private CapitalContributionId propose(CurrencyCode currency, long minor, String reference, Actor actor) {
        return administration.propose(UOW, currency, minor, reference, "treasury plan", actor, CORRELATION).id();
    }

    @SuppressWarnings("unchecked")
    private static LedgerAccountStore<Connection> capitalChart() {
        return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(
                LendingCapitalAdministrationTest.class.getClassLoader(), new Class<?>[] {LedgerAccountStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findOperational")) {
                        CurrencyCode currency = (CurrencyCode) args[2];
                        return Optional.of(LedgerAccount.rehydrate(
                                LedgerAccountId.of(IDS.next()), AccountType.EQUITY, AccountType.EQUITY.normalBalance(),
                                currency, OwnerKind.OPERATIONAL, null, (AccountPurpose) args[1], null,
                                LedgerAccountStatus.ACTIVE, CLOCK.instant(), CLOCK.instant()));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static PurposeBalances<Connection> unusedBalances() {
        return (PurposeBalances<Connection>) Proxy.newProxyInstance(
                LendingCapitalAdministrationTest.class.getClassLoader(), new Class<?>[] {PurposeBalances.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("the administration never derives a balance");
                });
    }

    /** The store, in memory - the database's arbiters are the database suite's. */
    private static final class FakeStore implements CapitalContributionStore {

        final List<CurrencyCode> locks = new ArrayList<>();
        final Map<CapitalContributionId, ContributionRow> rows = new LinkedHashMap<>();

        @Override
        public void lockCurrency(Connection unitOfWork, CurrencyCode currency) {
            locks.add(currency);
        }

        @Override
        public Instant transactionTime(Connection unitOfWork) {
            return CLOCK.instant();
        }

        @Override
        public boolean proposalPending(Connection unitOfWork, CurrencyCode currency) {
            assertThat(locks).as("pending is read under the currency's lock").contains(currency);
            return rows.values().stream()
                    .anyMatch(row -> row.currency().equals(currency) && row.status() == CapitalContributionStatus.PROPOSED);
        }

        @Override
        public boolean referenceTaken(Connection unitOfWork, String reference) {
            return rows.values().stream().anyMatch(row -> row.reference().equals(reference));
        }

        @Override
        public void insertProposal(Connection unitOfWork, NewProposal proposal) {
            rows.put(proposal.id(), new ContributionRow(proposal.id(), proposal.amount(), proposal.reference(),
                    proposal.proposer().id(), CLOCK.instant(), CapitalContributionStatus.PROPOSED, Optional.empty(),
                    Optional.empty(), Optional.empty()));
        }

        @Override
        public Optional<ContributionRow> find(Connection unitOfWork, CapitalContributionId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ContributionRow> recent(Connection unitOfWork, int limit) {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<Instant> decide(Connection unitOfWork, Decision decision) {
            ContributionRow row = rows.get(decision.contribution());
            assertThat(locks).as("a decision is taken under the currency's lock").contains(row.currency());
            if (row.status() != CapitalContributionStatus.PROPOSED) {
                return Optional.empty();
            }
            assertThat(decision.proposedBy()).isEqualTo(row.proposedBy());
            rows.put(row.id(), new ContributionRow(row.id(), row.amount(), row.reference(), row.proposedBy(),
                    row.proposedAt(), decision.status(), Optional.of(decision.actor().id()),
                    Optional.of(CLOCK.instant()), decision.expectationRef()));
            return Optional.of(CLOCK.instant());
        }
    }
}
