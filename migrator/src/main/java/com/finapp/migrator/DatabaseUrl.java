package com.finapp.migrator;

import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.postgresql.Driver;

/**
 * Whether a JDBC URL may be used, read the way the driver will read it ({@code THREAT_MODEL.md} B4; ADR-0023).
 *
 * <p>The application's guard ({@code DatabaseEndpoint}, in {@code app}) answers a harder question, for a URL that may
 * legitimately carry anything Hikari accepts. This one can be narrower, because a migrator's URL has one honest shape:
 * one host, one database, TLS parameters. So it takes the host from {@link Driver#parseURL}, the code that will connect,
 * rather than from a reading of its own, and it refuses every query parameter that is not on a short list. That is what
 * closes the class: {@code host}, {@code PGHOST} and {@code service} move the host the driver connects to away from the
 * one in the authority; {@code sslfactory} and {@code sslhostnameverifier} can make {@code verify-full} verify nothing;
 * {@code options} can set {@code lock_timeout} back to zero; {@code user} and {@code password} would put a credential in
 * a value that is logged.
 *
 * <p>Fails closed: a URL the driver cannot parse, or with more than one host, has not been shown to be anything.
 */
final class DatabaseUrl {

    /** Everything a migrator's URL may carry. Anything else is refused by name. */
    private static final Set<String> PERMITTED_PARAMETERS =
            Set.of("sslmode", "sslrootcert", "connectTimeout", "socketTimeout", "loginTimeout", "tcpKeepAlive");

    /** The hosts that do not leave the machine. No lookup: resolution is not this process's to trust. */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private static final String PREFIX = "jdbc:postgresql://";

    private DatabaseUrl() {}

    /**
     * @return true when the one host the URL names is this machine
     * @throws RefusedConfiguration when the URL must not be used
     */
    static boolean verify(String url) {
        if (url == null || url.isBlank()) {
            throw new RefusedConfiguration("FINAPP_DB_URL is required: a Job with no URL must not guess one");
        }
        if (!url.startsWith(PREFIX)) {
            throw new RefusedConfiguration("FINAPP_DB_URL must start with " + PREFIX);
        }
        verifyParameters(url);
        Properties parsed = Driver.parseURL(url, null);
        if (parsed == null) {
            throw new RefusedConfiguration("FINAPP_DB_URL could not be parsed by the driver");
        }
        String host = parsed.getProperty("PGHOST", "");
        if (host.isEmpty() || host.contains(",")) {
            throw new RefusedConfiguration("FINAPP_DB_URL must name exactly one host");
        }
        boolean loopback = LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        if (!loopback && !"verify-full".equals(parsed.getProperty("sslmode"))) {
            throw new RefusedConfiguration("FINAPP_DB_URL names a host off this machine and does not set"
                    + " sslmode=verify-full: the driver's default connects unencrypted without saying so");
        }
        return loopback;
    }

    private static void verifyParameters(String url) {
        int query = url.indexOf('?');
        if (query < 0) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String parameter : url.substring(query + 1).split("&", -1)) {
            if (parameter.isEmpty()) {
                continue;
            }
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            if (!PERMITTED_PARAMETERS.contains(name)) {
                throw new RefusedConfiguration("FINAPP_DB_URL carries a parameter this process does not accept: " + name);
            }
            if (!seen.add(name)) {
                throw new RefusedConfiguration("FINAPP_DB_URL sets a parameter twice: " + name);
            }
        }
    }
}
