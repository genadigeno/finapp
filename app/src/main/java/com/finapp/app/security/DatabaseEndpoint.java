package com.finapp.app.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     *
     * <p>Empty when any part of the URL could send the driver somewhere this reading does not see
     * (`P15-TSK-026`'s review fix round, measured against the driver this build pins, 42.7.13):
     *
     * <ul>
     *   <li>a {@code host}, {@code PGHOST} or {@code service} query parameter - the driver reads
     *       {@code //localhost:5432/finapp?host=db.prod} as {@code db.prod}, the query winning over
     *       the authority;
     *   <li>an entry that is not strictly {@code host[:port]} or {@code [v6][:port]} - the driver
     *       takes the text before an entry's <em>last</em> colon as its host, so an unbracketed IPv6
     *       literal such as {@code ::ffff:10.0.0.5:5432}, which a first-colon reading saw as an empty
     *       host and dropped, reached 10.0.0.5 beside a loopback first entry; and a {@code @} is no
     *       credential form to this driver but part of the name it resolves.
     * </ul>
     *
     * <p>Empty reads as "not shown to be local", so every guard fails closed on it.
     */
    static List<String> hostsOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return List.of();
        }
        int query = jdbcUrl.indexOf('?');
        if (query >= 0 && namesAHost(jdbcUrl.substring(query + 1))) {
            return List.of();
        }
        String server = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        int authorityStart = server.indexOf("//");
        if (authorityStart < 0) {
            return List.of();
        }
        String rest = server.substring(authorityStart + 2);
        int slash = rest.indexOf('/');
        return hostsOfEntries(slash < 0 ? rest : rest.substring(0, slash), false);
    }

    /**
     * The hosts of a Kafka bootstrap list (`P2-TSK-001`): every entry strictly {@code host:port} or
     * {@code [v6]:port}, or an empty list.
     *
     * <p>Strict because the client is not (`P15-TSK-026`'s review fix round): kafka-clients 4.2.1
     * reads {@code localhost://kafka.internal:9092} as {@code kafka.internal}, skipping any prefix up
     * to the last thing shaped like a host and port, where a first-colon reading saw
     * {@code localhost}. A list holding anything but host-and-port entries - a scheme, a path, a
     * {@code @}, whitespace, an empty entry, a port out of range, an IPv4 octet over 255 - is not one
     * this method can say the client will read the same way, so it reads none of it.
     */
    static List<String> bootstrapHosts(String hostPortList) {
        if (hostPortList == null || hostPortList.isBlank()) {
            return List.of();
        }
        return hostsOfEntries(hostPortList, true);
    }

    private static List<String> hostsOfEntries(String list, boolean portRequired) {
        List<String> hosts = new ArrayList<>();
        for (String candidate : list.split(",", -1)) {
            Optional<String> host = hostOfEntry(candidate.trim(), portRequired);
            if (host.isEmpty()) {
                // One entry this cannot read makes the whole list unread: dropping it would be the
                // failover trap again, the unread entry free to be anywhere.
                return List.of();
            }
            hosts.add(host.get());
        }
        return hosts;
    }

    /** A name or an IPv4 literal, and an optional port: nothing a second host could hide in. */
    private static final Pattern NAMED_ENTRY =
            Pattern.compile("([A-Za-z0-9_.\\-]+)(?::(\\d{1,5}))?");

    /** An IPv6 literal, bracketed so that its colons cannot be read as a port's. */
    private static final Pattern BRACKETED_ENTRY =
            Pattern.compile("\\[([0-9A-Fa-f:.]+)\\](?::(\\d{1,5}))?");

    private static final Pattern DIGITS_AND_DOTS = Pattern.compile("[0-9.]+");

    private static final Pattern IPV4 =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    /** The query parameters the driver takes a host from: {@code host} in any case, {@code PGHOST}, a service file. */
    private static final Set<String> HOST_PARAMETERS = Set.of("host", "pghost", "service");

    private static Optional<String> hostOfEntry(String entry, boolean portRequired) {
        Matcher matcher = BRACKETED_ENTRY.matcher(entry);
        if (!matcher.matches()) {
            matcher = NAMED_ENTRY.matcher(entry);
            if (!matcher.matches()) {
                return Optional.empty();
            }
        }
        String host = matcher.group(1);
        String port = matcher.group(2);
        if (port == null ? portRequired : !isPort(port)) {
            return Optional.empty();
        }
        if (DIGITS_AND_DOTS.matcher(host).matches() && !isIpv4(host)) {
            // 127.999.0.1 is no address, so the resolver would be asked for it as a name.
            return Optional.empty();
        }
        return Optional.of(host);
    }

    private static boolean namesAHost(String queryString) {
        for (String parameter : queryString.split("&")) {
            int equals = parameter.indexOf('=');
            String name = (equals < 0 ? parameter : parameter.substring(0, equals)).trim();
            if (HOST_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPort(String digits) {
        int port = Integer.parseInt(digits);
        return port >= 1 && port <= 65_535;
    }

    private static boolean isIpv4(String host) {
        Matcher octets = IPV4.matcher(host);
        if (!octets.matches()) {
            return false;
        }
        for (int group = 1; group <= 4; group++) {
            if (Integer.parseInt(octets.group(group)) > 255) {
                return false;
            }
        }
        return true;
    }

    /** No DNS lookup: resolution is not this platform's to trust, and it must not block startup. */
    static boolean isLoopback(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.equals("::1") || lower.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        // The whole 127.0.0.0/8 block, not 127.0.0.1 alone - and only addresses in it: 127.999.0.1
        // is a name the resolver would be asked for, not a loopback address.
        return lower.startsWith("127.") && isIpv4(lower);
    }

    static Optional<String> hikari(Environment environment, String property) {
        return Optional.ofNullable(
                        Binder.get(environment)
                                .bind("spring.datasource.hikari." + property, String.class)
                                .orElse(null))
                .filter(value -> !value.isBlank());
    }
}
