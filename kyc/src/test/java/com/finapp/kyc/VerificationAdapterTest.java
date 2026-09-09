package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adapter against every way a provider can misbehave (`P2-TSK-009`, ADR-0008,
 * {@code INV-LIFE-03}) — driven through {@link SimulatedProvider}, the harness `P0-TSK-037`
 * built for exactly this phase's arrivals, meeting its first production caller.
 *
 * <p>The property under test is the port's <strong>total contract</strong>: provider
 * misbehaviour is a <em>result</em>, never an exception — and the mapping's default is
 * {@code INDETERMINATE}, never success. Every branch of {@code SimulatedProviderClient}'s
 * normalisation is a row of this matrix.
 */
@DisplayName("the verification adapters against the provider failure matrix (P2-TSK-009)")
class VerificationAdapterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS =
            new IdGenerator(CLOCK, new java.security.SecureRandom());

    /** Short, so the timeout tests do not stall the tier; generous against loopback latency. */
    private static final Duration TIMEOUT = Duration.ofMillis(700);

    private static SimulatedProvider provider;
    private static IdentityVerificationAdapter adapter;

    private final VerificationProvider.VerificationSubject subject =
            new VerificationProvider.VerificationSubject(KycCaseId.next(IDS), IDS.next());

    @BeforeAll
    static void start() {
        provider = SimulatedProvider.start();
        adapter = new IdentityVerificationAdapter(URI.create(provider.baseUrl()), TIMEOUT);
    }

    @AfterAll
    static void stop() {
        provider.close();
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    @Test
    @DisplayName("a clear verdict is CLEAR, and the evidence is the bytes received, verbatim")
    void clearIsClear() {
        String body = "{\"status\":\"clear\",\"score\":97}";
        provider.succeedsWith(IdentityVerificationAdapter.PATH, 200, body);

        VerificationProvider.ProviderResult result = adapter.verify(subject);

        assertThat(result.outcome()).isEqualTo(CheckOutcome.CLEAR);
        assertThat(result.evidence().orElseThrow())
                .as("INV-HIST-02: retained verbatim, not our rendering of it")
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a hit verdict is HIT")
    void hitIsHit() {
        provider.succeedsWith(IdentityVerificationAdapter.PATH, 200, "{\"status\":\"hit\"}");

        assertThat(adapter.verify(subject).outcome()).isEqualTo(CheckOutcome.HIT);
    }

    @Test
    @DisplayName("a state nobody mapped is INDETERMINATE, never success - with the evidence kept")
    void unknownStateIsIndeterminate() {
        // ADR-0008's sentence, executable, sent through the harness mode built to send one.
        provider.returnsUnknownState(IdentityVerificationAdapter.PATH, "REVIEW_PENDING_2");

        VerificationProvider.ProviderResult result = adapter.verify(subject);

        assertThat(result.outcome()).isEqualTo(CheckOutcome.INDETERMINATE);
        assertThat(new String(result.evidence().orElseThrow(), StandardCharsets.UTF_8))
                .contains("REVIEW_PENDING_2");
    }

    @Test
    @DisplayName("a timeout is INDETERMINATE with no evidence - and the wait is bounded")
    void timeoutIsIndeterminate() {
        provider.neverResponds(IdentityVerificationAdapter.PATH);

        long before = System.nanoTime();
        VerificationProvider.ProviderResult result = adapter.verify(subject);
        long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(CheckOutcome.INDETERMINATE);
        assertThat(result.evidence()).as("nothing arrived, so there is nothing to retain").isEmpty();
        // The bound is the test's second subject: an unbounded wait is a thread held hostage
        // per in-flight check. Generous margin, because exceeding it is a failure and a tight
        // margin on a loaded machine is a flake (P1-TSK-002's waiting rule).
        assertThat(elapsedMillis)
                .as("the adapter must give up at its configured timeout")
                .isLessThan(TIMEOUT.toMillis() * 8);
    }

    @Test
    @DisplayName("an unavailable provider and a 5xx are INDETERMINATE")
    void unavailableAndServerErrorsAreIndeterminate() {
        provider.isUnavailable(IdentityVerificationAdapter.PATH);
        assertThat(adapter.verify(subject).outcome()).isEqualTo(CheckOutcome.INDETERMINATE);

        provider.reset();
        provider.failsWith(IdentityVerificationAdapter.PATH, 500);
        assertThat(adapter.verify(subject).outcome()).isEqualTo(CheckOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("a malformed body is INDETERMINATE, and the broken bytes are the evidence")
    void malformedBodyIsIndeterminateWithEvidence() {
        provider.respondsWithMalformedBody(IdentityVerificationAdapter.PATH);

        VerificationProvider.ProviderResult result = adapter.verify(subject);

        assertThat(result.outcome()).isEqualTo(CheckOutcome.INDETERMINATE);
        // The unparseable answer is precisely what an investigation of the provider wants.
        assertThat(result.evidence()).isPresent();
    }

    @Test
    @DisplayName("garbage that is not HTTP is INDETERMINATE")
    void garbageIsIndeterminate() {
        provider.respondsWithGarbage(IdentityVerificationAdapter.PATH);

        assertThat(adapter.verify(subject).outcome()).isEqualTo(CheckOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("a received request with a lost response is INDETERMINATE - and provably received")
    void lostResponseIsIndeterminateAndProvablyReceived() {
        // The harness's most important assertion: the provider ACTED and we never learned the
        // outcome. Indistinguishable from a timeout on our side - which is exactly why
        // INV-LIFE-03 demands an explicit indeterminate rather than a guess in either direction
        // - but distinguishable HERE, and the dispatch-before-call choreography is what makes
        // the DISPATCHED row the durable trace of "we asked".
        provider.receivesTheRequestThenLosesTheResponse(IdentityVerificationAdapter.PATH);

        assertThat(adapter.verify(subject).outcome()).isEqualTo(CheckOutcome.INDETERMINATE);
        assertThat(provider.requestCount(IdentityVerificationAdapter.PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("a slow answer within the timeout is an answer, not a failure")
    void slowButWithinTimeoutSucceeds() {
        // The other half of the timeout decision: a bound too aggressive turns a working
        // provider into a stream of indeterminate checks.
        provider.respondsAfter(
                IdentityVerificationAdapter.PATH,
                Duration.ofMillis(100),
                200,
                "{\"status\":\"clear\"}");

        assertThat(adapter.verify(subject).outcome()).isEqualTo(CheckOutcome.CLEAR);
    }

    @Test
    @DisplayName("the document adapter shares the contract and asks its own question")
    void documentAdapterSharesTheContract() {
        DocumentVerificationAdapter documents =
                new DocumentVerificationAdapter(URI.create(provider.baseUrl()), TIMEOUT);
        provider.succeedsWith(DocumentVerificationAdapter.PATH, 200, "{\"status\":\"clear\"}");

        assertThat(documents.checkType()).isEqualTo(CheckType.DOCUMENT);
        assertThat(documents.verify(subject).outcome()).isEqualTo(CheckOutcome.CLEAR);
        assertThat(provider.requestCount(DocumentVerificationAdapter.PATH)).isEqualTo(1);
        assertThat(provider.requestCount(IdentityVerificationAdapter.PATH))
                .as("each adapter asks its own path")
                .isZero();
    }
}
