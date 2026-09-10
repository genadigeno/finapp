package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.id.IdGenerator;
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
 * The three screening bindings (`P2-TSK-010`): each factory asks its own question on its own
 * path, and a hit is a {@code HIT}.
 *
 * <p>The misbehaviour matrix — timeout, unavailable, 5xx, malformed, garbage, lost response,
 * unknown state — is <strong>cited, not duplicated</strong>: it is a property of the shared
 * {@link SimulatedProviderClient} and is `VerificationAdapterTest`'s subject; a second copy per
 * screening type would be duplication that drifts (`P1-TSK-012`'s rule). What is a property of
 * <em>this</em> class is the type-to-path binding the private constructor makes
 * unconstructible-wrong, so that is what is tested.
 */
@DisplayName("the screening adapters bind type to path (P2-TSK-010)")
class ScreeningAdapterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS =
            new IdGenerator(CLOCK, new java.security.SecureRandom());
    private static final Duration TIMEOUT = Duration.ofMillis(700);

    private static SimulatedProvider provider;

    private final VerificationProvider.VerificationSubject subject =
            new VerificationProvider.VerificationSubject(KycCaseId.next(IDS), IDS.next());

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

    @Test
    @DisplayName("each factory binds its type to its own path, and asks only that path")
    void eachFactoryAsksItsOwnQuestion() {
        URI baseUrl = URI.create(provider.baseUrl());
        record Binding(ScreeningAdapter adapter, CheckType type, String path) {}
        var bindings =
                java.util.List.of(
                        new Binding(
                                ScreeningAdapter.sanctions(baseUrl, TIMEOUT),
                                CheckType.SANCTIONS,
                                ScreeningAdapter.SANCTIONS_PATH),
                        new Binding(
                                ScreeningAdapter.pep(baseUrl, TIMEOUT),
                                CheckType.PEP,
                                ScreeningAdapter.PEP_PATH),
                        new Binding(
                                ScreeningAdapter.adverseMedia(baseUrl, TIMEOUT),
                                CheckType.ADVERSE_MEDIA,
                                ScreeningAdapter.ADVERSE_MEDIA_PATH));

        for (Binding binding : bindings) {
            provider.succeedsWith(binding.path(), 200, "{\"status\":\"clear\"}");
            assertThat(binding.adapter().checkType()).isEqualTo(binding.type());
            assertThat(binding.adapter().verify(subject).outcome())
                    .isEqualTo(CheckOutcome.CLEAR);
        }
        for (Binding binding : bindings) {
            assertThat(provider.requestCount(binding.path()))
                    .as("%s asks %s and nothing else", binding.type(), binding.path())
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a screening hit is a HIT - the answer INV-KYC-04 exists to route to a person")
    void aScreeningHitIsAHit() {
        provider.succeedsWith(ScreeningAdapter.SANCTIONS_PATH, 200, "{\"status\":\"hit\"}");

        ScreeningAdapter sanctions =
                ScreeningAdapter.sanctions(URI.create(provider.baseUrl()), TIMEOUT);

        assertThat(sanctions.verify(subject).outcome()).isEqualTo(CheckOutcome.HIT);
    }
}
