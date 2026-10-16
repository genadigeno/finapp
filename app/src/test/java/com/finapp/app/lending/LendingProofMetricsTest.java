package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.ledger.AccountPurpose;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanReplayProof;
import com.finapp.lending.LoanStatus;
import com.finapp.lending.LoanSubledgerProof;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two proof gauges (`P11-TSK-029`; PHASE_11_PLAN.md section 15; DOD-OBS): counts of accounts per verdict from one
 * reading - and absent, never clean, when the reading fails.
 */
@DisplayName("finapp.lending.subledger.proof and finapp.lending.replay count accounts per verdict (P11-TSK-029)")
class LendingProofMetricsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Money ZERO = Money.zero(CurrencyCode.of("EUR"));

    @Test
    @DisplayName("one reading of three accounts - one failing its subledger proof, one diverged - publishes 2 HOLDS, 1 FAILS,"
            + " 2 IDENTICAL, 1 DIVERGED; an unreadable reading is NaN for every verdict and read-now refuses")
    void countsPerVerdictAndAbsentWhenUnreadable() {
        LendingProofs.Reading reading = new LendingProofs.Reading(List.of(
                proven(true, LoanReplayProof.Verdict.IDENTICAL),
                proven(false, LoanReplayProof.Verdict.IDENTICAL),
                proven(true, LoanReplayProof.Verdict.DIVERGED)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LendingProofMetrics metrics = new LendingProofMetrics(proofs(), work -> reading, Clock.systemUTC(), registry);
        assertThat(metrics.readNow().failing()).isEqualTo(1);
        assertThat(gauge(registry, LendingProofMetrics.SUBLEDGER, "HOLDS")).isEqualTo(2);
        assertThat(gauge(registry, LendingProofMetrics.SUBLEDGER, "FAILS")).isEqualTo(1);
        assertThat(gauge(registry, LendingProofMetrics.REPLAY, "IDENTICAL")).isEqualTo(2);
        assertThat(gauge(registry, LendingProofMetrics.REPLAY, "DIVERGED")).isEqualTo(1);

        SimpleMeterRegistry unreadable = new SimpleMeterRegistry();
        LendingProofMetrics down = new LendingProofMetrics(proofs(), work -> {
            throw new IllegalStateException("the database is down");
        }, Clock.systemUTC(), unreadable);
        for (String verdict : List.of("HOLDS", "FAILS")) {
            assertThat(gauge(unreadable, LendingProofMetrics.SUBLEDGER, verdict)).as(verdict).isNaN();
        }
        for (String verdict : List.of("IDENTICAL", "DIVERGED")) {
            assertThat(gauge(unreadable, LendingProofMetrics.REPLAY, verdict)).as(verdict).isNaN();
        }
        assertThatIllegalStateException().isThrownBy(down::readNow);
    }

    @Test
    @DisplayName("the CRITICAL lines count failing accounts per arm and diverged accounts per kind - never a loan id"
            + " (P11-TSK-030, PHASE_11_PLAN.md section 11); the floor is configurable and never below a minute")
    void theCriticalLinesNameNoLoan() {
        LendingProofs.Proven failing = proven(false, LoanReplayProof.Verdict.DIVERGED);
        LendingProofs.Reading reading = new LendingProofs.Reading(List.of(failing, proven(true,
                LoanReplayProof.Verdict.IDENTICAL)));
        LendingProofMetrics metrics = new LendingProofMetrics(proofs(), work -> reading, Clock.systemUTC(),
                new SimpleMeterRegistry(), LendingProofMetrics.DEFAULT_REFRESH);
        // The class's own logger, captured as written - the lines that would reach any appender.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LendingProofMetrics.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            assertThat(metrics.readNow().failing()).isEqualTo(1);
        } finally {
            logger.detachAppender(captured);
        }
        String output = captured.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(metrics.refresh()).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(output).contains("CRITICAL", "LOAN_PRINCIPAL", "ACCRUAL")
                .doesNotContain(failing.subledger().loan().value().toString())
                .doesNotContain(failing.replay().findings().get(0).subject().toString());
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new LendingProofMetrics(
                proofs(), work -> reading, Clock.systemUTC(), new SimpleMeterRegistry(), java.time.Duration.ofSeconds(59)));
    }

    /** The proofs over no database - the readings above are handed to the gauges by the snapshot function. */
    private static LendingProofs proofs() {
        return new LendingProofs(new com.finapp.lending.LoanRecordStore() {
            @Override
            public List<LoanId> loanIds(java.sql.Connection unitOfWork) {
                return List.of();
            }

            @Override
            public java.util.Optional<com.finapp.lending.LoanRecord> read(java.sql.Connection unitOfWork, LoanId loan) {
                return java.util.Optional.empty();
            }
        }, new LoanSubledgerProof(), new LoanReplayProof());
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String verdict) {
        return registry.get(name).tag("verdict", verdict).gauge().value();
    }

    private static LendingProofs.Proven proven(boolean holds, LoanReplayProof.Verdict verdict) {
        LoanId loan = LoanId.next(IDS);
        List<LoanSubledgerProof.Figure> figures = LoanSubledgerProof.PURPOSES.stream()
                .map(purpose -> new LoanSubledgerProof.Figure(purpose, ZERO,
                        holds || purpose != AccountPurpose.LOAN_PRINCIPAL ? ZERO : Money.ofMinorUnits(1, ZERO.currency())))
                .toList();
        return new LendingProofs.Proven(
                new LoanSubledgerProof.Verdict(loan, LoanKind.INSTALMENT, LoanStatus.ACTIVE, figures, true, 0, true, true, true),
                new LoanReplayProof.Replay(loan, verdict, verdict == LoanReplayProof.Verdict.IDENTICAL ? List.of()
                        : List.of(new LoanReplayProof.Finding(LoanReplayProof.Check.ACCRUAL, UUID.randomUUID())), 7));
    }
}
