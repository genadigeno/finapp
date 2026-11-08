package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.BreakType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.accounting.break.aged{type}} (`P14-TSK-020`; {@code PHASE_14_PLAN.md} section 16; alert {@code AccountingBreakAged}): per {@link BreakType}, how many breaks have stood {@code OPEN} past {@code finapp.accounting.break.aged-after} on the database's
 * clock. <strong>Why not {@code break.open}</strong> (`P14-TSK-012`'s item 2): that counts every status but {@code RESOLVED}, per generation, and an {@code ACKNOWLEDGED} break, superseded or not, was decided under four eyes and can stand for good, so an alert on it would fire
 * for good and be muted. An age nobody has decided on is the break here. Every type registered from the start, no value when unreadable, aggregate with {@code max()}; bounded by {@link AccountingGaugeRead}, cached 15 s. The tag is the closed enum's name: never a break, a period or a subject.
 */
public final class AccountingBreakAgeMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_break_aged}. */
    public static final String AGED = "finapp.accounting.break.aged";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final AccountingGaugeRead<Map<BreakType, Long>> read;

    public AccountingBreakAgeMetrics(AccountingBreakStore store, Duration agedAfter, GlSnapshots snapshots, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(agedAfter, "agedAfter");
        if (agedAfter.isNegative() || agedAfter.isZero()) {
            throw new IllegalArgumentException("a break's bound is positive: at zero every break raised would read aged");
        }
        this.read = new AccountingGaugeRead<>("The aged accounting breaks", snapshots, clock, MIN_REFRESH,
                connection -> Map.copyOf(store.agedOpenByType(connection, agedAfter)));
        for (BreakType type : BreakType.values()) {
            Gauge.builder(AGED, () -> aged(type))
                    .tag("type", type.name())
                    .description("Accounting breaks OPEN past the bound, per break type; ACKNOWLEDGED ones never count. No value when unreadable."
                            + " Fleet-wide from every instance: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** The cached count for {@code type}; {@code null} - no value - when the read failed. */
    Long aged(BreakType type) {
        Map<BreakType, Long> aged = read.current();
        return aged == null ? null : aged.getOrDefault(type, 0L);
    }
}
