package com.finapp.app.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Where the application's database actually is, and what it will connect with.
 *
 * <p>Shared by {@link DatabaseCredentialGuard} and {@link TransportSecurityGuard} because both ask
 * the same question — <em>is this database on this machine?</em> — and two copies of that predicate
 * would drift. This repository has found drift between duplicated definitions often enough to treat
 * it as the default outcome rather than a risk.
 *
 * <p>Every accessor answers about the <strong>effective</strong> configuration, not the obvious
 * one. Hikari's own properties are bound after the generic {@code spring.datasource.*} pair and
 * win, so a guard reading only the generic pair inspects a value nothing connects with — which was
 * a real bypass, proven by starting the application against a remote host with
 * {@code spring.datasource.url} left on loopback.
 */
/*
 * Public since `P1-TSK-017`, and that is ADR-0020's recorded debt being paid rather than scope
 * creep. Its row said the loopback guard "covers one credential" and named the trigger as "the
 * second credential, which is Phase 1's authentication" - the MFA encryption key is that credential,
 * and it needs the same question answered: is this database on this machine? A second definition of
 * that would be two answers free to disagree.
 */
public final class DatabaseEndpoint {

    private DatabaseEndpoint() {}

    /** What the pool will connect with, preferring Hikari's own property where it is set. */
    public static String url(Environment environment) {
        return hikari(environment, "jdbc-url")
                .orElseGet(() -> environment.getProperty("spring.datasource.url"));
    }

    static String password(Environment environment) {
        return hikari(environment, "password")
                .orElseGet(() -> environment.getProperty("spring.datasource.password"));
    }

    /**
     * True when every host in the URL is on this machine.
     *
     * <p>False when there are none, which is deliberate: a URL whose host cannot be read has not
     * been shown to be local, and both guards fail closed on that rather than assuming a shape
     * they did not recognise is harmless.
     */
    public static boolean isEntirelyLoopback(String jdbcUrl) {
        List<String> hosts = hostsOf(jdbcUrl);
        return !hosts.isEmpty() && hosts.stream().allMatch(DatabaseEndpoint::isLoopback);
    }

    /**
     * Every host in a JDBC URL, because PostgreSQL accepts a comma-separated list for failover and
     * checking only the first would let the second be anywhere at all.
     */
    static List<String> hostsOf(String jdbcUrl) {
        List<String> hosts = new ArrayList<>();
        if (jdbcUrl == null) {
            return hosts;
        }
        int authorityStart = jdbcUrl.indexOf("//");
        if (authorityStart < 0) {
            return hosts;
        }
        String rest = jdbcUrl.substring(authorityStart + 2);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == ';') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        // user:pass@host - the credential form of a URL. Discarded, never parsed.
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        for (String candidate : authority.split(",")) {
            String host = stripPort(candidate.trim());
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private static String stripPort(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            // IPv6 literal: the colons inside the brackets are part of the address.
            int close = hostAndPort.indexOf(']');
            return close < 0 ? hostAndPort : hostAndPort.substring(1, close);
        }
        int colon = hostAndPort.indexOf(':');
        return colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
    }

    /** No DNS lookup: resolution is not this platform's to trust, and it must not block startup. */
    static boolean isLoopback(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.equals("::1") || lower.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        // The whole 127.0.0.0/8 block, not 127.0.0.1 alone.
        return lower.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    static Optional<String> hikari(Environment environment, String property) {
        return Optional.ofNullable(
                        Binder.get(environment)
                                .bind("spring.datasource.hikari." + property, String.class)
                                .orElse(null))
                .filter(value -> !value.isBlank());
    }
}
