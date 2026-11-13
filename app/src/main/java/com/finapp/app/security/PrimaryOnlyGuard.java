package com.finapp.app.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the PostgreSQL driver could choose a server that is not the primary (`P16-TSK-012`, ADR-0142;
 * {@code PHASE_GATES.md} §5 Phase 16 criterion 6; ADR-0129 decision 7).
 *
 * <h2>The driver picks the server, not the code</h2>
 *
 * <p>Both of the application's pools connect to the one address {@link DatabaseEndpoint#url} reads (the writer's, and the
 * reporting pool built from it). Code alone cannot route a read to a replica. The driver can: pgjdbc 42.7 accepts a list of hosts
 * ({@code //a,b/finapp}) and chooses among them by {@code targetServerType}, whose default, {@code any}, takes the first host that
 * answers, primary or standby. So a deployment's URL naming a readable standby beside the primary, or a {@code targetServerType} of
 * {@code secondary}, {@code preferSecondary} or {@code any}, would put connections of either pool on a copy behind the primary by its
 * replay lag, with nothing in the code changed and every other check green. On a standby a money-moving door's first write (its
 * idempotency claim) fails, so no effect lands there; but a read answers stale, and report generation, which only reads, would build
 * a run's content from the copy and complete it through the writer.
 *
 * <h2>What is refused</h2>
 *
 * <ul>
 *   <li>a {@code targetServerType} set to anything but {@code primary} (or its legacy spelling {@code master}, which the driver reads
 *       as {@code primary}), in the URL's query or in the writer's driver properties, whatever the key's case: a value the driver
 *       would ignore or refuse is refused here too, because "it fails at the first connection" is a later and quieter refusal;</li>
 *   <li>a URL whose hosts are not exactly one readable host - several, or a {@code host}, {@code PGHOST} or {@code service}
 *       parameter that {@link DatabaseEndpoint#hostsOf} cannot read - unless {@code targetServerType=primary} is set under the
 *       driver's own spelling of the key, so the driver checks each server and takes only a primary.</li>
 * </ul>
 *
 * <p>One host and no setting is the platform's shape (the managed database's one endpoint, which moves with a failover; the
 * {@code kind} overlay's one Service). A single host that is itself a replica is a deployment pointing every pool at the wrong
 * server: every write fails, which the readiness and the first command show, and nothing here can tell it from the primary without
 * connecting.
 *
 * <p>Precedence-independent, as {@link TransportSecurityGuard} is: every configured source must say the same safe thing, so the
 * guard is never wrong about which source the driver reads first.
 */
@Component
public class PrimaryOnlyGuard {

    /** The driver's key, exactly as it reads it. */
    static final String KEY = "targetServerType";

    /** What the driver reads as "a primary only". */
    static final Set<String> PRIMARY = Set.of("primary", "master");

    /** Where a deployment sets it as a driver property. Named in the failure so the fix needs no documentation. */
    static final String SETTING = "spring.datasource.hikari.data-source-properties." + KEY;

    PrimaryOnlyGuard(Environment environment) {
        verify(DatabaseEndpoint.url(environment), Binder.get(environment)
                .bind("spring.datasource.hikari.data-source-properties", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of()));
    }

    /**
     * @param jdbcUrl what the writer's pool connects with, query string included; the reporting pool connects with the same
     * @param driverProperties the writer's driver properties, which the reporting pool copies
     * @throws IllegalStateException if the driver could choose a server other than the primary
     */
    public static void verify(String jdbcUrl, Map<String, String> driverProperties) {
        List<String> configured = new ArrayList<>();
        boolean exactPrimary = false;
        for (Map.Entry<String, String> setting : settings(jdbcUrl, driverProperties)) {
            if (!setting.getKey().trim().equalsIgnoreCase(KEY)) {
                continue;
            }
            String value = setting.getValue() == null ? "" : setting.getValue().trim();
            configured.add(value);
            if (!PRIMARY.contains(value)) {
                throw new IllegalStateException("Refusing to start: " + KEY + "=" + value + " lets the PostgreSQL driver choose a"
                        + " server that is not the primary, so a read could be answered by a copy behind it. Every connection the"
                        + " platform makes reads the primary (ADR-0142). Set " + KEY + "=primary, or remove it with a single host.");
            }
            exactPrimary |= setting.getKey().trim().equals(KEY);
        }
        if (DatabaseEndpoint.hostsOf(jdbcUrl).size() == 1) {
            return;
        }
        if (!exactPrimary) {
            throw new IllegalStateException("Refusing to start: the database URL does not name exactly one host this guard can read"
                    + " (" + DatabaseEndpoint.hostsOf(jdbcUrl).size() + " read), and " + (configured.isEmpty()
                            ? "no " + KEY + " is set, so the driver takes the first host that answers, primary or standby"
                            : KEY + " is not set under the driver's own spelling, which it would ignore")
                    + ". Set " + SETTING + "=primary (or ?" + KEY + "=primary in the URL), so the driver takes only a primary.");
        }
    }

    /** Every setting of the URL's query, then every driver property, in that order. */
    private static List<Map.Entry<String, String>> settings(String jdbcUrl, Map<String, String> driverProperties) {
        List<Map.Entry<String, String>> settings = new ArrayList<>();
        if (jdbcUrl != null) {
            int query = jdbcUrl.indexOf('?');
            if (query >= 0) {
                for (String parameter : jdbcUrl.substring(query + 1).split("&")) {
                    String[] pair = parameter.split("=", 2);
                    settings.add(Map.entry(pair[0], pair.length == 2 ? pair[1] : ""));
                }
            }
        }
        driverProperties.forEach((key, value) -> settings.add(Map.entry(key, Optional.ofNullable(value).orElse(""))));
        return settings;
    }
}
