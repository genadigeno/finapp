package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.JournalEntryId;
import com.finapp.merchant.MerchantPayable;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a merchant's two responses say of an adjustment (`P14-TSK-022`, the owner's rulings (q) and (s)), through the real rendering: the payable's term of its own, in the sum its doc states, and the report's one label. What each is folded from is {@code MerchantPayableTermsTest}'s and {@code BnplMerchantDrillDownTest}'s, hermetic, and
 * {@code PriorPeriodAdjustmentDatabaseTest}'s over real lines, owed.
 */
@DisplayName("a merchant's responses over an adjustment (P14-TSK-022)")
class MerchantStatementSurfacesTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    @Test
    @DisplayName("the payable: accountingCorrections, a decimal string and signed, a term a merchant can check: the published sum still equals the position")
    void thePayableNamesTheTerm() throws Exception {
        Money zero = eur(0);
        MerchantPayable.Payable payable = new MerchantPayable.Payable(eur(8_500), eur(10_000), zero, zero, zero, zero, zero, zero, zero, zero, eur(-1_500), zero, zero, zero, zero, zero);
        assertThat(payable.terms()).isEqualTo(payable.position());

        MerchantPayableController.PayableLine line = MerchantPayableController.render(payable);

        assertThat(line.accountingCorrections()).isEqualTo("-15.00");
        assertThat(line.captured()).isEqualTo("100.00");
        assertThat(line.position()).isEqualTo("85.00");
        assertThat(JSON.writeValueAsString(line)).contains("\"accountingCorrections\":\"-15.00\"");
    }

    @Test
    @DisplayName("the report: an adjustment's movement is OTHER with the one label and no reference, since a movement has none to serve; a payout, OTHER too, carries no label")
    void theReportLabelsTheMovement() throws Exception {
        MerchantTransactionController.MovementView adjustment = MerchantTransactionController.render(new MerchantTransactionReport.Movement(MerchantTransactionReport.Kind.OTHER, JournalEntryId.next(IDS), LocalDate.of(2026, 11, 4), Optional.empty(), Optional.empty(), eur(1_500),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("Account adjustment")));
        assertThat(adjustment.label()).isEqualTo("Account adjustment");
        assertThat(JSON.writeValueAsString(adjustment)).doesNotContain("reference");

        MerchantTransactionController.MovementView payout = MerchantTransactionController.render(new MerchantTransactionReport.Movement(MerchantTransactionReport.Kind.OTHER, JournalEntryId.next(IDS), LocalDate.of(2026, 11, 4), Optional.empty(), Optional.empty(), eur(-1_500),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(payout.label()).isNull();
    }
}
