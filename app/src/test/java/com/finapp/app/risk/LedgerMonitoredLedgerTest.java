package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The port's implementation, where it computes rather than reads (`P13-TSK-019`): the ledger's scope-and-key pair
 * yields the command key whose namespace classifies the entry; the rest - the walk, the chart - is the database's and
 * {@code MonitoringIntakeDatabaseTest}'s.
 */
@DisplayName("the ledger as the monitoring intake reads it (P13-TSK-019)")
class LedgerMonitoredLedgerTest {

    @Test
    @DisplayName("the ledger's own prefix is stripped - a posting's, a reversal's, an adjustment's - and no other")
    void theCommandKeyBehindTheScope() {
        assertThat(LedgerMonitoredLedger.commandKey("ledger.post:transfer:0199a3c1-2b3c-7d4e-8f00-000000000001"))
                .isEqualTo("transfer:0199a3c1-2b3c-7d4e-8f00-000000000001");
        assertThat(LedgerMonitoredLedger.commandKey("ledger.reverse:bnpl.waiver:w-1")).isEqualTo("bnpl.waiver:w-1");
        assertThat(LedgerMonitoredLedger.commandKey("ledger.adjust:adjustment:a-1")).isEqualTo("adjustment:a-1");
        assertThat(LedgerMonitoredLedger.commandKey("other.scope:transfer:t-1")).isEqualTo("other.scope:transfer:t-1");
        assertThat(LedgerMonitoredLedger.commandKey("ledger.post:")).as("an empty key is served as it is")
                .isEqualTo("ledger.post:");
    }
}
