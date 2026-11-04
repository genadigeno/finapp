package com.finapp.app.accounting;

import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.RegulatorySubmissions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.accounting.regulatory.attention{kind}} (`P14-TSK-018`; {@code PHASE_14_PLAN.md} section 16; {@code INV-ACC-13}): what stands on the regulatory submissions needing someone's eye, so a return never delivered, or overtaken by a reopen, is not
 * discovered by accident.
 *
 * <ul>
 *   <li>{@code overdue}: {@code SUBMITTED}, past its answer horizon;</li>
 *   <li>{@code resends_exhausted}: {@code SUBMITTED}, the provider's definite "not received" last, no resend left: a person's door is open;</li>
 *   <li>{@code overtaken}: sent or acknowledged, its month's revision moved, no correction standing: the return is frozen and disagrees with the books;</li>
 *   <li>{@code stale_unsent}: not yet sent, its month's revision moved: it can only be withdrawn or refused;</li>
 *   <li>{@code contrary}: answers kept flagged contrary, ever.</li>
 * </ul>
 *
 * <p><strong>From the database, on the database's clock</strong>: every instance publishes the same numbers with no leader - aggregate with {@code max()}, never {@code sum()}. No value (NaN at Micrometer's edge) when unreadable, never a zero for a read that failed. Cached
 * for 15 s ({@code CloseRunStallMetrics}' floor). The tag closed in the code: a count names no return. Alerts: `P14-TSK-020`'s.
 */
@Slf4j
public final class RegulatoryAttentionMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_regulatory_attention}, the plan's name. */
    public static final String ATTENTION = "finapp.accounting.regulatory.attention";

    /** The {@code kind} tag's values. */
    public static final List<String> KINDS = List.of("overdue", "resends_exhausted", "overtaken", "stale_unsent", "contrary");

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final RegulatorySubmissions submissions;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, null));

    private record Cached(Instant takenAt, RegulatorySubmissionStore.Attention attention) {}

    public RegulatoryAttentionMetrics(RegulatorySubmissions submissions, Connections connections, Clock clock, MeterRegistry registry) {
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        gauge(registry, "overdue", RegulatorySubmissionStore.Attention::overdue);
        gauge(registry, "resends_exhausted", RegulatorySubmissionStore.Attention::resendsExhausted);
        gauge(registry, "overtaken", RegulatorySubmissionStore.Attention::overtaken);
        gauge(registry, "stale_unsent", RegulatorySubmissionStore.Attention::staleUnsent);
        gauge(registry, "contrary", RegulatorySubmissionStore.Attention::contrary);
    }

    private void gauge(MeterRegistry registry, String kind, ToLongFunction<RegulatorySubmissionStore.Attention> read) {
        Gauge.builder(ATTENTION, () -> {
            RegulatorySubmissionStore.Attention attention = attention();
            return attention == null ? null : (Number) read.applyAsLong(attention);
        })
                .tag("kind", kind)
                .description("What stands on the regulatory submissions needing someone's eye; no value when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The cached counts; {@code null} - no value - when the read failed. */
    RegulatorySubmissionStore.Attention attention() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current.attention();
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Cached(clock.instant(), submissions.attention(connection));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("What stands on the regulatory submissions could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), null);
        }
        cached.set(fresh);
        return fresh.attention();
    }
}
