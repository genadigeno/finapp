package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.CheckOutcome;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.SourceProofs;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The real {@code SourceProofs} - {@code ProductSourceProofs} as the application wires it over the nine product proofs -
 * read through {@code AccountingChecks.run(SOURCE_COMPLETENESS)} on the checks' own verified {@code REPEATABLE READ READ
 * ONLY} snapshots against the shared database (`P14-TSK-010`, the independent review's I-3): <strong>no source is
 * unavailable</strong>. A proof that took a row lock, wrote, or needed a read-write transaction would be refused by the
 * read-only snapshot ({@code 25006}) and the check would answer {@code UNAVAILABLE:<proof>} - never a pass, but a check
 * that could never pass. Findings are tolerated: the shared database holds other suites' planted books.
 *
 * <p>By reading (the review's request), none of the seven sources takes a lock, writes or needs a read-write transaction:
 * {@code LendingProofs} and {@code BnplProofs} refuse anything but a read-only {@code REPEATABLE READ} snapshot;
 * {@code LendingCapitalProof} and {@code FxBooksProof} run read-only in their own gauges; {@code PositionProof.sweep}
 * (whose own gauge runs {@code REPEATABLE READ} but not read-only) issues only plain {@code SELECT}s - the chart by
 * purpose, the balance derivation, the journal's line keys, reconciliation's readings, settlement's batches and sources,
 * the unmatched confirmations - none {@code FOR UPDATE} or {@code FOR SHARE}; the merchant payable's drill-down and
 * {@code ProjectionVerification.verdictOf} are {@code SELECT}s. This case is the proof of that reading.
 * <strong>Written and owed</strong> (owner decision C5).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the real product proofs read through the source completeness check: none unavailable (P14-TSK-010)")
class AccountingSourceProofsDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private AccountingChecks checks;

    @Test
    @DisplayName("SOURCE_COMPLETENESS over the wired ProductSourceProofs on the real snapshot: no UNAVAILABLE outcome -"
            + " every one of the nine proofs answered, findings tolerated")
    void everyRealSourceAnswersOnTheReadOnlySnapshot() {
        YearMonth month = YearMonth.of(2097 + RANDOM.nextInt(3), 1 + RANDOM.nextInt(12));

        CheckOutcome outcome = checks.run(month, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM,
                CorrelationId.generate(IDS));

        assertThat(outcome.failures())
                .as("a proof that locks, writes or needs a read-write transaction is UNAVAILABLE on the read-only"
                        + " snapshot")
                .noneMatch(failure -> failure.startsWith(CheckOutcome.UNAVAILABLE));
        assertThat(outcome.judgedBy()).isEqualTo("source-proofs:v1");
        assertThat(SourceProofs.Proof.values()).hasSize(9);
    }
}
