package com.finapp.app.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the application would reach a database off this machine without verified
 * TLS.
 *
 * <h2>The default this exists to stop normalising</h2>
 *
 * <p>{@code P0-TSK-034}'s acceptance criterion is that "local setup does not normalise insecure
 * defaults into later environments". The PostgreSQL driver supplies exactly such a default, and it
 * was measured rather than assumed. Against this repository's own container, which runs with
 * {@code ssl = off}:
 *
 * <table>
 *   <caption>What the driver does</caption>
 *   <tr><td>{@code sslmode} unset</td><td>connects, <strong>unencrypted</strong>, silently</td></tr>
 *   <tr><td>{@code sslmode=prefer}</td><td>connects, <strong>unencrypted</strong>, silently</td></tr>
 *   <tr><td>{@code sslmode=require}</td><td>refused</td></tr>
 *   <tr><td>{@code sslmode=verify-full}</td><td>refused</td></tr>
 * </table>
 *
 * <p>The platform sets no {@code sslmode}. Locally that is correct — the database is on loopback
 * and the container offers no TLS. But the configuration is the same file a deployment inherits,
 * and there it would connect to a remote database in plaintext with <em>nothing saying so</em>:
 * the pool connects, readiness reports UP, and every credential, amount and account identifier
 * crosses the network in the clear. That is the shape the criterion forbids, and a comment saying
 * "remember to set sslmode" is not a control.
 *
 * <h2>Why `verify-full` and not `require`</h2>
 *
 * <p>{@code require} encrypts and verifies nothing. It stops passive eavesdropping and does not
 * stop an active attacker presenting their own certificate, which on a financial platform is the
 * threat that matters — the attacker who can reach the database's network path is the one worth
 * defending against. {@code verify-ca} checks the issuer but not the hostname, so it still accepts
 * a valid certificate for a different host. Only {@code verify-full} checks both.
 *
 * <p>The cost is that a deployment must supply a trust anchor. That is the correct cost: it is
 * the deployment asserting which database it trusts, which is exactly the assertion being made.
 *
 * <h2>Scope</h2>
 *
 * <p>PostgreSQL only, because it is the only hop that exists. There is no Kafka or Redis client on
 * the classpath, so a guard for those connections would be guarding nothing — the expectations for
 * them are documented in {@code SECURITY_ARCHITECTURE.md} and become enforceable with their first
 * client.
 *
 * <p>Fails closed, like {@link DatabaseCredentialGuard}: a URL whose host cannot be read has not
 * been shown to be local.
 */
@Component
public class TransportSecurityGuard {

    /** The only mode that both encrypts and authenticates the server. */
    static final String REQUIRED_MODE = "verify-full";

    /** How a deployment supplies it. Named in the failure so the fix needs no documentation. */
    static final String SETTING = "spring.datasource.hikari.data-source-properties.sslmode";

    TransportSecurityGuard(Environment environment) {
        verify(
                DatabaseEndpoint.url(environment),
                DatabaseEndpoint.hikari(environment, "data-source-properties.sslmode"));
    }

    /**
     * @param jdbcUrl what the pool will actually connect with, query string included
     * @param sslModeProperty {@code spring.datasource.hikari.data-source-properties.sslmode}, or
     *     empty when it is not set
     * @throws IllegalStateException if a non-local database would be reached without verified TLS
     */
    public static void verify(String jdbcUrl, Optional<String> sslModeProperty) {
        if (DatabaseEndpoint.isEntirelyLoopback(jdbcUrl)) {
            // A database on this machine, reached over loopback, which does not leave the host.
            // Requiring TLS here would mean every developer provisioning certificates for a
            // container - and a setup step that elaborate is one people work around, which costs
            // more security than it buys.
            return;
        }

        // BOTH sources are checked, and every one that is set must be verify-full.
        //
        // `sslmode` can be given in the JDBC URL's query string or as a data-source property, and
        // the URL was measured to win in both directions - `url=require, props=disable` is refused
        // and `url=disable, props=require` connects. That is a driver implementation detail, so
        // relying on it would make this control correct only for as long as the detail holds.
        // Requiring that no configured source is weaker is precedence-independent: it cannot be
        // wrong about which one the driver picks, because both must say the same safe thing.
        List<String> configured = new ArrayList<>();
        fromUrl(jdbcUrl).ifPresent(configured::add);
        sslModeProperty.ifPresent(configured::add);

        boolean everySourceVerifies =
                !configured.isEmpty()
                        && configured.stream()
                                .map(m -> m.toLowerCase(Locale.ROOT))
                                .allMatch(REQUIRED_MODE::equals);
        if (everySourceVerifies) {
            return;
        }

        String mode =
                configured.stream()
                        .map(m -> m.toLowerCase(Locale.ROOT))
                        .filter(m -> !REQUIRED_MODE.equals(m))
                        .findFirst()
                        .orElse("");

        throw new IllegalStateException(
                "Refusing to start: the database at "
                        + DatabaseEndpoint.hostsOf(jdbcUrl)
                        + " is not on this machine, and "
                        + (mode.isEmpty()
                                ? "no sslmode is configured. The PostgreSQL driver's default"
                                        + " connects unencrypted without reporting it"
                                : "sslmode=" + mode + " does not verify the server's certificate")
                        + ". Set "
                        + SETTING
                        + "="
                        + REQUIRED_MODE
                        + " (and a trust anchor). See docs/architecture/SECURITY_ARCHITECTURE.md.");
    }

    static Optional<String> fromUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Optional.empty();
        }
        int query = jdbcUrl.indexOf('?');
        if (query < 0) {
            return Optional.empty();
        }
        for (String parameter : jdbcUrl.substring(query + 1).split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("sslmode")) {
                return Optional.of(pair[1].trim()).filter(value -> !value.isEmpty());
            }
        }
        return Optional.empty();
    }
}
