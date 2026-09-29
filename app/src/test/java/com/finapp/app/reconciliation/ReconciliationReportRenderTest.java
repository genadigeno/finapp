package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The positions report's rendering rules (`P8-TSK-007`): the listing bound with
 * {@code truncated}, and each row an honest copy of its verdict — both sides of the
 * identity and their difference. Hermetic on purpose: the bound is the platform's listing
 * bound (100), while the live sweep's ceiling is ~12 rows, so the truncation branch can
 * only be proven here. The report's audit, permission and live figures ride
 * {@code ReconciliationOpeningDatabaseTest}.
 */
@DisplayName("the positions report's rendering (P8-TSK-007)")
class ReconciliationReportRenderTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static PositionProof.PositionVerdict verdict(long balanceMinor, long remainderMinor) {
        return new PositionProof.PositionVerdict(
                AccountPurpose.SETTLEMENT_CLEARING,
                EUR,
                Money.ofPersisted(balanceMinor, EUR, 2),
                Money.ofPersisted(remainderMinor, EUR, 2),
                3L,
                balanceMinor == remainderMinor);
    }

    @Test
    @DisplayName("a report over the bound serves exactly the bound and says truncated")
    void overTheBoundServesTheBoundAndSaysTruncated() {
        PositionProof.Report report =
                new PositionProof.Report(
                        IntStream.rangeClosed(1, 101)
                                .mapToObj(i -> verdict(i, i))
                                .toList(),
                        Map.of(AccountPurpose.SETTLEMENT_CLEARING, 0L),
                        Map.of());

        ReconciliationReportController.PositionsReport served =
                ReconciliationReportController.render(report);

        assertThat(served.positions()).hasSize(ReconciliationReportController.BOUND);
        assertThat(served.truncated()).as("the 101st row is announced, never silent").isTrue();
    }

    @Test
    @DisplayName("a report within the bound serves every row, not truncated, each row the"
            + " verdict's two sides and their difference")
    void withinTheBoundServesEveryRowFaithfully() {
        PositionProof.Report report =
                new PositionProof.Report(
                        List.of(verdict(1200, 700)),
                        Map.of(AccountPurpose.SETTLEMENT_CLEARING, 4L),
                        Map.of());

        ReconciliationReportController.PositionsReport served =
                ReconciliationReportController.render(report);

        assertThat(served.truncated()).isFalse();
        assertThat(served.positions()).hasSize(1);
        ReconciliationReportController.PositionRow row = served.positions().get(0);
        assertThat(row.purpose()).isEqualTo("SETTLEMENT_CLEARING");
        assertThat(row.currency()).isEqualTo("EUR");
        assertThat(row.ledgerBalance()).isEqualTo("12.00");
        assertThat(row.openRemainders()).isEqualTo("7.00");
        assertThat(row.difference())
                .as("the difference is the identity's own subtraction, never re-derived")
                .isEqualTo("5.00");
        assertThat(row.openExpectations()).isEqualTo(3L);
        assertThat(row.unattributedLines()).isEqualTo(4L);
        assertThat(row.explained()).isFalse();
    }
}
