package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The acceptance's rows against a real database, the other modules' ports scripted (`P12-TSK-013`; {@code bnpl V006};
 * {@code INV-BNPL-02}, {@code -03}, {@code -04}, {@code -05}, {@code INV-HIST-04}): {@link BnplAcceptances} over bnpl's
 * own JDBC stores commits the purchase, its financing, the obligation, agreement version 1 and its evidence and schedule
 * version 1 - and {@code bnpl V006}'s ranks hold for every writer: the born-once uniques, the deferred INV-BNPL-02
 * trigger alone (a raw financing without its obligation), the deferred conservation of a schedule, a principal fact
 * before the floor, the agreement immutable for every role, the offer's ACCEPTED edge only beside its FINANCED purchase,
 * and the act waiting on the offer's lock (B2) when another holds it. Credit, checkout, merchant, capital, the wallet and
 * the ledger are scripted here; their real locks, the counted races and the postings are {@code app}'s
 * {@code BnplPurchaseDatabaseTest}'s.
 *
 * <p>Written by `P12-TSK-013` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction for
 * the pair).
 */
@Tag("database")
@DisplayName("the acceptance's rows and bnpl V006's ranks, the ports scripted (P12-TSK-013)")
class BnplAcceptanceDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    private static final long GROSS = 600_00L;
    private static final JdbcBnplApplicationStore APPLICATIONS = new JdbcBnplApplicationStore();
    private static final JdbcBnplOfferStore OFFERS = new JdbcBnplOfferStore();
    private static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    private static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();
    private static final BnplTermsAdministration TERMS = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
            new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);

    @BeforeAll
    static void termsInForce() {
        if (inOneTransaction(uow -> TERMS_STORE.active(uow, BnplProduct.BNPL_PAY_IN_4)).isEmpty()) {
            BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> TERMS.propose(uow,
                    BnplTermsFixtures.payIn4().build(), "the acceptance store's terms", employee(), correlation()));
            inOneTransaction(uow -> TERMS.approve(uow, proposed.id(), employee(), "in force", correlation()));
        }
    }

    @Test
    @DisplayName("an acceptance commits its rows in one transaction: the purchase FINANCED with its order, the financing"
            + " conserving the gross, the obligation ACTIVE on the purchase's business date, agreement version 1 copying"
            + " the offer, its evidence, schedule version 1 conserving, instalment 1 billed and repaid, the offer ACCEPTED")
    void anAcceptanceCommitsItsRows() throws SQLException {
        Offered offered = offered();
        BnplAcceptances.Accepted accepted = accept(new Scripted(), PURCHASES, offered);
        String purchase = accepted.purchase().id().value().toString();
        String obligation = accepted.obligation().id().value().toString();
        assertThat(scalar("SELECT status || '|' || (order_id IS NOT NULL) FROM bnpl.purchase WHERE id = '" + purchase
                + "'")).isEqualTo("FINANCED|true");
        assertThat(scalar("SELECT gross_minor || '|' || fee_minor || '|' || net_minor FROM bnpl.merchant_financing"
                + " WHERE purchase_id = '" + purchase + "'")).isEqualTo("60000|2400|57600");
        assertThat(scalar("SELECT (o.purchase_date = p.business_date)::text FROM bnpl.bnpl_obligation o JOIN"
                + " bnpl.purchase p ON p.id = o.purchase_id WHERE o.id = '" + obligation + "'")).isEqualTo("true");
        assertThat(scalar("SELECT (a.canonical_terms = t.canonical_terms AND a.terms_sha256 = t.terms_sha256)::text"
                + " FROM bnpl.bnpl_agreement a JOIN bnpl.bnpl_offer_terms t ON t.offer_id = a.offer_id"
                + " WHERE a.obligation_id = '" + obligation + "'")).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_acceptance WHERE agreement_id = '"
                + accepted.agreement().id().value() + "' AND assurance = 'MULTI_FACTOR'")).isEqualTo(1);
        assertThat(scalar("SELECT sum(i.principal_minor)::text FROM bnpl.bnpl_instalment i WHERE i.obligation_id = '"
                + obligation + "'")).isEqualTo(Long.toString(GROSS));
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + obligation + "'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT kind || '|' || request_key FROM bnpl.bnpl_repayment WHERE obligation_id = '"
                + obligation + "'")).isEqualTo("AT_PURCHASE|" + purchase);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = '" + offered.offer().value() + "'"))
                .isEqualTo("ACCEPTED");
        assertThat(scalar("SELECT bnpl.principal_value_floor('" + obligation + "')::text"))
                .isEqualTo(accepted.purchase().businessDate().toString());
    }

    @Test
    @DisplayName("the born-once uniques: a second purchase for the session or the offer, a second financing entry, a"
            + " second billing of an instalment, a second repayment under one key - each 23505")
    void theBornOnceUniques() throws SQLException {
        BnplAcceptances.Accepted accepted = accept(new Scripted(), PURCHASES, offered());
        // The uniques alone: the bootstrap superuser with the row triggers suspended for its session, so the arbiter -
        // not a trigger that fires before it - is what refuses.
        try (Connection migrator = DatabaseRoles.bootstrap()) {
            try (Statement replica = migrator.createStatement()) {
                replica.execute("SET session_replication_role = replica");
            }
            migrator.setAutoCommit(false);
            BnplPurchase purchase = accepted.purchase();
            refused(migrator, "INSERT INTO bnpl.purchase SELECT gen_random_uuid(), offer_id, checkout_session_id,"
                    + " party_id, merchant_id, product, plan, currency, 'AUTHORISED', servicing_zone, business_date,"
                    + " authorised_at, NULL, NULL, correlation_id FROM bnpl.purchase WHERE id = '" + purchase.id().value()
                    + "'", "23505");
            refused(migrator, "INSERT INTO bnpl.bnpl_billing SELECT gen_random_uuid(), instalment_id, obligation_id,"
                    + " value_date, currency, scale, principal_minor, interest_minor, gen_random_uuid(), billed_at"
                    + " FROM bnpl.bnpl_billing WHERE obligation_id = '" + accepted.obligation().id().value() + "'",
                    "23505");
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment SELECT gen_random_uuid(), obligation_id, kind,"
                    + " request_key, currency, scale, amount_minor, agreement_id, agreement_version, allocation_engine,"
                    + " gen_random_uuid(), value_date, received_at FROM bnpl.bnpl_repayment WHERE obligation_id = '"
                    + accepted.obligation().id().value() + "'", "23505");
        }
    }

    @Test
    @DisplayName("a financing row without its obligation is refused at commit - the deferred INV-BNPL-02 trigger, proven"
            + " alone by a raw writer: a purchase and its financing, no obligation")
    void aFinancingRowWithoutItsObligationIsRefused() throws SQLException {
        Offered offered = offered();
        BnplPurchaseId purchase = BnplPurchaseId.next(IDS);
        assertThatThrownBy(() -> inOneTransaction(uow -> {
            PURCHASES.insertPurchase(uow, new BnplPurchaseStore.NewPurchase(purchase, offered.offer(),
                    offered.session(), offered.party(), offered.merchant(), BnplProduct.BNPL_PAY_IN_4,
                    BnplPlan.PAY_IN_4, EUR, correlation()));
            Money gross = Money.ofMinorUnits(GROSS, EUR);
            Money fee = Money.ofMinorUnits(24_00, EUR);
            PURCHASES.insertFinancing(uow, new MerchantFinancing(purchase, offered.merchant(),
                    new MerchantFeeSplit(gross, fee, gross.minus(fee), UUID.randomUUID()), JournalEntryId.of(IDS.next())));
            return null;
        })).as("a purchase and its financing, no obligation").hasRootCauseInstanceOf(SQLException.class)
                .rootCause().hasMessageContaining("INV-BNPL-02");
        assertThat(count("SELECT count(*) FROM bnpl.merchant_financing WHERE purchase_id = '" + purchase.value() + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a schedule that does not conserve is refused at commit - an instalment a minor unit high")
    void aScheduleThatDoesNotConserveIsRefused() throws SQLException {
        Offered offered = offered();
        BnplPurchaseStore tampering = new Delegating(PURCHASES) {
            @Override
            public void insertSchedule(Connection unitOfWork, StoredSchedule schedule) {
                List<StoredInstalment> instalments = new ArrayList<>(schedule.instalments());
                StoredInstalment last = instalments.remove(instalments.size() - 1);
                Money oneMore = last.principal().plus(Money.ofPersisted(1, EUR, last.principal().scale()));
                instalments.add(new StoredInstalment(last.id(), last.sequence(), last.dueDate(), oneMore,
                        last.interest(), oneMore.plus(last.interest())));
                super.insertSchedule(unitOfWork, new StoredSchedule(schedule.id(), schedule.obligation(),
                        schedule.version(), schedule.engine(), schedule.principal(), schedule.start(), instalments));
            }
        };
        assertThatThrownBy(() -> accept(new Scripted(), tampering, offered)).rootCause()
                .hasMessageContaining("INV-BNPL-05");
        assertThat(count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = '" + offered.offer().value() + "'"))
                .as("nothing committed").isZero();
    }

    @Test
    @DisplayName("a principal fact before the floor is refused: a billing value-dated the day before the purchase")
    void aPrincipalFactBeforeTheFloorIsRefused() throws SQLException {
        Offered offered = offered();
        BnplPurchaseStore early = new Delegating(PURCHASES) {
            @Override
            public void insertBilling(Connection unitOfWork, BnplBilling billing) {
                super.insertBilling(unitOfWork, new BnplBilling(billing.id(), billing.instalment(), billing.sequence(),
                        billing.obligation(), billing.scheduleId(), billing.scheduleVersion(), billing.scheduleEngine(),
                        billing.agreement(), billing.agreementVersion(), billing.accrualEngine(),
                        billing.periodStart().minusDays(1), billing.dueDate().minusDays(1), billing.principal(),
                        billing.interest(), billing.entry(), billing.correlation()));
            }
        };
        // bnpl V008: a billing is dated its due date - the purchase's own, the floor - never the day before.
        assertThatThrownBy(() -> accept(new Scripted(), early, offered)).rootCause().hasMessageContaining("due date");
        assertThat(count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = '" + offered.offer().value() + "'"))
                .isZero();
    }

    @Test
    @DisplayName("the agreement is never updated or deleted by any role: the application role holds no grant (42501), the"
            + " migrator meets the trigger (P0001)")
    void theAgreementIsNeverUpdatedByAnyRole() throws SQLException {
        BnplAcceptances.Accepted accepted = accept(new Scripted(), PURCHASES, offered());
        String where = " WHERE agreement_id = '" + accepted.agreement().id().value() + "'";
        try (Connection application = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            application.setAutoCommit(false);
            migrator.setAutoCommit(false);
            refused(application, "UPDATE bnpl.bnpl_agreement SET canonical_terms = canonical_terms" + where, "42501");
            application.rollback();
            refused(application, "DELETE FROM bnpl.bnpl_agreement" + where, "42501");
            application.rollback();
            refused(migrator, "UPDATE bnpl.bnpl_agreement SET plan = plan" + where, "P0001");
            migrator.rollback();
            refused(migrator, "DELETE FROM bnpl.bnpl_agreement" + where, "P0001");
            migrator.rollback();
            refused(migrator, "UPDATE bnpl.merchant_financing SET fee_minor = fee_minor WHERE purchase_id = '"
                    + accepted.purchase().id().value() + "'", "P0001");
        }
    }

    @Test
    @DisplayName("the offer's ACCEPTED edge: refused for every writer without its FINANCED purchase; an offer past its"
            + " expiry is not acceptable on the database clock")
    void theOffersEdge() throws SQLException {
        Offered offered = offered();
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "UPDATE bnpl.bnpl_offer SET status = 'ACCEPTED' WHERE id = '" + offered.offer().value()
                    + "'", "P0001");
        }
        assertThatThrownBy(() -> inOneTransaction(uow -> {
            OFFERS.lockOwnedBy(uow, offered.offer(), offered.party());
            return OFFERS.accept(uow, offered.offer());
        })).as("the domain's conditional matches, the trigger refuses: no FINANCED purchase beside it")
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("P0001");
        assertThat(scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = '" + offered.offer().value() + "'"))
                .isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("the act waits on the offer's lock (B2): another transaction holding the offer FOR UPDATE, the acceptance"
            + " waits on bnpl_offer and then accepts once")
    void theActWaitsOnTheOffer() throws Exception {
        Offered offered = offered();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM bnpl.bnpl_offer WHERE id = '" + offered.offer().value() + "' FOR UPDATE");
            }
            Future<BnplAcceptances.Accepted> accepting = pool.submit(() -> accept(new Scripted(), PURCHASES, offered));
            assertThat(awaitBlockedBy(pid(holder))).as("the acceptance waits on B2").contains("bnpl_offer");
            holder.rollback();
            assertThat(accepting.get(30, TimeUnit.SECONDS).purchase().status()).isEqualTo(BnplPurchaseStatus.FINANCED);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ plumbing

    record Offered(BnplOfferId offer, UUID party, UUID session, UUID merchant) {}

    /** An application offered for {@code GROSS} on pay-in-4's ACTIVE terms - the origination step's edge. */
    static Offered offered() {
        return offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
    }

    /** An application offered for {@code GROSS} on {@code product}'s ACTIVE terms - the origination step's edge (P12-TSK-015). */
    static Offered offered(BnplProduct product, BnplPlan plan) {
        UUID party = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        BnplApplicationStore.NewApplication row = new BnplApplicationStore.NewApplication(BnplApplicationId.next(IDS),
                party, product, plan, session, merchant, UUID.randomUUID(),
                inOneTransaction(uow -> TERMS_STORE.active(uow, product)).orElseThrow(),
                customer(party), correlation());
        BnplOfferId offer = BnplOfferId.next(IDS);
        inOneTransaction(uow -> {
            APPLICATIONS.insert(uow, row);
            APPLICATIONS.transition(uow, row.id(), BnplApplicationStatus.SUBMITTED, BnplApplicationStatus.AWAITING_DECISION);
            BnplTermsVersion version = TERMS.pinActive(uow, product);
            BnplOfferStore.Offered born = OFFERS.insert(uow, new BnplOfferStore.NewOffer(offer, row.id(), party,
                    product, plan, UUID.randomUUID(), version.id(), session,
                    Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofHours(1)), correlation()));
            BnplIllustration illustration = BnplIllustration.of(version.terms(), plan,
                    Money.ofMinorUnits(GROSS, EUR), LocalDate.ofInstant(born.offeredAt(), java.time.ZoneOffset.UTC));
            String canonical = BnplOfferTerms.render(new BnplOfferTerms.Content(offer, row.id(), UUID.randomUUID(),
                    version.id(), version.row().version(), version.terms(), plan, illustration));
            OFFERS.insertTerms(uow, offer, BnplOfferStore.StoredTerms.of(plan,
                    version.terms().nominalAnnualRate(), illustration, canonical, BnplOfferTerms.sha256("document")));
            APPLICATIONS.close(uow, row.id(), BnplApplicationStatus.OFFERED, Optional.empty());
            return null;
        });
        return new Offered(offer, party, session, merchant);
    }

    private static BnplAcceptances.Accepted accept(Scripted ports, BnplPurchaseStore store, Offered offered) {
        BnplAcceptances acceptances = new BnplAcceptances(OFFERS, TERMS_STORE, store, ports, (uow, party) -> true, ports, ports,
                ports, ports, ports, new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
        ports.session = offered.session();
        ports.merchant = offered.merchant();
        String hash = inOneTransaction(uow -> OFFERS.terms(uow, offered.offer()).orElseThrow().termsSha256Hex());
        return inOneTransaction(uow -> acceptances.accept(uow, offered.offer(), offered.party(),
                new BnplAcceptances.Acceptance(hash, new BnplAcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(),
                        "MULTI_FACTOR", Optional.of("192.0.2.7"), Optional.of("suite"))),
                customer(offered.party()), Correlation.startingWith(correlation())));
    }

    /**
     * Credit, checkout, merchant, capital, the wallet and the ledger, scripted: the profile held, the decision consumed
     * for exactly the gross, the session payable and completed, the merchant enabled at 4%, capital to spare, a wallet
     * with funds, postings that mint entry ids.
     */
    static final class Scripted implements BnplCreditDecisions, BnplCheckoutSessions, BnplMerchants,
            BnplCapital, BnplWallets, BnplBook {

        UUID session;
        UUID merchant;
        final LedgerAccountId payable = LedgerAccountId.of(IDS.next());
        final LedgerAccountId wallet = LedgerAccountId.of(IDS.next());

        @Override
        public UUID open(Connection unitOfWork, UUID party, CreditRequest request, Actor actor, CorrelationId correlation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                CorrelationId correlation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
            return List.of();
        }

        @Override
        public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
            return Optional.empty();
        }

        @Override
        public boolean lockExposure(Connection unitOfWork, UUID party) {
            return true;
        }

        @Override
        public BnplCreditDecisions.Consumption consume(Connection unitOfWork, UUID decision, BnplAgreementId agreement) {
            UUID party = UUID.fromString(scalarOn(unitOfWork, "SELECT party_id::text FROM bnpl.bnpl_offer WHERE"
                    + " decision_id = '" + decision + "'"));
            return new BnplCreditDecisions.Consumption.Consumed(decision, party, Money.ofMinorUnits(GROSS, EUR));
        }

        @Override
        public Optional<Session> read(Connection unitOfWork, com.finapp.sharedkernel.security.Sensitive<String> token) {
            return Optional.empty();
        }

        @Override
        public Optional<Session> byRef(Connection unitOfWork, UUID sessionRef) {
            return Optional.empty();
        }

        @Override
        public Lock lockForPurchase(Connection unitOfWork, UUID sessionRef) {
            return new Payable(new Session(sessionRef, merchant, Money.ofMinorUnits(GROSS, EUR),
                    Instant.now().plus(Duration.ofHours(1)), true, false));
        }

        @Override
        public Completion completeByFinancing(Connection unitOfWork, UUID sessionRef, BnplPurchaseId purchase,
                JournalEntryId financingEntry, Correlation correlation) {
            return new Completed(IDS.next());
        }

        @Override
        public Standing standing(Connection unitOfWork, UUID merchantRef, BnplProduct product) {
            return Standing.ENABLED;
        }

        @Override
        public boolean coversFee(Connection unitOfWork, UUID merchantRef, BnplProduct product, Money gross) {
            return true;
        }

        @Override
        public Optional<LedgerAccountId> payableAccount(Connection unitOfWork, UUID merchantRef, CurrencyCode currency) {
            return Optional.of(payable);
        }

        @Override
        public MerchantFeeSplit pinFee(Connection unitOfWork, UUID merchantRef, BnplProduct product,
                BnplPurchaseId purchaseId, Money gross) {
            Money fee = Money.ofPersisted(gross.minorUnits() * 4 / 100, EUR, gross.scale());
            MerchantFeeSplit split = new MerchantFeeSplit(gross, fee, gross.minus(fee), UUID.randomUUID());
            pins.put(purchaseId.value(), split);
            return split;
        }

        /** Each purchase's pinned split - the refund's fee arithmetic reads it (`P12-TSK-021`). */
        final java.util.Map<UUID, MerchantFeeSplit> pins = new java.util.concurrent.ConcurrentHashMap<>();

        /** The reference arithmetic over the pin - merchant's {@code FeeCalculation.returnedFee} by proof (`P12-TSK-009`). */
        @Override
        public Money returnedFee(Connection unitOfWork, BnplPurchaseId purchaseId, Money refundedBefore, Money refunded) {
            MerchantFeeSplit pinned = pins.get(purchaseId.value());
            if (pinned == null) {
                throw new NotPinned();
            }
            return com.finapp.bnpl.testing.ReferenceMerchantFeeReturn.halfEven(pinned.gross(), pinned.fee())
                    .returnedFee(refundedBefore, refunded);
        }

        @Override
        public BnplHeadroom underLock(Connection unitOfWork, CurrencyCode currency) {
            return new BnplHeadroom(currency, Money.ofMinorUnits(1_000_000_00L, currency));
        }

        @Override
        public Optional<LedgerAccountId> existingWallet(Connection unitOfWork, UUID party, CurrencyCode currency) {
            return Optional.of(wallet);
        }

        @Override
        public BnplLedgerAccounts openAccounts(Connection unitOfWork, BnplObligationId obligation, CurrencyCode currency) {
            List<LedgerAccount> six = new ArrayList<>();
            for (AccountPurpose purpose : BnplAccounts.purposes()) {
                six.add(LedgerAccount.owned(IDS, CLOCK, BnplAccounts.declaredTypeOf(purpose), purpose, currency,
                        obligation.value()));
            }
            return new BnplLedgerAccounts(six.get(0), six.get(1), six.get(2), six.get(3), six.get(4), six.get(5));
        }

        @Override
        public List<LedgerAccount> accountsOf(Connection unitOfWork, BnplObligationId obligation) {
            return List.of();
        }

        @Override
        public LedgerAccountId operational(Connection unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
            return LedgerAccountId.of(IDS.next());
        }

        @Override
        public Optional<LedgerAccountStatus> lockForUpdate(Connection unitOfWork, LedgerAccountId account) {
            return Optional.of(LedgerAccountStatus.ACTIVE);
        }

        @Override
        public Money availableUnderLock(Connection unitOfWork, LedgerAccountId account) {
            return Money.ofMinorUnits(1_000_00, EUR);
        }

        @Override
        public void lockBalancesInOrder(Connection unitOfWork, java.util.Collection<LedgerAccountId> accounts) {}

        @Override
        public JournalEntryId post(Connection unitOfWork, PostingCommand command) {
            return JournalEntryId.of(IDS.next());
        }

        @Override
        public Money balanceOf(Connection unitOfWork, LedgerAccount account) {
            return Money.zero(EUR);
        }

        @Override
        public List<com.finapp.ledger.PurposeBalances.DatedLine> datedLinesOn(Connection unitOfWork,
                java.util.Set<LedgerAccountId> accounts, java.time.LocalDate valueDateThrough) {
            return List.of();
        }

        @Override
        public com.finapp.ledger.JournalEntryId reverse(Connection unitOfWork,
                com.finapp.ledger.ReversalCommand command) {
            throw new UnsupportedOperationException("the acceptance reverses nothing (P12-TSK-024's corrections)");
        }

        @Override
        public List<com.finapp.ledger.JournalLine> entryLines(Connection unitOfWork,
                com.finapp.ledger.JournalEntryId entry) {
            return List.of();
        }
    }

    /** The JDBC store, one write tampered with by the case. */
    private static class Delegating implements BnplPurchaseStore {

        private final BnplPurchaseStore delegate;

        Delegating(BnplPurchaseStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Authorised insertPurchase(Connection unitOfWork, NewPurchase purchase) {
            return delegate.insertPurchase(unitOfWork, purchase);
        }

        @Override
        public Optional<Instant> finance(Connection unitOfWork, BnplPurchaseId purchase, UUID order) {
            return delegate.finance(unitOfWork, purchase, order);
        }

        @Override
        public void appendPurchaseEvent(Connection unitOfWork, HistoryRow row) {
            delegate.appendPurchaseEvent(unitOfWork, row);
        }

        @Override
        public Optional<BnplPurchaseStatus> purchaseStatus(Connection unitOfWork, BnplPurchaseId purchase) {
            return delegate.purchaseStatus(unitOfWork, purchase);
        }

        @Override
        public void insertFinancing(Connection unitOfWork, MerchantFinancing financing) {
            delegate.insertFinancing(unitOfWork, financing);
        }

        @Override
        public Instant insertObligation(Connection unitOfWork, NewObligation obligation) {
            return delegate.insertObligation(unitOfWork, obligation);
        }

        @Override
        public void appendObligationEvent(Connection unitOfWork, ObligationHistoryRow row) {
            delegate.appendObligationEvent(unitOfWork, row);
        }

        @Override
        public Instant insertAgreement(Connection unitOfWork, BnplAgreement agreement) {
            return delegate.insertAgreement(unitOfWork, agreement);
        }

        @Override
        public Instant insertAcceptance(Connection unitOfWork, UUID id, BnplAgreement agreement,
                BnplAcceptanceEvidence evidence, CorrelationId correlation) {
            return delegate.insertAcceptance(unitOfWork, id, agreement, evidence, correlation);
        }

        @Override
        public void insertSchedule(Connection unitOfWork, StoredSchedule schedule) {
            delegate.insertSchedule(unitOfWork, schedule);
        }

        @Override
        public void insertBilling(Connection unitOfWork, BnplBilling billing) {
            delegate.insertBilling(unitOfWork, billing);
        }

        @Override
        public void insertRepayment(Connection unitOfWork, Repayment repayment) {
            delegate.insertRepayment(unitOfWork, repayment);
        }

        @Override
        public List<Owned> ownedBy(Connection unitOfWork, UUID party, int limit) {
            return delegate.ownedBy(unitOfWork, party, limit);
        }

        @Override
        public Optional<Owned> agreementOwnedBy(Connection unitOfWork, BnplAgreementId id, UUID party) {
            return delegate.agreementOwnedBy(unitOfWork, id, party);
        }

        @Override
        public Optional<StoredSchedule> schedule(Connection unitOfWork, BnplObligationId obligation, int version) {
            return delegate.schedule(unitOfWork, obligation, version);
        }

        @Override
        public List<BilledItem> billedItems(Connection unitOfWork, BnplObligationId obligation) {
            return delegate.billedItems(unitOfWork, obligation);
        }

        @Override
        public BnplDisputeHold disputeHold(Connection unitOfWork, BnplObligationId obligation) {
            return delegate.disputeHold(unitOfWork, obligation);
        }

        @Override
        public Optional<MerchantBnplPurchaseView> atMerchant(Connection unitOfWork, BnplPurchaseId id, UUID merchant) {
            return delegate.atMerchant(unitOfWork, id, merchant);
        }
    }

    static Actor customer(UUID party) {
        return new Actor(party.toString(), ActorType.CUSTOMER);
    }

    static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            } catch (SQLException commitRefused) {
                connection.rollback();
                throw new IllegalStateException(commitRefused);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String scalarOn(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    static String scalar(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row =
                statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    /** The statement of the session blocked behind {@code blocker} on a lock, awaited 30 s. */
    static String awaitBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing ever waited behind session " + blocker);
    }

    static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the next statement's own assertion says what matters
        }
    }
}
