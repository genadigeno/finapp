package com.finapp.app.accounting;

import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.RegulatorySubmissions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * {@code finapp.accounting.regulatory.attention{kind}} (`P14-TSK-018`; {@code PHASE_14_PLAN.md} section 16; {@code INV-ACC-13}): what stands on the regulatory submissions needing someone's eye, so a return never delivered, or overtaken by a reopen, is not
 * discovered by accident.
 *
 * <ul>
 *   <li>{@code overdue}: {@code SUBMITTED}, past its answer horizon;</li>
 *   <li>{@code resends_exhausted}: {@code SUBMITTED}, the provider's definite "not received" last, no resend left: a person's door is open;</li>
 *   <li>{@code overtaken}: sent or acknowledged, its month's revision moved, and no acknowledged return of its form at the month's present revision: the regulator holds, or may hold, a figure the books no longer say;</li>
 *   <li>{@code stale_unsent}: not yet sent, its month's revision moved: it can only be withdrawn or refused.</li>
 * </ul>
 *
 * <p>A contrary answer is no state that clears, so it is not here: an alert reads {@code increase} of {@code finapp.accounting.regulatory.submission{outcome="contrary"}}, and the standing list names the return.
 *
 * <p><strong>From the database, on the database's clock</strong>: every instance publishes the same numbers with no leader - aggregate with {@code max()}, never {@code sum()}. No value (NaN at Micrometer's edge) when unreadable, never a zero for a read that failed. Bounded by {@link AccountingGaugeRead} (`P14-TSK-020`: it had no statement timeout of its own until then), cached
 * for 15 s ({@code CloseRunStallMetrics}' floor). The tag closed in the code: a count names no return. Alerts: `P14-TSK-020`'s, {@code overdue} and {@code overtaken} on their growth, since each can hold for good.
 */
public final class RegulatoryAttentionMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_regulatory_attention}, the plan's name. */
    public static final String ATTENTION = "finapp.accounting.regulatory.attention";

    /** The {@code kind} tag's values. */
    public static final List<String> KINDS = List.of("overdue", "resends_exhausted", "overtaken", "stale_unsent");

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final AccountingGaugeRead<RegulatorySubmissionStore.Attention> read;

    public RegulatoryAttentionMetrics(RegulatorySubmissions submissions, GlSnapshots snapshots, AccountingGaugeRefresher refresher, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(submissions, "submissions");
        Objects.requireNonNull(registry, "registry");
        this.read = new AccountingGaugeRead<>("What stands on the regulatory submissions", snapshots, refresher, clock, MIN_REFRESH, submissions::attention);
        gauge(registry, "overdue", RegulatorySubmissionStore.Attention::overdue);
        gauge(registry, "resends_exhausted", RegulatorySubmissionStore.Attention::resendsExhausted);
        gauge(registry, "overtaken", RegulatorySubmissionStore.Attention::overtaken);
        gauge(registry, "stale_unsent", RegulatorySubmissionStore.Attention::staleUnsent);
    }

    private void gauge(MeterRegistry registry, String kind, ToLongFunction<RegulatorySubmissionStore.Attention> value) {
        Gauge.builder(ATTENTION, () -> {
            RegulatorySubmissionStore.Attention attention = attention();
            return attention == null ? null : (Number) value.applyAsLong(attention);
        })
                .tag("kind", kind)
                .description("What stands on the regulatory submissions needing someone's eye; no value when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The cached counts; {@code null} - no value - when the read failed. */
    RegulatorySubmissionStore.Attention attention() {
        return read.current();
    }
}
