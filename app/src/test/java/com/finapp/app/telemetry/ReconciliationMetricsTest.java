package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.PositionProof;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.reconciliation.JdbcExpectationReadings;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.JdbcSettlementFileStore;
import com.finapp.settlement.SettlementFileCipher;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation verdict gauges' honesty rules (`P8-TSK-007`): every proven purpose's,
 * reconciled purpose's and declared source's series exist eagerly, and an unreadable sweep
 * reads NaN on every one — absent is alertable, a comforting zero is not (`P1-TSK-029`).
 * The verdicts' real values ride the database tier (`ReconciliationOpeningDatabaseTest`,
 * the storm at rest), where the sweep runs against the live schema.
 */
@DisplayName("the reconciliation verdict gauges (P8-TSK-007)")
class ReconciliationMetricsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static SettlementSources register() {
        return SettlementSources.of(
                List.of(
                        new SettlementSourceDescriptor(
                                "simulated-psp.settlement",
                                SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                Optional.of("PSP-REM-[0-9]{4,12}"))));
    }

    private static PositionProof proof() {
        return new PositionProof(
                new JdbcLedgerAccountStore(),
                new JdbcBalanceDerivation(),
                new JdbcJournalEntryStore(IDS),
                new JdbcExpectationReadings(),
                register(),
                new JdbcSettlementFileStore(
                        new SettlementFileCipher(new byte[32], 1, new SecureRandom())));
    }

    @Test
    @DisplayName("an unreadable sweep reads NaN on every series, and every series exists"
            + " eagerly - the proven purposes, all four reconciled purposes, each source")
    void unreadableReadsNaNOnEagerSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ReconciliationMetrics(
                proof(),
                register(),
                () -> {
                    throw new SQLException("nope");
                },
                CLOCK,
                registry);

        for (AccountPurpose purpose : PositionProof.PROVEN) {
            assertThat(registry.find(ReconciliationMetrics.PROOF)
                            .tag("purpose", purpose.name())
                            .gauge())
                    .as("the proof series exists eagerly for %s", purpose)
                    .isNotNull();
            assertThat(registry.find(ReconciliationMetrics.PROOF)
                            .tag("purpose", purpose.name())
                            .gauge()
                            .value())
                    .isNaN();
        }
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            assertThat(registry.find(ReconciliationMetrics.UNATTRIBUTED)
                            .tag("purpose", purpose.name())
                            .gauge())
                    .as("the completeness series exists eagerly for %s", purpose)
                    .isNotNull();
            assertThat(registry.find(ReconciliationMetrics.UNATTRIBUTED)
                            .tag("purpose", purpose.name())
                            .gauge()
                            .value())
                    .isNaN();
        }
        assertThat(registry.find(ReconciliationMetrics.OPEN)
                        .tag("source", "simulated-psp.settlement")
                        .gauge())
                .as("the open series exists eagerly per declared source")
                .isNotNull();
        assertThat(registry.find(ReconciliationMetrics.OPEN)
                        .tag("source", "simulated-psp.settlement")
                        .gauge()
                        .value())
                .isNaN();
    }
}
