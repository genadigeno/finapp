package com.finapp.app.security;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the application would reach an external provider off this machine
 * without TLS (the Phase 7 -&gt; 8 transition).
 *
 * <p>{@code SECURITY_ARCHITECTURE.md} listed the application-to-provider hop as "TLS with
 * certificate verification, Phase 5, with the first adapter" — and the gate found it enforced
 * nowhere: every provider base URL was any URI, while Phase 7 widened what crosses the hop to
 * the instant scheme's bearer key, a customer's single-use linking grant, withdrawal
 * destinations and decrypted dispute evidence ({@code INV-DSP-03} material). A deployment
 * setting {@code http://psp.internal} started cleanly and sent all of it in the clear.
 *
 * <p>The rule is {@link KafkaTransportGuard}'s: not "verify-full" but "not plaintext" — the
 * JDK's {@code HttpClient} verifies the server certificate and its hostname by default over
 * {@code https}, and no adapter here configures otherwise, so an {@code https} base URL is a
 * verified hop. Plain {@code http} is allowed only to loopback, where the simulated providers
 * and the tests live, and fails closed: a URL whose host cannot be read has not been shown to be
 * local. Every provider this build can be configured with is checked, absent ones skipped (an
 * unconfigured provider is the honest 503, never a hop).
 *
 * <p>Since `P8-TSK-021` the settlement pull sources join it (ADR-0066 §1): a pulled report
 * moves money over a confined credential, so each source URL must be {@code https} or
 * {@code sftp} off loopback — {@code sftp} admitted for these alone, a file-transfer channel a
 * counterparty may offer — and plain transport only to loopback.
 */
@Component
public class ProviderTransportGuard {

    /** Every provider base URL this build reads — one list, so a new adapter's is added here. */
    static final List<String> PROVIDER_URLS =
            List.of(
                    "finapp.payments.provider.url",
                    "finapp.payments.instant.url",
                    "finapp.paymentmethods.tokenisation.url",
                    "finapp.kyc.provider.url",
                    "finapp.merchant.payout.provider.url",
                    // P9-TSK-005: the independent reference rate (ADR-0075).
                    "finapp.fx.reference.url",
                    // P9-TSK-006: the FX provider fx-sim-a (ADR-0075, ADR-0077).
                    "finapp.fx.provider.url");

    /**
     * Every settlement pull source URL (`P8-TSK-021`): {@code https} or {@code sftp} off
     * loopback.
     */
    static final List<String> SOURCE_URLS =
            List.of(
                    "finapp.settlement.psp.report.url",
                    "finapp.settlement.scheme.report.url",
                    "finapp.settlement.payout.report.url",
                    "finapp.settlement.bank.statement.url");

    ProviderTransportGuard(Environment environment) {
        for (String property : PROVIDER_URLS) {
            verify(property, Optional.ofNullable(environment.getProperty(property)));
        }
        for (String property : SOURCE_URLS) {
            verifySource(property, Optional.ofNullable(environment.getProperty(property)));
        }
    }

    /**
     * A settlement pull source's URL (`P8-TSK-021`, ADR-0066 §1): {@link #verify}'s rule, with
     * {@code sftp} admitted off loopback beside {@code https}.
     *
     * @throws IllegalStateException when a source off this machine would be reached over a
     *     cleartext channel, or the URL cannot be shown to be either
     */
    public static void verifySource(String property, Optional<String> configured) {
        check(property, configured, true);
    }

    /**
     * @param property the setting's name, for a failure that names its own fix
     * @param configured its value, or empty when the provider is not configured
     * @throws IllegalStateException when a provider off this machine would be reached without
     *     TLS, or the URL cannot be shown to be either
     */
    public static void verify(String property, Optional<String> configured) {
        check(property, configured, false);
    }

    private static void check(String property, Optional<String> configured, boolean sftp) {
        // Absent, blank or "false" is an unconfigured provider - exactly what each provider's
        // @ConditionalOnProperty reads as absent (a deployment and the tests switch one off by
        // setting it to false): no hop exists to guard.
        if (configured.isEmpty()
                || configured.get().isBlank()
                || configured.get().trim().equalsIgnoreCase("false")) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(configured.get().trim());
        } catch (IllegalArgumentException unparseable) {
            throw refused(property, "cannot be read as a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw refused(property, "names no host");
        }
        if (scheme.equals("https") || (sftp && scheme.equals("sftp"))) {
            return;
        }
        String bareHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        if (scheme.equals("http") && DatabaseEndpoint.isLoopback(bareHost)) {
            // A simulated provider on this machine: the hop never leaves the host.
            return;
        }
        throw refused(
                property,
                "points at '" + host + "' over " + (scheme.isEmpty() ? "no scheme" : scheme)
                        + ", which is not this machine - every credential, grant, destination"
                        + " and evidence document would cross the network in the clear");
    }

    private static IllegalStateException refused(String property, String why) {
        return new IllegalStateException(
                "Refusing to start: " + property + " " + why + ". Use an https URL (the JDK"
                        + " client verifies the certificate and hostname), or point it at"
                        + " loopback. See docs/architecture/SECURITY_ARCHITECTURE.md.");
    }
}
