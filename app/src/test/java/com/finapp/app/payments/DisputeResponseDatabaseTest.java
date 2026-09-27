package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.payments.DisputeActor;
import com.finapp.payments.DisputeEvidenceContent;
import com.finapp.payments.DisputeEvidenceStore;
import com.finapp.payments.DisputeId;
import com.finapp.payments.DisputeResponder;
import com.finapp.payments.DisputeResponseKind;
import com.finapp.payments.DisputeResponseOutcomes;
import com.finapp.payments.DisputeResponseResolution;
import com.finapp.payments.DisputeResponseStatus;
import com.finapp.payments.DisputeResponseStore;
import com.finapp.payments.DisputeResponses;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.JdbcDisputeStore;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Representment and dispute evidence over the real chain (`P7-TSK-014`, ADR-0061 §7): the signed
 * card door stating the chargeback and its deadline, the merchant's key and an operator's session
 * on the HTTP surfaces, the production evidence and response commands, the simulated PSP, and the
 * P7-TSK-013 accounting the network's verdict drives.
 *
 * <p>The phase's two acceptance criteria are {@link #aRepresentmentIsSubmittedAndWonEndToEnd} and
 * {@link #evidenceIsUnreadableAcrossTenants}. A response moves no stage and no money: every test
 * that answers a dispute asserts the stage is still the network's.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("representment and dispute evidence (P7-TSK-014)")
class DisputeResponseDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final byte[] WEBHOOK_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String RESPONSES = SimulatedCardPspAdapter.DISPUTE_RESPONSES_PATH;

    private static SimulatedProvider provider;
    private static String merchantAdministrator;
    private static String disputeOperator;

    @LocalServerPort private int port;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private PostingService postingService;
    @Autowired private Authorization authorization;
    @Autowired private DisputeResponseResolution resolution;
    @Autowired private MeterRegistry registry;

    // The parts a command aimed at a dead port is built from (the refused-first-send test).
    @Autowired
    @Qualifier("paymentTransactionRunner")
    private TransactionRunner transactions;

    @Autowired private IdempotentExecutor executor;
    @Autowired private DisputeStore<Connection> disputeStore;
    @Autowired private PaymentAttemptStore<Connection> attemptStore;
    @Autowired private PaymentIntentStore<Connection> intentStore;
    @Autowired private DisputeEvidenceStore<Connection> evidenceStore;
    @Autowired private DisputeResponseStore<Connection> responseStore;
    @Autowired private DisputeResponseOutcomes outcomes;
    @Autowired private ProviderEvidenceStore<Connection> providerEvidence;
    @Autowired private AuditWriter<Connection> auditWriter;

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
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
        // The sweep ages every stranded or ambiguous response at once: the suite drives sweep().
        registry.add("finapp.payments.dispute-response.sweeper.dispatched-age", () -> "PT0.001S");
        registry.add("finapp.payments.dispute-response.sweeper.unknown-age", () -> "PT0.001S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The acceptance criteria
    // -----------------------------------------------------------------

    @Test
    @DisplayName("THE ACCEPTANCE CRITERION: a representment submitted and WON end to end - the"
            + " merchant attaches evidence, answers with its key, the PSP takes it by our"
            + " reference with both documents on the wire, the stage stays the network's, and the"
            + " network's under_review and won drive P7-TSK-013's exact inverse")
    void aRepresentmentIsSubmittedAndWonEndToEnd() throws Exception {
        Merchant merchant = merchant();
        Payment sale = captured(merchant.payable());
        String reference = someDisputeReference();
        Instant respondBy = Instant.now(CLOCK).plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.SECONDS);
        java.util.Map<com.finapp.payments.DisputeStage, Long> stagesBefore = stages();
        assertThat(deliver(chargeback(sale, reference, "needs_response", respondBy)).statusCode())
                .isEqualTo(204);
        UUID dispute = disputeId(reference);
        assertThat(instant("SELECT respond_by FROM payments.dispute WHERE id = ?", dispute))
                .isEqualTo(respondBy);
        // The stage gauge's read (P7-TSK-015): one more dispute standing charged back.
        assertThat(standing(com.finapp.payments.DisputeStage.CHARGED_BACK, stagesBefore))
                .isEqualTo(1);

        byte[] receipt = ("receipt for order 1042 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        byte[] delivered = ("signed delivery note " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> first =
                post(merchantPath(dispute, "evidence"), evidence("RECEIPT", "PDF", receipt),
                        merchant.key(), null);
        HttpResponse<String> second =
                post(merchantPath(dispute, "evidence"),
                        evidence("PROOF_OF_DELIVERY", "PNG", delivered), merchant.key(), null);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        double submittedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(
                        registry, SimulatedCardPspAdapter.RAIL.id(), "dispute_response",
                        "submitted");

        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        HttpResponse<String> answered =
                post(merchantPath(dispute, "representment"), null, merchant.key(), someKey());
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(201);
        assertThat(field(answered.body(), "status")).isEqualTo("SUBMITTED");
        UUID response = UUID.fromString(field(answered.body(), "responseId"));
        // The answer's judgement counted where it was written, on the disputed payment's own
        // rail, once (P7-TSK-015) - never at the door.
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(
                                registry, SimulatedCardPspAdapter.RAIL.id(), "dispute_response",
                                "submitted")
                        - submittedBefore)
                .isEqualTo(1);

        // The wire: one send, OUR reference in the header, both documents labelled, the network's
        // dispute named, the answer in the PSP's word.
        assertThat(provider.requestCount(RESPONSES)).isEqualTo(1);
        String ours = oneString(
                "SELECT provider_idempotency_reference FROM payments.dispute_response WHERE id = ?",
                response);
        assertThat(provider.headerValues(RESPONSES, SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER))
                .containsExactly(ours);
        assertThat(provider.bodyValues(RESPONSES).get(0))
                .contains("\"dispute\":\"" + reference + "\"")
                .contains("\"answer\":\"represent\"")
                .contains("\"kind\":\"receipt\"")
                .contains("\"kind\":\"proof_of_delivery\"")
                .contains("\"contentType\":\"application/pdf\"")
                .contains(Base64.getEncoder().encodeToString(receipt))
                .contains(Base64.getEncoder().encodeToString(delivered));
        assertThat(oneString("SELECT provider_reference FROM payments.dispute_response WHERE id = ?",
                        response))
                .as("the PSP's submission reference, the reconciliation key")
                .isEqualTo("psp_dr-" + ours);

        // It moved no stage and no money.
        assertThat(stage(dispute)).isEqualTo("CHARGED_BACK");
        assertThat(operations(dispute)).containsExactly("dispute-chargeback", "dispute-attribution");

        // The record: two uploads, the act, the transmission, the outcome - and the fact.
        assertThat(audits(dispute, "payments.DisputeEvidenceUploaded")).isEqualTo(2);
        assertThat(audits(dispute, "payments.DisputeResponseDispatched")).isEqualTo(1);
        assertThat(audits(response, "payments.DisputeEvidenceTransmitted")).isEqualTo(1);
        assertThat(audits(response, "payments.DisputeResponseOutcomeApplied")).isEqualTo(1);
        assertThat(eventPayload(response, "payments.DisputeResponseSubmitted"))
                .contains("\"kind\":\"REPRESENTMENT\"")
                .contains("\"disputeId\":\"" + dispute + "\"")
                .doesNotContain(ours);

        // The network decides: represented, then WON - the chargeback's exact inverse, and the
        // merchant's payable whole again.
        assertThat(deliver(chargeback(sale, reference, "under_review", null)).statusCode())
                .isEqualTo(204);
        assertThat(deliver(chargeback(sale, reference, "won", null)).statusCode()).isEqualTo(204);
        assertThat(stage(dispute)).isEqualTo("WON");
        // The stage gauge's read follows the dispute: an outcome gained, the workload given back.
        assertThat(standing(com.finapp.payments.DisputeStage.WON, stagesBefore)).isEqualTo(1);
        assertThat(standing(com.finapp.payments.DisputeStage.CHARGED_BACK, stagesBefore)).isZero();
        assertThat(standing(com.finapp.payments.DisputeStage.REPRESENTED, stagesBefore)).isZero();
        assertThat(registry.find("finapp.payments.dispute").tag("stage", "won").gauge().value())
                .as("the WIRED stage gauge reads the real schema: a number, never NaN")
                .isNotNaN();
        assertThat(operations(dispute))
                .containsExactly("dispute-chargeback", "dispute-attribution", "dispute-won",
                        "dispute-restoration");
        assertThat(balance(merchant.payable())).as("captured, charged back, won back").isEqualTo(1000);

        String view = get("/v1/merchant/disputes/" + dispute, merchant.key()).body();
        assertThat(view)
                .contains("\"stage\":\"WON\"")
                .contains("\"respondBy\":\"" + respondBy + "\"")
                .contains("\"status\":\"SUBMITTED\"")
                .contains(field(first.body(), "evidenceId"))
                .contains(field(second.body(), "evidenceId"))
                .as("a dispute view lists documents, never their content")
                .doesNotContain(Base64.getEncoder().encodeToString(receipt));
    }

    @Test
    @DisplayName("M7.6's other half - a representment LOST: the answer SUBMITTED, then the"
            + " network's under_review and lost; P7-TSK-013 writes off only the excess (none"
            + " here), the chargeback stands against the payable, and the dispute takes no"
            + " further answer")
    void aRepresentmentLostStandsAsTheNetworksWord() throws Exception {
        Merchant merchant = merchant();
        Payment sale = captured(merchant.payable());
        String reference = someDisputeReference();
        assertThat(deliver(chargeback(sale, reference, "needs_response", future())).statusCode())
                .isEqualTo(204);
        UUID dispute = disputeId(reference);
        post(merchantPath(dispute, "evidence"), evidence("RECEIPT", "PDF", bytes("the receipt")),
                merchant.key(), null);
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        HttpResponse<String> answered =
                post(merchantPath(dispute, "representment"), null, merchant.key(), someKey());
        assertThat(field(answered.body(), "status")).isEqualTo("SUBMITTED");

        assertThat(deliver(chargeback(sale, reference, "under_review", null)).statusCode())
                .isEqualTo(204);
        assertThat(deliver(chargeback(sale, reference, "lost", null)).statusCode()).isEqualTo(204);

        assertThat(stage(dispute)).isEqualTo("LOST");
        assertThat(operations(dispute))
                .as("the whole chargeback was the counterparty's: no excess to write off")
                .containsExactly("dispute-chargeback", "dispute-attribution");
        assertThat(balance(merchant.payable())).as("the chargeback stands against the payable")
                .isZero();
        assertThat(status(UUID.fromString(field(answered.body(), "responseId"))))
                .as("the answer is what the PSP took; the verdict is the network's")
                .isEqualTo("SUBMITTED");
        assertRefused(post(merchantPath(dispute, "acceptance"), null, merchant.key(), someKey()),
                409, "payments.DisputeNotRespondable");
    }

    @Test
    @DisplayName("malformed evidence is refused at the boundary with nothing written: a kind outside"
            + " the closed set, content that is not base64, an empty and an oversized document")
    void malformedEvidenceIsRefusedAtTheBoundary() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        long before = count("SELECT count(*) FROM payments.dispute_evidence");

        HttpResponse<String> foreignKind =
                post(merchantPath(dispute, "evidence"),
                        "{\"kind\":\"SELFIE\",\"contentType\":\"PDF\",\"content\":\"cGRm\"}",
                        merchant.key(), null);
        assertThat(foreignKind.statusCode()).as(foreignKind.body()).isEqualTo(400);
        HttpResponse<String> notBase64 =
                post(merchantPath(dispute, "evidence"),
                        "{\"kind\":\"RECEIPT\",\"contentType\":\"PDF\",\"content\":\"***\"}",
                        merchant.key(), null);
        assertThat(notBase64.body()).contains("api.ValidationFailed");
        HttpResponse<String> empty =
                post(merchantPath(dispute, "evidence"),
                        "{\"kind\":\"RECEIPT\",\"contentType\":\"PDF\",\"content\":\"\"}",
                        merchant.key(), null);
        assertThat(empty.body()).contains("api.ValidationFailed");
        HttpResponse<String> oversized =
                post(merchantPath(dispute, "evidence"),
                        evidence("RECEIPT", "PDF", new byte[DisputeEvidenceContent.MAX_BYTES + 1]),
                        merchant.key(), null);
        assertThat(oversized.body()).contains("api.ValidationFailed");
        assertThat(count("SELECT count(*) FROM payments.dispute_evidence")).isEqualTo(before);
    }

    @Test
    @DisplayName("THE ACCEPTANCE CRITERION: evidence is unreadable across tenants - another"
            + " merchant's key reads, adds to and answers nothing of this dispute, each the same"
            + " 404 as an unknown one, and nothing is written")
    void evidenceIsUnreadableAcrossTenants() throws Exception {
        Merchant owner = merchant();
        Merchant stranger = merchant();
        UUID dispute = chargedBack(owner.payable(), future());
        String document =
                field(post(merchantPath(dispute, "evidence"),
                                evidence("RECEIPT", "PDF", bytes("the owner's receipt")),
                                owner.key(), null)
                        .body(),
                        "evidenceId");
        long audited = count("SELECT count(*) FROM platform.audit_record");
        long documents = count("SELECT count(*) FROM payments.dispute_evidence");
        long responses = count("SELECT count(*) FROM payments.dispute_response");

        List<HttpResponse<String>> foreign =
                List.of(
                        get(merchantPath(dispute, "evidence/" + document), stranger.key()),
                        post(merchantPath(dispute, "evidence"),
                                evidence("OTHER", "PDF", bytes("a stranger's page")),
                                stranger.key(), null),
                        post(merchantPath(dispute, "representment"), null, stranger.key(),
                                someKey()),
                        post(merchantPath(dispute, "acceptance"), null, stranger.key(),
                                someKey()));
        UUID nothing = IDS.next();
        List<HttpResponse<String>> unknown =
                List.of(
                        get(merchantPath(nothing, "evidence/" + IDS.next()), stranger.key()),
                        post(merchantPath(nothing, "evidence"),
                                evidence("OTHER", "PDF", bytes("a stranger's page")),
                                stranger.key(), null),
                        post(merchantPath(nothing, "representment"), null, stranger.key(),
                                someKey()),
                        post(merchantPath(nothing, "acceptance"), null, stranger.key(),
                                someKey()));
        for (int i = 0; i < foreign.size(); i++) {
            assertThat(foreign.get(i).statusCode()).as(foreign.get(i).body()).isEqualTo(404);
            assertThat(normalised(foreign.get(i).body()))
                    .as("another tenant's dispute is indistinguishable from none")
                    .isEqualTo(normalised(unknown.get(i).body()));
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record")).isEqualTo(audited);
        assertThat(count("SELECT count(*) FROM payments.dispute_evidence")).isEqualTo(documents);
        assertThat(count("SELECT count(*) FROM payments.dispute_response")).isEqualTo(responses);
        assertThat(provider.requestCount(RESPONSES)).isZero();
        // The positive control: the owner reads its own document.
        assertThat(get(merchantPath(dispute, "evidence/" + document), owner.key()).statusCode())
                .isEqualTo(200);
    }

    // -----------------------------------------------------------------
    // Evidence at rest and on the record (INV-DSP-03)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("INV-DSP-03: evidence rests as AES-GCM ciphertext under its own key - no"
            + " plaintext anywhere in the row, the tag's arithmetic exact, the checksum the"
            + " plaintext's - and every content read, the merchant's and the operator's, is on the"
            + " record, while a guessed identifier records nothing")
    void evidenceRestsAsCiphertextAndEveryReadIsOnTheRecord() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        byte[] plaintext = bytes("card ending 4242, customer Ada Lovelace, delivered to door");
        String document =
                field(post(merchantPath(dispute, "evidence"), evidence("RECEIPT", "PDF", plaintext),
                                merchant.key(), null)
                        .body(),
                        "evidenceId");

        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT content_ciphertext, content_nonce, key_version, checksum_sha256,"
                                + " content_length FROM payments.dispute_evidence WHERE id = ?")) {
            read.setObject(1, UUID.fromString(document));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                byte[] ciphertext = row.getBytes(1);
                assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1))
                        .doesNotContain("4242")
                        .doesNotContain("Lovelace");
                assertThat(ciphertext).hasSize(plaintext.length + 16);
                assertThat(row.getBytes(2)).hasSize(12);
                assertThat(row.getInt(3)).isEqualTo(1);
                assertThat(row.getBytes(4))
                        .isEqualTo(MessageDigest.getInstance("SHA-256").digest(plaintext));
                assertThat(row.getInt(5)).isEqualTo(plaintext.length);
            }
        }

        HttpResponse<String> mine = get(merchantPath(dispute, "evidence/" + document), merchant.key());
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(field(mine.body(), "content"))
                .isEqualTo(Base64.getEncoder().encodeToString(plaintext));
        HttpResponse<String> operators =
                get(operatorPath(dispute, "evidence/" + document), operator());
        assertThat(operators.statusCode()).as(operators.body()).isEqualTo(200);

        List<String> readers =
                strings("SELECT actor_type FROM platform.audit_record WHERE target_id = ?"
                                + " AND operation = 'payments.DisputeEvidenceRead' ORDER BY audit_id",
                        document);
        // A session identity is a CUSTOMER actor whatever its roles; the operator's own
        // identity is the actor id.
        assertThat(readers).containsExactly("MERCHANT", "CUSTOMER");

        long before = count("SELECT count(*) FROM platform.audit_record"
                + " WHERE operation = 'payments.DisputeEvidenceRead'");
        assertThat(get(merchantPath(dispute, "evidence/" + IDS.next()), merchant.key()).statusCode())
                .isEqualTo(404);
        assertThat(count("SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'payments.DisputeEvidenceRead'"))
                .as("a guessed identifier puts nothing on the record")
                .isEqualTo(before);
        // The same bytes again converge on the one document, and say so.
        HttpResponse<String> again =
                post(merchantPath(dispute, "evidence"), evidence("OTHER", "JPEG", plaintext),
                        merchant.key(), null);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(field(again.body(), "evidenceId")).isEqualTo(document);
        assertThat(count("SELECT count(*) FROM payments.dispute_evidence WHERE dispute_id = ?",
                        dispute))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a tampered document is never served and never sent: GCM refuses it on read, and"
            + " a representment carrying it rolls back whole - nothing claimed, written or sent")
    void aTamperedDocumentIsNeverServedOrSent() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        String document =
                field(post(merchantPath(dispute, "evidence"),
                                evidence("RECEIPT", "PDF", bytes("an honest receipt")),
                                merchant.key(), null)
                        .body(),
                        "evidenceId");
        // The table's owner flips one ciphertext byte: the application role may not update it.
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement tamper = migrator.prepareStatement(
                        "UPDATE payments.dispute_evidence SET content_ciphertext ="
                                + " overlay(content_ciphertext placing"
                                + " decode(lpad(to_hex(get_byte(content_ciphertext, 0) # 1), 2,"
                                + " '0'), 'hex') from 1 for 1) WHERE id = ?")) {
            tamper.setObject(1, UUID.fromString(document));
            assertThat(tamper.executeUpdate()).isEqualTo(1);
        }

        assertThat(get(merchantPath(dispute, "evidence/" + document), merchant.key()).statusCode())
                .isEqualTo(500);
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        HttpResponse<String> answered =
                post(merchantPath(dispute, "representment"), null, merchant.key(), someKey());
        assertThat(answered.statusCode()).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM payments.dispute_response WHERE dispute_id = ?",
                        dispute))
                .isZero();
        assertThat(provider.requestCount(RESPONSES)).as("nothing unverified ever leaves").isZero();
    }

    // -----------------------------------------------------------------
    // Refusals (INV-LIFE-04) - nothing written, nothing sent
    // -----------------------------------------------------------------

    @Test
    @DisplayName("INV-LIFE-04: every refusal writes nothing and sends nothing - past the deadline,"
            + " after resolution, at an inquiry, a representment with no evidence, a second answer"
            + " while one is live, an upload after the answer, the sixth document")
    void everyRefusalWritesNothingAndSendsNothing() throws Exception {
        Merchant merchant = merchant();
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");

        // Past the network's deadline: the platform refuses its own late dispatch.
        UUID late = chargedBack(merchant.payable(), Instant.now(CLOCK).minus(Duration.ofHours(1)));
        assertRefused(post(merchantPath(late, "evidence"), evidence("RECEIPT", "PDF", bytes("late")),
                merchant.key(), null), 409, "payments.DisputeDeadlinePassed");
        assertRefused(post(merchantPath(late, "acceptance"), null, merchant.key(), someKey()),
                409, "payments.DisputeDeadlinePassed");

        // After resolution: a LOST dispute takes no answer.
        Payment lostSale = captured(merchant.payable());
        String lostReference = someDisputeReference();
        deliver(chargeback(lostSale, lostReference, "needs_response", future()));
        deliver(chargeback(lostSale, lostReference, "lost", null));
        UUID lost = disputeId(lostReference);
        assertRefused(post(merchantPath(lost, "representment"), null, merchant.key(), someKey()),
                409, "payments.DisputeNotRespondable");
        assertRefused(post(merchantPath(lost, "evidence"), evidence("RECEIPT", "PDF", bytes("lost")),
                merchant.key(), null), 409, "payments.DisputeNotRespondable");

        // At an inquiry: nothing to contest yet.
        Payment asked = captured(merchant.payable());
        String inquiryReference = someDisputeReference();
        deliver(chargeback(asked, inquiryReference, "warning_needs_response", future()));
        UUID inquiry = disputeId(inquiryReference);
        assertThat(stage(inquiry)).isEqualTo("INQUIRY");
        assertThat(count("SELECT count(*) FROM payments.dispute WHERE id = ? AND respond_by IS NULL",
                        inquiry))
                .as("an inquiry's own answer-by date is dropped at the door")
                .isEqualTo(1);
        assertRefused(post(merchantPath(inquiry, "acceptance"), null, merchant.key(), someKey()),
                409, "payments.DisputeNotRespondable");

        // A representment with nothing to carry.
        UUID bare = chargedBack(merchant.payable(), future());
        assertRefused(post(merchantPath(bare, "representment"), null, merchant.key(), someKey()),
                422, "payments.DisputeEvidenceRequired");

        // The sixth document.
        for (int i = 0; i < 5; i++) {
            assertThat(post(merchantPath(bare, "evidence"),
                                    evidence("OTHER", "PDF", bytes("page " + i)), merchant.key(),
                                    null)
                            .statusCode())
                    .isEqualTo(201);
        }
        assertRefused(post(merchantPath(bare, "evidence"), evidence("OTHER", "PDF", bytes("page 6")),
                merchant.key(), null), 422, "payments.DisputeEvidenceLimitReached");

        // A live answer freezes the dispute: a second answer and a new document both refused.
        assertThat(post(merchantPath(bare, "representment"), null, merchant.key(), someKey())
                        .statusCode())
                .isEqualTo(201);
        assertRefused(post(merchantPath(bare, "acceptance"), null, merchant.key(), someKey()),
                409, "payments.DisputeAlreadyAnswered");
        assertRefused(post(merchantPath(bare, "evidence"),
                        evidence("OTHER", "PDF", bytes("after the answer")), merchant.key(), null),
                409, "payments.DisputeAlreadyAnswered");
        assertThat(count("SELECT count(*) FROM payments.dispute_evidence WHERE dispute_id = ?",
                        bare))
                .as("the evidence set froze with the answer")
                .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM payments.dispute_response WHERE dispute_id = ?",
                        bare))
                .isEqualTo(1);
        assertThat(provider.requestCount(RESPONSES)).as("only the one accepted answer was sent")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.dispute_response WHERE dispute_id IN"
                        + " (?, ?, ?)", late, lost, inquiry))
                .isZero();
        assertThat(count("SELECT count(*) FROM payments.dispute_evidence WHERE dispute_id IN"
                        + " (?, ?, ?)", late, lost, inquiry))
                .isZero();
    }

    // -----------------------------------------------------------------
    // Idempotency and races (INV-PAY-04, INV-IDEM-01/-03)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("INV-IDEM-01/-03: a retried key replays the recorded judgement with no second"
            + " send; the same key for another answer is the conflict")
    void aRetriedKeyReplaysAndAReusedKeyConflicts() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        String key = someKey();

        HttpResponse<String> first = post(merchantPath(dispute, "acceptance"), null, merchant.key(), key);
        HttpResponse<String> retried = post(merchantPath(dispute, "acceptance"), null, merchant.key(), key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(field(retried.body(), "responseId")).isEqualTo(field(first.body(), "responseId"));
        assertThat(field(retried.body(), "status")).isEqualTo("SUBMITTED");
        assertThat(provider.requestCount(RESPONSES)).isEqualTo(1);
        assertThat(provider.bodyValues(RESPONSES).get(0))
                .contains("\"answer\":\"accept\"")
                .contains("\"evidence\":[]");

        HttpResponse<String> reused =
                post(merchantPath(dispute, "representment"), null, merchant.key(), key);
        assertThat(reused.statusCode()).as(reused.body()).isEqualTo(409);
        assertThat(provider.requestCount(RESPONSES)).isEqualTo(1);

        // KEYED PER RESPONDER: another merchant choosing the same key string answers its own
        // dispute - a second claim, a second send, never the first merchant's judgement replayed.
        Merchant other = merchant();
        UUID theirs = chargedBack(other.payable(), future());
        HttpResponse<String> own = post(merchantPath(theirs, "acceptance"), null, other.key(), key);
        assertThat(own.statusCode()).as(own.body()).isEqualTo(201);
        assertThat(field(own.body(), "responseId")).isNotEqualTo(field(first.body(), "responseId"));
        assertThat(provider.requestCount(RESPONSES)).isEqualTo(2);
    }

    @Test
    @DisplayName("ten racing answers under ten keys produce ONE response and ONE send - the attempt"
            + " and dispute locks serialise them and the one-live index binds every writer")
    void tenRacingAnswersProduceOneResponseAndOneSend() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        post(merchantPath(dispute, "evidence"), evidence("RECEIPT", "PDF", bytes("the receipt")),
                merchant.key(), null);
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");

        List<Integer> answers =
                race(10, i -> post(merchantPath(dispute, "representment"), null, merchant.key(),
                        someKey()).statusCode());

        assertThat(answers).filteredOn(status -> status == 201).hasSize(1);
        assertThat(answers).filteredOn(status -> status == 409).hasSize(9);
        assertThat(count("SELECT count(*) FROM payments.dispute_response WHERE dispute_id = ?",
                        dispute))
                .isEqualTo(1);
        assertThat(provider.requestCount(RESPONSES)).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Ambiguity and its resolution (INV-LIFE-03, INV-PAY-04)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("INV-LIFE-03: a lost answer is honestly UNKNOWN until the sweep asks the PSP by"
            + " our reference - SUBMITTED then, and a second sweep converges quietly")
    void aLostAnswerIsUnknownUntilTheSweepResolvesIt() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        provider.receivesTheRequestThenLosesTheResponse(RESPONSES);
        double unknownBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(
                        registry, SimulatedCardPspAdapter.RAIL.id(), "dispute_response", "unknown");
        double submittedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(
                        registry, SimulatedCardPspAdapter.RAIL.id(), "dispute_response",
                        "submitted");
        long stuckBefore = stuckAnswers(Duration.ofMinutes(10)).active();

        HttpResponse<String> answered =
                post(merchantPath(dispute, "acceptance"), null, merchant.key(), someKey());
        assertThat(field(answered.body(), "status")).isEqualTo("UNKNOWN");
        UUID response = UUID.fromString(field(answered.body(), "responseId"));
        // INV-LIFE-03's age metric sees it (P7-TSK-015), and the honest unknown IS a judgement.
        assertThat(stuckAnswers(Duration.ofMinutes(10)).active()).isEqualTo(stuckBefore + 1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(
                                registry, SimulatedCardPspAdapter.RAIL.id(), "dispute_response",
                                "unknown")
                        - unknownBefore)
                .isEqualTo(1);
        String ours = oneString(
                "SELECT provider_idempotency_reference FROM payments.dispute_response WHERE id = ?",
                response);

        provider.succeedsWith(SimulatedCardPspAdapter.OPERATIONS_PATH + ours, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_dr-late\"}");
        resolution.sweep();
        assertThat(status(response)).isEqualTo("SUBMITTED");
        assertThat(provider.requestCount(RESPONSES)).as("resolved by query, never re-sent")
                .isEqualTo(1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(
                                registry, SimulatedCardPspAdapter.RAIL.id(), "dispute_response",
                                "submitted")
                        - submittedBefore)
                .as("the sweep's resolution counted once, where it was written")
                .isEqualTo(1);
        long applied = audits(response, "payments.DisputeResponseOutcomeApplied");
        resolution.sweep();
        assertThat(audits(response, "payments.DisputeResponseOutcomeApplied")).isEqualTo(applied);
        assertThat(stage(dispute)).isEqualTo("CHARGED_BACK");
    }

    @Test
    @DisplayName("INV-PAY-04: a response the PSP says it never saw is re-sent by the sweep with the"
            + " SAME reference and the SAME documents, the transmission on the record as the"
            + " platform's")
    void anUnrecognisedAnswerIsResentWithTheSameReference() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        byte[] receipt = bytes("the receipt the PSP lost");
        post(merchantPath(dispute, "evidence"), evidence("RECEIPT", "PDF", receipt), merchant.key(),
                null);
        provider.receivesTheRequestThenLosesTheResponse(RESPONSES);
        HttpResponse<String> answered =
                post(merchantPath(dispute, "representment"), null, merchant.key(), someKey());
        UUID response = UUID.fromString(field(answered.body(), "responseId"));
        String ours = oneString(
                "SELECT provider_idempotency_reference FROM payments.dispute_response WHERE id = ?",
                response);

        provider.reset();
        provider.succeedsWith(SimulatedCardPspAdapter.OPERATIONS_PATH + ours, 200,
                "{\"status\":\"unrecognised\"}");
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        resolution.sweep();

        assertThat(status(response)).isEqualTo("SUBMITTED");
        assertThat(provider.headerValues(RESPONSES, SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER))
                .containsExactly(ours);
        assertThat(provider.bodyValues(RESPONSES).get(0))
                .contains(Base64.getEncoder().encodeToString(receipt));
        assertThat(strings("SELECT actor_type FROM platform.audit_record WHERE target_id = ?"
                                + " AND operation = 'payments.DisputeEvidenceTransmitted'"
                                + " ORDER BY audit_id",
                        response.toString()))
                .as("every transmission on the record: the merchant's send, the platform's re-send")
                .containsExactly("MERCHANT", "SYSTEM");
    }

    @Test
    @DisplayName("ADR-0057 section 3: a refused connection on the FIRST send proves nothing was"
            + " sent - FAILED(PROVIDER_UNAVAILABLE), and the dispute is free for a new answer")
    void aRefusedFirstSendFailsAndFreesTheDispute() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        DisputeResponses deadPort =
                new DisputeResponses(
                        transactions, executor, disputeStore, attemptStore, intentStore,
                        ledgerAccountStore, evidenceStore, responseStore,
                        new SimulatedCardPspAdapter(
                                URI.create("http://127.0.0.1:9"), Duration.ofSeconds(2),
                                new byte[32]),
                        outcomes, providerEvidence, auditWriter, IDS, CLOCK);
        DisputeResponses.ResponseResult refused =
                asMerchant(merchant, () -> deadPort.respond(
                        new DisputeActor.Counterparty(Set.of(merchant.payable())),
                        DisputeId.of(dispute), DisputeResponseKind.ACCEPTANCE, someKey()));
        assertThat(refused.status()).isEqualTo(DisputeResponseStatus.FAILED);
        assertThat(oneString("SELECT failure_reason FROM payments.dispute_response WHERE id = ?",
                        refused.response().value()))
                .isEqualTo("PROVIDER_UNAVAILABLE");

        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        HttpResponse<String> again =
                post(merchantPath(dispute, "acceptance"), null, merchant.key(), someKey());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(field(again.body(), "status")).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("THE GATE'S OWN FIND (restart): a flight that dies between its committed dispatch"
            + " and the PSP call leaves the response DISPATCHED with nothing on the wire - the sweep"
            + " asks by our reference, hears it was never seen, and sends the SAME request once")
    void aCrashedFlightsDispatchIsFinishedByTheSweep() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());
        byte[] receipt = bytes("the receipt a dead instance never sent");
        post(merchantPath(dispute, "evidence"), evidence("RECEIPT", "PDF", receipt), merchant.key(),
                null);
        DisputeResponder dies =
                new DisputeResponder() {
                    @Override
                    public String providerName() {
                        return SimulatedCardPspAdapter.NAME;
                    }

                    @Override
                    public ProviderAnswer respond(DisputeResponseRequest request) {
                        throw new IllegalStateException("the instance died mid-call");
                    }

                    @Override
                    public QueryAnswer query(ProviderIdempotencyReference ourReference) {
                        throw new IllegalStateException("never asked");
                    }
                };
        DisputeResponses crashing =
                new DisputeResponses(
                        transactions, executor, disputeStore, attemptStore, intentStore,
                        ledgerAccountStore, evidenceStore, responseStore, dies, outcomes,
                        providerEvidence, auditWriter, IDS, CLOCK);
        assertThatThrownBy(
                        () ->
                                asMerchant(merchant, () -> crashing.respond(
                                        new DisputeActor.Counterparty(Set.of(merchant.payable())),
                                        DisputeId.of(dispute), DisputeResponseKind.REPRESENTMENT,
                                        someKey())))
                .isInstanceOf(IllegalStateException.class);
        UUID response =
                UUID.fromString(oneString(
                        "SELECT id FROM payments.dispute_response WHERE dispute_id = ?", dispute));
        assertThat(status(response)).as("the reconcilable state, never a fabricated outcome")
                .isEqualTo("DISPATCHED");
        assertThat(provider.requestCount(RESPONSES)).isZero();
        String ours = oneString(
                "SELECT provider_idempotency_reference FROM payments.dispute_response WHERE id = ?",
                response);

        provider.succeedsWith(SimulatedCardPspAdapter.OPERATIONS_PATH + ours, 200,
                "{\"status\":\"unrecognised\"}");
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        resolution.sweep();

        assertThat(status(response)).isEqualTo("SUBMITTED");
        assertThat(provider.headerValues(RESPONSES, SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER))
                .containsExactly(ours);
        assertThat(provider.bodyValues(RESPONSES).get(0))
                .contains(Base64.getEncoder().encodeToString(receipt));
        assertThat(stage(dispute)).isEqualTo("CHARGED_BACK");
    }

    @Test
    @DisplayName("THE GATE'S OWN FIND (the bound under concurrency): ten racing uploads of ten"
            + " different documents admit EXACTLY the five one answer may carry - the count is"
            + " judged under the dispute's lock, never read-then-written")
    void tenRacingUploadsAdmitExactlyTheBound() throws Exception {
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), future());

        List<Integer> answers =
                race(10, i -> post(merchantPath(dispute, "evidence"),
                                evidence("OTHER", "PDF", bytes("racing page " + i)),
                                merchant.key(), null)
                        .statusCode());

        assertThat(answers).filteredOn(status -> status == 201)
                .hasSize(DisputeEvidenceContent.MAX_PER_DISPUTE);
        assertThat(answers).filteredOn(status -> status == 422)
                .hasSize(10 - DisputeEvidenceContent.MAX_PER_DISPUTE);
        assertThat(count("SELECT count(*) FROM payments.dispute_evidence WHERE dispute_id = ?",
                        dispute))
                .isEqualTo(DisputeEvidenceContent.MAX_PER_DISPUTE);
    }

    // -----------------------------------------------------------------
    // The deadline, the operator, the door
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the stuck-answer reading counts what the sweep would ask about - an UNKNOWN answer"
            + " and a dispatch past the bound, never a dispatch still mid-question - and the wired"
            + " gauges read it (INV-LIFE-03's age metric, P7-TSK-015)")
    void theStuckAnswerReadingCountsWhatTheSweepWould() throws Exception {
        Merchant merchant = merchant();
        Duration bound = Duration.ofMinutes(10);
        long before = stuckAnswers(bound).active();
        // An hour dwarfs any clock skew between this JVM and the database: only the bound decides.
        Instant anHourAgo = Instant.now(CLOCK).minus(Duration.ofHours(1));
        List<UUID> seeded =
                List.of(
                        seedAnswer(chargedBack(merchant.payable(), future()), anHourAgo, false),
                        seedAnswer(chargedBack(merchant.payable(), future()), Instant.now(CLOCK),
                                false),
                        seedAnswer(chargedBack(merchant.payable(), future()), anHourAgo, true));
        try {
            com.finapp.payments.PaymentAttemptStore.UnknownReading reading = stuckAnswers(bound);
            assertThat(reading.active())
                    .as("the overdue dispatch and the UNKNOWN answer; not the one mid-question")
                    .isEqualTo(before + 2);
            assertThat(reading.oldestAgeSeconds())
                    .isGreaterThanOrEqualTo(Duration.ofMinutes(55).toSeconds());
            assertThat(registry.find("finapp.payments.dispute.response.unknown.active").gauge()
                            .value())
                    .as("published eagerly, and a number from a readable database")
                    .isNotNaN();
            assertThat(registry.find("finapp.payments.dispute.response.unknown.age").gauge())
                    .isNotNull();
        } finally {
            // Never leave a stuck answer for another test's sweep to find.
            try (Connection app = DatabaseRoles.application()) {
                for (UUID answer : seeded) {
                    execute(app,
                            "UPDATE payments.dispute_response SET status = 'FAILED',"
                                    + " failure_reason = 'DECLINED' WHERE id = ?",
                            answer);
                }
            }
        }
    }

    @Test
    @DisplayName("the deadline alarm counts a chargeback near its respond-by date until an answer"
            + " the PSP took stands - the platform's clock raises the alarm and decides nothing")
    void theDeadlineAlarmCountsUntilAnAnswerIsTaken() throws Exception {
        Instant horizon = Instant.now(CLOCK).plus(Duration.ofDays(3));
        long before = nearDeadlines(horizon);
        Merchant merchant = merchant();
        UUID dispute = chargedBack(merchant.payable(), Instant.now(CLOCK).plus(Duration.ofDays(1)));
        chargedBack(merchant.payable(), Instant.now(CLOCK).plus(Duration.ofDays(30)));
        assertThat(nearDeadlines(horizon)).as("the near one, not the far one").isEqualTo(before + 1);

        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        post(merchantPath(dispute, "acceptance"), null, merchant.key(), someKey());
        assertThat(nearDeadlines(horizon)).isEqualTo(before);
        assertThat(registry.find("finapp.payments.dispute.deadline.near").gauge())
                .as("published eagerly by a running instance")
                .isNotNull();
    }

    @Test
    @DisplayName("ADR-0061 section 7: an operator answers a payment with NO merchant - evidence and"
            + " a representment on behalf, reasoned and on the record - and is refused on a"
            + " merchant's dispute, writing nothing")
    void anOperatorAnswersOnlyAPaymentWithNoMerchant() throws Exception {
        UUID topUp = chargedBack(wallet(), future());
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        HttpResponse<String> attached =
                post(operatorPath(topUp, "evidence"),
                        "{\"kind\":\"CUSTOMER_COMMUNICATION\",\"contentType\":\"PDF\",\"content\":\""
                                + Base64.getEncoder().encodeToString(bytes("the chat log"))
                                + "\",\"reason\":\"the customer confirmed the top-up in chat\"}",
                        operator(), null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        HttpResponse<String> answered =
                post(operatorPath(topUp, "representment"),
                        "{\"reason\":\"the top-up was the customer's own\"}", operator(), someKey());
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(201);
        assertThat(field(answered.body(), "status")).isEqualTo("SUBMITTED");
        assertThat(strings("SELECT reason FROM platform.audit_record WHERE target_id = ?"
                                + " AND operation = 'payments.DisputeResponseDispatched'",
                        topUp.toString()))
                .containsExactly("the top-up was the customer's own");
        assertThat(stage(topUp)).isEqualTo("CHARGED_BACK");

        Merchant merchant = merchant();
        UUID merchants = chargedBack(merchant.payable(), future());
        assertRefused(post(operatorPath(merchants, "acceptance"),
                        "{\"reason\":\"trying anyway\"}", operator(), someKey()),
                409, "payments.DisputeAnsweredByItsMerchant");
        assertRefused(post(operatorPath(merchants, "evidence"),
                        "{\"kind\":\"OTHER\",\"contentType\":\"PDF\",\"content\":\""
                                + Base64.getEncoder().encodeToString(bytes("x"))
                                + "\",\"reason\":\"trying anyway\"}",
                        operator(), null),
                409, "payments.DisputeAnsweredByItsMerchant");
        assertThat(count("SELECT count(*) FROM payments.dispute_response WHERE dispute_id = ?",
                        merchants))
                .isZero();
        // Without the permission, the one 403.
        assertThat(post(operatorPath(topUp, "acceptance"), "{\"reason\":\"no role\"}",
                                merchantAdministrator(), someKey())
                        .statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the door records the network's deadline ONCE: with the chargeback, or first"
            + " reported later; a restated other date moves nothing; an unreadable one makes the"
            + " statement unusable")
    void theDoorRecordsTheDeadlineOnce() throws Exception {
        Merchant merchant = merchant();
        Payment sale = captured(merchant.payable());
        String reference = someDisputeReference();
        deliver(chargeback(sale, reference, "needs_response", null));
        UUID dispute = disputeId(reference);
        assertThat(count("SELECT count(*) FROM payments.dispute WHERE id = ? AND respond_by IS NULL",
                        dispute))
                .isEqualTo(1);

        Instant stated = Instant.now(CLOCK).plus(Duration.ofDays(5)).truncatedTo(ChronoUnit.SECONDS);
        assertThat(deliver(chargeback(sale, reference, "needs_response", stated)).statusCode())
                .isEqualTo(204);
        assertThat(instant("SELECT respond_by FROM payments.dispute WHERE id = ?", dispute))
                .as("first reported late, recorded then")
                .isEqualTo(stated);

        deliver(chargeback(sale, reference, "needs_response", stated.plus(Duration.ofDays(2))));
        assertThat(instant("SELECT respond_by FROM payments.dispute WHERE id = ?", dispute))
                .as("the first statement stands")
                .isEqualTo(stated);

        double unmappable = registry.find("finapp.payments.webhook").tag("outcome", "unmappable")
                .counter().count();
        String unreadable =
                chargeback(sale, someDisputeReference(), "needs_response", null)
                        .replace("}", ",\"respondBy\":\"next tuesday\"}");
        assertThat(deliver(unreadable).statusCode()).isEqualTo(204);
        assertThat(registry.find("finapp.payments.webhook").tag("outcome", "unmappable").counter()
                        .count())
                .isEqualTo(unmappable + 1);
    }

    // -----------------------------------------------------------------
    // Seeds
    // -----------------------------------------------------------------

    private record Merchant(UUID id, LedgerAccountId payable, String key) {}

    private record Payment(UUID intent, UUID attempt, String operation, LedgerAccountId credit) {}

    /** An active merchant with a payable and an API key issued through the operator's route. */
    private Merchant merchant() throws Exception {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                            + " settlement_currency, status, created_at, status_changed_at) VALUES"
                            + " (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now(), now())",
                    id, UUID.randomUUID());
        }
        LedgerAccountId payable =
                asCustomer(uow -> ledgerAccountStore
                        .createOrConverge(
                                uow,
                                LedgerAccount.owned(
                                        IDS, CLOCK, AccountType.LIABILITY,
                                        AccountPurpose.MERCHANT_PAYABLE, EUR, id))
                        .account()
                        .id());
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + id + "/api-keys", null, merchantAdministrator(),
                        someKey());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return new Merchant(id, payable,
                field(issued.body(), "keyId") + "." + field(issued.body(), "secret"));
    }

    private LedgerAccountId wallet() throws Exception {
        return asCustomer(uow -> ledgerAccountStore
                .createOrConverge(
                        uow,
                        LedgerAccount.owned(
                                IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET,
                                EUR, IDS.next()))
                .account()
                .id());
    }

    /** A charged-back 10.00 sale crediting {@code credit}, stated through the signed door. */
    private UUID chargedBack(LedgerAccountId credit, Instant respondBy) throws Exception {
        Payment sale = captured(credit);
        String reference = someDisputeReference();
        assertThat(deliver(chargeback(sale, reference, "needs_response", respondBy)).statusCode())
                .isEqualTo(204);
        return disputeId(reference);
    }

    /** A captured 10.00 card payment crediting {@code credit}, WITH its capture's posting. */
    private Payment captured(LedgerAccountId credit) throws Exception {
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
                    intent, IDS.next(), IDS.next(), IDS.next(), credit.value());
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
        LedgerAccountId clearing =
                asCustomer(uow -> new ChartOfAccounts<>(ledgerAccountStore)
                        .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                        .id());
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope platform = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LocalDate today = LocalDate.now(CLOCK);
            postingService.post(
                    app,
                    new PostingCommand(
                            "payment-capture:" + attempt, today, today, attempt.toString(),
                            List.of(
                                    new JournalLine(clearing, Direction.DEBIT,
                                            Money.ofMinorUnits(1000, EUR)),
                                    new JournalLine(credit, Direction.CREDIT,
                                            Money.ofMinorUnits(1000, EUR)))));
            app.commit();
        }
        return new Payment(intent, attempt, capture, credit);
    }

    /** A MERCHANT_ADMINISTRATOR's session - issues merchant keys. */
    private String merchantAdministrator() throws Exception {
        if (merchantAdministrator == null) {
            merchantAdministrator = session(RoleName.MERCHANT_ADMINISTRATOR);
        }
        return merchantAdministrator;
    }

    /** A LEDGER_OPERATOR's session - holds DISPUTE_ADMINISTER. */
    private String operator() throws Exception {
        if (disputeOperator == null) {
            disputeOperator = session(RoleName.LEDGER_OPERATOR);
        }
        return disputeOperator;
    }

    private String session(RoleName role) throws Exception {
        String login = "disputes." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration =
                post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey());
        assertThat(registration.statusCode()).as(registration.body()).isEqualTo(201);
        UUID identity =
                UUID.fromString(oneString(
                        "SELECT id FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity),
                    "test fixture");
            app.commit();
        }
        HttpResponse<String> authenticated =
                post("/v1/authentications",
                        "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                        null, someKey());
        return field(authenticated.body(), "sessionToken");
    }

    private <R> R asCustomer(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    private <R> R asMerchant(Merchant merchant, java.util.concurrent.Callable<R> work)
            throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(merchant.id().toString(), ActorType.MERCHANT))) {
            return work.call();
        }
    }

    private static long nearDeadlines(Instant horizon) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new JdbcDisputeStore().countDeadlinesNear(app, horizon);
        }
    }

    private static Instant future() {
        return Instant.now(CLOCK).plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
    }

    // -----------------------------------------------------------------
    // The wire
    // -----------------------------------------------------------------

    /** A dispute statement in the simulated PSP's wire shape, a fresh event id each time. */
    private static String chargeback(Payment payment, String dispute, String stage,
            Instant respondBy) {
        return "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"operation\":\""
                + payment.operation() + "\",\"status\":\"disputed\",\"dispute\":\"" + dispute
                + "\",\"stage\":\"" + stage + "\",\"reasonCode\":\"fraudulent\","
                + "\"amountMinor\":\"1000\",\"currency\":\"EUR\",\"scale\":2"
                + (respondBy == null ? "" : ",\"respondBy\":\"" + respondBy + "\"")
                + "}";
    }

    private static String evidence(String kind, String contentType, byte[] content) {
        return "{\"kind\":\"" + kind + "\",\"contentType\":\"" + contentType + "\",\"content\":\""
                + Base64.getEncoder().encodeToString(content) + "\"}";
    }

    private static byte[] bytes(String text) {
        return (text + " " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private static String merchantPath(UUID dispute, String tail) {
        return "/v1/merchant/disputes/" + dispute + "/" + tail;
    }

    private static String operatorPath(UUID dispute, String tail) {
        return "/v1/operator/disputes/" + dispute + "/" + tail;
    }

    private HttpResponse<String> deliver(String body) throws Exception {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        return send(
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port
                                        + "/v1/providers/payments/webhooks"))
                        .header("Content-Type", "application/json")
                        .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                        .header(WebhookSignature.SIGNATURE_HEADER, hmacHex(timestamp + "." + body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private List<Integer> race(int racers, IntCall answer) throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int racer = i;
                answers.add(pool.submit(() -> {
                    open.await();
                    return answer.call(racer);
                }));
            }
            open.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : answers) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IntCall {
        int call(int racer) throws Exception;
    }

    /** The dispute stage gauge's own read (P7-TSK-015). */
    private java.util.Map<com.finapp.payments.DisputeStage, Long> stages() {
        return transactions.inTransaction(uow -> disputeStore.countByStage(uow));
    }

    /** How many more disputes stand at {@code stage} than {@code before} recorded. */
    private long standing(
            com.finapp.payments.DisputeStage stage,
            java.util.Map<com.finapp.payments.DisputeStage, Long> before) {
        return stages().get(stage) - before.get(stage);
    }

    /** The stuck-answer gauges' own read (P7-TSK-015), over {@code bound}. */
    private com.finapp.payments.PaymentAttemptStore.UnknownReading stuckAnswers(Duration bound) {
        return transactions.inTransaction(uow -> responseStore.unknownReading(uow, bound));
    }

    /** A raw acceptance born DISPATCHED at {@code permit} - then UNKNOWN, when asked. */
    private UUID seedAnswer(UUID dispute, Instant permit, boolean unknown) throws Exception {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.dispute_response (id, dispute_id, kind, status,"
                            + " failure_reason, provider_idempotency_reference, provider_reference,"
                            + " evidence_ids, requested_by_id, requested_by_type, reason,"
                            + " dispatch_scope, dispatch_key, send_permit, created_at)"
                            + " VALUES (?, ?, 'ACCEPTANCE', 'DISPATCHED', NULL, ?, NULL, '{}',"
                            + " 'meters-test', 'MERCHANT', NULL, 'dispute.respond:merchant:meters',"
                            + " ?, ?, ?)",
                    id, dispute, "dsr-" + id, UUID.randomUUID().toString(),
                    java.sql.Timestamp.from(permit), java.sql.Timestamp.from(permit));
            if (unknown) {
                execute(app, "UPDATE payments.dispute_response SET status = 'UNKNOWN' WHERE id = ?",
                        id);
            }
        }
        return id;
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    // -----------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------

    private static UUID disputeId(String reference) throws SQLException {
        return UUID.fromString(oneString(
                "SELECT id FROM payments.dispute WHERE provider = ? AND"
                        + " provider_dispute_reference = ?",
                SimulatedCardPspAdapter.NAME, reference));
    }

    private static String stage(UUID dispute) throws SQLException {
        return oneString("SELECT stage FROM payments.dispute WHERE id = ?", dispute);
    }

    private static String status(UUID response) throws SQLException {
        return oneString("SELECT status FROM payments.dispute_response WHERE id = ?", response);
    }

    private static List<String> operations(UUID dispute) throws SQLException {
        return strings(
                "SELECT split_part(substring(idempotency_scope FROM length('ledger.post:') + 1),"
                        + " ':', 1) FROM ledger.journal_entry WHERE reference = ? ORDER BY id",
                dispute.toString());
    }

    /** A liability-normal account's settled position from its lines: credits minus debits. */
    private static long balance(LedgerAccountId account) throws SQLException {
        return count(
                "SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_minor"
                        + " ELSE -amount_minor END), 0) FROM ledger.journal_line"
                        + " WHERE ledger_account_id = ?",
                account.value());
    }

    private static long audits(UUID target, String operation) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation = ?",
                target.toString(), operation);
    }

    private static String eventPayload(UUID aggregate, String type) throws SQLException {
        return oneString(
                "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                        + " WHERE aggregate_id = ? AND event_type = ?",
                aggregate, type);
    }

    private static Instant instant(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                Timestamp value = row.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        }
    }

    private static String someDisputeReference() {
        return "dp_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher found = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }

    /** The per-request fields: the correlation id differs by design, and instance echoes the path. */
    private static String normalised(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"n\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"n\"");
    }

    private static String oneString(String sql, Object... arguments) throws SQLException {
        List<String> values = strings(sql, arguments);
        assertThat(values).as(sql).hasSize(1);
        return values.get(0);
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
}
