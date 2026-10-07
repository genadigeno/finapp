package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreeningObserver;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningStatus;
import com.finapp.kyc.CounterpartyScreeningStore;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Decision;
import com.finapp.kyc.CounterpartyScreeningVocabulary.EntityType;
import com.finapp.kyc.CounterpartyScreeningVocabulary.PayeeVerdict;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReviewReason;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.kyc.CounterpartyScreenings;
import com.finapp.kyc.CounterpartySubject;
import com.finapp.kyc.CounterpartySubjectCipher;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.kyc.ScreeningOutcomeListener;
import com.finapp.kyc.TransactionRunner;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Counterparty screening against a live database (`P9-TSK-016`, ADR-0081; {@code INV-KYC-01},
 * {@code INV-KYC-04}, {@code INV-KYC-05}, {@code INV-AUD-01}): every outcome a recorded decision with its
 * basis, policy version and time; a hit, an indeterminate answer and an unverified payee always waiting
 * for a person; the unavailable path retried and never cleared; {@code kyc V009}'s decision-basis CHECKs
 * by raw SQL; T-e's atomicity; ten reviewers one decision and ten sweepers one attempt, counted; the
 * name nowhere but kyc's ciphertext; and the review door over HTTP, closed to everyone without
 * {@code COUNTERPARTY_SCREENING_REVIEW}.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("counterparty screening in kyc (P9-TSK-016)")
class CounterpartyScreeningDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String DOOR = "/v1/operator/kyc/counterparty-screenings/";
    private static final byte[] EVIDENCE = "{\"status\":\"provider-answer\"}".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private CounterpartyScreeningStore counterpartyScreeningStore;
    @Autowired private CounterpartySubjectCipher counterpartySubjectCipher;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private TransactionRunner kycTransactionRunner;
    @Autowired private IdGenerator idGenerator;
    @Autowired private Clock clock;
    @Autowired private RecordingListener recordingListener;

    // ------------------------------------------------------------------ automatic outcomes

    @Test
    @DisplayName("a provider CLEAR beside a payee MATCH is decided CLEAR - AUTOMATIC, the policy pinned, the time"
            + " recorded, the answer sealed as evidence, audited with a reason and announced")
    void clearWithAMatchClears() throws Exception {
        Scripted provider = new Scripted(Verdict.CLEAR);
        Recorded observer = new Recorded();
        CounterpartyScreenings screenings = service(provider, recordingListener, observer);
        CounterpartyScreenings.Screening screened = screenings.screen(request(PayeeVerdict.MATCH), correlation());

        assertThat(screened.status()).isEqualTo(CounterpartyScreeningStatus.CLEAR);
        assertThat(screened.replayed()).isFalse();
        Map<String, Object> row = row(screened.id());
        assertThat(row).containsEntry("status", "CLEAR").containsEntry("decision_basis", "AUTOMATIC")
                .containsEntry("policy_version", KycPolicyVersion.CURRENT.value()).containsEntry("attempts", 1);
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("decided_by")).isNull();
        assertThat(row.get("next_attempt_at")).as("an answered screening is never due").isNull();
        assertThat(attempts(screened.id())).containsExactly("1:CLEAR:evidence");
        assertThat(auditReasons(screened.id())).singleElement().asString()
                .contains("CLEAR").contains("MATCH");
        assertThat(events(screened.id())).isEqualTo(1);
        assertThat(recordingListener.statusOf(screened.id())).contains(CounterpartyScreeningStatus.CLEAR);
        assertThat(observer.statuses).containsExactly(CounterpartyScreeningStatus.CLEAR);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(screenings.clearance(app, screened.id())).hasValueSatisfying(clearance -> {
                assertThat(clearance.clears()).isTrue();
                assertThat(clearance.decidedAt()).isPresent();
            });
        }
    }

    @Test
    @DisplayName("a provider CLEAR beside a NO_MATCH or UNAVAILABLE payee goes IN_REVIEW, PAYEE_UNVERIFIED - the"
            + " provider's CLEAR kept as evidence, never CLEAR")
    void anUnverifiedPayeeMeetsAPerson() throws Exception {
        CounterpartyScreenings screenings = service(new Scripted(Verdict.CLEAR), recordingListener, CounterpartyScreeningObserver.NONE);
        for (PayeeVerdict payee : new PayeeVerdict[] {PayeeVerdict.NO_MATCH, PayeeVerdict.UNAVAILABLE}) {
            CounterpartyScreenings.Screening screened = screenings.screen(request(payee), correlation());
            assertThat(screened.status()).as(payee.name()).isEqualTo(CounterpartyScreeningStatus.IN_REVIEW);
            assertThat(screened.reviewReason()).contains(ReviewReason.PAYEE_UNVERIFIED);
            assertThat(attempts(screened.id())).as("the provider's CLEAR is evidence").containsExactly("1:CLEAR:evidence");
            assertThat(row(screened.id())).containsEntry("decision_basis", "AUTOMATIC");
        }
    }

    @Test
    @DisplayName("a hit and an indeterminate answer go IN_REVIEW, whatever the payee - never CLEAR, never BLOCKED")
    void aHitNeverAutoClearsOrAutoRejects() throws Exception {
        for (Verdict verdict : new Verdict[] {Verdict.HIT, Verdict.INDETERMINATE}) {
            CounterpartyScreenings screenings = service(new Scripted(verdict), recordingListener, CounterpartyScreeningObserver.NONE);
            for (PayeeVerdict payee : PayeeVerdict.values()) {
                CounterpartyScreenings.Screening screened = screenings.screen(request(payee), correlation());
                assertThat(screened.status()).as(verdict + "/" + payee).isEqualTo(CounterpartyScreeningStatus.IN_REVIEW);
                assertThat(screened.reviewReason()).contains(
                        verdict == Verdict.HIT ? ReviewReason.HIT : ReviewReason.INDETERMINATE);
            }
        }
    }

    @Test
    @DisplayName("an unavailable provider is UNAVAILABLE - due again, nothing cleared - and a later retry decides")
    void theUnavailablePathIsRetried() throws Exception {
        Scripted provider = new Scripted(Verdict.UNAVAILABLE);
        CounterpartyScreenings screenings = service(provider, recordingListener, CounterpartyScreeningObserver.NONE);
        CounterpartyScreenings.Screening screened = screenings.screen(request(PayeeVerdict.MATCH), correlation());
        assertThat(screened.status()).isEqualTo(CounterpartyScreeningStatus.UNAVAILABLE);
        assertThat(row(screened.id())).containsEntry("decision_basis", "AUTOMATIC").containsEntry("attempts", 1);
        assertThat(row(screened.id()).get("next_attempt_at")).isNotNull();
        assertThat(attempts(screened.id())).containsExactly("1:UNAVAILABLE:none");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(screenings.clearance(app, screened.id()).orElseThrow().clears()).isFalse();
        }

        makeDue(screened.id());
        provider.verdict = Verdict.CLEAR;
        CounterpartyScreenings.Screening retried = screenings.retry(screened.id(), correlation());
        assertThat(retried.status()).isEqualTo(CounterpartyScreeningStatus.CLEAR);
        assertThat(attempts(screened.id())).containsExactly("1:UNAVAILABLE:none", "2:CLEAR:evidence");
        assertThat(screenings.retry(screened.id(), correlation()).replayed())
                .as("an answered screening is never asked again").isTrue();
        assertThat(provider.calls(screened.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("a duplicate request converges without a second call; the same reference for another counterparty"
            + " is refused; a re-screen reuses the stored subject under a new reference")
    void requestsAreIdempotent() throws Exception {
        Scripted provider = new Scripted(Verdict.CLEAR);
        CounterpartyScreenings screenings = service(provider, recordingListener, CounterpartyScreeningObserver.NONE);
        CounterpartyScreenings.Request request = request(PayeeVerdict.MATCH);
        CounterpartyScreenings.Screening first = screenings.screen(request, correlation());
        CounterpartyScreenings.Screening again = screenings.screen(request, correlation());
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.replayed()).isTrue();
        assertThat(provider.total.get()).isEqualTo(1);
        assertThatThrownBy(() -> screenings.screen(new CounterpartyScreenings.Request(request.requestReference(),
                        new CounterpartySubject("Someone Else", CountryCode.of("US"), EntityType.INDIVIDUAL),
                        PayeeVerdict.MATCH, "requester-" + UUID.randomUUID()), correlation()))
                .isInstanceOf(CounterpartyScreenings.RequestConflict.class);

        CounterpartyScreenings.Screening rescreened = screenings.rescreen(first.id(), "rescreen-" + UUID.randomUUID(), correlation());
        assertThat(rescreened.id()).isNotEqualTo(first.id());
        assertThat(provider.subjects.get(rescreened.id())).isEqualTo(request.subject());
    }

    @Test
    @DisplayName("T-e is one transaction: a listener that fails rolls the decision, its attempt, audit and event back")
    void theDecisionAndTheListenerCommitTogether() throws Exception {
        CounterpartyScreenings screenings =
                service(new Scripted(Verdict.HIT), ScreeningOutcomeListener.REFUSING, CounterpartyScreeningObserver.NONE);
        CounterpartyScreenings.Request request = request(PayeeVerdict.MATCH);
        assertThatThrownBy(() -> screenings.screen(request, correlation())).isInstanceOf(IllegalStateException.class);
        CounterpartyScreeningId id;
        try (Connection app = DatabaseRoles.application()) {
            id = counterpartyScreeningStore.byRequest(app, request.requestReference()).orElseThrow().id();
        }
        assertThat(row(id)).containsEntry("status", "REQUESTED").containsEntry("attempts", 0);
        assertThat(row(id).get("decision_basis")).isNull();
        assertThat(attempts(id)).isEmpty();
        assertThat(auditReasons(id)).isEmpty();
        assertThat(events(id)).isZero();
    }

    // ------------------------------------------------------------------ the person

    @Test
    @DisplayName("a reviewer releases with a reason: REVIEWER, the person named, the code and narrative stored,"
            + " audited with the reason; the same retry converges; anything else is refused")
    void aPersonDecides() throws Exception {
        Recorded observer = new Recorded();
        CounterpartyScreenings screenings = service(new Scripted(Verdict.HIT), recordingListener, observer);
        CounterpartyScreeningId id = screenings.screen(request(PayeeVerdict.MATCH), correlation()).id();
        Actor reviewer = new Actor("reviewer-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        Actor other = new Actor("reviewer-" + UUID.randomUUID(), ActorType.EMPLOYEE);

        assertThatThrownBy(() -> review(screenings, id, reviewer, Decision.RELEASE, ReasonCode.TRUE_MATCH, "a true match"))
                .isInstanceOf(CounterpartyScreenings.ScreeningReviewInvalid.class);
        assertThatThrownBy(() -> review(screenings, id, reviewer, Decision.BLOCK, ReasonCode.TRUE_MATCH, " "))
                .isInstanceOf(CounterpartyScreenings.ScreeningReviewInvalid.class);
        assertThatThrownBy(() -> review(screenings, id, reviewer, Decision.BLOCK, ReasonCode.TRUE_MATCH,
                        "see card 4111 1111 1111 1111"))
                .isInstanceOf(CounterpartyScreenings.ScreeningReviewInvalid.class);

        CounterpartyScreenings.Screening released =
                review(screenings, id, reviewer, Decision.RELEASE, ReasonCode.FALSE_POSITIVE, "a different person, born 1971");
        assertThat(released.status()).isEqualTo(CounterpartyScreeningStatus.RELEASED);
        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("decision_basis", "REVIEWER").containsEntry("decided_by", reviewer.id())
                .containsEntry("decision_reason_code", "FALSE_POSITIVE").containsEntry("review_reason", "HIT")
                .containsEntry("policy_version", KycPolicyVersion.CURRENT.value());
        assertThat(auditReasons(id)).hasSize(2).last().asString().isEqualTo("FALSE_POSITIVE: a different person, born 1971");
        assertThat(recordingListener.statusOf(id)).contains(CounterpartyScreeningStatus.RELEASED);

        assertThat(review(screenings, id, reviewer, Decision.RELEASE, ReasonCode.FALSE_POSITIVE, "again").replayed())
                .as("the same person's retry converges").isTrue();
        assertThatThrownBy(() -> review(screenings, id, other, Decision.BLOCK, ReasonCode.TRUE_MATCH, "disagree"))
                .isInstanceOf(CounterpartyScreenings.ScreeningNotInReview.class);
        assertThatThrownBy(() -> review(screenings, CounterpartyScreeningId.next(IDS), other, Decision.BLOCK,
                        ReasonCode.TRUE_MATCH, "unknown"))
                .isInstanceOf(CounterpartyScreenings.ScreeningNotFound.class);
        CounterpartyScreeningId clear = service(new Scripted(Verdict.CLEAR), recordingListener, CounterpartyScreeningObserver.NONE)
                .screen(request(PayeeVerdict.MATCH), correlation()).id();
        assertThatThrownBy(() -> review(screenings, clear, other, Decision.BLOCK, ReasonCode.TRUE_MATCH, "overrule"))
                .as("a clearance is not reviewable").isInstanceOf(CounterpartyScreenings.ScreeningNotInReview.class);
        assertThat(auditReasons(id)).as("no refused review wrote a record").hasSize(2);
    }

    @Test
    @DisplayName("ten reviewers deciding one screening at once produce one decision, one record and one event (counted)")
    void tenReviewersOneDecision() throws Exception {
        CounterpartyScreenings screenings = service(new Scripted(Verdict.HIT), recordingListener, CounterpartyScreeningObserver.NONE);
        CounterpartyScreeningId id = screenings.screen(request(PayeeVerdict.MATCH), correlation()).id();
        int herd = 10;
        ExecutorService pool = Executors.newFixedThreadPool(herd);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < herd; i++) {
            Actor reviewer = new Actor("herd-" + i + "-" + UUID.randomUUID(), ActorType.EMPLOYEE);
            Decision decision = i % 2 == 0 ? Decision.RELEASE : Decision.BLOCK;
            ReasonCode code = decision == Decision.RELEASE ? ReasonCode.FALSE_POSITIVE : ReasonCode.TRUE_MATCH;
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    review(screenings, id, reviewer, decision, code, "herd decision");
                    return true;
                } catch (CounterpartyScreenings.ScreeningNotInReview refused) {
                    return false;
                }
            }));
        }
        start.countDown();
        int decided = 0;
        for (Future<Boolean> outcome : outcomes) {
            decided += outcome.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(decided).as("exactly one reviewer decides").isEqualTo(1);
        assertThat(auditReasons(id)).as("the automatic decision and one person's").hasSize(2);
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("ten sweepers retrying due screenings at once ask each one once, and record one attempt each (counted)")
    void tenSweepersOneAttemptEach() throws Exception {
        Scripted down = new Scripted(Verdict.UNAVAILABLE);
        CounterpartyScreenings first = service(down, recordingListener, CounterpartyScreeningObserver.NONE);
        List<CounterpartyScreeningId> due = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            CounterpartyScreeningId id = first.screen(request(PayeeVerdict.MATCH), correlation()).id();
            makeDue(id);
            due.add(id);
        }
        Scripted up = new Scripted(Verdict.CLEAR);
        CounterpartyScreenings screenings = service(up, recordingListener, CounterpartyScreeningObserver.NONE);
        int herd = 10;
        ExecutorService pool = Executors.newFixedThreadPool(herd);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> sweeps = new ArrayList<>();
        for (int i = 0; i < herd; i++) {
            sweeps.add(pool.submit(() -> {
                start.await();
                new CounterpartyScreeningRetrySchedule(screenings, IDS, Duration.ofMinutes(1), 50).sweepOnce();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> sweep : sweeps) {
            sweep.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        for (CounterpartyScreeningId id : due) {
            assertThat(up.calls(id)).as("asked once by the herd").isEqualTo(1);
            assertThat(attempts(id)).containsExactly("1:UNAVAILABLE:none", "2:CLEAR:evidence");
            assertThat(row(id)).containsEntry("status", "CLEAR");
        }
    }

    // ------------------------------------------------------------------ the schema

    @Test
    @DisplayName("V009 refuses, by raw SQL: a REVIEWER outcome without a person, an AUTOMATIC CLEAR without a payee"
            + " MATCH, an outcome without its basis, a machine edge that does not exist, a thawed subject, a delete")
    void theDecisionBasisChecks() throws Exception {
        CounterpartyScreeningId inReview = service(new Scripted(Verdict.HIT), recordingListener, CounterpartyScreeningObserver.NONE)
                .screen(request(PayeeVerdict.NO_MATCH), correlation()).id();
        CounterpartyScreeningId clear = service(new Scripted(Verdict.CLEAR), recordingListener, CounterpartyScreeningObserver.NONE)
                .screen(request(PayeeVerdict.MATCH), correlation()).id();
        refused("23514", "counterparty_screening_reviewer_names_a_person",
                "UPDATE kyc.counterparty_screening SET status = 'RELEASED', decision_basis = 'REVIEWER' WHERE id = ?",
                inReview.value());
        refused("23514", "counterparty_screening_automatic_clear_needs_a_payee_match",
                "INSERT INTO kyc.counterparty_screening (id, request_reference, subject_ciphertext, subject_nonce,"
                        + " subject_key_version, country, entity_type, payee_verdict, status, decision_basis, policy_version,"
                        + " decided_at, attempts, requested_at) VALUES (?, ?, ?, ?, 1, 'US', 'INDIVIDUAL', 'NO_MATCH',"
                        + " 'CLEAR', 'AUTOMATIC', 'kyc-2026-09', now(), 1, now())",
                UUID.randomUUID(), "raw-" + UUID.randomUUID(), new byte[24], new byte[12]);
        refused("23514", "counterparty_screening_every_outcome_is_decided",
                "INSERT INTO kyc.counterparty_screening (id, request_reference, subject_ciphertext, subject_nonce,"
                        + " subject_key_version, country, entity_type, payee_verdict, status, review_reason, attempts,"
                        + " requested_at) VALUES (?, ?, ?, ?, 1, 'US', 'INDIVIDUAL', 'MATCH', 'IN_REVIEW', 'HIT', 1, now())",
                UUID.randomUUID(), "raw-" + UUID.randomUUID(), new byte[24], new byte[12]);
        refused("23514", "counterparty_screening_basis_matches_the_outcome",
                "UPDATE kyc.counterparty_screening SET status = 'BLOCKED', review_reason = review_reason WHERE id = ?",
                inReview.value());
        refused("P0001", "cannot move from CLEAR to IN_REVIEW",
                "UPDATE kyc.counterparty_screening SET status = 'IN_REVIEW', review_reason = 'HIT' WHERE id = ?", clear.value());
        // The subject and the payee verdict are not even updatable by the application (no column grant);
        // the edge trigger's freeze is the rank beneath, for the owner.
        refused("42501", "permission denied",
                "UPDATE kyc.counterparty_screening SET payee_verdict = 'MATCH' WHERE id = ?", inReview.value());
        refused("42501", "permission denied",
                "DELETE FROM kyc.counterparty_screening WHERE id = ?", clear.value());
        refused("42501", "permission denied",
                "UPDATE kyc.counterparty_screening_attempt SET verdict = 'CLEAR' WHERE screening_id = ?", inReview.value());
    }

    // ------------------------------------------------------------------ the needle

    @Test
    @DisplayName("the needle (kyc's leg): the counterparty's name is in no column of any table the application can"
            + " read, no audit body and no event - only kyc's ciphertext, which opens to it")
    void theNameRestsOnlyAsCiphertext() throws Exception {
        String needle = "Zebulon Quackenbush " + UUID.randomUUID().toString().substring(0, 8);
        CounterpartyScreenings screenings = service(new Scripted(Verdict.HIT), recordingListener, CounterpartyScreeningObserver.NONE);
        CounterpartyScreenings.Request request = new CounterpartyScreenings.Request(
                "needle-" + UUID.randomUUID(), new CounterpartySubject(needle, CountryCode.of("JP"), EntityType.BUSINESS),
                PayeeVerdict.MATCH, "requester-needle");
        CounterpartyScreeningId id = screenings.screen(request, correlation()).id();
        review(screenings, id, new Actor("reviewer-needle", ActorType.EMPLOYEE), Decision.BLOCK, ReasonCode.TRUE_MATCH,
                "the listed entity itself");

        List<String> holders = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(true);
            List<String> tables = new ArrayList<>();
            try (PreparedStatement list = app.prepareStatement(
                            "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                                    + " WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')"
                                    + " AND has_table_privilege(table_schema || '.' || table_name, 'SELECT')");
                    ResultSet rows = list.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            assertThat(tables).contains("kyc.counterparty_screening", "platform.audit_record", "platform.outbox_event");
            for (String table : tables) {
                try (PreparedStatement scan = app.prepareStatement(
                        "SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?")) {
                    scan.setString(1, "%" + needle + "%");
                    try (ResultSet count = scan.executeQuery()) {
                        count.next();
                        if (count.getLong(1) > 0) {
                            holders.add(table);
                        }
                    }
                }
            }
            try (PreparedStatement events = app.prepareStatement(
                    "SELECT count(*) FROM platform.outbox_event WHERE convert_from(payload, 'UTF8') LIKE ?")) {
                events.setString(1, "%" + needle + "%");
                try (ResultSet count = events.executeQuery()) {
                    count.next();
                    assertThat(count.getLong(1)).as("no event payload names the counterparty").isZero();
                }
            }
            assertThat(counterpartySubjectCipher.decrypt(id,
                    counterpartyScreeningStore.find(app, id).orElseThrow().subject())).isEqualTo(needle);
        }
        assertThat(holders).as("tables holding the name in clear").isEmpty();
        assertThat(request.toString()).doesNotContain(needle);
    }

    // ------------------------------------------------------------------ the door

    @Test
    @DisplayName("the review door: a KYC_REVIEWER releases over HTTP, keyed; a decided screening 409s, an unknown or"
            + " malformed one 404s, a code that cannot justify the decision 422s; FX_CONTROLLER and a customer 403,"
            + " no session 401")
    void theReviewDoor() throws Exception {
        CounterpartyScreenings screenings = service(new Scripted(Verdict.HIT), recordingListener, CounterpartyScreeningObserver.NONE);
        CounterpartyScreeningId id = screenings.screen(request(PayeeVerdict.MATCH), correlation()).id();
        String reviewer = sessionWith(RoleName.KYC_REVIEWER);
        String controller = sessionWith(RoleName.FX_CONTROLLER);
        String customer = sessionWith(null);
        String body = decision("RELEASE", "FALSE_POSITIVE", "different date of birth");

        for (String bearer : new String[] {controller, customer}) {
            assertThat(post(DOOR + id.value() + "/decision", body, bearer, someKey()).statusCode()).isEqualTo(403);
        }
        assertThat(post(DOOR + id.value() + "/decision", body, null, someKey()).statusCode()).isEqualTo(401);
        HttpResponse<String> invalid = post(DOOR + id.value() + "/decision",
                decision("RELEASE", "TRUE_MATCH", "contradiction"), reviewer, someKey());
        assertThat(invalid.statusCode()).as(invalid.body()).isEqualTo(422);
        assertThat(invalid.body()).contains("kyc.ScreeningReviewInvalid");
        assertThat(row(id)).as("no refused caller decided").containsEntry("status", "IN_REVIEW");

        String key = someKey();
        HttpResponse<String> released = post(DOOR + id.value() + "/decision", body, reviewer, key);
        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
        assertThat(field(released.body(), "status")).isEqualTo("RELEASED");
        assertThat(post(DOOR + id.value() + "/decision", body, reviewer, key).statusCode()).as("the same key replays").isEqualTo(200);
        assertThat(auditReasons(id)).hasSize(2);

        HttpResponse<String> again = post(DOOR + id.value() + "/decision",
                decision("BLOCK", "TRUE_MATCH", "second thoughts"), sessionWith(RoleName.KYC_REVIEWER), someKey());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("kyc.ScreeningNotInReview");
        assertThat(post(DOOR + UUID.randomUUID() + "/decision", body, reviewer, someKey()).statusCode()).isEqualTo(404);
        assertThat(post(DOOR + "not-a-uuid/decision", body, reviewer, someKey()).statusCode()).isEqualTo(404);
        assertThat(released.body()).doesNotContain("different date of birth");
    }

    // ------------------------------------------------------------------ the Phase 9 to 10 transition

    @Test
    @DisplayName("Phase 9 to 10 transition (the domain's rank): the person who requested a screening never reviews it -"
            + " refused, nothing written; another reviewer decides; a re-screen inherits the requester")
    void aRequesterNeverReviewsTheirOwnScreening() throws Exception {
        CounterpartyScreenings screenings = service(new Scripted(Verdict.HIT), recordingListener, CounterpartyScreeningObserver.NONE);
        String requester = "registrant-" + UUID.randomUUID();
        CounterpartyScreenings.Request request = new CounterpartyScreenings.Request("self-" + UUID.randomUUID(),
                new CounterpartySubject("Ana Lima Self", CountryCode.of("US"), EntityType.INDIVIDUAL), PayeeVerdict.MATCH,
                requester);
        CounterpartyScreeningId id = screenings.screen(request, correlation()).id();
        assertThat(scalar("SELECT requested_by FROM kyc.counterparty_screening WHERE id = ?", id.value())).isEqualTo(requester);
        Actor self = new Actor(requester, ActorType.CUSTOMER);
        for (Decision decision : Decision.values()) {
            ReasonCode code = decision == Decision.RELEASE ? ReasonCode.FALSE_POSITIVE : ReasonCode.TRUE_MATCH;
            assertThatThrownBy(() -> review(screenings, id, self, decision, code, "my own payee"))
                    .isInstanceOf(CounterpartyScreenings.ScreeningReviewInvalid.class)
                    .hasMessageContaining("someone other than the person who requested it");
        }
        assertThat(row(id)).containsEntry("status", "IN_REVIEW");
        assertThat(row(id).get("decided_by")).isNull();
        assertThat(auditReasons(id)).as("only the automatic decision").hasSize(1);
        assertThat(events(id)).isEqualTo(1);

        CounterpartyScreenings.Screening rescreened = screenings.rescreen(id, "self-rescreen-" + UUID.randomUUID(), correlation());
        assertThat(scalar("SELECT requested_by FROM kyc.counterparty_screening WHERE id = ?", rescreened.id().value()))
                .as("the re-screen inherits the requester").isEqualTo(requester);
        assertThatThrownBy(() -> review(screenings, rescreened.id(), self, Decision.RELEASE, ReasonCode.FALSE_POSITIVE, "mine"))
                .isInstanceOf(CounterpartyScreenings.ScreeningReviewInvalid.class);

        Actor other = new Actor("reviewer-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        assertThat(review(screenings, id, other, Decision.BLOCK, ReasonCode.TRUE_MATCH, "a second person").status())
                .isEqualTo(CounterpartyScreeningStatus.BLOCKED);
    }

    @Test
    @DisplayName("Phase 9 to 10 transition (the database's rank, alone): V010 refuses by raw SQL a reviewer's decision"
            + " naming the requester, and the requester is frozen - never updatable by the application")
    void theSchemaRefusesASelfReview() throws Exception {
        String requester = "registrant-" + UUID.randomUUID();
        CounterpartyScreeningId id = service(new Scripted(Verdict.HIT), recordingListener, CounterpartyScreeningObserver.NONE)
                .screen(new CounterpartyScreenings.Request("raw-self-" + UUID.randomUUID(),
                        new CounterpartySubject("Raw Self", CountryCode.of("US"), EntityType.INDIVIDUAL), PayeeVerdict.MATCH,
                        requester), correlation())
                .id();
        refused("23514", "counterparty_screening_reviewer_is_not_the_requester",
                "UPDATE kyc.counterparty_screening SET status = 'RELEASED', decision_basis = 'REVIEWER', decided_by = ?,"
                        + " decision_reason_code = 'FALSE_POSITIVE', decision_narrative = 'my own payee' WHERE id = ?",
                requester, id.value());
        refused("42501", "permission denied",
                "UPDATE kyc.counterparty_screening SET requested_by = 'someone-else' WHERE id = ?", id.value());
        try (Connection owner = DatabaseRoles.migrator();
                PreparedStatement thaw = owner.prepareStatement(
                        "UPDATE kyc.counterparty_screening SET requested_by = 'someone-else' WHERE id = ?")) {
            thaw.setObject(1, id.value());
            thaw.executeUpdate();
            throw new AssertionError("the requester was thawed");
        } catch (SQLException frozen) {
            assertThat(frozen.getMessage()).contains("requester are frozen");
        }
        assertThat(row(id)).containsEntry("status", "IN_REVIEW");
    }

    @Test
    @DisplayName("Phase 9 to 10 transition: a decision's instant is the database's - an instance an hour ahead or behind"
            + " stamps decided_at (and requested_at) within seconds of the database's now, never its own clock")
    void theDecisionInstantIsTheDatabases() throws Exception {
        for (Duration skew : List.of(Duration.ofHours(1), Duration.ofHours(-1))) {
            CounterpartyScreenings skewed = new CounterpartyScreenings(counterpartyScreeningStore, counterpartySubjectCipher,
                    new Scripted(Verdict.HIT), recordingListener, CounterpartyScreeningObserver.NONE, auditWriter, outboxWriter,
                    kycTransactionRunner, idGenerator, Clock.offset(clock, skew));
            CounterpartyScreeningId id = skewed.screen(request(PayeeVerdict.MATCH), correlation()).id();
            assertThat(secondsFromDatabaseNow("decided_at", id)).as("the automatic decision, skew " + skew).isLessThan(60);
            assertThat(secondsFromDatabaseNow("requested_at", id)).as("the request, skew " + skew).isLessThan(60);
            review(skewed, id, new Actor("reviewer-" + UUID.randomUUID(), ActorType.EMPLOYEE), Decision.RELEASE,
                    ReasonCode.FALSE_POSITIVE, "a different person");
            assertThat(secondsFromDatabaseNow("decided_at", id)).as("the person's decision, skew " + skew).isLessThan(60);
        }
    }

    private static long secondsFromDatabaseNow(String column, CounterpartyScreeningId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT abs(extract(epoch FROM (" + column
                        + " - now())))::bigint FROM kyc.counterparty_screening WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static String scalar(String sql, Object arg) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, arg);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as(sql).isTrue();
                return row.getString(1);
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private CounterpartyScreenings service(
            CounterpartyScreeningProvider provider, ScreeningOutcomeListener listener, CounterpartyScreeningObserver observer) {
        return new CounterpartyScreenings(counterpartyScreeningStore, counterpartySubjectCipher, provider, listener, observer,
                auditWriter, outboxWriter, kycTransactionRunner, idGenerator, clock);
    }

    private CounterpartyScreenings.Screening review(
            CounterpartyScreenings screenings, CounterpartyScreeningId id, Actor actor, Decision decision, ReasonCode code,
            String narrative) {
        return kycTransactionRunner.inTransaction(uow -> screenings.review(uow, id, actor, decision, code, narrative, correlation()));
    }

    private static CounterpartyScreenings.Request request(PayeeVerdict payee) {
        return new CounterpartyScreenings.Request("screen-" + UUID.randomUUID(),
                new CounterpartySubject("Ana Lima " + UUID.randomUUID().toString().substring(0, 6), CountryCode.of("US"),
                        EntityType.INDIVIDUAL),
                payee, "requester-" + UUID.randomUUID());
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static Map<String, Object> row(CounterpartyScreeningId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT status, review_reason, decision_basis, policy_version, decided_at, decided_by,"
                                + " decision_reason_code, attempts, next_attempt_at FROM kyc.counterparty_screening WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                Map<String, Object> values = new java.util.HashMap<>();
                for (int i = 1; i <= row.getMetaData().getColumnCount(); i++) {
                    values.put(row.getMetaData().getColumnName(i), row.getObject(i));
                }
                return values;
            }
        }
    }

    private static List<String> attempts(CounterpartyScreeningId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT attempt || ':' || verdict || ':' || CASE WHEN evidence_ciphertext IS NULL THEN 'none'"
                                + " ELSE 'evidence' END FROM kyc.counterparty_screening_attempt WHERE screening_id = ? ORDER BY attempt")) {
            select.setObject(1, id.value());
            List<String> rows = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(row.getString(1));
                }
            }
            return rows;
        }
    }

    private static List<String> auditReasons(CounterpartyScreeningId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT reason FROM platform.audit_record WHERE operation = 'kyc.CounterpartyScreeningDecided'"
                                + " AND target_type = 'kyc_counterparty_screening' AND target_id = ? ORDER BY occurred_at, audit_id")) {
            select.setString(1, id.value().toString());
            List<String> rows = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(row.getString(1));
                }
            }
            return rows;
        }
    }

    private static long events(CounterpartyScreeningId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type = 'kyc.CounterpartyScreeningDecided'"
                                + " AND aggregate_id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void makeDue(CounterpartyScreeningId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement(
                        "UPDATE kyc.counterparty_screening SET next_attempt_at = now() - interval '1 second' WHERE id = ?")) {
            update.setObject(1, id.value());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static void refused(String sqlState, String fragment, String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (SQLException refusal) {
            assertThat(refusal.getSQLState()).as(refusal.getMessage()).isEqualTo(sqlState);
            assertThat(refusal.getMessage()).contains(fragment);
            return;
        }
        throw new AssertionError("admitted, but it should be refused (" + fragment + "): " + sql);
    }

    private static String decision(String decision, String code, String narrative) {
        return "{\"decision\":\"" + decision + "\",\"reasonCode\":\"" + code + "\",\"narrative\":\"" + narrative + "\"}";
    }

    /** A signed-in session holding {@code role}, or a plain registered customer when it is null. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "cs." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey()).statusCode())
                .isEqualTo(201);
        if (role != null) {
            UUID identity;
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                }
            }
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
                app.commit();
            }
        }
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        return field(session.body(), "sessionToken");
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    /** A provider answering a scripted verdict, counting calls per screening. */
    static final class Scripted implements CounterpartyScreeningProvider {
        volatile Verdict verdict;
        final AtomicInteger total = new AtomicInteger();
        final Map<CounterpartyScreeningId, AtomicInteger> perScreening = new ConcurrentHashMap<>();
        final Map<CounterpartyScreeningId, CounterpartySubject> subjects = new ConcurrentHashMap<>();

        Scripted(Verdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public Answer screen(CounterpartyScreeningId screening, CounterpartySubject subject) {
            total.incrementAndGet();
            perScreening.computeIfAbsent(screening, any -> new AtomicInteger()).incrementAndGet();
            subjects.put(screening, subject);
            return verdict == Verdict.UNAVAILABLE ? Answer.withoutEvidence(verdict) : Answer.of(verdict, EVIDENCE);
        }

        int calls(CounterpartyScreeningId screening) {
            return perScreening.getOrDefault(screening, new AtomicInteger()).get();
        }
    }

    /** An observer recording what it was told. */
    static final class Recorded implements CounterpartyScreeningObserver {
        final List<CounterpartyScreeningStatus> statuses = new CopyOnWriteArrayList<>();

        @Override
        public void decided(CounterpartyScreeningStatus status) {
            statuses.add(status);
        }
    }

    /** The listener the context composes here - records, and never refuses. */
    static final class RecordingListener implements ScreeningOutcomeListener {
        final Map<CounterpartyScreeningId, CounterpartyScreeningStatus> last = new ConcurrentHashMap<>();

        @Override
        public void decided(Connection unitOfWork, Outcome outcome) {
            last.put(outcome.screening(), outcome.status());
        }

        java.util.Optional<CounterpartyScreeningStatus> statusOf(CounterpartyScreeningId id) {
            return java.util.Optional.ofNullable(last.get(id));
        }
    }

    @TestConfiguration
    static class Listening {

        @Bean
        @Primary
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }
}
