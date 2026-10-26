package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskDay;
import com.finapp.risk.RiskOperation;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The owners' side of the assessment census, by its statements (`P13-TSK-023`; {@code DESIGN-023} D2): each judged set
 * entered through the owner's primary key over a day's identifiers, holding exactly the rows its seam judged by
 * construction - a transfer's judged states, a person's withdrawal, a merchant-bound card or wallet intent that left
 * {@code REQUIRES_CONFIRMATION} other than by cancellation, a cross-border payment by its quote - and never a risk
 * table nor a tenant column. Their run over real rows is {@code RiskProofsDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("the owners' judged commands: by the owner's primary key, the seam's judged rows only, no risk table"
        + " (P13-TSK-023)")
class PlatformJudgedCommandsTest {

    private static final List<String> JUDGED = List.of(PlatformJudgedCommands.TRANSFERS,
            PlatformJudgedCommands.WITHDRAWALS, PlatformJudgedCommands.CARD_PAYMENTS,
            PlatformJudgedCommands.WALLET_PAYMENTS, PlatformJudgedCommands.CROSS_BORDER);

    @Test
    @DisplayName("every judged statement enters a day through its owner's primary key; every recorded probe through the"
            + " primary key or crossborder's quote unique")
    void throughTheOwnersKeys() {
        for (String sql : JUDGED) {
            assertThat(sql).containsPattern("\\b(i\\.)?id >= \\? AND (i\\.)?id < \\?").startsWith("SELECT ");
        }
        assertThat(PlatformJudgedCommands.TRANSFERS_RECORDED).contains("WHERE id = ANY (?)");
        assertThat(PlatformJudgedCommands.WITHDRAWALS_RECORDED).contains("WHERE id = ANY (?)");
        assertThat(PlatformJudgedCommands.INTENTS_RECORDED).contains("WHERE id = ANY (?)");
        assertThat(PlatformJudgedCommands.CROSS_BORDER).startsWith("SELECT quote_id ");
        assertThat(PlatformJudgedCommands.CROSS_BORDER_RECORDED).contains("WHERE quote_id = ANY (?)");
    }

    @Test
    @DisplayName("the seam's judged rows only: a transfer's COMPLETED, REVERSED, LIMIT_REFUSED and RISK_REFUSED - never"
            + " the refusals before the seams; a person's withdrawal; a merchant-bound intent past its confirmation by"
            + " its instrument")
    void theJudgedRowsOnly() {
        assertThat(PlatformJudgedCommands.TRANSFERS).contains("status IN ('COMPLETED', 'REVERSED')")
                .contains("failure_reason IN ('LIMIT_REFUSED', 'RISK_REFUSED')").doesNotContain("DESTINATION_NOT_POSTABLE")
                .doesNotContain("INSUFFICIENT_FUNDS");
        assertThat(PlatformJudgedCommands.WITHDRAWALS).contains("instruction_reference IS NULL");
        assertThat(PlatformJudgedCommands.CARD_PAYMENTS).contains("m.kind = 'CARD_TOKEN'")
                .contains("a.purpose = 'MERCHANT_PAYABLE'").contains("i.status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')");
        assertThat(PlatformJudgedCommands.WALLET_PAYMENTS).contains("i.debit_account_id IS NOT NULL")
                .contains("a.purpose = 'MERCHANT_PAYABLE'").contains("i.status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')");
    }

    @Test
    @DisplayName("no statement names a risk table (NoCustomerSurfaceReadsRiskTest's rule) or a tenant column"
            + " (OwnershipIsScopedTest's)")
    void noRiskTableNoTenantColumn() {
        for (String sql : List.of(PlatformJudgedCommands.TRANSFERS, PlatformJudgedCommands.TRANSFERS_RECORDED,
                PlatformJudgedCommands.WITHDRAWALS, PlatformJudgedCommands.WITHDRAWALS_RECORDED,
                PlatformJudgedCommands.CARD_PAYMENTS, PlatformJudgedCommands.WALLET_PAYMENTS,
                PlatformJudgedCommands.INTENTS_RECORDED, PlatformJudgedCommands.CROSS_BORDER,
                PlatformJudgedCommands.CROSS_BORDER_RECORDED, LedgerMonitoredLedger.ENTRY_IDS)) {
            assertThat(sql).doesNotContain("risk.").doesNotContainPattern("\\bmerchant_(id|ref)\\b");
        }
    }

    @Test
    @DisplayName("a reference that is no canonical UUID names no owner's row - answered empty without a statement; an"
            + " operation that judges no movement is refused")
    void malformedAndUnjudged() {
        Connection untouched = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        PlatformJudgedCommands owners = new PlatformJudgedCommands();
        assertThat(owners.recorded(untouched, RiskOperation.TRANSFER, List.of(CommandRef.of("transfer-1"))))
                .isEmpty();
        assertThatThrownBy(() -> owners.judged(untouched, RiskOperation.MONITORING,
                RiskDay.of(LocalDate.of(2026, 10, 23)))).isInstanceOf(IllegalArgumentException.class);
    }
}
