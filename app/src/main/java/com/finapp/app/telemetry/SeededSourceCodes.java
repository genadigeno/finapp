package com.finapp.app.telemetry;

import com.finapp.settlement.SettlementFileStore;
import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * A reconciliation source id onto its declared code (`P8-TSK-024`) - what a counter's
 * {@code source} tag is published as. The register's ids are seeded literals, never deleted, so
 * one read serves the process; an id it does not know reloads - at most once a minute, so an
 * unseeded id never costs a connection per count - and an id still unknown is answered empty:
 * the counter is then not counted rather than tagged with an invented value. A per-instance
 * read cache, deciding nothing.
 */
@Slf4j
public final class SeededSourceCodes implements Function<UUID, Optional<String>> {

    private final SettlementFileStore<Connection> store;
    private final DataSource dataSource;
    private final java.time.Clock clock;
    private final AtomicReference<Map<UUID, String>> codes = new AtomicReference<>(Map.of());

    /** The last reload - an unknown id reloads at most once per floor, never per count. */
    private final AtomicReference<java.time.Instant> reloadedAt =
            new AtomicReference<>(java.time.Instant.EPOCH);

    static final java.time.Duration RELOAD_FLOOR = java.time.Duration.ofMinutes(1);

    public SeededSourceCodes(
            SettlementFileStore<Connection> store, DataSource dataSource, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<String> apply(UUID sourceId) {
        String code = codes.get().get(sourceId);
        if (code == null) {
            java.time.Instant now = clock.instant();
            java.time.Instant last = reloadedAt.get();
            if (last.plus(RELOAD_FLOOR).isBefore(now) && reloadedAt.compareAndSet(last, now)) {
                code = reload().get(sourceId);
            }
        }
        return Optional.ofNullable(code);
    }

    private Map<UUID, String> reload() {
        try (Connection connection = dataSource.getConnection()) {
            Map<UUID, String> fresh =
                    store.sources(connection).stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    SettlementFileStore.SourceRow::id,
                                    SettlementFileStore.SourceRow::code));
            codes.set(fresh);
            return fresh;
        } catch (java.sql.SQLException | RuntimeException unreadable) {
            log.warn(
                    "The source register could not be read; the count goes untagged and"
                            + " uncounted: {}",
                    unreadable.getClass().getSimpleName());
            return codes.get();
        }
    }
}
