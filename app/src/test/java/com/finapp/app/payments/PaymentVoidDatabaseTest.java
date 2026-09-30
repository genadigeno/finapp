package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.EvidenceCipher;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcProviderEvidenceStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentCapture;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.PaymentSweeper;
import com.finapp.payments.PaymentVoid;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.UnknownPaymentException;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The void against the real schema, the real chain and a real HTTP provider
 * (`P7-TSK-004`, ADR-0059 §1, `INV-REV-03`'s revocable half): the release moves
 * <strong>no money</strong> on any verdict — proven by the journal staying empty — the
 * refused connection concludes NOTHING and any instance's permit-free re-send finishes it,
 * the ambiguous release is resolved by the query, the declined-capture redirect's stranded
 * half is the sweeper's to send, and the race against the capture chain settles on exactly
 * one terminal family, counted in the journal.
 */
@Tag("database")
@DisplayName("payment void command (P7-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PaymentVoidDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(10_00, EUR);
    private static final byte[] PSP_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String APPROVED_BODY =
            "  {\"status\" : \"approved\",  \"reference\":\"psp_%s\"}\n";
    private static final Duration DUE_NOW = Duration.ofNanos(1_000);

    private static SimulatedProvider psp;

    private final CountingRunner runner = new CountingRunner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcProviderEvidenceStore evidence =
            new JdbcProviderEvidenceStore(
                    new EvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom()), IDS);
    private final JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcPaymentParticipants participants =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(),
                    new com.finapp.accounts.JdbcCustomerAccountStore(),
                    ledgerAccounts,
                    new JdbcPaymentMethodStore());

    @BeforeAll
    static void startProvider() {
        psp = SimulatedProvider.start();
    }

    @AfterAll
    static void stopProvider() {
        psp.close();
    }

    private CorrelationContext.Scope testFlow;

    @BeforeEach
    void reset() {
        psp.reset();
        testFlow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("void-" + UUID.randomUUID())));
    }

    @AfterEach
    void leaveFlow() throws Exception {
        testFlow.close();
    }

    @Test
    @DisplayName("the customer's void end to end: VOIDED with the acknowledgement, the intent"
            + " FAILED, the journal EMPTY, the event announced, both answers retained")
    void voidEndToEndReleasesThePromise() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.succeedsWith(
                SimulatedCardPspAdapter.VOIDS_PATH, 200, APPROVED_BODY.formatted("void-e2e"));

        PaymentVoid.VoidResult result;
        try (SecurityContext.Scope actor = SecurityContext.enter(holder.person())) {
            result =
                    voids(adapter())
                            .voidAuthorized(
                                    Optional.of(holder.party()), holder.intent(),
                                    Optional.empty());
        }

        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(result.converged()).isFalse();
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt released = attempts.findById(app, attempt).orElseThrow();
            assertThat(released.status()).isEqualTo(PaymentAttemptStatus.VOIDED);
            assertThat(released.voidReference().value()).startsWith("void-");
            assertThat(released.voidProviderReference().value()).isEqualTo("psp_void-e2e");
            assertThat(released.failureReason())
                    .as("VOIDED is a release, not a failure")
                    .isNull();
            assertThat(intents.findById(app, holder.intent()).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);

            // INV-REV-03's whole premise, at the journal: nothing was taken, so nothing
            // posts and nothing needs reversing. The wallet never heard of this payment.
            assertThat(entriesReferencing(app, attempt)).isZero();

            // Both wire answers retained: the authorization's and the release's.
            assertThat(evidence.payloadsFor(app, attempt)).hasSize(2);

            // The announcement: downstream learns the authorization died released.
            assertThat(outboxCount(app, "payments.AuthorizationVoided",
                            holder.intent().value()))
                    .isEqualTo(1);

            // The dispatch audit: the customer's act speaks for itself - no reason - and
            // the summary names the rail and OUR reference, never an amount.
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT reason, change_summary FROM platform.audit_record"
                                    + " WHERE operation = 'payments.PaymentVoidDispatched'"
                                    + " AND target_id = ?")) {
                read.setString(1, holder.intent().value().toString());
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("reason")).isNull();
                    assertThat(row.getString("change_summary"))
                            .contains("rail=card")
                            .contains("reference=" + released.voidReference().value());
                }
            }
        }
    }

    @Test
    @DisplayName("an ambiguous release commits VOID_UNKNOWN with nothing invented, and the"
            + " sweep's query resolves it to the provider's truth")
    void anAmbiguousReleaseIsResolvedByTheQuery() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.neverResponds(SimulatedCardPspAdapter.VOIDS_PATH);

        PaymentVoid.VoidResult result;
        try (SecurityContext.Scope actor = SecurityContext.enter(holder.person())) {
            result =
                    voids(adapter(Duration.ofMillis(400)))
                            .voidAuthorized(
                                    Optional.of(holder.party()), holder.intent(),
                                    Optional.empty());
        }
        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOID_UNKNOWN);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);

        ProviderIdempotencyReference voidReference;
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt parked = attempts.findById(app, attempt).orElseThrow();
            assertThat(parked.status()).isEqualTo(PaymentAttemptStatus.VOID_UNKNOWN);
            assertThat(parked.voidProviderReference()).isNull();
            assertThat(entriesReferencing(app, attempt)).isZero();
            voidReference = parked.voidReference();
        }

        // The sweep asks by OUR stored reference - the void's own, not the capture's.
        psp.reset();
        queryAnswers(voidReference, "approved", "psp_q-void");
        sweepUntil(
                releasingOnly(null, "psp_never-used",
                        new java.util.concurrent.atomic.AtomicInteger()),
                attempt, PaymentAttemptStatus.VOIDED);

        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt released = attempts.findById(app, attempt).orElseThrow();
            assertThat(released.status()).isEqualTo(PaymentAttemptStatus.VOIDED);
            assertThat(released.voidProviderReference().value()).isEqualTo("psp_q-void");
            assertThat(intents.findById(app, holder.intent()).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);
            assertThat(entriesReferencing(app, attempt)).isZero();
        }
    }

    @Test
    @DisplayName("a refused connection concludes NOTHING - the row rests VOID_DISPATCHED and"
            + " the sweep re-sends WITHOUT a permit, because releasing a released promise"
            + " converges (the deliberate V009 asymmetry)")
    void aRefusedConnectionStrandsAndTheSweepReSends() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);

        PaymentVoid.VoidResult refused;
        try (SecurityContext.Scope actor = SecurityContext.enter(holder.person())) {
            refused =
                    voids(deadAdapter())
                            .voidAuthorized(
                                    Optional.of(holder.party()), holder.intent(),
                                    Optional.empty());
        }
        assertThat(refused.attempt()).isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(refused.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(refused.acting()).isFalse();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(attempts.findById(app, attempt).orElseThrow().status())
                    .as("no conclusion was invented from a connection that never opened")
                    .isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
        }

        // ANY instance finishes it: the sweeper's dispatched leg re-sends the SAME stored
        // reference - no permit is consulted, and the provider converges idempotently.
        ProviderIdempotencyReference ours;
        try (Connection app = DatabaseRoles.application()) {
            ours = attempts.findById(app, attempt).orElseThrow().voidReference();
        }
        java.util.concurrent.atomic.AtomicInteger sends =
                new java.util.concurrent.atomic.AtomicInteger();
        sweepUntil(
                releasingOnly(ours, "psp_void-swept-" + shortId(), sends),
                attempt, PaymentAttemptStatus.VOIDED);

        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt released = attempts.findById(app, attempt).orElseThrow();
            assertThat(released.status()).isEqualTo(PaymentAttemptStatus.VOIDED);
            assertThat(released.voidProviderReference().value())
                    .startsWith("psp_void-swept-");
            assertThat(intents.findById(app, holder.intent()).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);
        }
        assertThat(sends.get())
                .as("one send released it; the ticks before and after moved nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a void the provider says it NEVER RECEIVED is re-sent by its stored reference"
            + " and concluded by the answer - never FAILED(NEVER_RECEIVED) with the"
            + " authorization left standing (the Phase 7 -> 8 transition)")
    void aNeverReceivedVoidIsResentNeverFailed() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.neverResponds(SimulatedCardPspAdapter.VOIDS_PATH);
        try (SecurityContext.Scope actor = SecurityContext.enter(holder.person())) {
            assertThat(voids(adapter(Duration.ofMillis(400)))
                            .voidAuthorized(
                                    Optional.of(holder.party()), holder.intent(),
                                    Optional.empty())
                            .attempt())
                    .isEqualTo(PaymentAttemptStatus.VOID_UNKNOWN);
        }
        ProviderIdempotencyReference ours;
        try (Connection app = DatabaseRoles.application()) {
            ours = attempts.findById(app, attempt).orElseThrow().voidReference();
        }

        // The query says it never saw our void: the sweep RE-SENDS it, permit-free.
        psp.reset();
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + ours.value(), 200,
                "{\"status\":\"unrecognised\"}");
        java.util.concurrent.atomic.AtomicInteger sends =
                new java.util.concurrent.atomic.AtomicInteger();
        sweepUntil(
                releasingOnly(ours, "psp_void-resent-" + shortId(), sends),
                attempt, PaymentAttemptStatus.VOIDED);

        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt released = attempts.findById(app, attempt).orElseThrow();
            assertThat(released.status()).isEqualTo(PaymentAttemptStatus.VOIDED);
            assertThat(released.failureReason()).as("a release, never NEVER_RECEIVED").isNull();
            assertThat(released.voidReference()).as("the SAME stored reference").isEqualTo(ours);
            assertThat(intents.findById(app, holder.intent()).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);
            assertThat(entriesReferencing(app, attempt)).isZero();
        }
        assertThat(sends.get()).as("one re-send released it").isEqualTo(1);
    }

    @Test
    @DisplayName("a CAPTURE the provider says it never received redirects into the void and"
            + " releases the authorization - never FAILED with the hold left on the customer's"
            + " funds (the Phase 7 -> 8 transition)")
    void aNeverReceivedCaptureReleasesTheAuthorization() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        ProviderIdempotencyReference capture =
                new ProviderIdempotencyReference("cap-" + IDS.next());
        runner.inTransaction(
                uow -> {
                    assertThat(attempts.dispatchCapture(uow, attempt, capture)).isTrue();
                    return null;
                });
        com.finapp.payments.ProviderReference authorization;
        try (Connection app = DatabaseRoles.application()) {
            authorization =
                    attempts.findById(app, attempt).orElseThrow()
                            .authorizationProviderReference();
        }
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + capture.value(), 200,
                "{\"status\":\"unrecognised\"}");
        java.util.concurrent.atomic.AtomicInteger sends =
                new java.util.concurrent.atomic.AtomicInteger();
        sweepUntil(
                releasingAuthorization(authorization, "psp_void-cap-" + shortId(), sends),
                attempt, PaymentAttemptStatus.VOIDED);

        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt released = attempts.findById(app, attempt).orElseThrow();
            assertThat(released.status()).isEqualTo(PaymentAttemptStatus.VOIDED);
            assertThat(released.failureReason()).isNull();
            assertThat(released.captureReference()).as("the capture's history rides along")
                    .isEqualTo(capture);
            assertThat(intents.findById(app, holder.intent()).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);
            assertThat(entriesReferencing(app, attempt)).as("nothing was captured").isZero();
        }
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a capture that never LEFT (a refused connection) redirects into the void; with"
            + " the provider down for the void too the row rests VOID_DISPATCHED and the sweep"
            + " releases it - never FAILED(PROVIDER_UNAVAILABLE) with the authorization standing"
            + " (the Phase 7 -> 8 transition)")
    void aCaptureThatNeverLeftReleasesTheAuthorization() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);

        PaymentCapture.CaptureResult refused;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            refused = capture(deadAdapter()).capture(attempt);
        }
        assertThat(refused.attempt()).isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(refused.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);

        ProviderIdempotencyReference ours;
        try (Connection app = DatabaseRoles.application()) {
            ours = attempts.findById(app, attempt).orElseThrow().voidReference();
        }
        java.util.concurrent.atomic.AtomicInteger sends =
                new java.util.concurrent.atomic.AtomicInteger();
        sweepUntil(
                releasingOnly(ours, "psp_void-refused-" + shortId(), sends),
                attempt, PaymentAttemptStatus.VOIDED);
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt released = attempts.findById(app, attempt).orElseThrow();
            assertThat(released.status()).isEqualTo(PaymentAttemptStatus.VOIDED);
            assertThat(released.failureReason()).isNull();
            assertThat(intents.findById(app, holder.intent()).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);
            assertThat(entriesReferencing(app, attempt)).isZero();
        }
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a port-less resolver's declined-capture redirect strands VOID_DISPATCHED -"
            + " and the sweep SENDS it: no stranded redirect outlives the next tick")
    void aStrandedRedirectIsTheSweepsToSend() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);

        // The webhook door's shape (P7-TSK-004): a resolver holding no provider port
        // applies a DECLINED capture. The redirect commits CAPTURE_DISPATCHED ->
        // VOID_DISPATCHED with its minted reference - and sends nothing, because it can't.
        runner.inTransaction(
                uow -> {
                    assertThat(attempts.dispatchCapture(
                                    uow, attempt,
                                    new ProviderIdempotencyReference("cap-" + IDS.next())))
                            .isTrue();
                    return null;
                });
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            runner.inTransaction(
                    uow -> {
                        PaymentIntent intent = intents.findById(uow, holder.intent())
                                .orElseThrow();
                        return outcomes().applyCapture(
                                uow,
                                holder.intent(),
                                attempt,
                                PaymentAttemptStatus.CAPTURE_DISPATCHED,
                                ProviderAnswer.Verdict.DECLINED,
                                Optional.empty(),
                                intent.creditAccount(),
                                intent.amount(),
                                Correlation.startingWith(
                                        CorrelationId.of("webhook-" + UUID.randomUUID())));
                    });
        }

        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt redirected = attempts.findById(app, attempt).orElseThrow();
            assertThat(redirected.status()).isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
            assertThat(redirected.voidReference()).isNotNull();
            assertThat(redirected.captureReference())
                    .as("the redirect's history rides along")
                    .isNotNull();
        }
        assertThat(psp.requestCount(SimulatedCardPspAdapter.VOIDS_PATH))
                .as("the port-less resolver sent nothing")
                .isZero();

        ProviderIdempotencyReference ours;
        try (Connection app = DatabaseRoles.application()) {
            ours = attempts.findById(app, attempt).orElseThrow().voidReference();
        }
        sweepUntil(
                releasingOnly(ours, "psp_void-stranded-" + shortId(),
                        new java.util.concurrent.atomic.AtomicInteger()),
                attempt, PaymentAttemptStatus.VOIDED);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(attempts.findById(app, attempt).orElseThrow().status())
                    .isEqualTo(PaymentAttemptStatus.VOIDED);
            assertThat(entriesReferencing(app, attempt)).isZero();
        }
    }

    @Test
    @DisplayName("ten racers - five voids, five captures - settle ONE terminal family,"
            + " counted in the journal: a captured payment posted once, a voided one never")
    void tenRacersSettleOneTerminalFamily() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH, 200, APPROVED_BODY.formatted("cap-void-race"));
        psp.succeedsWith(
                SimulatedCardPspAdapter.VOIDS_PATH, 200, APPROVED_BODY.formatted("void-race"));

        List<Callable<String>> racers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            racers.add(
                    () -> {
                        try (CorrelationContext.Scope flow =
                                CorrelationContext.enter(
                                        Correlation.startingWith(
                                                CorrelationId.of(
                                                        "race-c-" + UUID.randomUUID())))) {
                            return "capture:"
                                    + capture(adapter()).capture(attempt).attempt();
                        } catch (com.finapp.payments
                                .IllegalPaymentAttemptTransitionException refused) {
                            return "refused";
                        }
                    });
            racers.add(
                    () -> {
                        try (CorrelationContext.Scope flow =
                                        CorrelationContext.enter(
                                                Correlation.startingWith(
                                                        CorrelationId.of(
                                                                "race-v-"
                                                                        + UUID.randomUUID())));
                                SecurityContext.Scope actor =
                                        SecurityContext.enter(
                                                new Actor(
                                                        holder.party().toString(),
                                                        ActorType.CUSTOMER))) {
                            return "void:"
                                    + voids(adapter())
                                            .voidAuthorized(
                                                    Optional.of(holder.party()),
                                                    holder.intent(),
                                                    Optional.empty())
                                            .attempt();
                        } catch (com.finapp.payments
                                .IllegalPaymentAttemptTransitionException refused) {
                            return "refused";
                        }
                    });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<String> outcomes = new ArrayList<>();
        try {
            for (Future<String> raced : pool.invokeAll(racers)) {
                outcomes.add(raced.get());
            }
        } finally {
            pool.shutdown();
        }

        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt settled = attempts.findById(app, attempt).orElseThrow();
            PaymentIntentStatus intent =
                    intents.findById(app, holder.intent()).orElseThrow().status();
            if (settled.status() == PaymentAttemptStatus.CAPTURED) {
                assertThat(intent).isEqualTo(PaymentIntentStatus.SUCCEEDED);
                assertThat(entriesReferencing(app, attempt))
                        .as("the captured family posts exactly once: %s", outcomes)
                        .isEqualTo(1);
                assertThat(settled.voidReference())
                        .as("no void fact on the captured row")
                        .isNull();
            } else {
                assertThat(settled.status())
                        .as("the only other lawful settlement: %s", outcomes)
                        .isEqualTo(PaymentAttemptStatus.VOIDED);
                assertThat(intent).isEqualTo(PaymentIntentStatus.FAILED);
                assertThat(entriesReferencing(app, attempt))
                        .as("the voided family posts NOTHING: %s", outcomes)
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("the operator's reason is recorded verbatim, and a stranger's or unknown"
            + " intent is refused with nothing written on either door")
    void theOperatorDoorAuditsAndTheDoorsRefuseStrangers() throws Exception {
        Holder holder = holder();
        authorizedAttempt(holder);
        psp.succeedsWith(
                SimulatedCardPspAdapter.VOIDS_PATH, 200, APPROVED_BODY.formatted("void-cmd"));

        String reason = "authorization stranded by a crashed capture chain";
        Actor operator = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        PaymentVoid.VoidResult result;
        try (SecurityContext.Scope actor = SecurityContext.enter(operator)) {
            result =
                    voids(adapter())
                            .voidAuthorized(
                                    Optional.empty(), holder.intent(), Optional.of(reason));
        }
        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);

        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT reason FROM platform.audit_record"
                                        + " WHERE operation ="
                                        + " 'payments.PaymentVoidDispatched'"
                                        + " AND target_id = ?")) {
            read.setString(1, holder.intent().value().toString());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("reason")).isEqualTo(reason);
            }
        }

        // Both doors refuse an unknown intent as UNKNOWN - and the customer door refuses a
        // stranger's the same way (the one-404 discipline, nothing written).
        PaymentIntentId ghost = PaymentIntentId.next(IDS);
        try (SecurityContext.Scope actor = SecurityContext.enter(operator)) {
            assertThatThrownBy(
                            () ->
                                    voids(adapter())
                                            .voidAuthorized(
                                                    Optional.empty(), ghost,
                                                    // The operator door is always reasoned
                                                    // (P7-DOC-001: the domain refuses it
                                                    // unreasoned before looking anything up).
                                                    Optional.of("reversing a ghost")))
                    .isInstanceOf(UnknownPaymentException.class);
        }
        Holder other = holder();
        authorizedAttempt(other);
        try (SecurityContext.Scope actor = SecurityContext.enter(holder.person())) {
            assertThatThrownBy(
                            () ->
                                    voids(adapter())
                                            .voidAuthorized(
                                                    Optional.of(holder.party()),
                                                    other.intent(),
                                                    Optional.empty()))
                    .isInstanceOf(UnknownPaymentException.class);
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(attempts.findForIntent(app, other.intent()).orElseThrow().status())
                    .isEqualTo(PaymentAttemptStatus.AUTHORIZED);
        }
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private record Holder(
            UUID party, Actor person, PaymentIntentId intent, LedgerAccountId wallet) {}

    /** The REAL chain: party → ACTIVE customer → wallet product → ledger wallet → instrument. */
    private Holder holder() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        Actor person = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Void Holder', now() - interval '2 hour')",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer, party);
            execute(app,
                    "INSERT INTO paymentmethods.payment_method (id, party_id, kind, token_reference,"
                            + " brand, display_suffix, expiry_month, expiry_year, status,"
                            + " created_at) VALUES (?, ?, 'CARD_TOKEN', ?, 'Visa', '4242', 12, 2030,"
                            + " 'ACTIVE', now())",
                    method, party, "tok-void-" + UUID.randomUUID());
        }

        SecurityContext.Scope actor = SecurityContext.enter(person);
        CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("fix-" + UUID.randomUUID())));
        try {
            runner.inTransaction(
                    uow ->
                            new AccountOpening(
                                            new com.finapp.accounts.JdbcCustomerAccountStore(),
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
                                                            "void-" + UUID.randomUUID())));
            LedgerAccountId wallet =
                    runner.inTransaction(
                            uow ->
                                    intents.findById(uow, created.intent())
                                            .orElseThrow()
                                            .creditAccount());
            return new Holder(party, person, created.intent(), wallet);
        } finally {
            flow.close();
            actor.close();
        }
    }

    /** Confirms through the real adapter to a real, RESTING {@code AUTHORIZED} attempt. */
    private PaymentAttemptId authorizedAttempt(Holder holder) throws Exception {
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                APPROVED_BODY.formatted("auth-" + UUID.randomUUID()));
        SecurityContext.Scope actor = SecurityContext.enter(holder.person());
        CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("auth-" + UUID.randomUUID())));
        try {
            PaymentConfirmation.ConfirmationResult confirmed =
                    new PaymentConfirmation(
                                    runner,
                                    intents,
                                    attempts,
                                    evidence,
                                    participants,
                                    adapter(),
                                    outcomes(),
                                    new com.finapp.payments.JdbcRoutingStore(),
                                    com.finapp.payments.PaymentRails.of(
                                            java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                                    new JdbcAuditWriter(),
                                    new JdbcOutboxWriter(),
                                    IDS,
                                    CLOCK,
                                    com.finapp.payments.RoutingTelemetry.NONE,
                java.util.Optional.empty())
                            .confirm(holder.party(), holder.intent());
            assertThat(confirmed.attempt()).contains(PaymentAttemptStatus.AUTHORIZED);
        } finally {
            flow.close();
            actor.close();
        }
        psp.reset();
        try (Connection app = DatabaseRoles.application()) {
            return attempts.findForIntent(app, holder.intent()).orElseThrow().id();
        }
    }

    private PaymentOutcomes outcomes() {
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
                // The production seams (P6-TSK-005 / P6-TSK-014), exactly as the sibling
                // payment suites wire them: no fee pin exists here, so every posting falls
                // back to Phase 5's two lines - and this suite's whole claim is that the
                // void never reaches EITHER composition.
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
                com.finapp.payments.PaymentRails.of(
                        java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new com.finapp.payments.JdbcUnmatchedConfirmationStore(),
                new com.finapp.ledger.JdbcLedgerAccountStore(),
                // The dispute money (P7-TSK-013), production-shaped: a failed refund here
                // locks its card attempt first and finds no chargeback.
                com.finapp.app.payments.ChargebackAccountingFixture.over(
                        new PostingService(
                                executor(),
                                new JdbcJournalEntryStore(IDS),
                                new JdbcAuditWriter(),
                                new JdbcOutboxWriter(),
                                new JdbcBalanceProjection(),
                                IDS,
                                CLOCK,
                                PostingObserver.NONE),
                        com.finapp.payments.PaymentRails.of(
                                java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                        IDS,
                        CLOCK),
                com.finapp.payments.RailOutcomeObserver.NONE,
                new com.finapp.payments.JdbcSchemeExecutionClaimStore(),
                // The expectation seam (P8-TSK-004): a quiet double - this suite
                // asserts its own flow's semantics, and the register's coupling is
                // SettlementExpectationDatabaseTest's to prove with the REAL recorder.
                new com.finapp.payments.SettlementExpectations() {
                    @Override
                    public void open(java.sql.Connection uow, Opening opening) {}

                    @Override
                    public void alias(
                            java.sql.Connection uow, AliasRegistration registration) {}

                    @Override
                    public void parked(java.sql.Connection uow, ParkedValue parked) {}
                });
    }

    private PaymentVoid voids(PaymentProvider provider) {
        return new PaymentVoid(
                runner,
                intents,
                attempts,
                evidence,
                provider,
                outcomes(),
                com.finapp.payments.PaymentRails.of(
                        java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new JdbcAuditWriter(),
                IDS,
                CLOCK);
    }

    private PaymentCapture capture(PaymentProvider provider) {
        return new PaymentCapture(
                runner, intents, attempts, evidence, provider, outcomes(),
                voids(provider), new JdbcAuditWriter(), IDS, CLOCK);
    }

    private PaymentSweeper sweeper(PaymentProvider provider) {
        return new PaymentSweeper(
                runner, attempts, intents, new com.finapp.payments.JdbcRefundStore(), evidence,
                provider, outcomes(), capture(provider), voids(provider),
                IDS, CLOCK, DUE_NOW, DUE_NOW, 50);
    }

    /**
     * A sweep provider that releases exactly OUR void and leaves every foreign row alone:
     * this battery's database is persistent and shared, so the candidate batch carries
     * other suites' parked rows - {@code nothingSent} moves none of them and sends
     * nothing, the honest no-op (the applyVoid NOTHING_SENT arm). Queries delegate to the
     * real adapter, whose unstubbed answer is indeterminate - also a no-op.
     */
    private PaymentProvider releasingOnly(
            ProviderIdempotencyReference ours, String pspReference,
            java.util.concurrent.atomic.AtomicInteger sends) {
        SimulatedCardPspAdapter delegate = adapter();
        return new PaymentProvider() {
            @Override
            public String providerName() {
                return delegate.providerName();
            }

            @Override
            public ProviderAnswer authorize(AuthorizationRequest request) {
                return delegate.authorize(request);
            }

            @Override
            public ProviderAnswer capture(CaptureRequest request) {
                return delegate.capture(request);
            }

            @Override
            public ProviderAnswer refund(RefundRequest request) {
                return delegate.refund(request);
            }

            @Override
            public ProviderAnswer voidAuthorization(VoidRequest request) {
                if (request.reference().equals(ours)) {
                    sends.incrementAndGet();
                    return ProviderAnswer.approved(
                            new com.finapp.payments.ProviderReference(pspReference),
                            "swept".getBytes());
                }
                return ProviderAnswer.nothingSent();
            }

            @Override
            public QueryAnswer query(ProviderIdempotencyReference reference) {
                return delegate.query(reference);
            }
        };
    }

    /**
     * {@link #releasingOnly}'s twin keyed on the AUTHORIZATION the void releases - for a void
     * whose reference the sweep itself mints (a redirect), so the test cannot know it first.
     */
    private PaymentProvider releasingAuthorization(
            com.finapp.payments.ProviderReference authorization, String pspReference,
            java.util.concurrent.atomic.AtomicInteger sends) {
        PaymentProvider mine = releasingOnly(null, pspReference, sends);
        return new PaymentProvider() {
            @Override
            public String providerName() {
                return mine.providerName();
            }

            @Override
            public ProviderAnswer authorize(AuthorizationRequest request) {
                return mine.authorize(request);
            }

            @Override
            public ProviderAnswer capture(CaptureRequest request) {
                return mine.capture(request);
            }

            @Override
            public ProviderAnswer refund(RefundRequest request) {
                return mine.refund(request);
            }

            @Override
            public ProviderAnswer voidAuthorization(VoidRequest request) {
                if (authorization.equals(request.authorization())) {
                    sends.incrementAndGet();
                    return ProviderAnswer.approved(
                            new com.finapp.payments.ProviderReference(pspReference),
                            "swept".getBytes());
                }
                return ProviderAnswer.nothingSent();
            }

            @Override
            public QueryAnswer query(ProviderIdempotencyReference reference) {
                return mine.query(reference);
            }
        };
    }

    /** Sweeps until OUR attempt reaches {@code target} - the shared batch needs ticks. */
    private void sweepUntil(
            PaymentProvider provider, PaymentAttemptId attempt, PaymentAttemptStatus target)
            throws SQLException {
        for (int tick = 0; tick < 5; tick++) {
            sweeper(provider).sweep();
            try (Connection app = DatabaseRoles.application()) {
                if (attempts.findById(app, attempt).orElseThrow().status() == target) {
                    return;
                }
            }
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private SimulatedCardPspAdapter adapter() {
        return adapter(Duration.ofSeconds(2));
    }

    private SimulatedCardPspAdapter adapter(Duration timeout) {
        return new SimulatedCardPspAdapter(URI.create(psp.baseUrl()), timeout, PSP_KEY);
    }

    /** Nobody listens on port 9: the connection is refused before anything is sent. */
    private SimulatedCardPspAdapter deadAdapter() {
        return new SimulatedCardPspAdapter(
                URI.create("http://127.0.0.1:9"), Duration.ofMillis(400), PSP_KEY);
    }

    private void queryAnswers(
            ProviderIdempotencyReference reference, String status, String pspReference) {
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + reference.value(),
                200,
                "{\"status\":\"" + status + "\",\"reference\":\"" + pspReference + "\"}");
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private static int entriesReferencing(Connection app, PaymentAttemptId attempt)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?")) {
            count.setString(1, attempt.value().toString());
            try (ResultSet row = count.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private static int outboxCount(Connection app, String eventType, UUID aggregate)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event"
                                + " WHERE event_type = ? AND aggregate_id = ?")) {
            count.setString(1, eventType);
            count.setObject(2, aggregate);
            try (ResultSet row = count.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
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

    private static final class CountingRunner implements TransactionRunner {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection unitOfWork = DatabaseRoles.application()) {
                try {
                    unitOfWork.setAutoCommit(false);
                    R result = work.apply(unitOfWork);
                    unitOfWork.commit();
                    return result;
                } catch (RuntimeException failure) {
                    unitOfWork.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("transaction plumbing failed", failure);
            }
        }
    }
}
