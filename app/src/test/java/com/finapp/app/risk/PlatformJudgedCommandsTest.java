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
        for (String sql : List.of(PlatformJudgedCommands.TRANSFERS, PlatformJudgedCommands.WITHDRAWALS, PlatformJudgedCommands.CROSS_BORDER)) {
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
                .contains("a.purpose = 'MERCHANT_PAYABLE'");
        assertThat(PlatformJudgedCommands.WALLET_PAYMENTS).contains("i.debit_account_id IS NOT NULL")
                .contains("a.purpose = 'MERCHANT_PAYABLE'");
    }

    @Test
    @DisplayName("an intent's day is its confirmation's: the two statements enter through the confirmation edges V037 indexes, never the intent's own id - so a late-confirmed intent is counted on its confirmation's day (P15-TSK-025)")
    void anIntentIsDatedByItsConfirmation() {
        for (String sql : List.of(PlatformJudgedCommands.CARD_PAYMENTS, PlatformJudgedCommands.WALLET_PAYMENTS)) {
            assertThat(sql).startsWith("SELECT i.id FROM payments.payment_intent_event e")
                    .contains("e.occurred_at >= ? AND e.occurred_at < ?")
                    .contains("e.from_status = 'REQUIRES_CONFIRMATION' AND e.to_status IN ('PROCESSING', 'FAILED')")
                    .doesNotContain("i.id >= ?").doesNotContain("i.status IN");
        }
        assertThat(com.finapp.platform.testing.RepositoryPaths.read(
                        "payments/src/main/resources/db/migration/payments/V037__a_payment_intent_is_readable_by_its_confirmation.sql"))
                .as("the index covers exactly the statements' edges")
                .contains("ON payments.payment_intent_event (occurred_at)")
                .contains("WHERE from_status = 'REQUIRES_CONFIRMATION' AND to_status IN ('PROCESSING', 'FAILED')");
    }

    @Test
    @DisplayName("a day's bounds reach the statement as instants for an intent and as identifiers for the commands minted by their judgement (P15-TSK-025)")
    void theBoundsMatchTheStatement() {
        RiskDay day = RiskDay.of(LocalDate.of(2026, 10, 23));
        List<Object> bound = new java.util.ArrayList<>();
        Connection recording = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (self, method, args) -> method.getName().equals("prepareStatement")
                        ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class},
                                (statement, call, values) -> {
                                    switch (call.getName()) {
                                        case "setObject" -> bound.add(values[1]);
                                        case "executeQuery" -> {
                                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.ResultSet.class},
                                                    (rows, next, none) -> next.getName().equals("next") ? Boolean.FALSE : null);
                                        }
                                        default -> { }
                                    }
                                    return null;
                                })
                        : null);
        PlatformJudgedCommands owners = new PlatformJudgedCommands();

        owners.judged(recording, RiskOperation.CARD_PAYMENT, day);
        assertThat(bound).containsExactly(day.start().atOffset(java.time.ZoneOffset.UTC), day.end().atOffset(java.time.ZoneOffset.UTC));

        bound.clear();
        owners.judged(recording, RiskOperation.TRANSFER, day);
        assertThat(bound).containsExactly(day.fromId(), day.toId());
    }

    @Test
    @DisplayName("no statement names a risk table (NoCustomerSurfaceReadsRiskTest's rule) or a tenant column"
            + " (OwnershipIsScopedTest's)")
    void noRiskTableNoTenantColumn() {
        for (String sql : List.of(PlatformJudgedCommands.TRANSFERS, PlatformJudgedCommands.TRANSFERS_RECORDED,
                PlatformJudgedCommands.WITHDRAWALS, PlatformJudgedCommands.WITHDRAWALS_RECORDED,
                PlatformJudgedCommands.CARD_PAYMENTS, PlatformJudgedCommands.WALLET_PAYMENTS,
                PlatformJudgedCommands.INTENTS_RECORDED, PlatformJudgedCommands.CROSS_BORDER,
                PlatformJudgedCommands.CROSS_BORDER_RECORDED, LedgerMonitoredLedger.ENTRY_IDS,
                LedgerMonitoredLedger.FIRST_BEYOND)) {
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
