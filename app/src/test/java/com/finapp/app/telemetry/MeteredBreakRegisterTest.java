package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.telemetry.Spans;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.Severity;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.reconciliation.break.raised} at the one door every break is born through
 * (`P8-TSK-024`): a raise this transaction CREATED counts once by type and the severity the
 * register assigned; a converged raise - ten sweepers raising one discrepancy - counts nothing;
 * the register's answer is returned unchanged and its failure is never swallowed.
 */
@DisplayName("the metered break register (P8-TSK-024)")
class MeteredBreakRegisterTest {

    private static final Connection NO_CONNECTION = null;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReconciliationOutcomeMeters meters =
            new ReconciliationOutcomeMeters(
                    registry, SettlementSources.of(List.of()), id -> Optional.empty(),
                    Spans.NONE);

    private static BreakRegister.NewBreak newBreak(BreakType type, BreakCause cause) {
        return new BreakRegister.NewBreak(
                UUID.randomUUID(),
                type,
                cause,
                BreakRegister.Subject.expectation(UUID.randomUUID()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Money.ofMinorUnits(12_34, CurrencyCode.of("EUR")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Actor.SYSTEM,
                Instant.parse("2026-09-29T12:00:00Z"),
                CorrelationId.of("metered-break-register-test"));
    }

    private double raised(String type, String severity) {
        Counter counter =
                registry.find(ReconciliationOutcomeMeters.BREAK_RAISED)
                        .tag("type", type)
                        .tag("severity", severity)
                        .counter();
        assertThat(counter).as("break.raised{%s,%s} exists", type, severity).isNotNull();
        return counter.count();
    }

    private double everyRaise() {
        return registry.find(ReconciliationOutcomeMeters.BREAK_RAISED).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    @Test
    @DisplayName("a created raise counts once, by type and the register's severity, in lower"
            + " case - and the register's answer is returned unchanged")
    void aCreatedRaiseCountsOnce() {
        BreakRegister.Raised answer =
                new BreakRegister.Raised(true, UUID.randomUUID(), Severity.CRITICAL);
        AtomicInteger delegated = new AtomicInteger();
        BreakRegister metered =
                new MeteredBreakRegister(
                        (unitOfWork, newBreak) -> {
                            delegated.incrementAndGet();
                            return answer;
                        },
                        meters);

        BreakRegister.Raised raised =
                metered.raise(
                        NO_CONNECTION,
                        newBreak(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE));

        assertThat(raised).isSameAs(answer);
        assertThat(delegated).hasValue(1);
        assertThat(raised("missing_external", "critical")).isEqualTo(1.0d);
        assertThat(everyRaise()).as("exactly one series moved, by one").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the severity counted is the register's answer, not a guess from the type")
    void theSeverityIsTheRegisters() {
        BreakRegister metered =
                new MeteredBreakRegister(
                        (unitOfWork, newBreak) ->
                                new BreakRegister.Raised(true, newBreak.breakId(), Severity.LOW),
                        meters);

        metered.raise(NO_CONNECTION, newBreak(BreakType.AMOUNT_MISMATCH, BreakCause.AMOUNT_DIFFERS));

        assertThat(raised("amount_mismatch", "low")).isEqualTo(1.0d);
        assertThat(everyRaise()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a converged raise (created=false) counts nothing - ten sweepers, one count")
    void aConvergedRaiseCountsNothing() {
        UUID standing = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        BreakRegister metered =
                new MeteredBreakRegister(
                        (unitOfWork, newBreak) ->
                                new BreakRegister.Raised(
                                        calls.getAndIncrement() == 0, standing, Severity.HIGH),
                        meters);

        for (int sweeper = 0; sweeper < 10; sweeper++) {
            metered.raise(
                    NO_CONNECTION,
                    newBreak(BreakType.DUPLICATE_EXTERNAL, BreakCause.REPEATED_FINGERPRINT));
        }

        assertThat(raised("duplicate_external", "high"))
                .as("the first raise created the break; nine converged onto it")
                .isEqualTo(1.0d);
        assertThat(everyRaise()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the register's failure propagates and counts nothing")
    void aFailedRaiseCountsNothing() {
        IllegalStateException failure = new IllegalStateException("the insert failed");
        BreakRegister metered =
                new MeteredBreakRegister(
                        (unitOfWork, newBreak) -> {
                            throw failure;
                        },
                        meters);

        assertThatThrownBy(
                        () -> metered.raise(
                                NO_CONNECTION,
                                newBreak(BreakType.MISSING_EXTERNAL,
                                        BreakCause.EXPECTATION_OVERDUE)))
                .isSameAs(failure);
        assertThat(everyRaise()).isZero();
    }
}
