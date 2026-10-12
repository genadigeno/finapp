package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPurposeBalances;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The acceptance against a real database (`P11-TSK-012`; {@code lending V006}, {@code PHASE_11_PLAN.md} sections 5.2,
 * 5.3, 7.1 T-acc, 7.2 and 12.9, {@code LENDING_LIFECYCLES.md} sections 4.4 to 4.6; {@code INV-LND-05},
 * {@code INV-LND-06}, {@code INV-LND-13}, {@code INV-IDEM-01}): every race counted from the rows - ten acceptances of one
 * offer, one loan; acceptance against expiry at the boundary under a skewed instance clock, exactly one; the profile
 * (L0) taken before the offer; acceptances racing for the last capital, one admitted, serialised on L6; a line taking
 * no capital; a crash after every insert leaving nothing - and every rank held for every writer by raw SQL.
 *
 * <p><strong>Credit is scripted</strong>, as in {@link LoanOriginationDatabaseTest}: lending has no build edge to
 * {@code credit} (A16). The double's {@link LoanCreditDecisions#lockExposure} takes a transaction-scoped advisory lock on
 * the party - the stand-in for credit's profile row, with the same serialisation and the same release at commit or
 * rollback - and its {@link LoanCreditDecisions#consume} judges the lapse on the database clock as credit's definer
 * function does. Credit's real profile lock and consumption are proven end to end in {@code app}'s
 * {@code LoanAccountDatabaseTest}. The ledger, the capital headroom and the six accounts are real; capital is planted by
 * a posting from {@code CASH_AT_BANK} - the fixture's, never a path the platform has (bank recognition is the one poster,
 * proven by {@code LendingCapitalDatabaseTest}).
 */
@Tag("database")
@DisplayName("the acceptance (P11-TSK-012)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LoanAcceptanceDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcLoanTermsStore TERMS_STORE = new JdbcLoanTermsStore();
    private static final LoanTermsAdministration ADMINISTRATION = new LoanTermsAdministration(TERMS_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, LoanTermsFixtures.BOUNDS);
    private static final JdbcLoanApplicationStore APPLICATIONS = new JdbcLoanApplicationStore();
    private static final JdbcLoanOfferStore OFFER_STORE = new JdbcLoanOfferStore();
    private static final JdbcLoanStore LOAN_STORE = new JdbcLoanStore();
    private static final LedgerAccountStore<Connection> LEDGER_ACCOUNTS = new JdbcLedgerAccountStore();
    private static final LendingCapitalHeadroom HEADROOM =
            new LendingCapitalHeadroom(LEDGER_ACCOUNTS, new JdbcPurposeBalances(), LOAN_STORE);
    private static final Credit CREDIT = new Credit();
    private static final Map<UUID, Boolean> STANDING = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> VERIFIED_METHODS = new ConcurrentHashMap<>();
    private static final AtomicInteger DESTINATION_CHECKS = new AtomicInteger();
    private static final LoanPartyStanding STANDING_PORT = (unitOfWork, party) -> STANDING.getOrDefault(party, true);
    private static final LoanPayoutDestinations DESTINATIONS = (unitOfWork, party, method) -> {
        DESTINATION_CHECKS.incrementAndGet();
        return party.equals(VERIFIED_METHODS.get(method)) ? Optional.of(method) : Optional.empty();
    };
    private static final LoanOrigination ORIGINATION = new LoanOrigination(APPLICATIONS, OFFER_STORE, ADMINISTRATION,
            CREDIT, new JdbcOutboxWriter(), IDS, CLOCK);
    private static final LoanAcceptances ACCEPTANCES = acceptances(CLOCK, LOAN_STORE, OFFER_STORE, LEDGER_ACCOUNTS,
            new JdbcOutboxWriter(), new JdbcAuditWriter());
    private static final Loans LOANS = new Loans(LOAN_STORE, LEDGER_ACCOUNTS, new JdbcPurposeBalances());
    private static final Actor SYSTEM = new Actor("lending-origination", ActorType.SYSTEM);
    private static final LoanProduct LOAN = LoanProduct.PERSONAL_LOAN;
    private static final LoanProduct LINE = LoanProduct.CREDIT_LINE;

    // ------------------------------------------------------------------ the counted races

    @Test
    @DisplayName("ten acceptances of one offer - ten callers released at once - leave one loan, one agreement v1, one"
            + " evidence row, one PENDING disbursement, six ledger accounts, one ACCEPTED history row, one LoanAccepted"
            + " and one audit record; nine 409 lending.OfferNotAcceptable; nothing else thrown")
    void tenAcceptancesOneLoan() throws Exception {
        Offer offer = offered(LOAN, 4_000_00, Optional.of(24), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(offer.amount());
        List<String> outcomes = race(10, index -> {
            try {
                accept(offer, offer.termsSha256(), Optional.empty());
                return "ACCEPTED";
            } catch (LoanOffers.OfferNotAcceptable refused) {
                return "REFUSED";
            }
        });
        assertThat(outcomes).filteredOn("ACCEPTED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("REFUSED"::equals).hasSize(9);
        UUID loan = loanOf(offer);
        assertThat(count("SELECT count(*) FROM lending.loan WHERE offer_id = ?", offer.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE decision_id = ?", offer.decision()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_acceptance WHERE offer_id = ?", offer.id().value()))
                .isEqualTo(1);
        assertThat(scalar("SELECT status FROM lending.loan_disbursement WHERE loan_id = ?", loan)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", loan)).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM lending.loan_offer_event WHERE offer_id = ? AND to_status = 'ACCEPTED'",
                offer.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ?", loan)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanAccepted'", loan)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                + " AND operation = 'lending.OfferAccepted'", loan.toString())).isEqualTo(1);
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?", offer.id().value())).isEqualTo("ACCEPTED");
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?", loan)).isEqualTo("PENDING_DISBURSEMENT");
    }

    @Test
    @DisplayName("acceptance and expiry at the boundary leave exactly one - five offers raced on their instant by an"
            + " acceptance on an instance five seconds fast and an expiry on one five seconds slow, and the reverse: never"
            + " both, a loan exactly when ACCEPTED; raw SQL on the wrong side of the clock refused for every role")
    void expiryAndAcceptanceLeaveExactlyOne() throws Exception {
        for (int round = 0; round < 6; round++) {
            Duration skew = Duration.ofSeconds(round % 2 == 0 ? 5 : -5);
            LoanAcceptances skewedAcceptance = acceptances(Clock.offset(CLOCK, skew), LOAN_STORE, OFFER_STORE,
                    LEDGER_ACCOUNTS, new JdbcOutboxWriter(), new JdbcAuditWriter());
            LoanOrigination skewedExpiry = new LoanOrigination(APPLICATIONS, OFFER_STORE, ADMINISTRATION, CREDIT,
                    new JdbcOutboxWriter(), IDS, Clock.offset(CLOCK, skew.negated()));
            Offer offer = offered(LINE, 1_500_00, Optional.empty(), Duration.ofMillis(1_500), PayoutPreference.WALLET);
            List<String> outcomes = race(2, index -> {
                // Both released on the same database instant, a few milliseconds before the expiry.
                awaitDatabase("SELECT expires_at - interval '3 milliseconds' <= statement_timestamp()"
                        + " FROM lending.loan_offer WHERE id = ?", offer.id().value());
                if (index == 0) {
                    try {
                        inOneTransaction(uow -> skewedAcceptance.accept(uow, offer.id(), offer.party(),
                                acceptance(offer.termsSha256(), Optional.empty()), customer(offer.party()),
                                correlation()));
                        return "ACCEPTED";
                    } catch (LoanOffers.OfferNotAcceptable refused) {
                        return "-";
                    }
                }
                Optional<LoanProduct> expired =
                        inOneTransaction(uow -> skewedExpiry.expire(uow, offer.id(), SYSTEM));
                return expired.isPresent() ? "EXPIRED" : "-";
            });
            List<String> moved = outcomes.stream().filter(outcome -> !outcome.equals("-")).toList();
            assertThat(moved).as("round %d: never both", round).hasSizeLessThanOrEqualTo(1);
            if (moved.isEmpty()) {
                awaitDatabase("SELECT expires_at <= statement_timestamp() FROM lending.loan_offer WHERE id = ?",
                        offer.id().value());
                Optional<LoanProduct> late = inOneTransaction(uow -> ORIGINATION.expire(uow, offer.id(), SYSTEM));
                assertThat(late).isPresent();
                moved = List.of("EXPIRED");
            }
            assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?", offer.id().value()))
                    .as("round %d", round).isEqualTo(moved.get(0));
            assertThat(count("SELECT count(*) FROM lending.loan_offer_event WHERE offer_id = ?"
                    + " AND from_status = 'OFFERED'", offer.id().value()))
                    .as("round %d: exactly one edge out of OFFERED", round).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM lending.loan WHERE offer_id = ?", offer.id().value()))
                    .as("round %d: a loan exactly when accepted", round).isEqualTo(moved.get(0).equals("ACCEPTED") ? 1 : 0);
        }

        Offer expiring = offered(LINE, 1_500_00, Optional.empty(), Duration.ofSeconds(2), PayoutPreference.WALLET);
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE lending.loan_offer SET status = 'ACCEPTED' WHERE id = '" + expiring.id().value() + "'",
                    "P0001");
        }
        awaitDatabase("SELECT expires_at <= statement_timestamp() FROM lending.loan_offer WHERE id = ?",
                expiring.id().value());
        assertThatThrownBy(() -> accept(expiring, expiring.termsSha256(), Optional.empty()))
                .as("past its instant, unswept: refused, and the sweep expires it")
                .isInstanceOf(LoanOffers.OfferNotAcceptable.class);
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (writer) {
                refused(writer, "UPDATE lending.loan_offer SET status = 'ACCEPTED' WHERE id = '"
                        + expiring.id().value() + "'", "P0001");
            }
        }
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?", expiring.id().value()))
                .isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("a decision credit finds lapsed refuses the acceptance 409 lending.OfferNotAcceptable - credit's own"
            + " clock, read under its lock after the offer's - and writes nothing: no loan, no agreement, no accounts, the"
            + " offer still OFFERED for the sweep to expire")
    void aLapsedDecisionRefusesAcceptance() throws Exception {
        Offer offer = offered(LOAN, 2_000_00, Optional.of(12), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(offer.amount());
        CREDIT.refuse(offer.decision(), LoanCreditDecisions.Consumption.Refusal.LAPSED);
        long loanAccounts = count("SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'LOAN'");
        assertThatThrownBy(() -> accept(offer, offer.termsSha256(), Optional.empty()))
                .isInstanceOf(LoanOffers.OfferNotAcceptable.class)
                .hasMessageContaining("LAPSED");
        assertNothingWritten(offer, loanAccounts);
    }

    @Test
    @DisplayName("the acceptance takes the party's profile first: behind a transaction holding L0 it waits in"
            + " lockExposure's statement holding no lending row - the offer free to lock NOWAIT - and completes once L0"
            + " is released")
    void acceptanceTakesTheProfileFirst() throws Exception {
        Offer offer = offered(LINE, 1_000_00, Optional.empty(), Duration.ofDays(30), PayoutPreference.WALLET);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            Credit.lockProfile(holder, offer.party());
            Future<LoanAcceptances.Accepted> accepting =
                    pool.submit(() -> accept(offer, offer.termsSha256(), Optional.empty()));
            String waiting = awaitAStatementBlockedBy(pid(holder));
            assertThat(waiting).as("the acceptance waits on L0 itself").contains("pg_advisory_xact_lock");
            assertThat(offerLockableNoWait(offer))
                    .as("while it waits on L0 it holds no offer lock: L0 is taken before L2").isTrue();
            assertThat(accepting.isDone()).isFalse();
            holder.commit();
            assertThat(accepting.get(60, TimeUnit.SECONDS).loan().status()).isEqualTo(LoanStatus.ACTIVE);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("acceptances racing for the last capital admit one: behind a transaction holding L6 an acceptance waits"
            + " on the capital row holding its offer; then ten loans raced for exactly one loan's headroom - one"
            + " admitted, nine 422 lending.CapitalUnavailable, headroom exactly zero and the principal committed")
    void acceptancesRacingForTheLastCapitalAdmitOne() throws Exception {
        Offer waiter = offered(LOAN, 1_000_00, Optional.of(12), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(waiter.amount());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            HEADROOM.underLock(holder, LoanTermsFixtures.EUR);
            Future<LoanAcceptances.Accepted> accepting =
                    pool.submit(() -> accept(waiter, waiter.termsSha256(), Optional.empty()));
            String waiting = awaitAStatementBlockedBy(pid(holder));
            assertThat(waiting).as("the acceptance waits on the capital row (L6)").contains("ledger.ledger_account");
            assertThat(offerLockableNoWait(waiter)).as("holding its offer (L2) while it waits on L6").isFalse();
            holder.commit();
            assertThat(accepting.get(60, TimeUnit.SECONDS).loan().status()).isEqualTo(LoanStatus.PENDING_DISBURSEMENT);
        } finally {
            pool.shutdownNow();
        }

        Money headroom = headroomNow().headroom();
        Money planted = Money.ofMinorUnits(750_00 + Math.max(-headroom.minorUnits(), 0), LoanTermsFixtures.EUR);
        plantCapital(planted);
        Money last = headroom.plus(planted);
        assertThat(headroomNow().headroom()).isEqualTo(last);
        Money committedBefore = headroomNow().committed();
        List<Offer> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            racers.add(offered(LOAN, last.minorUnits(), Optional.of(12), Duration.ofDays(30), PayoutPreference.WALLET));
        }
        List<String> outcomes = race(10, index -> {
            try {
                accept(racers.get(index), racers.get(index).termsSha256(), Optional.empty());
                return "ADMITTED";
            } catch (LoanAcceptances.CapitalUnavailable short_) {
                return "CAPITAL_UNAVAILABLE";
            }
        });
        assertThat(outcomes).as("exactly one acceptance takes the last capital").filteredOn("ADMITTED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("CAPITAL_UNAVAILABLE"::equals).hasSize(9);
        LendingCapitalHeadroom.Headroom after = headroomNow();
        assertThat(after.headroom().minorUnits()).as("INV-LND-13: never over-deployed").isZero();
        assertThat(after.committed()).isEqualTo(committedBefore.plus(last));
        assertThat(count("SELECT count(*) FROM lending.loan l JOIN lending.loan_offer o ON o.id = l.offer_id"
                + " WHERE o.id = ANY (?)", (Object) ids(racers))).isEqualTo(1);
    }

    @Test
    @DisplayName("a line commits no capital (A28): accepted for a limit beyond the headroom while another transaction"
            + " holds L6, it never waits on the capital row, and the committed term is unchanged")
    void aLineCommitsNoCapital() throws Exception {
        Money headroom = headroomNow().headroom();
        long beyond = Math.max(headroom.minorUnits(), 0) + 100_000_00;
        Offer line = offered(LINE, beyond, Optional.empty(), Duration.ofDays(30), PayoutPreference.WALLET);
        Money committedBefore = headroomNow().committed();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            HEADROOM.underLock(holder, LoanTermsFixtures.EUR);
            LoanAcceptances.Accepted accepted = pool.submit(() -> accept(line, line.termsSha256(), Optional.empty()))
                    .get(20, TimeUnit.SECONDS);
            assertThat(accepted.loan().kind()).isEqualTo(LoanKind.REVOLVING);
            assertThat(accepted.loan().status()).isEqualTo(LoanStatus.ACTIVE);
            holder.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertThat(headroomNow().committed()).isEqualTo(committedBefore);
        assertThat(count("SELECT count(*) FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id"
                + " WHERE l.offer_id = ?", line.id().value())).as("a line draws; it is never disbursed").isZero();
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("a mismatched hash refuses 409 lending.TermsChanged and writes nothing - another offer's hash, a"
            + " malformed echo, a flipped character - and credit is never asked")
    void aMismatchedHashRefuses() throws Exception {
        Offer offer = offered(LOAN, 2_500_00, Optional.of(18), Duration.ofDays(30), PayoutPreference.WALLET);
        Offer other = offered(LOAN, 2_600_00, Optional.of(18), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(offer.amount());
        long loanAccounts = count("SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'LOAN'");
        String flipped = (offer.termsSha256().charAt(0) == 'a' ? "b" : "a") + offer.termsSha256().substring(1);
        for (String echoed : List.of(other.termsSha256(), "not-a-hash", flipped, "")) {
            int consumed = CREDIT.consumptions.get();
            assertThatThrownBy(() -> accept(offer, echoed, Optional.empty())).as(echoed)
                    .isInstanceOf(LoanAcceptances.TermsChanged.class);
            assertThat(CREDIT.consumptions.get()).as("credit never asked").isEqualTo(consumed);
        }
        assertNothingWritten(offer, loanAccounts);
        assertThat(accept(offer, offer.termsSha256().toUpperCase(java.util.Locale.ROOT), Optional.empty()).agreement()
                .termsSha256Hex()).as("the offer's own hash, in either case, accepts").isEqualTo(offer.termsSha256());
    }

    @Test
    @DisplayName("standing lost refuses: a party no longer in good standing is refused before credit is asked, nothing"
            + " written")
    void standingLostRefuses() throws Exception {
        Offer offer = offered(LINE, 1_200_00, Optional.empty(), Duration.ofDays(30), PayoutPreference.WALLET);
        STANDING.put(offer.party(), false);
        long loanAccounts = count("SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'LOAN'");
        int consumed = CREDIT.consumptions.get();
        assertThatThrownBy(() -> accept(offer, offer.termsSha256(), Optional.empty()))
                .isInstanceOf(LoanApplications.NotInGoodStanding.class);
        assertThat(CREDIT.consumptions.get()).isEqualTo(consumed);
        assertNothingWritten(offer, loanAccounts);
        STANDING.remove(offer.party());
    }

    @Test
    @DisplayName("an unverified destination refuses 422 lending.PayoutDestinationInvalid before any write: none named"
            + " for an external payout, another party's method, a method named for a wallet payout; the customer's own"
            + " verified method is recorded on the PENDING disbursement as its opaque reference")
    void anUnverifiedDestinationRefuses() throws Exception {
        Offer external = offered(LOAN, 3_000_00, Optional.of(24), Duration.ofDays(30), PayoutPreference.EXTERNAL_ACCOUNT);
        Offer wallet = offered(LOAN, 3_000_00, Optional.of(24), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(external.amount());
        UUID own = UUID.randomUUID();
        UUID anothers = UUID.randomUUID();
        VERIFIED_METHODS.put(own, external.party());
        VERIFIED_METHODS.put(anothers, UUID.randomUUID());
        long loanAccounts = count("SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'LOAN'");
        int consumed = CREDIT.consumptions.get();
        for (Optional<UUID> method : List.of(Optional.<UUID>empty(), Optional.of(anothers), Optional.of(UUID.randomUUID()))) {
            assertThatThrownBy(() -> accept(external, external.termsSha256(), method)).as(method.toString())
                    .isInstanceOf(LoanAcceptances.PayoutDestinationInvalid.class);
        }
        assertThatThrownBy(() -> accept(wallet, wallet.termsSha256(), Optional.of(own)))
                .as("a wallet payout names no destination").isInstanceOf(LoanAcceptances.PayoutDestinationInvalid.class);
        assertThat(CREDIT.consumptions.get()).as("refused before credit is asked").isEqualTo(consumed);
        assertNothingWritten(external, loanAccounts);

        LoanAcceptances.Accepted accepted = accept(external, external.termsSha256(), Optional.of(own));
        assertThat(scalar("SELECT payout_method_id::text FROM lending.loan_disbursement WHERE loan_id = ?",
                accepted.loan().id().value())).isEqualTo(own.toString());
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "INSERT INTO lending.loan_disbursement (id, loan_id, status, payout_method_id, created_at,"
                    + " deadline_at, next_attempt_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + accepted.loan().id().value() + "', 'PENDING', NULL, now(), now() + interval '1 hour', now(), 'raw')",
                    "P0001");
            owner.rollback();
        }
    }

    // ------------------------------------------------------------------ atomicity

    @Test
    @DisplayName("a crash after each of the acceptance's fifteen writes leaves nothing - no loan, agreement, evidence,"
            + " disbursement, ledger account, offer edge, event or audit record - and the retry then accepts once")
    void aCrashAfterEachInsertLeavesNothing() throws Exception {
        Offer offer = offered(LOAN, 1_800_00, Optional.of(12), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(offer.amount());
        long loanAccounts = count("SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'LOAN'");
        long events = count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'lending.LoanAccepted'");
        long audits = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.OfferAccepted'");
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger crashAt = new AtomicInteger();
        LoanAcceptances crashing = acceptances(CLOCK, crashing(LoanStore.class, LOAN_STORE, writes, crashAt),
                crashing(LoanOfferStore.class, OFFER_STORE, writes, crashAt),
                crashing(LedgerAccountStore.class, LEDGER_ACCOUNTS, writes, crashAt),
                crashing(OutboxWriter.class, new JdbcOutboxWriter(), writes, crashAt),
                crashing(AuditWriter.class, new JdbcAuditWriter(), writes, crashAt));
        for (int at = 1; at <= 15; at++) {
            writes.set(0);
            crashAt.set(at);
            assertThatThrownBy(() -> inOneTransaction(uow -> crashing.accept(uow, offer.id(), offer.party(),
                    acceptance(offer.termsSha256(), Optional.empty()), customer(offer.party()), correlation())))
                    .as("crash after write %d", at).hasMessageContaining("crashed after write " + at);
            assertNothingWritten(offer, loanAccounts);
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'lending.LoanAccepted'"))
                    .isEqualTo(events);
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.OfferAccepted'"))
                    .isEqualTo(audits);
        }
        writes.set(0);
        crashAt.set(Integer.MAX_VALUE);
        inOneTransaction(uow -> crashing.accept(uow, offer.id(), offer.party(),
                acceptance(offer.termsSha256(), Optional.empty()), customer(offer.party()), correlation()));
        assertThat(writes.get()).as("the loop crashed after every one of the acceptance's writes").isEqualTo(15);
        assertThat(count("SELECT count(*) FROM lending.loan WHERE offer_id = ?", offer.id().value())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the records and their ranks

    @Test
    @DisplayName("the six accounts are opened in the loan's currency, each of its declared type and owned by the loan;"
            + " the read derives every balance from journal lines - zero before any posting - and shows the agreement")
    void theSixAccountsAreOpenedInTheAccountsCurrency() throws Exception {
        Offer offer = offered(LOAN, 2_200_00, Optional.of(24), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(offer.amount());
        LoanAcceptances.Accepted accepted = accept(offer, offer.termsSha256(), Optional.empty());
        assertThat(accepted.ledgerAccounts().all()).hasSize(6).allSatisfy(account -> {
            assertThat(account.currency()).isEqualTo(LoanTermsFixtures.EUR);
            assertThat(account.ownerRef()).contains(accepted.loan().id().value());
            assertThat(account.accountType()).isEqualTo(LoanAccounts.declaredTypeOf(account.purpose()));
        });
        assertThat(accepted.ledgerAccounts().all().stream().map(account -> account.purpose()).toList())
                .isEqualTo(LoanAccounts.purposes());
        Loans.Read read = inOneTransaction(uow -> LOANS.read(uow, accepted.loan().id(), offer.party())).orElseThrow();
        assertThat(read.owned().agreement().amount()).isEqualTo(offer.amount());
        assertThat(read.owned().agreement().termsSha256Hex()).isEqualTo(offer.termsSha256());
        assertThat(read.balances().principalOutstanding().isZero()).isTrue();
        assertThat(read.balances().creditBalance().isZero()).isTrue();
        Optional<Loans.Read> stranger = inOneTransaction(uow -> LOANS.read(uow, accepted.loan().id(), UUID.randomUUID()));
        assertThat(stranger).as("another party's loan is absent").isEmpty();
        List<Loans.Read> listed = inOneTransaction(uow -> LOANS.list(uow, offer.party()));
        assertThat(listed).hasSize(1);
        assertThat(count("SELECT count(*) FROM lending.loan_acceptance WHERE loan_id = ? AND assurance_level ="
                + " 'MULTI_FACTOR' AND channel = 'API' AND client_ip = '203.0.113.7'::inet"
                + " AND document_sha256 = (SELECT document_sha256 FROM lending.loan_offer_terms WHERE offer_id = ?)",
                accepted.loan().id().value(), offer.id().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("an offer east of UTC is accepted end to end (P11-TSK-031): its +05:30 terms in canonical format 2, the"
            + " agreement copying the bytes, their hash and their format 2 - V011's widened CHECK admits it")
    void anOfferEastOfUtcIsAccepted() throws Exception {
        Offer offer;
        try {
            activate(LoanTermsFixtures.of(LOAN, "0.0899").servicingZone("+05:30").build());
            offer = offered(LOAN, 1_900_00, Optional.of(12), Duration.ofDays(30), PayoutPreference.WALLET);
        } finally {
            activate(LoanTermsFixtures.of(LOAN, "0.0899").build()); // the suite's later cases find a UTC version
        }
        fund(offer.amount());
        UUID loan = accept(offer, offer.termsSha256(), Optional.empty()).loan().id().value();
        assertThat(scalar("SELECT canonical_format::text FROM lending.loan_agreement WHERE loan_id = ?", loan))
                .isEqualTo("2");
        assertThat(scalar("SELECT canonical_terms FROM lending.loan_agreement WHERE loan_id = ?", loan))
                .contains("\"servicingZone\":\"+05:30\"")
                .isEqualTo(scalar("SELECT canonical_terms FROM lending.loan_offer_terms WHERE offer_id = ?",
                        offer.id().value()));
        assertThat(scalar("SELECT encode(terms_sha256, 'hex') FROM lending.loan_agreement WHERE loan_id = ?", loan))
                .isEqualTo(offer.termsSha256());
    }

    @Test
    @DisplayName("a format-1 offer left OFFERED from before V011 is still accepted (P11-TSK-031): the agreement copies its"
            + " offer's format 1 - the format its bytes declare, never the build's - or V006's exact-copy trigger"
            + " would refuse every such acceptance")
    void aFormat1OfferIsStillAccepted() throws Exception {
        LoanOrigination beforeV011 = new LoanOrigination(APPLICATIONS, writingFormat1(OFFER_STORE), ADMINISTRATION,
                CREDIT, new JdbcOutboxWriter(), IDS, CLOCK);
        Offer offer = offered(beforeV011, LOAN, 1_700_00, Optional.of(12), Duration.ofDays(30), PayoutPreference.WALLET);
        assertThat(scalar("SELECT canonical_format::text FROM lending.loan_offer_terms WHERE offer_id = ?",
                offer.id().value())).as("the offer as the format-1 build wrote it").isEqualTo("1");
        fund(offer.amount());
        UUID loan = accept(offer, offer.termsSha256(), Optional.empty()).loan().id().value();
        assertThat(scalar("SELECT canonical_format::text FROM lending.loan_agreement WHERE loan_id = ?", loan))
                .isEqualTo("1");
        assertThat(scalar("SELECT canonical_terms FROM lending.loan_agreement WHERE loan_id = ?", loan))
                .startsWith("{\"format\":\"1\",")
                .isEqualTo(scalar("SELECT canonical_terms FROM lending.loan_offer_terms WHERE offer_id = ?",
                        offer.id().value()));
    }

    @Test
    @DisplayName("the agreement is never updated by any role - nor the evidence, the loan, its disbursement or its"
            + " history: refused by privilege for the application and by trigger for the owner; version 2 and a forged"
            + " copy refused; a loan or an ACCEPTED edge committed without the other refused at commit")
    void theAgreementIsNeverUpdatedByAnyRole() throws Exception {
        Offer offer = offered(LOAN, 1_600_00, Optional.of(12), Duration.ofDays(30), PayoutPreference.WALLET);
        fund(offer.amount());
        UUID loan = accept(offer, offer.termsSha256(), Optional.empty()).loan().id().value();
        // Each table, the column its owner keyed on, and an update a forger would want.
        Map<String, String[]> tables = Map.of(
                "lending.loan_agreement", new String[] {"loan_id", "amount_minor = amount_minor + 1"},
                "lending.loan_acceptance", new String[] {"loan_id", "terms_sha256 = sha256('forged'::bytea)"},
                "lending.loan", new String[] {"id", "party_id = gen_random_uuid()"},
                "lending.loan_disbursement", new String[] {"loan_id", "payout_method_id = gen_random_uuid()"},
                "lending.loan_event", new String[] {"loan_id", "reason = 'FORGED'"});
        for (Map.Entry<String, String[]> table : tables.entrySet()) {
            String where = " WHERE " + table.getValue()[0] + " = '" + loan + "'";
            String forged = "UPDATE " + table.getKey() + " SET " + table.getValue()[1] + where;
            try (Connection app = DatabaseRoles.application()) {
                refused(app, forged, "42501");
                refused(app, "DELETE FROM " + table.getKey() + where, "42501");
                refused(app, "TRUNCATE " + table.getKey() + " CASCADE", "42501");
            }
            try (Connection owner = DatabaseRoles.migrator()) {
                owner.setAutoCommit(false);
                refused(owner, forged, "P0001");
                owner.rollback();
                refused(owner, "DELETE FROM " + table.getKey() + where, "P0001");
                owner.rollback();
                refused(owner, "TRUNCATE " + table.getKey() + " CASCADE", "P0001");
                owner.rollback();
            }
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE lending.loan SET status = 'ACTIVE' WHERE id = '" + loan + "'", "P0001");
            owner.rollback();
            refused(owner, "INSERT INTO lending.loan_agreement SELECT gen_random_uuid(), loan_id, 2, NULL,"
                    + " gen_random_uuid(), decision_id, terms_version_id, product, currency, scale, amount_minor,"
                    + " term_months, nominal_annual_rate, repayment_day, schedule_engine_version, accrual_engine_version,"
                    + " allocation_engine_version, statement_engine_version, agreement_template_id,"
                    + " agreement_template_version, canonical_format, canonical_terms, terms_sha256, now(), now()"
                    + " FROM lending.loan_agreement WHERE loan_id = '" + loan + "'", "P0001");
            owner.rollback();
            refused(owner, "INSERT INTO lending.loan_agreement SELECT gen_random_uuid(), loan_id, 1, offer_id, NULL,"
                    + " decision_id, terms_version_id, product, currency, scale, amount_minor + 1, term_months,"
                    + " nominal_annual_rate, repayment_day, schedule_engine_version, accrual_engine_version,"
                    + " allocation_engine_version, statement_engine_version, agreement_template_id,"
                    + " agreement_template_version, canonical_format, canonical_terms, terms_sha256, now(), now()"
                    + " FROM lending.loan_agreement WHERE loan_id = '" + loan + "'", "P0001");
            owner.rollback();
        }

        Offer bare = offered(LINE, 900_00, Optional.empty(), Duration.ofDays(30), PayoutPreference.WALLET);
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO lending.loan (id, party_id, product, kind, status, currency, offer_id,"
                    + " decision_id, opened_at, correlation_id) VALUES (gen_random_uuid(), '" + bare.party()
                    + "', 'CREDIT_LINE', 'REVOLVING', 'ACTIVE', 'EUR', '" + bare.id().value() + "', '" + bare.decision()
                    + "', now(), 'raw')");
            assertThatThrownBy(owner::commit).as("a loan commits only beside its ACCEPTED offer")
                    .isInstanceOf(SQLException.class).hasMessageContaining("ACCEPTED edge");
            owner.rollback();
            execute(owner, "INSERT INTO lending.loan (id, party_id, product, kind, status, currency, offer_id,"
                    + " decision_id, opened_at, correlation_id) VALUES (gen_random_uuid(), '" + bare.party()
                    + "', 'CREDIT_LINE', 'REVOLVING', 'ACTIVE', 'EUR', '" + bare.id().value() + "', '" + bare.decision()
                    + "', now(), 'raw')");
            execute(owner, "UPDATE lending.loan_offer SET status = 'ACCEPTED' WHERE id = '" + bare.id().value() + "'");
            assertThatThrownBy(owner::commit).as("an ACCEPTED edge commits only with its agreement and evidence")
                    .isInstanceOf(SQLException.class).hasMessageContaining("agreement v1");
            owner.rollback();
            refused(owner, "INSERT INTO lending.loan (id, party_id, product, kind, status, currency, offer_id,"
                    + " decision_id, opened_at, correlation_id) VALUES (gen_random_uuid(), '" + UUID.randomUUID()
                    + "', 'CREDIT_LINE', 'REVOLVING', 'ACTIVE', 'EUR', '" + bare.id().value() + "', '" + bare.decision()
                    + "', now(), 'raw')", "P0001");
            owner.rollback();
        }
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?", bare.id().value())).isEqualTo("OFFERED");
    }

    // ------------------------------------------------------------------ plumbing

    /** An offer as the origination step births it, with what the acceptance needs to name. */
    private record Offer(LoanOfferId id, UUID party, UUID decision, Money amount, String termsSha256) {}

    private static LoanAcceptances acceptances(
            Clock clock,
            LoanStore loans,
            LoanOfferStore offers,
            LedgerAccountStore<Connection> ledgerAccounts,
            OutboxWriter<Connection> outbox,
            AuditWriter<Connection> audit) {
        return new LoanAcceptances(offers, APPLICATIONS, loans, CREDIT, STANDING_PORT, DESTINATIONS,
                new LendingCapitalHeadroom(ledgerAccounts, new JdbcPurposeBalances(), loans),
                new LoanAccounts(ledgerAccounts, IDS, clock), outbox, audit, IDS, clock);
    }

    /**
     * {@code product} applied for by a fresh party, approved by credit for {@code minor} (and a term) valid
     * {@code validity}, and offered by the origination step itself.
     */
    private static Offer offered(
            LoanProduct product, long minor, Optional<Integer> term, Duration validity, PayoutPreference preference) {
        return offered(ORIGINATION, product, minor, term, validity, preference);
    }

    /** As {@link #offered(LoanProduct, long, Optional, Duration, PayoutPreference)}, offered by {@code origination}. */
    private static Offer offered(LoanOrigination origination, LoanProduct product, long minor, Optional<Integer> term,
            Duration validity, PayoutPreference preference) {
        ensureActive(product);
        LoanApplicationId application = LoanApplicationId.next(IDS);
        UUID party = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        inOneTransaction(uow -> {
            LoanTermsStore.LoanTermsVersion version = ADMINISTRATION.pinActive(uow, product);
            Actor customer = customer(party);
            CorrelationId correlation = correlation();
            APPLICATIONS.insert(uow, new LoanApplicationStore.NewApplication(application, party, product, request,
                    version.row().id(), 10, RepaymentMethod.WALLET, preference, customer, correlation));
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application,
                    Optional.empty(), LoanApplicationStatus.SUBMITTED, "APPLICATION_SUBMITTED", customer, correlation));
            APPLICATIONS.transition(uow, application, LoanApplicationStatus.SUBMITTED,
                    LoanApplicationStatus.AWAITING_DECISION);
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application,
                    Optional.of(LoanApplicationStatus.SUBMITTED), LoanApplicationStatus.AWAITING_DECISION,
                    "CREDIT_REQUEST_OPENED", customer, correlation));
            return null;
        });
        Money amount = Money.ofMinorUnits(minor, LoanTermsFixtures.EUR);
        // The validity from the DATABASE's clock - the one every boundary is judged on - never this JVM's.
        UUID decision = CREDIT.approve(request, party, product, amount, term, databaseNow().plus(validity));
        LoanOrigination.Outcome outcome = inOneTransaction(uow -> origination.originate(uow, application, SYSTEM));
        assertThat(outcome.kind()).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        LoanOfferId offer = LoanOfferId.of(UUID.fromString(scalar(
                "SELECT id::text FROM lending.loan_offer WHERE application_id = ?", application.value())));
        String hash = scalar("SELECT encode(terms_sha256, 'hex') FROM lending.loan_offer_terms WHERE offer_id = ?",
                offer.value());
        return new Offer(offer, party, decision, amount, hash);
    }

    /** A fresh version of {@code terms}, proposed by one person and activated by another. */
    private static void activate(LoanTerms terms) {
        LoanTermsAdministration.Proposed proposed = inOneTransaction(uow -> ADMINISTRATION.propose(uow, terms,
                "a case's terms", employee(), correlation()));
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "reviewed", correlation()));
    }

    /**
     * {@code delegate} writing every offer's canonical terms as the format-1 build did (`P11-TSK-011` to before
     * `P11-TSK-031`): the same content with the format token 1 - byte for byte format 1 wherever it spells the content
     * ({@code CanonicalOfferTermsTest#format1IsKeptByteForByte}) - its hash and the document's recomputed. The offer's
     * event keeps the format-2 hash; this suite reads the rows.
     */
    private static LoanOfferStore writingFormat1(LoanOfferStore delegate) {
        return (LoanOfferStore) Proxy.newProxyInstance(LoanOfferStore.class.getClassLoader(),
                new Class<?>[] {LoanOfferStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("insertTerms")) {
                        LoanOffer.Terms written = (LoanOffer.Terms) args[3];
                        String two = written.canonicalTerms();
                        assertThat(two).startsWith("{\"format\":\"2\",").doesNotContain("+");
                        String one = "{\"format\":\"1\"" + two.substring("{\"format\":\"2\"".length());
                        LoanOffer.Terms unhashed = withCanonical(written, one, new byte[32]);
                        args[3] = withCanonical(written, one, CanonicalOfferTerms.sha256(
                                OfferDocument.render((LoanOfferId) args[1], (LoanProduct) args[2], unhashed)));
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static LoanOffer.Terms withCanonical(LoanOffer.Terms terms, String canonical, byte[] documentSha256) {
        return new LoanOffer.Terms(terms.amount(), terms.termMonths(), terms.nominalAnnualRate(), terms.repaymentDay(),
                terms.illustration(), terms.minimumPayment(), terms.scheduleEngine(), terms.accrualEngine(),
                terms.allocationEngine(), terms.statementEngine(), terms.template(), canonical,
                CanonicalOfferTerms.sha256(canonical), documentSha256);
    }

    private static void ensureActive(LoanProduct product) {
        Boolean active = inOneTransaction(uow -> TERMS_STORE.active(uow, product).isPresent());
        if (!active) {
            LoanTermsAdministration.Proposed proposed = inOneTransaction(uow -> ADMINISTRATION.propose(uow,
                    LoanTermsFixtures.of(product, product == LOAN ? "0.0899" : "0.1599").build(), "the suite's terms",
                    employee(), correlation()));
            inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "reviewed", correlation()));
        }
    }

    private static LoanAcceptances.Accepted accept(Offer offer, String echoed, Optional<UUID> payoutMethod) {
        return inOneTransaction(uow -> ACCEPTANCES.accept(uow, offer.id(), offer.party(), acceptance(echoed, payoutMethod),
                customer(offer.party()), correlation()));
    }

    private static LoanAcceptances.Acceptance acceptance(String echoed, Optional<UUID> payoutMethod) {
        return new LoanAcceptances.Acceptance(echoed, payoutMethod, new AcceptanceEvidence(UUID.randomUUID(),
                UUID.randomUUID(), "MULTI_FACTOR", Optional.of("203.0.113.7"), Optional.of("finapp-suite/1.0")));
    }

    /** Capital headroom for {@code principal}: what is already free plus a planted contribution covering the rest. */
    private static void fund(Money principal) {
        Money missing = principal.minus(headroomNow().headroom());
        if (missing.isPositive()) {
            plantCapital(missing);
        }
    }

    /** The fixture's capital: DR CASH_AT_BANK / CR LENDING_CAPITAL, posted directly - never a path the platform has. */
    private static void plantCapital(Money amount) {
        PostingService postings = new PostingService(
                new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(),
                IDS, CLOCK, PostingObserver.NONE);
        try (var actor = com.finapp.platform.security.SecurityContext.enterSystem();
                var flow = com.finapp.platform.correlation.CorrelationContext.enter(
                        com.finapp.sharedkernel.correlation.Correlation.startingWith(correlation())
                                .causing(com.finapp.sharedkernel.correlation.CausationId.of("p11t12-capital")))) {
            inOneTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                postings.post(uow, new PostingCommand("p11t12-capital:" + UUID.randomUUID(), today, today,
                        "the suite's planted capital", List.of(
                                new JournalLine(LEDGER_ACCOUNTS.findOperational(uow, AccountPurpose.CASH_AT_BANK,
                                        LoanTermsFixtures.EUR).orElseThrow().id(), Direction.DEBIT, amount),
                                new JournalLine(HEADROOM.capitalAccount(uow, LoanTermsFixtures.EUR).id(),
                                        Direction.CREDIT, amount))));
                return null;
            });
        }
    }

    private static Instant databaseNow() {
        return Instant.parse(scalar("SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"));
    }

    private static LendingCapitalHeadroom.Headroom headroomNow() {
        return inOneTransaction(uow -> HEADROOM.atRest(uow, LoanTermsFixtures.EUR));
    }

    /** Nothing of {@code offer}'s acceptance exists, and no loan account was opened since {@code loanAccounts}. */
    private static void assertNothingWritten(Offer offer, long loanAccounts) {
        assertThat(count("SELECT count(*) FROM lending.loan WHERE offer_id = ?", offer.id().value())).isZero();
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE decision_id = ?", offer.decision())).isZero();
        assertThat(count("SELECT count(*) FROM lending.loan_acceptance WHERE offer_id = ?", offer.id().value())).isZero();
        assertThat(count("SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'LOAN'")).isEqualTo(loanAccounts);
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?", offer.id().value())).isEqualTo("OFFERED");
        assertThat(count("SELECT count(*) FROM lending.loan_offer_event WHERE offer_id = ? AND to_status = 'ACCEPTED'",
                offer.id().value())).isZero();
    }

    private static UUID loanOf(Offer offer) {
        return UUID.fromString(scalar("SELECT id::text FROM lending.loan WHERE offer_id = ?", offer.id().value()));
    }

    private static UUID[] ids(List<Offer> offers) {
        return offers.stream().map(offer -> offer.id().value()).toArray(UUID[]::new);
    }

    /** Whether another transaction can lock {@code offer} at once - that is, nobody holds it. */
    private static boolean offerLockableNoWait(Offer offer) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(
                    "SELECT 1 FROM lending.loan_offer WHERE id = ? FOR UPDATE NOWAIT")) {
                lock.setObject(1, offer.id().value());
                lock.executeQuery().close();
                return true;
            } catch (SQLException held) {
                if ("55P03".equals(held.getSQLState())) {
                    return false;
                }
                throw held;
            } finally {
                probe.rollback();
            }
        }
    }

    /**
     * {@code delegate} behind a proxy counting the acceptance's writes - inserts, appends, edges, account openings - and
     * throwing right after the {@code crashAt}-th, as a crash would.
     */
    @SuppressWarnings("unchecked")
    private static <T> T crashing(Class<?> type, Object delegate, AtomicInteger writes, AtomicInteger crashAt) {
        Set<String> counted = Set.of("insert", "appendEvent", "insertAgreement", "insertAcceptance", "insertDisbursement",
                "accept", "createOrConverge", "write", "append");
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            if (counted.contains(method.getName())) {
                int done = writes.incrementAndGet();
                if (done == crashAt.get()) {
                    throw new IllegalStateException("crashed after write " + done);
                }
            }
            return result;
        });
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static Actor customer(UUID party) {
        return new Actor(party.toString(), ActorType.CUSTOMER);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long count(String sql, Object... parameters) {
        return Long.parseLong(scalar(sql, parameters));
    }

    private static String scalar(String sql, Object... parameters) {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement statement = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i] instanceof UUID[] array) {
                    statement.setArray(i + 1, migrator.createArrayOf("uuid", array));
                } else {
                    statement.setObject(i + 1, parameters[i]);
                }
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    /** Waits until the database itself says {@code booleanQuery} - never the JVM's clock. */
    private static void awaitDatabase(String booleanQuery, Object... parameters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if ("t".equals(scalar(booleanQuery, parameters))) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the database never said: " + booleanQuery);
    }

    /** Waits until a session is blocked by {@code blocker}, and answers the statement it waits in. */
    private static String awaitAStatementBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid))")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no acceptance ever waited behind the held lock");
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState}. */
    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> execute(connection, sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    /** Racer {@code i} of {@code racers} runs {@code work.apply(i)}, all released at once. */
    private static <T> List<T> race(int racers, IndexedWork<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int index = i;
                pending.add(pool.submit((Callable<T>) () -> {
                    start.await();
                    return work.apply(index);
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedWork<T> {
        T apply(int index) throws Exception;
    }

    /**
     * Credit as each case says it stands: approvals the origination step reads, and the acceptance's two calls - the
     * exposure lock as a transaction-scoped advisory lock on the party (credit's profile row's stand-in), and the
     * consumption judging the lapse on the database clock, as credit's definer function does.
     */
    private static final class Credit implements LoanCreditDecisions {

        /** An advisory namespace this suite alone uses - the profile row's stand-in. */
        private static final int PROFILE_STAND_IN = 1_112;

        private final Map<UUID, CreditOutcome.Approved> byRequest = new ConcurrentHashMap<>();
        private final Map<UUID, CreditOutcome.Approved> byDecision = new ConcurrentHashMap<>();
        private final Map<UUID, Consumption.Refusal> refusals = new ConcurrentHashMap<>();
        private final AtomicInteger consumptions = new AtomicInteger();

        UUID approve(UUID request, UUID party, LoanProduct product, Money amount, Optional<Integer> term,
                Instant validUntil) {
            UUID decision = UUID.randomUUID();
            CreditOutcome.Approved approved = new CreditOutcome.Approved(decision, party, product, amount, term, validUntil);
            byRequest.put(request, approved);
            byDecision.put(decision, approved);
            return decision;
        }

        void refuse(UUID decision, Consumption.Refusal refusal) {
            refusals.put(decision, refusal);
        }

        static void lockProfile(Connection unitOfWork, UUID party) {
            try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
                lock.setInt(1, PROFILE_STAND_IN);
                lock.setString(2, party.toString());
                lock.executeQuery().close();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor actor, CorrelationId correlation) {
            throw new UnsupportedOperationException("the acceptance opens no request");
        }

        @Override
        public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                CorrelationId correlation) {
            throw new UnsupportedOperationException("the acceptance cancels no request");
        }

        @Override
        public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
            return List.of();
        }

        @Override
        public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
            CreditOutcome.Approved approved = byRequest.get(decisionRequest);
            return approved == null ? new CreditOutcome.Pending() : approved;
        }

        @Override
        public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
            return Optional.empty();
        }

        @Override
        public boolean lockExposure(Connection unitOfWork, UUID party) {
            lockProfile(unitOfWork, party);
            return true;
        }

        @Override
        public Consumption consume(Connection unitOfWork, UUID decision, LoanAgreementId agreement) {
            consumptions.incrementAndGet();
            Consumption.Refusal forced = refusals.get(decision);
            if (forced != null) {
                return new Consumption.Refused(forced);
            }
            CreditOutcome.Approved approved = byDecision.get(decision);
            if (approved == null) {
                return new Consumption.Refused(Consumption.Refusal.NOT_FOUND);
            }
            try (PreparedStatement clock = unitOfWork.prepareStatement("SELECT ? > clock_timestamp()")) {
                clock.setTimestamp(1, java.sql.Timestamp.from(approved.validUntil()));
                try (ResultSet row = clock.executeQuery()) {
                    row.next();
                    if (!row.getBoolean(1)) {
                        return new Consumption.Refused(Consumption.Refusal.LAPSED);
                    }
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return new Consumption.Consumed(decision, approved.party(), approved.amount());
        }
    }
}
