package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.BookRail;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentClearing;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.SettlementExpectations;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.JdbcExpectationRegister;
import com.finapp.reconciliation.JdbcRuleSets;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.JdbcSettlementFileStore;
import com.finapp.settlement.SettlementFileCipher;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import com.finapp.app.payments.ChargebackAccountingFixture;
import com.finapp.app.payments.JdbcPaymentParticipants;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every settling card completion opens its expectation, proven against the real schema and
 * the REAL recorder (`P8-TSK-004`, ADR-0067): the expectation IS the clearing journal line's
 * copy — asserted against the ledger, never against the applier's inputs — the coupling
 * rolls back whole, ten appliers open one, the ARN arrives in either order, a collision
 * completes the payment, and a book movement opens nothing.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the expectation register at the card completions (P8-TSK-004)")
class SettlementExpectationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(12_00, EUR);
    private static final Actor PLATFORM = Actor.SYSTEM;

    /** The psp source's seeded identity and rule set (settlement/reconciliation V002). */
    private static final UUID PSP_SOURCE = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID PSP_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");

    private final Runner runner = new Runner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcPaymentParticipants participants =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(),
                    new JdbcCustomerAccountStore(),
                    ledgerAccounts,
                    new JdbcPaymentMethodStore());
    private final ExpectationRegister register = new JdbcExpectationRegister(IDS);

    // -----------------------------------------------------------------
    // The capture: exactly one expectation, equal to its clearing line, keys registered.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a capture opens exactly one CARD_CAPTURE expectation whose amount, entry"
            + " and account equal its clearing journal line - asserted against the ledger -"
            + " and a converging resolver opens nothing more")
    void aCaptureOpensItsClearingLinesCopy() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        String pspRef = "psp-cap-" + UUID.randomUUID();

        applyCapture(outcomes(recorder()), holder, attemptId, pspRef);

        UUID entryId = entryOf(attemptId.value().toString());
        ExpectationRow expectation = theOneExpectationOf("CARD_CAPTURE", attemptId.value());
        LedgerLine line = theOneClearingLineOf(entryId, expectation.ledgerAccountId());
        assertThat(expectation.amountMinor()).isEqualTo(line.amountMinor());
        assertThat(expectation.currency()).isEqualTo(line.currency());
        assertThat(expectation.journalEntryId()).isEqualTo(entryId);
        assertThat(expectation.direction())
                .as("a DEBIT on the position is INBOUND - the ledger's own sign")
                .isEqualTo("INBOUND");
        assertThat(line.direction()).isEqualTo("DEBIT");
        assertThat(expectation.sourceId()).isEqualTo(PSP_SOURCE);
        assertThat(expectation.ruleSetId())
                .as("the deciding version pinned on the row (INV-HIST-04)")
                .isEqualTo(PSP_RULE_SET);
        assertThat(expectation.expectedBy())
                .as("posting date + the seeded card lag of 3")
                .isEqualTo(LocalDate.now(CLOCK.withZone(ZoneOffset.UTC)).plusDays(3));
        assertThat(keyOwner("PSP_CAPTURE_REF", pspRef)).isEqualTo(expectation.id());
        assertThat(keyOwner("CARD_ATTEMPT", attemptId.value().toString()))
                .isEqualTo(expectation.id());
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation_event WHERE"
                                + " expectation_id = ? AND event_type = 'OPENED'",
                        expectation.id()))
                .isEqualTo(1);
        // The register's shared proof (P8-TSK-005): the same facts, by the posting key.
        ClearingLineCopies.assertOpensItsClearingLinesCopy(
                com.finapp.reconciliation.ExpectationKind.CARD_CAPTURE,
                attemptId.value().toString(),
                "payment-capture:" + attemptId.value(),
                com.finapp.reconciliation.ExpectationDirection.INBOUND);

        // The converging resolver: the conditional transition already fired, so the port is
        // not even called - and the uniques would converge it if it were.
        applyCapture(outcomes(recorder()), holder, attemptId, pspRef);
        assertThat(expectationCount("CARD_CAPTURE", attemptId.value())).isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances applying one capture: one acting, one entry, one expectation"
            + " - the acting conditional decides and the uniques back it")
    void tenAppliersOpenOne() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        String pspRef = "psp-race-" + UUID.randomUUID();
        PaymentOutcomes outcomes = outcomes(recorder());

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acting = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (SimulatedInstance instance =
                                                        SimulatedInstance
                                                                .inAgreementWithTheServer();
                                                SecurityContext.Scope scope =
                                                        SecurityContext.enterSystem();
                                                CorrelationContext.Scope inFlow =
                                                        CorrelationContext.enter(flow())) {
                                            start.await();
                                            PaymentOutcomes.Applied applied =
                                                    outcomes.applyCapture(
                                                            instance.connection(),
                                                            holder.intent(),
                                                            attemptId,
                                                            PaymentAttemptStatus
                                                                    .CAPTURE_DISPATCHED,
                                                            ProviderAnswer.Verdict.APPROVED,
                                                            Optional.of(
                                                                    new ProviderReference(
                                                                            pspRef)),
                                                            holder.wallet(),
                                                            AMOUNT,
                                                            flow());
                                            instance.commit();
                                            if (applied.acting()) {
                                                acting.incrementAndGet();
                                            }
                                        } catch (Exception failure) {
                                            failures.add(failure);
                                        }
                                    }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(120_000);
        }
        assertThat(failures).isEmpty();
        assertThat(acting.get()).as("exactly one racer applies the capture").isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                        attemptId.value().toString()))
                .isEqualTo(1);
        assertThat(expectationCount("CARD_CAPTURE", attemptId.value())).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The card refund: the OUTBOUND copy of ITS clearing line.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a card refund opens exactly one OUTBOUND CARD_REFUND expectation equal to"
            + " its own clearing journal line, keyed PSP_REFUND_REF and OUR_REF")
    void aCardRefundOpensItsOutboundCopy() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        applyCapture(outcomes(recorder()), holder, attemptId, "psp-cap-" + UUID.randomUUID());

        Refund refund = dispatchedRefund(holder, attemptId);
        String pspRefundRef = "psp-rfd-" + UUID.randomUUID();
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            runner.inTransaction(
                    uow -> {
                        outcomes(recorder())
                                .applyRefund(
                                        uow,
                                        holder.intent(),
                                        refund,
                                        RefundStatus.DISPATCHED,
                                        ProviderAnswer.Verdict.APPROVED,
                                        Optional.of(new ProviderReference(pspRefundRef)),
                                        holder.wallet(),
                                        flow());
                        return null;
                    });
        }

        UUID entryId = entryOf(refund.id().value().toString());
        ExpectationRow expectation =
                theOneExpectationOf("CARD_REFUND", refund.id().value());
        LedgerLine line = theOneClearingLineOf(entryId, expectation.ledgerAccountId());
        assertThat(expectation.amountMinor()).isEqualTo(line.amountMinor());
        assertThat(expectation.journalEntryId()).isEqualTo(entryId);
        assertThat(line.direction()).isEqualTo("CREDIT");
        assertThat(expectation.direction())
                .as("a CREDIT on the position is OUTBOUND - money going back")
                .isEqualTo("OUTBOUND");
        assertThat(keyOwner("PSP_REFUND_REF", pspRefundRef)).isEqualTo(expectation.id());
        assertThat(keyOwner("OUR_REF", refund.providerIdempotencyReference().value()))
                .isEqualTo(expectation.id());
        // The register's shared proof (P8-TSK-005): the same facts, by the posting key.
        ClearingLineCopies.assertOpensItsClearingLinesCopy(
                com.finapp.reconciliation.ExpectationKind.CARD_REFUND,
                refund.id().value().toString(),
                "payment-refund:" + refund.id().value(),
                com.finapp.reconciliation.ExpectationDirection.OUTBOUND);
    }

    // -----------------------------------------------------------------
    // The ARN alias: either order, first writer wins.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the ARN aliases to its attempt whether the clearing notice arrives before"
            + " or after the capture, and a reference claimed elsewhere registers nothing")
    void theArnAliasRegistersInEitherOrder() throws Exception {
        // BEFORE: the notice lands while the attempt is only authorized.
        Holder early = holder();
        PaymentAttemptId earlyAttempt = captureDispatched(early);
        String earlyArn = "arn-early-" + UUID.randomUUID();
        assertThat(clear(earlyAttempt, earlyArn))
                .isEqualTo(PaymentClearing.Outcome.RECORDED);
        assertThat(aliasAnchor("ACQUIRER_REF", earlyArn))
                .isEqualTo("CARD_ATTEMPT:" + earlyAttempt.value());
        applyCapture(outcomes(recorder()), early, earlyAttempt,
                "psp-cap-" + UUID.randomUUID());
        assertThat(keyOwner("CARD_ATTEMPT", earlyAttempt.value().toString()))
                .as("the alias's anchor key exists once the capture lands: the two-hop join"
                        + " resolves")
                .isNotNull();

        // AFTER: the capture lands first, the notice follows.
        Holder late = holder();
        PaymentAttemptId lateAttempt = captureDispatched(late);
        applyCapture(outcomes(recorder()), late, lateAttempt, "psp-cap-" + UUID.randomUUID());
        String lateArn = "arn-late-" + UUID.randomUUID();
        assertThat(clear(lateAttempt, lateArn)).isEqualTo(PaymentClearing.Outcome.RECORDED);
        assertThat(aliasAnchor("ACQUIRER_REF", lateArn))
                .isEqualTo("CARD_ATTEMPT:" + lateAttempt.value());

        // CLAIMED ELSEWHERE: the first record stands and no alias is written for the loser.
        Holder claimant = holder();
        PaymentAttemptId claimantAttempt = captureDispatched(claimant);
        assertThat(clear(claimantAttempt, lateArn))
                .isEqualTo(PaymentClearing.Outcome.REFERENCE_CLAIMED_ELSEWHERE);
        assertThat(aliasAnchor("ACQUIRER_REF", lateArn))
                .as("the standing alias is untouched")
                .isEqualTo("CARD_ATTEMPT:" + lateAttempt.value());
    }

    // -----------------------------------------------------------------
    // The coupling: a failing opener rolls the capture back; redelivery completes both.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a failure inside the port rolls the whole capture back - transition,"
            + " posting and expectation - and the redelivery completes all three")
    void aFailingOpenerRollsTheCaptureBack() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        String pspRef = "psp-fail-" + UUID.randomUUID();
        PaymentOutcomes failing =
                outcomes(
                        new SettlementExpectations() {
                            @Override
                            public void open(Connection uow, Opening opening) {
                                throw new IllegalStateException(
                                        "the register is down (P8-TSK-004's coupling probe)");
                            }

                            @Override
                            public void alias(Connection uow, AliasRegistration registration) {}
                        });

        assertThatThrownBy(() -> applyCapture(failing, holder, attemptId, pspRef))
                .isInstanceOf(IllegalStateException.class);

        assertThat(attemptStatus(attemptId))
                .as("the completion rolled back with its expectation (ADR-0067 §6)")
                .isEqualTo("CAPTURE_DISPATCHED");
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                        attemptId.value().toString()))
                .isEqualTo(0);
        assertThat(expectationCount("CARD_CAPTURE", attemptId.value())).isEqualTo(0);

        // The redelivery - the provider's webhook retry, the sweep's next query - completes
        // both halves at once.
        applyCapture(outcomes(recorder()), holder, attemptId, pspRef);
        assertThat(attemptStatus(attemptId)).isEqualTo("CAPTURED");
        assertThat(expectationCount("CARD_CAPTURE", attemptId.value())).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // A collision is recorded, never a failed payment.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a planted key collision completes the payment, opens the expectation, and"
            + " records KEY_COLLISION - the standing key untouched")
    void aPlantedCollisionNeverFailsThePayment() throws Exception {
        String collidingRef = "psp-shared-" + UUID.randomUUID();
        UUID planted = plantExpectationWithKey(collidingRef);

        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        applyCapture(outcomes(recorder()), holder, attemptId, collidingRef);

        assertThat(attemptStatus(attemptId)).as("the payment completed").isEqualTo("CAPTURED");
        ExpectationRow expectation = theOneExpectationOf("CARD_CAPTURE", attemptId.value());
        assertThat(keyOwner("PSP_CAPTURE_REF", collidingRef))
                .as("the first writer stands (ADR-0067 §6)")
                .isEqualTo(planted);
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation_event WHERE"
                                + " expectation_id = ? AND event_type = 'KEY_COLLISION'",
                        expectation.id()))
                .as("the record P8-TSK-010's sweep raises DUPLICATE_INTERNAL from")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The book rail: no reconciled position moved, nothing opened (INV-SET-01).
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a book top-up and a book refund open no expectation: SettlementModel.NONE"
            + " is INV-SET-01's per-rail guarantee, judged from the declaration")
    void aBookMovementOpensNothing() throws Exception {
        Holder payer = holder();
        Holder payee = holder();
        // Fund the payer: a captured card top-up, so the book dispatch's hold can bite on
        // real value - and one more CARD_CAPTURE expectation, which is exactly the contrast
        // this test draws against the book movements below.
        applyCapture(
                outcomes(recorder()),
                payer,
                captureDispatched(payer),
                "psp-fund-" + UUID.randomUUID());

        PaymentIntentId bookIntent;
        PaymentAttemptId bookAttempt;
        try (SecurityContext.Scope scope =
                        SecurityContext.enter(
                                new Actor(payer.party().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            PaymentOutcomes outcomes = outcomes(recorder());
            PaymentIntent intent =
                    PaymentIntent.createFromWallet(
                            IDS,
                            CLOCK,
                            payer.party(),
                            payer.customer(),
                            payer.wallet(),
                            payee.wallet(),
                            AMOUNT);
            bookIntent = intent.id();
            bookAttempt =
                    runner.inTransaction(
                            uow -> {
                                intents.insert(uow, intent);
                                intents.transition(
                                        uow,
                                        intent.id(),
                                        PaymentIntentStatus.REQUIRES_CONFIRMATION,
                                        PaymentIntentStatus.PROCESSING);
                                PaymentOutcomes.BookDispatch booked =
                                        outcomes.dispatchBook(uow, intent, BookRail.RAIL.id());
                                outcomes.settleBook(uow, intent, booked, flow());
                                return booked.attempt().id();
                            });
        }
        assertThat(intentStatus(bookIntent)).isEqualTo("SUCCEEDED");
        assertThat(expectationCount("CARD_CAPTURE", bookAttempt.value())).isEqualTo(0);

        // The book refund: the counterpart is the payer's own wallet, no clearing between.
        PaymentAttempt executed =
                runner.inTransaction(uow -> attempts.findById(uow, bookAttempt).orElseThrow());
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope inFlow = CorrelationContext.enter(flow())) {
        Refund bookRefund =
                runner.inTransaction(
                        uow -> {
                            com.finapp.ledger.Hold hold =
                                    new com.finapp.ledger.HoldService(
                                                    ledgerAccounts,
                                                    new com.finapp.ledger.JdbcBalanceDerivation(),
                                                    new com.finapp.ledger.JdbcHoldStore(),
                                                    new JdbcBalanceProjection(),
                                                    new JdbcAuditWriter(),
                                                    new JdbcOutboxWriter(),
                                                    IDS,
                                                    CLOCK)
                                            .place(uow, payee.wallet(), AMOUNT);
                            Refund fresh =
                                    Refund.createBookRefund(
                                            IDS,
                                            CLOCK,
                                            executed,
                                            AMOUNT,
                                            AMOUNT,
                                            Money.ofMinorUnits(0, EUR),
                                            "book refund opens nothing",
                                            hold.id(),
                                            new ProviderIdempotencyReference(
                                                    "bkr-" + IDS.next()));
                            new com.finapp.payments.JdbcRefundStore()
                                    .insert(uow, fresh, "p8t4-book-" + UUID.randomUUID());
                            return fresh;
                        });
        runner.inTransaction(
                uow -> {
                    outcomes(recorder())
                            .applyRefund(
                                    uow,
                                    bookIntent,
                                    bookRefund,
                                    RefundStatus.DISPATCHED,
                                    ProviderAnswer.Verdict.APPROVED,
                                    Optional.of(
                                            new ProviderReference(
                                                    "book-" + UUID.randomUUID())),
                                    payee.wallet(),
                                    flow());
                    return null;
                });
        assertThat(refundStatus(bookRefund.id().value())).isEqualTo("COMPLETED");
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation WHERE operation_ref"
                                + " IN (?, ?)",
                        bookAttempt.value().toString(),
                        bookRefund.id().value().toString()))
                .as("neither book movement opened anything")
                .isEqualTo(0);
        // By the posting key too (P8-TSK-005): the book payment's execution and the book
        // refund each posted - and touched no reconciled position, so opened nothing.
        ClearingLineCopies.assertOpensNothing("payment-execution:" + bookAttempt.value());
        ClearingLineCopies.assertOpensNothing("payment-refund:" + bookRefund.id().value());
        }
    }

    // -----------------------------------------------------------------
    // Fixtures: the sweeper suite's holder, walked to CAPTURE_DISPATCHED by store edges.
    // -----------------------------------------------------------------

    private record Holder(UUID party, UUID customer, Actor person, PaymentIntentId intent,
            LedgerAccountId wallet) {}

    private Holder holder() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        Actor person = new Actor(party.toString(), ActorType.CUSTOMER);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Expectation Holder',"
                            + " now() - interval '2 hour')",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer, party);
            execute(app,
                    "INSERT INTO paymentmethods.payment_method (id, party_id, kind,"
                            + " token_reference, brand, display_suffix, expiry_month,"
                            + " expiry_year, status, created_at) VALUES (?, ?, 'CARD_TOKEN',"
                            + " ?, 'Visa', '4242', 12, 2030, 'ACTIVE', now())",
                    method, party, "tok-p8t4-" + UUID.randomUUID());
        }
        try (SecurityContext.Scope actor = SecurityContext.enter(person);
                CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(
                                        CorrelationId.of("p8t4-" + UUID.randomUUID())))) {
            runner.inTransaction(
                    uow ->
                            new AccountOpening(
                                            new JdbcCustomerAccountStore(),
                                            ledgerAccounts,
                                            new VerifiedAccountHolder(new JdbcPartyStore()),
                                            new JdbcAuditWriter(),
                                            new JdbcOutboxWriter(),
                                            IDS,
                                            CLOCK)
                                    .open(uow, party, ProductType.WALLET, EUR));
            PaymentCreation.CreationResult created =
                    runner.inTransaction(
                            uow ->
                                    new PaymentCreation(
                                                    executor(),
                                                    participants,
                                                    intents,
                                                    new JdbcAuditWriter(),
                                                    new JdbcOutboxWriter(),
                                                    IDS,
                                                    CLOCK,
                                                    PaymentCreation.IDEMPOTENCY_SCOPE)
                                            .create(
                                                    uow,
                                                    new PaymentCreation.CreatePaymentCommand(
                                                            party,
                                                            method,
                                                            AMOUNT,
                                                            "p8t4-" + UUID.randomUUID())));
            LedgerAccountId wallet =
                    runner.inTransaction(
                            uow ->
                                    intents.findById(uow, created.intent())
                                            .orElseThrow()
                                            .creditAccount());
            return new Holder(party, customer, person, created.intent(), wallet);
        }
    }

    /** Intent PROCESSING, attempt authorized and its capture dispatched — store edges. */
    private PaymentAttemptId captureDispatched(Holder holder) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            return runner.inTransaction(
                    uow -> {
                        intents.transition(
                                uow,
                                holder.intent(),
                                PaymentIntentStatus.REQUIRES_CONFIRMATION,
                                PaymentIntentStatus.PROCESSING);
                        PaymentAttempt attempt =
                                PaymentAttempt.create(
                                        IDS,
                                        CLOCK,
                                        holder.intent(),
                                        SimulatedCardPspAdapter.RAIL.id(),
                                        new ProviderIdempotencyReference("p8t4a-" + IDS.next()));
                        attempts.insert(uow, attempt);
                        outcomes(recorder())
                                .applyAuthorization(
                                        uow,
                                        holder.intent(),
                                        attempt.id(),
                                        PaymentAttemptStatus.AUTH_DISPATCHED,
                                        ProviderAnswer.Verdict.APPROVED,
                                        Optional.of(
                                                new ProviderReference(
                                                        "psp-auth-" + UUID.randomUUID())),
                                        AMOUNT,
                                        flow());
                        attempts.dispatchCapture(
                                uow,
                                attempt.id(),
                                new ProviderIdempotencyReference("p8t4c-" + IDS.next()));
                        return attempt.id();
                    });
        }
    }

    private void applyCapture(
            PaymentOutcomes outcomes, Holder holder, PaymentAttemptId attemptId, String pspRef)
            throws Exception {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            runner.inTransaction(
                    uow -> {
                        outcomes.applyCapture(
                                uow,
                                holder.intent(),
                                attemptId,
                                PaymentAttemptStatus.CAPTURE_DISPATCHED,
                                ProviderAnswer.Verdict.APPROVED,
                                Optional.of(new ProviderReference(pspRef)),
                                holder.wallet(),
                                AMOUNT,
                                flow());
                        return null;
                    });
        }
    }

    private Refund dispatchedRefund(Holder holder, PaymentAttemptId attemptId) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            return runner.inTransaction(
                    uow -> {
                        PaymentAttempt captured =
                                attempts.findById(uow, attemptId).orElseThrow();
                        com.finapp.ledger.Hold hold =
                                new com.finapp.ledger.HoldService(
                                                ledgerAccounts,
                                                new com.finapp.ledger.JdbcBalanceDerivation(),
                                                new com.finapp.ledger.JdbcHoldStore(),
                                                new JdbcBalanceProjection(),
                                                new JdbcAuditWriter(),
                                                new JdbcOutboxWriter(),
                                                IDS,
                                                CLOCK)
                                        .place(uow, holder.wallet(), AMOUNT);
                        Refund fresh =
                                Refund.create(
                                        IDS,
                                        CLOCK,
                                        captured,
                                        AMOUNT,
                                        Money.ofMinorUnits(0, EUR),
                                        "expectation suite refund",
                                        hold.id(),
                                        new ProviderIdempotencyReference("rfd-" + IDS.next()));
                        new com.finapp.payments.JdbcRefundStore()
                                .insert(uow, fresh, "p8t4-rfd-" + UUID.randomUUID());
                        return fresh;
                    });
        }
    }

    private PaymentClearing.Outcome clear(PaymentAttemptId attemptId, String arn) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            return runner.inTransaction(
                    uow -> {
                        PaymentAttempt attempt =
                                attempts.findById(uow, attemptId).orElseThrow();
                        return new PaymentClearing(
                                        new com.finapp.payments.JdbcClearingRecordStore(),
                                        new JdbcOutboxWriter(),
                                        IDS,
                                        CLOCK,
                                        PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL)),
                                        recorder())
                                .record(
                                        uow,
                                        attempt,
                                        new ProviderReference(arn),
                                        new ProviderReference("ntx-" + UUID.randomUUID()),
                                        flow());
                    });
        }
    }

    /** A standing expectation holding the colliding key, planted through the register. */
    private UUID plantExpectationWithKey(String pspRef) throws SQLException {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem()) {
            String planted = "planted-" + UUID.randomUUID();
            runner.inTransaction(
                    uow -> {
                        register.open(
                                uow,
                                new NewExpectation(
                                        com.finapp.reconciliation.ExpectationKind.CARD_CAPTURE,
                                        planted,
                                        "payment-capture:" + planted,
                                        PSP_SOURCE,
                                        AccountPurpose.SETTLEMENT_CLEARING,
                                        UUID.randomUUID(),
                                        com.finapp.reconciliation.ExpectationDirection.INBOUND,
                                        AMOUNT,
                                        Optional.of(UUID.randomUUID()),
                                        LocalDate.now(ZoneOffset.UTC),
                                        Optional.empty(),
                                        LocalDate.now(ZoneOffset.UTC).plusDays(3),
                                        PSP_RULE_SET,
                                        List.of(
                                                new NewExpectation.ExpectationKey(
                                                        KeyKind.PSP_CAPTURE_REF, pspRef)),
                                        PLATFORM,
                                        CLOCK.instant(),
                                        CorrelationId.of("p8t4-plant")));
                        return null;
                    });
            return keyOwner("PSP_CAPTURE_REF", pspRef);
        }
    }

    // -----------------------------------------------------------------

    private SettlementExpectations recorder() {
        return new ReconciliationExpectationRecorder(
                SettlementSources.of(
                        List.of(
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}")))),
                new JdbcSettlementFileStore(
                        new SettlementFileCipher(new byte[32], 1, new SecureRandom())),
                new JdbcJournalEntryStore(IDS),
                new JdbcRuleSets(),
                register,
                CLOCK);
    }

    private PaymentOutcomes outcomes(SettlementExpectations expectations) {
        return new PaymentOutcomes(
                intents,
                attempts,
                new com.finapp.payments.JdbcRefundStore(),
                new com.finapp.ledger.HoldService(
                        ledgerAccounts,
                        new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore(),
                        new JdbcBalanceProjection(),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        IDS,
                        CLOCK),
                new PostingService(
                        executor(),
                        new JdbcJournalEntryStore(IDS),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        new JdbcBalanceProjection(),
                        IDS,
                        CLOCK,
                        PostingObserver.NONE),
                new ChartOfAccounts<>(ledgerAccounts),
                new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                ledgerAccounts,
                                new ChartOfAccounts<>(ledgerAccounts),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletTopUpComposition(),
                        landed -> {},
                        new com.finapp.app.telemetry.MerchantMeters(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())),
                new com.finapp.app.merchant.MerchantBoundRefundComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                ledgerAccounts,
                                new ChartOfAccounts<>(ledgerAccounts),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletRefundComposition()),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK,
                PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, BookRail.RAIL)),
                new com.finapp.payments.JdbcUnmatchedConfirmationStore(),
                ledgerAccounts,
                ChargebackAccountingFixture.over(
                        new PostingService(
                                executor(),
                                new JdbcJournalEntryStore(IDS),
                                new JdbcAuditWriter(),
                                new JdbcOutboxWriter(),
                                new JdbcBalanceProjection(),
                                IDS,
                                CLOCK,
                                PostingObserver.NONE),
                        PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, BookRail.RAIL)),
                        IDS,
                        CLOCK),
                com.finapp.payments.RailOutcomeObserver.NONE,
                new com.finapp.payments.JdbcSchemeExecutionClaimStore(),
                expectations);
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8-tsk-004-expectation-test"))
                .causing(CausationId.of("p8-tsk-004-cause"));
    }

    // ----------------------------------------------------------------- readers

    private record ExpectationRow(
            UUID id,
            UUID sourceId,
            UUID ledgerAccountId,
            UUID journalEntryId,
            String direction,
            long amountMinor,
            String currency,
            LocalDate expectedBy,
            UUID ruleSetId) {}

    private record LedgerLine(String direction, long amountMinor, String currency) {}

    private ExpectationRow theOneExpectationOf(String kind, Object operationRef)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id, source_id, ledger_account_id, journal_entry_id,"
                                        + " direction, amount_minor, currency, expected_by,"
                                        + " rule_set_id FROM reconciliation.expectation"
                                        + " WHERE kind = ? AND operation_ref = ?")) {
            read.setString(1, kind);
            read.setString(2, operationRef.toString());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one %s expectation for %s", kind, operationRef)
                        .isTrue();
                ExpectationRow result =
                        new ExpectationRow(
                                row.getObject("id", UUID.class),
                                row.getObject("source_id", UUID.class),
                                row.getObject("ledger_account_id", UUID.class),
                                row.getObject("journal_entry_id", UUID.class),
                                row.getString("direction"),
                                row.getLong("amount_minor"),
                                row.getString("currency"),
                                row.getObject("expected_by", LocalDate.class),
                                row.getObject("rule_set_id", UUID.class));
                assertThat(row.next()).as("and only one").isFalse();
                return result;
            }
        }
    }

    private LedgerLine theOneClearingLineOf(UUID entryId, UUID accountId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT direction, amount_minor, currency FROM"
                                        + " ledger.journal_line WHERE entry_id = ? AND"
                                        + " ledger_account_id = ?")) {
            read.setObject(1, entryId);
            read.setObject(2, accountId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the clearing line exists").isTrue();
                LedgerLine line =
                        new LedgerLine(
                                row.getString("direction"),
                                row.getLong("amount_minor"),
                                row.getString("currency"));
                assertThat(row.next()).as("exactly one line on the position").isFalse();
                return line;
            }
        }
    }

    private long expectationCount(String kind, Object operationRef) throws SQLException {
        return count(
                "SELECT count(*) FROM reconciliation.expectation WHERE kind = ? AND"
                        + " operation_ref = ?",
                kind,
                operationRef.toString());
    }

    private UUID keyOwner(String kind, String value) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT expectation_id FROM reconciliation.expectation_key"
                                        + " WHERE source_id = ? AND key_kind = ? AND"
                                        + " key_value = ?")) {
            read.setObject(1, PSP_SOURCE);
            read.setString(2, kind);
            read.setString(3, value);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? row.getObject("expectation_id", UUID.class) : null;
            }
        }
    }

    private String aliasAnchor(String kind, String value) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT anchor_kind || ':' || anchor_value FROM"
                                        + " reconciliation.reference_alias WHERE source_id = ?"
                                        + " AND key_kind = ? AND key_value = ?")) {
            read.setObject(1, PSP_SOURCE);
            read.setString(2, kind);
            read.setString(3, value);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private String attemptStatus(PaymentAttemptId attemptId) throws SQLException {
        return text("SELECT status FROM payments.payment_attempt WHERE id = ?",
                attemptId.value());
    }

    private String intentStatus(PaymentIntentId intentId) throws SQLException {
        return text("SELECT status FROM payments.payment_intent WHERE id = ?",
                intentId.value());
    }

    private String refundStatus(UUID refundId) throws SQLException {
        return text("SELECT status FROM payments.refund WHERE id = ?", refundId);
    }

    private UUID entryOf(String reference) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM ledger.journal_entry WHERE"
                                        + " reference = ?")) {
            read.setString(1, reference);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the posting exists for %s", reference).isTrue();
                return row.getObject("id", UUID.class);
            }
        }
    }

    private String text(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    /** One transaction per act, committed — the appliers run on the caller's connection. */
    private static final class Runner implements TransactionRunner {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
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
                throw new IllegalStateException("could not run the transaction", failure);
            }
        }
    }
}
