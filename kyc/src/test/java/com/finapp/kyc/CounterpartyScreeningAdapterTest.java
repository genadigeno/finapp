package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CounterpartyScreeningVocabulary.EntityType;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
import java.net.URI;
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
 * The counterparty subject on the existing screening adapter (`P9-TSK-016`, ADR-0081 point 1): its
 * own path, the name JSON-escaped, no bank identifier, the screening id as the idempotency key - and
 * the fail-safe mapping, where nothing arriving is {@code UNAVAILABLE} (retried, nothing cleared) and
 * an unreadable answer is {@code INDETERMINATE} (a person). Neither is ever a success.
 */
@DisplayName("the counterparty screening adapter maps every answer fail-safe (P9-TSK-016)")
class CounterpartyScreeningAdapterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new java.security.SecureRandom());
    private static final Duration TIMEOUT = Duration.ofMillis(700);
    private static final String PATH = ScreeningAdapter.COUNTERPARTY_PATH;

    private static SimulatedProvider provider;

    private final CounterpartySubject subject =
            new CounterpartySubject("Ana \"the\" O\\Brien", CountryCode.of("US"), EntityType.INDIVIDUAL);

    @BeforeAll
    static void start() {
        provider = SimulatedProvider.start();
    }

    @AfterAll
    static void stop() {
        provider.close();
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    private CounterpartyScreeningProvider.Answer ask() {
        return ScreeningAdapter.sanctions(URI.create(provider.baseUrl()), TIMEOUT)
                .screen(CounterpartyScreeningId.next(IDS), subject);
    }

    @Test
    @DisplayName("clear, hit and indeterminate answers map to their verdicts, the bytes kept as evidence")
    void theThreeWordsMap() {
        provider.succeedsWith(PATH, 200, "{\"status\":\"clear\"}");
        assertThat(ask().verdict()).isEqualTo(Verdict.CLEAR);
        assertThat(ask().evidence()).hasValueSatisfying(bytes -> assertThat(new String(bytes)).contains("clear"));
        provider.succeedsWith(PATH, 200, "{\"status\":\"hit\"}");
        assertThat(ask().verdict()).isEqualTo(Verdict.HIT);
        provider.succeedsWith(PATH, 200, "{\"status\":\"indeterminate\"}");
        assertThat(ask().verdict()).isEqualTo(Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("an unknown word, a missing field or a 4xx is INDETERMINATE - a person, never a success")
    void unreadableAnswersGoToAPerson() {
        provider.returnsUnknownState(PATH, "probably_fine");
        assertThat(ask().verdict()).isEqualTo(Verdict.INDETERMINATE);
        provider.respondsWithMalformedBody(PATH);
        assertThat(ask().verdict()).isEqualTo(Verdict.INDETERMINATE);
        provider.succeedsWith(PATH, 404, "{\"status\":\"clear\"}");
        assertThat(ask().verdict()).as("a clear word on a non-200 is not an answer").isEqualTo(Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("a refused connection, a timeout or a 5xx is UNAVAILABLE - retried, nothing cleared")
    void nothingArrivingIsUnavailable() {
        provider.isUnavailable(PATH);
        assertThat(ask().verdict()).isEqualTo(Verdict.UNAVAILABLE);
        provider.neverResponds(PATH);
        assertThat(ask().verdict()).isEqualTo(Verdict.UNAVAILABLE);
        provider.failsWith(PATH, 503);
        assertThat(ask().verdict()).isEqualTo(Verdict.UNAVAILABLE);
        provider.receivesTheRequestThenLosesTheResponse(PATH);
        assertThat(ask().verdict()).isEqualTo(Verdict.UNAVAILABLE);
    }

    @Test
    @DisplayName("the request carries the escaped name, country and entity type, keyed by the screening id")
    void theRequestIsTheSubjectAndNothingMore() {
        provider.succeedsWith(PATH, 200, "{\"status\":\"clear\"}");
        CounterpartyScreeningId id = CounterpartyScreeningId.next(IDS);
        ScreeningAdapter.sanctions(URI.create(provider.baseUrl()), TIMEOUT).screen(id, subject);
        assertThat(provider.bodyValues(PATH)).singleElement().satisfies(body -> assertThat(body)
                .contains("\"name\":\"Ana \\\"the\\\" O\\\\Brien\"")
                .contains("\"country\":\"US\"")
                .contains("\"entityType\":\"INDIVIDUAL\"")
                .contains("\"screeningId\":\"" + id.value() + "\"")
                .doesNotContain("iban")
                .doesNotContain("account"));
        assertThat(provider.headerValues(PATH, "Idempotency-Key")).containsExactly(id.value().toString());
    }

    @Test
    @DisplayName("JSON escaping covers quotes, backslashes and control characters")
    void escaping() {
        assertThat(SimulatedProviderClient.json("a\"b\\c\u0001")).isEqualTo("a\\\"b\\\\c\\u0001");
    }

    @Test
    @DisplayName("a subject's toString never names the counterparty")
    void theSubjectRedacts() {
        assertThat(subject.toString()).doesNotContain("Brien").contains("redacted");
    }
}
