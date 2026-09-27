package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Dispute notifications over the real chain (`P7-TSK-012`, ADR-0061 §1, §2, §6): the card
 * PSP's signed statements alone run a dispute through its stages — one row however often
 * notified ({@code INV-IDEM-04}), later stages applying the intervening ones in order, a late
 * stage changing nothing quietly and a contradiction changing nothing loudly
 * ({@code INV-LIFE-04}), the PSP's words confined behind the door ({@code INV-PAY-03}) — and
 * the read surfaces: the merchant's tenant-scoped in the statement ({@code INV-MER-01}; the
 * cross-tenant probe is the tenancy battery's row), the operator's audited per dispute.
 *
 * <p>The disputed payments are seeded raw (a captured card attempt, UUIDv7 ids as every
 * honest writer mints them, crediting a REAL wallet account) because the dispute is recorded
 * against whatever attempt the network names — the capture's own chain is P5's suites'
 * subject. <strong>What each stage's money is</strong> — the split, the balances, the bound
 * against refunds — is {@code ChargebackAccountingDatabaseTest}'s subject (`P7-TSK-013`); here
 * each flow counts its stage entries, exactly once each, by the posting keys that reference the
 * dispute. *(Until `P7-TSK-013` every flow here asserted NO journal entry referenced the
 * dispute — the stages existed and their postings were owed; those pins became these counts.)*
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("dispute notifications and their stages (P7-TSK-012)")
class DisputeNotificationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final byte[] WEBHOOK_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private MeterRegistry registry;

    private final Map<RoleName, String> sessions = new EnumMap<>(RoleName.class);

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // The card door exists only where a card provider is configured.
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // Stages from notifications alone
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a chargeback notification alone opens the dispute: one row in OUR words, the"
            + " birth on the record, DisputeOpened and ChargebackReceived once, the bytes"
            + " retained - and the chargeback posted once, its share attributed once")
    void aChargebackOpensTheDisputeFromTheNotificationAlone() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        long evidenceBefore = evidenceCount(payment.attempt());

        assertThat(deliver(notice(someEvent(), payment.operation(), reference,
                        "needs_response", "fraudulent", "1000", "EUR", 2))
                .statusCode())
                .isEqualTo(204);

        DisputeRow row = disputeRow(reference);
        assertThat(row.stage()).isEqualTo("CHARGED_BACK");
        assertThat(row.reason()).as("OUR category, never the network's code").isEqualTo("FRAUD");
        assertThat(row.attempt()).isEqualTo(payment.attempt());
        assertThat(row.chargebackMinor()).as("what the network took").isEqualTo(1000L);
        assertThat(row.chargebackCurrency()).isEqualTo("EUR");
        assertThat(history(row.id())).as("the birth is the row, not a trail move").isEmpty();
        assertThat(audits(row.id(), "payments.DisputeStageApplied")).isEqualTo(1);
        assertThat(auditSummaries(row.id()))
                .singleElement()
                .asString()
                .contains("from=-, to=CHARGED_BACK")
                .doesNotContain("needs_response")
                .doesNotContain("fraudulent");
        assertThat(events(row.id(), "payments.DisputeOpened")).isEqualTo(1);
        assertThat(events(row.id(), "payments.ChargebackReceived")).isEqualTo(1);
        assertThat(events(row.id(), "payments.DisputeResolved")).isZero();
        assertThat(eventPayloads(row.id()))
                .allSatisfy(
                        payload ->
                                assertThat(payload)
                                        .as("no amount and no network vocabulary in a fact")
                                        // Needles that cannot occur inside a UUID's hex
                                        // (the P7-TSK-008 needle class): a key name, the
                                        // decimal form, the prefixed reference.
                                        .doesNotContain("amount")
                                        .doesNotContain("10.00")
                                        .doesNotContain(reference)
                                        .doesNotContain("needs_response"));
        assertThat(evidenceCount(payment.attempt()))
                .as("the statement retained verbatim, attributed to the attempt (INV-HIST-02)")
                .isEqualTo(evidenceBefore + 1);
        assertThat(postings(row.id()))
                .as("the external fact, then the attribution - each once (P7-TSK-013)")
                .containsExactly("dispute-chargeback", "dispute-attribution");
    }

    @Test
    @DisplayName("every path through the machine runs from notifications alone, each edge on the"
            + " trail in order, the resolution announced once")
    void everyStageRunsFromNotificationsAlone() throws Exception {
        List<List<String>> journeys =
                List.of(
                        List.of("warning_needs_response", "warning_closed"),
                        List.of("warning_needs_response", "needs_response", "under_review", "won"),
                        List.of("needs_response", "lost"),
                        List.of("needs_response", "accepted"),
                        List.of("needs_response", "under_review", "lost"));
        List<List<String>> trails =
                List.of(
                        List.of("INQUIRY>CLOSED"),
                        List.of("INQUIRY>CHARGED_BACK", "CHARGED_BACK>REPRESENTED",
                                "REPRESENTED>WON"),
                        List.of("CHARGED_BACK>LOST"),
                        List.of("CHARGED_BACK>ACCEPTED"),
                        List.of("CHARGED_BACK>REPRESENTED", "REPRESENTED>LOST"));
        List<String> outcomes = List.of("CLOSED", "WON", "LOST", "ACCEPTED", "LOST");
        // The money each journey moved (P7-TSK-013): nothing for an inquiry that closed; the
        // chargeback and its attribution for every other, the win reversing both; no loss
        // entry, because an unrefunded payment's chargeback has no excess to write off.
        List<String> charged = List.of("dispute-chargeback", "dispute-attribution");
        List<List<String>> postings =
                List.of(
                        List.of(),
                        List.of("dispute-chargeback", "dispute-attribution", "dispute-won",
                                "dispute-restoration"),
                        charged,
                        charged,
                        charged);
        for (int i = 0; i < journeys.size(); i++) {
            Payment payment = capturedCardPayment(wallet());
            String reference = someDisputeReference();
            for (String stage : journeys.get(i)) {
                assertThat(deliver(notice(payment.operation(), reference, stage))
                        .statusCode())
                        .isEqualTo(204);
            }
            DisputeRow row = disputeRow(reference);
            assertThat(row.stage()).isEqualTo(outcomes.get(i));
            assertThat(history(row.id())).as("journey %s", journeys.get(i))
                    .isEqualTo(trails.get(i));
            assertThat(audits(row.id(), "payments.DisputeStageApplied"))
                    .as("one record per stage applied, the birth included")
                    .isEqualTo(trails.get(i).size() + 1);
            assertThat(events(row.id(), "payments.DisputeOpened")).isEqualTo(1);
            assertThat(events(row.id(), "payments.DisputeResolved")).isEqualTo(1);
            assertThat(eventPayloads(row.id(), "payments.DisputeResolved"))
                    .singleElement()
                    .asString()
                    .contains("\"outcome\":\"" + outcomes.get(i) + "\"");
            assertThat(events(row.id(), "payments.ChargebackReceived"))
                    .as("a chargeback is announced exactly when the stage is entered")
                    .isEqualTo(outcomes.get(i).equals("CLOSED") ? 0 : 1);
            assertThat(postings(row.id())).as("journey %s", journeys.get(i))
                    .isEqualTo(postings.get(i));
        }
    }

    @Test
    @DisplayName("ADR-0061 section 2: a WON heard first opens at CHARGED_BACK and walks through"
            + " REPRESENTED - the history shows both; a LOST on an inquiry walks through the"
            + " chargeback")
    void aLaterStageAppliesTheInterveningOnesInOrder() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        assertThat(deliver(notice(payment.operation(), reference, "won")).statusCode())
                .isEqualTo(204);
        DisputeRow row = disputeRow(reference);
        assertThat(row.stage()).isEqualTo("WON");
        assertThat(history(row.id()))
                .containsExactly("CHARGED_BACK>REPRESENTED", "REPRESENTED>WON");
        assertThat(auditSummaries(row.id()))
                .as("each intervening stage stands on the record, in order")
                .hasSize(3)
                .satisfiesExactly(
                        first -> assertThat(first).contains("from=-, to=CHARGED_BACK"),
                        second -> assertThat(second).contains("from=CHARGED_BACK, to=REPRESENTED"),
                        third -> assertThat(third).contains("from=REPRESENTED, to=WON"));
        assertThat(eventTypes(row.id()))
                .as("the facts in causal order - the monotonic UUIDv7 event ids")
                .containsExactly(
                        "payments.DisputeOpened",
                        "payments.ChargebackReceived",
                        "payments.DisputeResolved");
        // The platform applied every stage - the door's one enterSystem() site: an
        // unsolicited network statement has no session, whichever delivery won.
        assertThat(strings(
                        "SELECT DISTINCT actor_type FROM platform.audit_record"
                                + " WHERE target_id = ? AND operation = 'payments.DisputeStageApplied'",
                        row.id().toString()))
                .containsExactly("SYSTEM");
        assertThat(strings(
                        "SELECT DISTINCT actor_type FROM payments.dispute_event"
                                + " WHERE dispute_id = ?",
                        row.id()))
                .containsExactly("SYSTEM");

        Payment inquired = capturedCardPayment(wallet());
        String inquiry = someDisputeReference();
        deliver(notice(inquired.operation(), inquiry, "warning_needs_response"));
        assertThat(deliver(notice(inquired.operation(), inquiry, "lost")).statusCode())
                .isEqualTo(204);
        assertThat(history(disputeRow(inquiry).id()))
                .containsExactly("INQUIRY>CHARGED_BACK", "CHARGED_BACK>LOST");
    }

    @Test
    @DisplayName("INV-LIFE-04: a late stage changes nothing QUIETLY; a contradicting one changes"
            + " nothing LOUDLY - the first record stands and both statements are retained")
    void lateIsQuietAndAContradictionIsLoud() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        deliver(notice(payment.operation(), reference, "won"));
        UUID dispute = disputeRow(reference).id();
        List<String> trail = history(dispute);
        long audits = audits(dispute, "payments.DisputeStageApplied");
        long facts = eventTypes(dispute).size();
        long evidence = evidenceCount(payment.attempt());

        // Late: representment and the chargeback, both stages the dispute has passed.
        double unmappableBefore = webhooks("unmappable");
        double processedBefore = webhooks("processed");
        for (String late : List.of("under_review", "needs_response", "warning_needs_response")) {
            assertThat(deliver(notice(payment.operation(), reference, late))
                    .statusCode())
                    .isEqualTo(204);
        }
        assertThat(webhooks("unmappable")).as("ordering is not breakage")
                .isEqualTo(unmappableBefore);
        assertThat(webhooks("processed")).isEqualTo(processedBefore + 3);

        // Contradiction: a second outcome.
        assertThat(deliver(notice(payment.operation(), reference, "lost")).statusCode())
                .as("acknowledged: we hold the statement - never an error")
                .isEqualTo(204);
        assertThat(webhooks("unmappable"))
                .as("a second outcome is an integration break reconciliation must see")
                .isEqualTo(unmappableBefore + 1);

        assertThat(disputeRow(reference).stage()).isEqualTo("WON");
        assertThat(history(dispute)).isEqualTo(trail);
        assertThat(audits(dispute, "payments.DisputeStageApplied")).isEqualTo(audits);
        assertThat(eventTypes(dispute)).hasSize((int) facts);
        assertThat(evidenceCount(payment.attempt())).isEqualTo(evidence + 4);
    }

    @Test
    @DisplayName("the chargeback's amount ARRIVES with the chargeback: an inquiry records none,"
            + " a partial chargeback on its escalation records what was taken, a late inquiry"
            + " stating the transaction's full amount is quiet ordering, and a later statement"
            + " of ANOTHER chargeback amount moves nothing, loudly")
    void theChargebackAmountArrivesWithTheChargeback() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        deliver(notice(payment.operation(), reference, "warning_needs_response"));
        DisputeRow inquiry = disputeRow(reference);
        assertThat(inquiry.stage()).isEqualTo("INQUIRY");
        assertThat(inquiry.chargebackMinor()).as("an inquiry has taken nothing").isNull();
        assertThat(inquiry.chargebackCurrency()).isNull();

        // The network escalates and takes LESS than the transaction: 8.00 of 10.00.
        double unmappable = webhooks("unmappable");
        assertThat(deliver(notice(someEvent(), payment.operation(), reference, "needs_response",
                        "fraudulent", "800", "EUR", 2))
                .statusCode())
                .isEqualTo(204);
        DisputeRow charged = disputeRow(reference);
        assertThat(charged.stage()).isEqualTo("CHARGED_BACK");
        assertThat(charged.chargebackMinor())
                .as("the figure P7-TSK-013 will post is what was TAKEN, never the inquiry's")
                .isEqualTo(800L);

        // A late inquiry repeating the transaction's full amount: ordering, not a contradiction.
        deliver(notice(payment.operation(), reference, "warning_under_review"));
        assertThat(webhooks("unmappable")).isEqualTo(unmappable);

        // A later stage stating ANOTHER chargeback amount: a second story about money.
        deliver(notice(someEvent(), payment.operation(), reference, "won",
                "fraudulent", "900", "EUR", 2));
        assertThat(disputeRow(reference).stage()).isEqualTo("CHARGED_BACK");
        assertThat(webhooks("unmappable")).isEqualTo(unmappable + 1);

        // The same stage and amount again moves it on.
        deliver(notice(someEvent(), payment.operation(), reference, "won",
                "fraudulent", "800", "EUR", 2));
        DisputeRow won = disputeRow(reference);
        assertThat(won.stage()).isEqualTo("WON");
        assertThat(won.chargebackMinor()).isEqualTo(800L);
        assertThat(history(won.id()))
                .containsExactly(
                        "INQUIRY>CHARGED_BACK", "CHARGED_BACK>REPRESENTED", "REPRESENTED>WON");
        assertThat(postings(won.id()))
                .as("the partial chargeback posted and reversed once each - the refused"
                        + " statements posted nothing")
                .containsExactly("dispute-chargeback", "dispute-attribution", "dispute-won",
                        "dispute-restoration");
    }

    @Test
    @DisplayName("the external fact first (ADR-0061 section 4): a dispute is recorded against the"
            + " card attempt the network names WHATEVER its state - a capture still UNKNOWN to"
            + " us, a void, an authorization never captured - because the network has already"
            + " acted; nothing captured credited nobody, so the counterparty bears none of it"
            + " until a capture lands (P7-TSK-013)")
    void aDisputeIsRecordedWhateverTheAttemptsState() throws Exception {
        double unmappable = webhooks("unmappable");
        for (String state : List.of("CAPTURE_UNKNOWN", "VOIDED", "AUTHORIZED")) {
            Payment payment = cardPaymentIn(state, wallet());
            String reference = someDisputeReference();
            assertThat(deliver(notice(payment.operation(), reference, "needs_response"))
                    .statusCode())
                    .isEqualTo(204);
            DisputeRow row = disputeRow(reference);
            assertThat(row.stage()).as("recorded on a %s attempt", state)
                    .isEqualTo("CHARGED_BACK");
            assertThat(row.attempt()).isEqualTo(payment.attempt());
            assertThat(attemptStatus(payment.attempt()))
                    .as("a chargeback contests the payment; it never moves the attempt")
                    .isEqualTo(state);
            assertThat(postings(row.id()))
                    .as("the external fact alone: nothing captured credited nobody, so nothing"
                            + " is attributed and the whole chargeback rests recoverable")
                    .containsExactly("dispute-chargeback");
        }
        assertThat(webhooks("unmappable")).as("recorded, not refused").isEqualTo(unmappable);
    }

    @Test
    @DisplayName("the dispute rides the signed door: a forged dispute statement is the one 401,"
            + " and nothing is written - no row, no evidence (INV-PAY-01)")
    void aForgedDisputeStatementWritesNothing() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        long evidence = evidenceCount(payment.attempt());
        String body = notice(payment.operation(), reference, "needs_response");
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        HttpResponse<String> forged =
                send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://localhost:" + port
                                                        + "/v1/providers/payments/webhooks"))
                                .header("Content-Type", "application/json")
                                .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                                .header(WebhookSignature.SIGNATURE_HEADER, "00".repeat(32))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build());
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(disputeCount(reference)).isZero();
        assertThat(evidenceCount(payment.attempt())).isEqualTo(evidence);
    }

    @Test
    @DisplayName("a second cycle is a NEW dispute: a new reference on the same payment opens a"
            + " second row, the resolved one untouched (ADR-0061 section 2)")
    void aSecondCycleIsANewDispute() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String first = someDisputeReference();
        deliver(notice(payment.operation(), first, "needs_response"));
        deliver(notice(payment.operation(), first, "lost"));
        String second = someDisputeReference();
        assertThat(deliver(notice(payment.operation(), second, "needs_response"))
                .statusCode())
                .isEqualTo(204);
        assertThat(disputeRow(first).stage()).isEqualTo("LOST");
        assertThat(disputeRow(second).stage()).isEqualTo("CHARGED_BACK");
        assertThat(count("SELECT count(*) FROM payments.dispute WHERE attempt_id = ?",
                        payment.attempt()))
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------
    // One row however often notified
    // -----------------------------------------------------------------

    @Test
    @DisplayName("INV-IDEM-04, rank one: ten deliveries under ONE event id race - the inbox"
            + " absorbs all but one, one row, one record, one fact")
    void tenIdenticalDeliveriesHaveOneEffect() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        String body = notice("evt_same_" + UUID.randomUUID(), payment.operation(),
                reference, "needs_response", "duplicate", "1000", "EUR", 2);

        List<Integer> answers = race(10, i -> body);
        // A racer that meets the first delivery still in flight is told to redeliver (409:
        // unacknowledged); every answer is one of the two the door owes.
        assertThat(answers).allSatisfy(status -> assertThat(status).isIn(204, 409));
        // The provider's redelivery, bounded: once the first delivery has committed, the same
        // event id is SKIPPED_DUPLICATE and acknowledged.
        int redelivered = deliver(body).statusCode();
        for (int attempt = 0; redelivered == 409 && attempt < 50; attempt++) {
            redelivered = deliver(body).statusCode();
        }
        assertThat(redelivered).as("the redelivery is acknowledged").isEqualTo(204);

        DisputeRow row = disputeRow(reference);
        assertThat(disputeCount(reference)).isEqualTo(1);
        assertThat(row.reason()).isEqualTo("PROCESSING_ERROR");
        assertThat(audits(row.id(), "payments.DisputeStageApplied")).isEqualTo(1);
        assertThat(events(row.id(), "payments.DisputeOpened")).isEqualTo(1);
        assertThat(events(row.id(), "payments.ChargebackReceived")).isEqualTo(1);
        assertThat(postings(row.id()))
                .as("one financial effect (the Phase 7 gate's criterion, INV-DSP-02)")
                .containsExactly("dispute-chargeback", "dispute-attribution");
    }

    @Test
    @DisplayName("INV-IDEM-04, rank two: ten deliveries under FRESH event ids race past the"
            + " inbox - the unique reference arbitrates the opening and the locked row the"
            + " stages: one row, every statement retained")
    void tenFreshIdDeliveriesHaveOneEffect() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        long evidenceBefore = evidenceCount(payment.attempt());

        List<Integer> answers =
                race(10, i -> notice(payment.operation(), reference, "needs_response"));
        assertThat(answers).containsOnly(204);

        DisputeRow row = disputeRow(reference);
        assertThat(disputeCount(reference)).isEqualTo(1);
        assertThat(row.stage()).isEqualTo("CHARGED_BACK");
        assertThat(history(row.id())).isEmpty();
        assertThat(audits(row.id(), "payments.DisputeStageApplied")).isEqualTo(1);
        assertThat(events(row.id(), "payments.DisputeOpened")).isEqualTo(1);
        assertThat(events(row.id(), "payments.ChargebackReceived")).isEqualTo(1);
        assertThat(evidenceCount(payment.attempt()))
                .as("ten genuine statements retained")
                .isEqualTo(evidenceBefore + 10);
        assertThat(postings(row.id()))
                .as("one financial effect under ten fresh ids (INV-DSP-02)")
                .containsExactly("dispute-chargeback", "dispute-attribution");
    }

    @Test
    @DisplayName("ten racing deliveries naming DIFFERENT consistent stages converge on one row at"
            + " WON, the trail a legal walk with no stage twice, each fact once - whatever the"
            + " order")
    void aMixedStageRaceConverges() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        List<String> stages =
                List.of("warning_needs_response", "needs_response", "under_review", "won");

        List<Integer> answers =
                race(10, i -> notice(payment.operation(), reference,
                        stages.get(i % stages.size())));
        assertThat(answers).containsOnly(204);

        DisputeRow row = disputeRow(reference);
        assertThat(disputeCount(reference)).isEqualTo(1);
        assertThat(row.stage()).isEqualTo("WON");
        List<String> trail = history(row.id());
        // A legal walk from the birth stage to WON, each move from where the last one ended.
        assertThat(trail).isNotEmpty();
        assertThat(trail.get(trail.size() - 1)).isEqualTo("REPRESENTED>WON");
        for (int i = 1; i < trail.size(); i++) {
            assertThat(trail.get(i).split(">")[0]).isEqualTo(trail.get(i - 1).split(">")[1]);
        }
        assertThat(trail.get(0).split(">")[0]).isIn("INQUIRY", "CHARGED_BACK");
        assertThat(trail).doesNotHaveDuplicates();
        assertThat(audits(row.id(), "payments.DisputeStageApplied")).isEqualTo(trail.size() + 1);
        assertThat(events(row.id(), "payments.DisputeOpened")).isEqualTo(1);
        assertThat(events(row.id(), "payments.ChargebackReceived")).isEqualTo(1);
        assertThat(events(row.id(), "payments.DisputeResolved")).isEqualTo(1);
        assertThat(postings(row.id()))
                .as("each stage's money once, whatever order the racers landed in")
                .containsExactly("dispute-chargeback", "dispute-attribution", "dispute-won",
                        "dispute-restoration");
    }

    // -----------------------------------------------------------------
    // The guards and the door's totality
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the facts guard: a later statement with another amount, another payment's"
            + " operation, or a currency the payment is not in moves nothing, loudly")
    void theFrozenFactsAreGuarded() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        deliver(notice(payment.operation(), reference, "needs_response"));
        UUID dispute = disputeRow(reference).id();

        double unmappable = webhooks("unmappable");
        // Another amount: the P7-TSK-009 amount guard at the dispute.
        deliver(notice(someEvent(), payment.operation(), reference, "won",
                "fraudulent", "900", "EUR", 2));
        // Another payment's operation naming this dispute.
        Payment other = capturedCardPayment(wallet());
        deliver(notice(other.operation(), reference, "won"));
        assertThat(disputeRow(reference).stage()).isEqualTo("CHARGED_BACK");
        assertThat(disputeRow(reference).attempt()).isEqualTo(payment.attempt());
        assertThat(history(dispute)).isEmpty();
        assertThat(webhooks("unmappable")).isEqualTo(unmappable + 2);

        // The reason is the opening statement's too: a later stage re-coded by the issuer
        // advances the dispute, and the category stays what the network opened with - the
        // new code rests in the evidence, deciding nothing (it moves no money).
        deliver(notice(someEvent(), payment.operation(), reference, "under_review",
                "duplicate", "1000", "EUR", 2));
        assertThat(disputeRow(reference).stage()).isEqualTo("REPRESENTED");
        assertThat(disputeRow(reference).reason()).isEqualTo("FRAUD");
        assertThat(webhooks("unmappable")).isEqualTo(unmappable + 2);

        // A currency the payment is not in: never converted, never opened.
        String foreign = someDisputeReference();
        deliver(notice(someEvent(), payment.operation(), foreign, "needs_response",
                "fraudulent", "1000", "USD", 2));
        assertThat(disputeCount(foreign)).isZero();
        assertThat(webhooks("unmappable")).isEqualTo(unmappable + 3);
    }

    @Test
    @DisplayName("INV-PAY-03's totality at the door: an unknown stage word moves nothing, an"
            + " unknown reason is UNCATEGORISED, an amount or reference we cannot read is"
            + " unactionable - every statement retained and acknowledged")
    void theDoorIsTotal() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        long evidence = evidenceCount(payment.attempt());
        double unmappable = webhooks("unmappable");

        String unknownStage = someDisputeReference();
        assertThat(deliver(notice(payment.operation(), unknownStage, "prevented"))
                .statusCode())
                .isEqualTo(204);
        assertThat(disputeCount(unknownStage)).isZero();

        String scaleless = someDisputeReference();
        deliver(notice(someEvent(), payment.operation(), scaleless, "needs_response",
                "fraudulent", "1000", "EUR", null));
        String wrongScale = someDisputeReference();
        deliver(notice(someEvent(), payment.operation(), wrongScale, "needs_response",
                "fraudulent", "1000", "EUR", 3));
        String decimal = someDisputeReference();
        deliver(notice(someEvent(), payment.operation(), decimal, "needs_response",
                "fraudulent", "10.00", "EUR", 2));
        deliver(notice(someEvent(), payment.operation(), "not a reference!",
                "needs_response", "fraudulent", "1000", "EUR", 2));
        assertThat(disputeCount(scaleless)).isZero();
        assertThat(disputeCount(wrongScale)).isZero();
        assertThat(disputeCount(decimal)).isZero();
        assertThat(webhooks("unmappable")).isEqualTo(unmappable + 5);

        String uncategorised = someDisputeReference();
        deliver(notice(someEvent(), payment.operation(), uncategorised, "needs_response",
                "general", "1000", "EUR", 2));
        assertThat(disputeRow(uncategorised).reason())
                .as("recorded as unknown, never placed in a category")
                .isEqualTo("UNCATEGORISED");
        assertThat(evidenceCount(payment.attempt())).isEqualTo(evidence + 6);

        // A dispute naming an operation we never minted: unattributed, nothing opened.
        String stranger = someDisputeReference();
        assertThat(deliver(notice("cap-" + IDS.next(), stranger, "needs_response"))
                .statusCode())
                .isEqualTo(204);
        assertThat(disputeCount(stranger)).isZero();

        // The PSP's dispute fee (P7-TSK-013) is read as strictly as the amount: digits we
        // cannot parse, or a fee on an inquiry - a fee is charged with the funds taken - make
        // the WHOLE statement unusable rather than half-read.
        double beforeFees = webhooks("unmappable");
        String unreadableFee = someDisputeReference();
        deliver(withFee(notice(payment.operation(), unreadableFee, "needs_response"), "15.00"));
        String inquiryFee = someDisputeReference();
        deliver(withFee(notice(payment.operation(), inquiryFee, "warning_needs_response"),
                "1500"));
        assertThat(disputeCount(unreadableFee)).isZero();
        assertThat(disputeCount(inquiryFee)).isZero();
        assertThat(webhooks("unmappable")).isEqualTo(beforeFees + 2);
    }

    /** The statement with the PSP's dispute fee field added, verbatim. */
    private static String withFee(String statement, String feeMinor) {
        return statement.substring(0, statement.length() - 1)
                + ",\"feeMinor\":\"" + feeMinor + "\"}";
    }

    // -----------------------------------------------------------------
    // The read surfaces
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the merchant reads its own disputes - the listing and one with its trail -"
            + " and a wallet top-up's dispute is on no merchant's list")
    void theMerchantReadsItsOwnDisputes() throws Exception {
        Merchant merchant = merchant();
        Payment sale = capturedCardPayment(merchant.payable().value());
        String reference = someDisputeReference();
        deliver(notice(sale.operation(), reference, "needs_response"));
        deliver(notice(sale.operation(), reference, "under_review"));
        UUID dispute = disputeRow(reference).id();
        // A top-up credits a customer's wallet: disputable, but nobody's sale.
        Payment topUp = capturedCardPayment(wallet());
        String topUpReference = someDisputeReference();
        deliver(notice(topUp.operation(), topUpReference, "needs_response"));
        UUID topUpDispute = disputeRow(topUpReference).id();

        HttpResponse<String> listed = get("/v1/merchant/disputes", merchant.key());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.body())
                .contains("\"disputeId\":\"" + dispute + "\"")
                .contains("\"paymentIntentId\":\"" + sale.intent() + "\"")
                .contains("\"stage\":\"REPRESENTED\"")
                .contains("\"reason\":\"FRAUD\"")
                .contains("\"chargebackAmount\":\"10.00\"")
                .contains("\"truncated\":false")
                .doesNotContain(topUpDispute.toString())
                .as("the network's reference is reconciliation's key, not the merchant's")
                .doesNotContain(reference);

        HttpResponse<String> read = get("/v1/merchant/disputes/" + dispute, merchant.key());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body())
                .contains("\"from\":\"CHARGED_BACK\",\"to\":\"REPRESENTED\"")
                .doesNotContain(reference);
        assertThat(get("/v1/merchant/disputes/" + topUpDispute, merchant.key()).statusCode())
                .as("somebody else's dispute is the one 404")
                .isEqualTo(404);
        assertThat(get("/v1/merchant/disputes/not-a-uuid", merchant.key()).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("the merchant's listing is bounded and SAYS so: the newest 100 of 101, newest"
            + " first, with truncated=true - a client can tell it has not seen everything")
    void theMerchantListingIsBoundedAndSaysSo() throws Exception {
        Merchant merchant = merchant();
        Payment sale = capturedCardPayment(merchant.payable().value());
        List<UUID> seeded = new ArrayList<>();
        // One base from the test's clock, each dispute a second after the one before, so the
        // listing's order is the seeding's by construction. It was now() plus i seconds, one
        // database clock read per statement - and the local container's clock was measured
        // stepping back 1.7 s at once (X-TSK-005), enough to swap two neighbours.
        OffsetDateTime base =
                OffsetDateTime.ofInstant(
                        Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        try (Connection app = DatabaseRoles.application()) {
            for (int i = 0; i < 101; i++) {
                UUID dispute = IDS.next();
                // With the chargeback comes its attribution (V021, P7-TSK-013): none here -
                // 101 chargebacks on one payment could never all be the payable's, and the
                // combined bound refuses the raw writer that tried.
                execute(app,
                        "INSERT INTO payments.dispute (id, provider, provider_dispute_reference,"
                                + " attempt_id, reason, stage, chargeback_amount_minor,"
                                + " chargeback_currency, chargeback_scale,"
                                + " counterparty_share_amount_minor, counterparty_share_currency,"
                                + " counterparty_share_scale, parked_share_amount_minor,"
                                + " parked_share_currency, parked_share_scale, opened_at)"
                                + " VALUES (?, 'simulated-card', ?, ?, 'FRAUD', 'CHARGED_BACK',"
                                + " 1000, 'EUR', 2, 0, 'EUR', 2, 0, 'EUR', 2, ?)",
                        dispute, someDisputeReference(), sale.attempt(), base.plusSeconds(i));
                seeded.add(dispute);
            }
        }
        HttpResponse<String> listed = get("/v1/merchant/disputes", merchant.key());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        Matcher found = Pattern.compile("\"disputeId\":\"([^\"]+)\"").matcher(listed.body());
        while (found.find()) {
            ids.add(found.group(1));
        }
        assertThat(ids).hasSize(100);
        assertThat(ids.get(0)).as("newest first").isEqualTo(seeded.get(100).toString());
        assertThat(ids).doesNotContain(seeded.get(0).toString());
        assertThat(listed.body()).contains("\"truncated\":true");
    }

    @Test
    @DisplayName("the operator reads any dispute under DISPUTE_ADMINISTER - with the network's"
            + " reference, audited per dispute shown - and another operator population is"
            + " refused with nothing recorded")
    void theOperatorReadsUnderThePermission() throws Exception {
        Payment payment = capturedCardPayment(wallet());
        String reference = someDisputeReference();
        deliver(notice(payment.operation(), reference, "needs_response"));
        UUID dispute = disputeRow(reference).id();
        String operator = session(RoleName.LEDGER_OPERATOR);

        HttpResponse<String> read = get("/v1/operator/disputes/" + dispute, operator);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body())
                .contains("\"providerDisputeReference\":\"" + reference + "\"")
                .contains("\"attemptId\":\"" + payment.attempt() + "\"")
                .contains("\"stage\":\"CHARGED_BACK\"");
        assertThat(audits(dispute, "payments.DisputeRead")).isEqualTo(1);

        HttpResponse<String> listed =
                get("/v1/operator/payments/" + payment.intent() + "/disputes", operator);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.body()).contains("\"disputeId\":\"" + dispute + "\"");
        assertThat(audits(dispute, "payments.DisputeRead"))
                .as("every dispute shown is on the record")
                .isEqualTo(2);

        assertThat(get("/v1/operator/disputes/" + IDS.next(), operator).statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/operator/payments/" + IDS.next() + "/disputes", operator)
                .statusCode())
                .as("an unknown payment is told apart from an undisputed one")
                .isEqualTo(404);
        Payment undisputed = capturedCardPayment(wallet());
        assertThat(get("/v1/operator/payments/" + undisputed.intent() + "/disputes", operator)
                .body())
                .contains("\"disputes\":[]");

        // Least privilege: the counterparty-administering population cannot read disputes.
        String administrator = session(RoleName.MERCHANT_ADMINISTRATOR);
        assertThat(get("/v1/operator/disputes/" + dispute, administrator).statusCode())
                .isEqualTo(403);
        assertThat(get("/v1/operator/payments/" + payment.intent() + "/disputes", administrator)
                .statusCode())
                .isEqualTo(403);
        assertThat(audits(dispute, "payments.DisputeRead"))
                .as("a refusal reads nothing and records no read")
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------
    // Seeds
    // -----------------------------------------------------------------

    /** A seeded payment and the operation reference the network names it by. */
    private record Payment(UUID intent, UUID attempt, String operation) {}

    /**
     * A card payment whose attempt stands in {@code state}, seeded raw with the payload that
     * state requires (the schema suite's coherent rows); the network names it by the capture
     * reference where one exists, else by the authorization's.
     */
    private static Payment cardPaymentIn(String state, UUID creditAccount) throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        String auth = "auth-" + IDS.next();
        String capture =
                state.equals("CAPTURE_UNKNOWN") || state.equals("CAPTURED")
                        ? "cap-" + IDS.next()
                        : null;
        boolean voided = state.equals("VOIDED");
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, credit_account_id, amount_minor, currency,"
                            + " scale, status, created_at, capture_mode)"
                            + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, 'PROCESSING', now(),"
                            + " 'AUTOMATIC')",
                    intent, IDS.next(), IDS.next(), IDS.next(), creditAccount);
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " capture_reference, auth_provider_reference,"
                            + " authorized_amount_minor, authorized_currency, authorized_scale,"
                            + " void_reference, void_provider_reference, status, created_at,"
                            + " rail, interaction_model)"
                            + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, ?, ?, ?, now(), 'card',"
                            + " 'TWO_STEP')",
                    attempt, intent, auth, capture, "psp-auth-" + IDS.next(),
                    voided ? "void-" + IDS.next() : null,
                    voided ? "psp-void-" + IDS.next() : null,
                    state);
        }
        return new Payment(intent, attempt, capture != null ? capture : auth);
    }

    private static String attemptStatus(UUID attempt) throws SQLException {
        List<String> status =
                strings("SELECT status FROM payments.payment_attempt WHERE id = ?", attempt);
        assertThat(status).hasSize(1);
        return status.get(0);
    }

    /** A captured card payment of 10.00 EUR crediting {@code creditAccount}, seeded raw. */
    private static Payment capturedCardPayment(UUID creditAccount) throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        String capture = "cap-" + IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, credit_account_id, amount_minor, currency,"
                            + " scale, status, created_at, capture_mode)"
                            + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, 'SUCCEEDED', now(),"
                            + " 'AUTOMATIC')",
                    intent, IDS.next(), IDS.next(), IDS.next(), creditAccount);
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " capture_reference, auth_provider_reference,"
                            + " capture_provider_reference, authorized_amount_minor,"
                            + " authorized_currency, authorized_scale, captured_amount_minor,"
                            + " captured_currency, captured_scale, status, created_at, rail,"
                            + " interaction_model)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 1000, 'EUR', 2, 1000, 'EUR', 2,"
                            + " 'CAPTURED', now(), 'card', 'TWO_STEP')",
                    attempt, intent, "auth-" + IDS.next(), capture, "psp-auth-" + IDS.next(),
                    "psp-cap-" + IDS.next());
        }
        return new Payment(intent, attempt, capture);
    }

    /**
     * A real customer wallet ledger account, the counterparty a top-up credits (`P7-TSK-013`):
     * the chargeback's attribution posts to the payment's credit account, so a seed crediting a
     * random identifier would fail the posting's foreign key.
     */
    private UUID wallet() throws Exception {
        return asActor(
                        uow ->
                                ledgerAccountStore
                                        .createOrConverge(
                                                uow,
                                                LedgerAccount.owned(
                                                        IDS, CLOCK, AccountType.LIABILITY,
                                                        AccountPurpose.CUSTOMER_WALLET, EUR,
                                                        IDS.next()))
                                        .account()
                                        .id())
                .value();
    }

    private record Merchant(UUID id, String key, LedgerAccountId payable) {}

    /** A trading merchant with its payable and an API key (the tenancy battery's world). */
    private Merchant merchant() throws Exception {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                            + " settlement_currency, status, created_at, status_changed_at)"
                            + " VALUES (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now(),"
                            + " now())",
                    id, IDS.next());
        }
        LedgerAccountId payable =
                asActor(
                        uow ->
                                ledgerAccountStore
                                        .createOrConverge(
                                                uow,
                                                LedgerAccount.owned(
                                                        IDS, CLOCK, AccountType.LIABILITY,
                                                        AccountPurpose.MERCHANT_PAYABLE, EUR, id))
                                        .account()
                                        .id());
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + id + "/api-keys", null,
                        session(RoleName.MERCHANT_ADMINISTRATOR), true);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return new Merchant(
                id, field(issued.body(), "keyId") + "." + field(issued.body(), "secret"), payable);
    }

    /** A signed-in holder of {@code role}, created once per test. */
    private String session(RoleName role) throws Exception {
        String existing = sessions.get(role);
        if (existing != null) {
            return existing;
        }
        String login = "disputes." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration =
                post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, true);
        assertThat(registration.statusCode()).isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> authenticated =
                post("/v1/authentications",
                        "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD
                                + "\"}",
                        null, true);
        String token = field(authenticated.body(), "sessionToken");
        sessions.put(role, token);
        return token;
    }

    private <R> R asActor(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    // -----------------------------------------------------------------
    // The wire
    // -----------------------------------------------------------------

    /** A dispute statement in the simulated PSP's wire shape; a null field is left out. */
    private static String notice(
            String eventId,
            String operation,
            String dispute,
            String stage,
            String reasonCode,
            String amountMinor,
            String currency,
            Integer scale) {
        StringBuilder json =
                new StringBuilder("{\"eventId\":\"").append(eventId)
                        .append("\",\"operation\":\"").append(operation)
                        .append("\",\"status\":\"disputed\"");
        append(json, "dispute", dispute);
        append(json, "stage", stage);
        append(json, "reasonCode", reasonCode);
        append(json, "amountMinor", amountMinor);
        append(json, "currency", currency);
        if (scale != null) {
            json.append(",\"scale\":").append(scale);
        }
        return json.append("}").toString();
    }

    /** The common case: a fresh event id, a fraud chargeback's reason, the payment's 10.00. */
    private static String notice(String operation, String dispute, String stage) {
        return notice(someEvent(), operation, dispute, stage, "fraudulent", "1000", "EUR", 2);
    }

    private static void append(StringBuilder json, String name, String value) {
        if (value != null) {
            json.append(",\"").append(name).append("\":\"").append(value).append("\"");
        }
    }

    /** A legitimately signed, fresh delivery — the webhook door's own scheme. */
    private HttpResponse<String> deliver(String body) throws Exception {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        return send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port
                                                + "/v1/providers/payments/webhooks"))
                        .header("Content-Type", "application/json")
                        .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                        .header(WebhookSignature.SIGNATURE_HEADER, hmacHex(timestamp + "." + body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    /** {@code racers} deliveries released at once; the status each was answered with. */
    private List<Integer> race(int racers, java.util.function.IntFunction<String> bodies)
            throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String body = bodies.apply(i);
                answers.add(
                        pool.submit(
                                () -> {
                                    open.await();
                                    return deliver(body).statusCode();
                                }));
            }
            open.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(
            String path, String body, String token, boolean idempotencyKey) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey) {
            request.header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString());
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    // -----------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------

    /** A dispute row; the chargeback's amount and currency are null until the funds are taken. */
    private record DisputeRow(
            UUID id,
            String stage,
            String reason,
            UUID attempt,
            Long chargebackMinor,
            String chargebackCurrency) {}

    private static DisputeRow disputeRow(String reference) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id, stage, reason, attempt_id, chargeback_amount_minor,"
                                        + " chargeback_currency"
                                        + " FROM payments.dispute WHERE provider = ?"
                                        + " AND provider_dispute_reference = ?")) {
            read.setString(1, SimulatedCardPspAdapter.NAME);
            read.setString(2, reference);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("a dispute for %s", reference).isTrue();
                return new DisputeRow(
                        row.getObject("id", UUID.class),
                        row.getString("stage"),
                        row.getString("reason"),
                        row.getObject("attempt_id", UUID.class),
                        row.getObject("chargeback_amount_minor", Long.class),
                        row.getString("chargeback_currency"));
            }
        }
    }

    private static long disputeCount(String reference) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.dispute WHERE provider = ?"
                        + " AND provider_dispute_reference = ?",
                SimulatedCardPspAdapter.NAME,
                reference);
    }

    private static List<String> history(UUID dispute) throws SQLException {
        return strings(
                "SELECT from_stage || '>' || to_stage FROM payments.dispute_event"
                        + " WHERE dispute_id = ? ORDER BY id",
                dispute);
    }

    private static long audits(UUID dispute, String operation) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                        + " AND operation = ?",
                dispute.toString(),
                operation);
    }

    private static List<String> auditSummaries(UUID dispute) throws SQLException {
        return strings(
                "SELECT change_summary FROM platform.audit_record WHERE target_id = ?"
                        + " AND operation = 'payments.DisputeStageApplied' ORDER BY audit_id",
                dispute.toString());
    }

    private static long events(UUID dispute, String type) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                        + " AND event_type = ?",
                dispute,
                type);
    }

    private static List<String> eventTypes(UUID dispute) throws SQLException {
        return strings(
                "SELECT event_type FROM platform.outbox_event WHERE aggregate_id = ?"
                        + " ORDER BY event_id",
                dispute);
    }

    private static List<String> eventPayloads(UUID dispute) throws SQLException {
        return strings(
                "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                        + " WHERE aggregate_id = ? ORDER BY event_id",
                dispute);
    }

    private static List<String> eventPayloads(UUID dispute, String type) throws SQLException {
        return strings(
                "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                        + " WHERE aggregate_id = ? AND event_type = ? ORDER BY event_id",
                dispute,
                type);
    }

    private static long evidenceCount(UUID attempt) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.provider_evidence WHERE attempt_id = ?", attempt);
    }

    /**
     * The operations whose entries reference the dispute, in the order they posted (the
     * store-minted UUIDv7 entry ids): each posting key's operation name — {@code
     * dispute-chargeback}, {@code dispute-attribution}, ... — so a second effect shows as a
     * second element (`P7-TSK-013`).
     */
    private static List<String> postings(UUID dispute) throws SQLException {
        return strings(
                "SELECT split_part(substring(idempotency_scope FROM"
                        + " length('ledger.post:') + 1), ':', 1)"
                        + " FROM ledger.journal_entry WHERE reference = ? ORDER BY id",
                dispute.toString());
    }

    private double webhooks(String outcome) {
        return registry.find("finapp.payments.webhook").tag("outcome", outcome).counter().count();
    }

    private static List<String> strings(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
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

    private static String someEvent() {
        return "evt_" + UUID.randomUUID();
    }

    private static String someDisputeReference() {
        return "dp_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String field(String body, String name) {
        Matcher found = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]*)\"")
                .matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }
}
