package com.finapp.app.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.EntryType;
import com.finapp.accounting.PostingRule;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.app.api.StatementLabels;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.StatementDerivation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * One label on a customer's statement for every adjustment, whatever its origin (`P14-TSK-022`, the owner's ruling (s)): the real rendering over a line of each origin, as {@code JdbcStatementDerivation} reads it, so a {@code MANUAL} reference's free text, a reconciliation's bare id and accounting's constant reach no customer, and
 * each line that is no adjustment reads as it did. A line is an adjustment by its entry's type; that the ledger gives each origin that type is {@code PriorPeriodAdjustmentDatabaseTest} and {@code StatementEndpointDatabaseTest}'s, owed. The merchant's report is {@code BnplMerchantDrillDownTest}'s.
 */
@DisplayName("one label on a statement for every adjustment (P14-TSK-022)")
class StatementLabelsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final LocalDate ON = LocalDate.of(2026, 11, 4);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static StatementDerivation.StatementLine line(JournalEntryType type, String reference, boolean attributed) {
        return new StatementDerivation.StatementLine(JournalEntryId.next(IDS), ON, ON, type, reference, Direction.CREDIT, Money.ofMinorUnits(1_500, EUR), attributed);
    }

    @Test
    @DisplayName("the label is the owner's word")
    void theOwnersWord() {
        assertThat(StatementLabels.ACCOUNT_ADJUSTMENT).isEqualTo("Account adjustment");
    }

    @Test
    @DisplayName("an adjustment of each origin reads the one label with no reference served: MANUAL's free text, RECONCILIATION's bare id, ACCOUNTING's constant - and the old RECONCILIATION_ATTRIBUTION label is gone - with its money and its entry as they were")
    void everyOriginReadsTheOneLabel() throws Exception {
        String manual = "INC-2041 per Bob, the customer complained";
        String resolution = UUID.randomUUID().toString();
        String accounting = "accounting-correction";
        for (StatementDerivation.StatementLine adjustment : List.of(line(JournalEntryType.ADJUSTMENT, manual, false), line(JournalEntryType.ADJUSTMENT, resolution, true), line(JournalEntryType.ADJUSTMENT, accounting, false))) {
            AccountController.StatementLineView view = AccountController.render(adjustment);

            assertThat(view.label()).isEqualTo("Account adjustment");
            assertThat(view.reference()).as("no reference served").isNull();
            assertThat(view.entryType()).isEqualTo("ADJUSTMENT");
            assertThat(view.amount()).isEqualTo("15.00");
            assertThat(view.direction()).isEqualTo("CREDIT");
            assertThat(view.entryId()).isEqualTo(adjustment.entry().value().toString());
            assertThat(JSON.writeValueAsString(view)).as("nor anywhere in the body").doesNotContain(manual).doesNotContain(resolution).doesNotContain(accounting).doesNotContain("RECONCILIATION_ATTRIBUTION");
        }
    }

    @Test
    @DisplayName("what is no adjustment must not change: a posting and a reversal keep their reference, with no label")
    void whatIsNoAdjustmentKeepsItsReference() {
        String attempt = UUID.randomUUID().toString();
        for (JournalEntryType type : List.of(JournalEntryType.POSTING, JournalEntryType.REVERSAL)) {
            AccountController.StatementLineView view = AccountController.render(line(type, attempt, false));

            assertThat(view.reference()).as(type.name()).isEqualTo(attempt);
            assertThat(view.label()).as(type.name()).isNull();
            assertThat(view.entryType()).isEqualTo(type.name());
        }
    }

    @Test
    @DisplayName("every entry type is judged: ADJUSTMENT is the one that takes the label, so a new type reads as a posting until someone decides otherwise")
    void onlyAnAdjustmentTakesIt() {
        for (JournalEntryType type : JournalEntryType.values()) {
            assertThat(AccountController.render(line(type, "reference", false)).label() != null).as(type.name()).isEqualTo(type == JournalEntryType.ADJUSTMENT);
        }
    }

    @Test
    @DisplayName("verified, not assumed (P14-TSK-021's finding): a write-off's entry is a POSTING with no line on a customer's wallet or a merchant's payable, so the label neither reaches it nor needs to; its lines are on the expense and the receivables, whose owner is the loan or the obligation, never a customer's account")
    void aWriteOffIsNoAdjustmentAndNoStatementLine() {
        List<PostingRule> writeOffs = PostingRuleRegister.CURRENT.rules().stream().filter(rule -> rule.namespace().equals("lending.write-off:") || rule.namespace().equals("bnpl.write-off:")).toList();
        assertThat(writeOffs).hasSize(2).allSatisfy(rule -> {
            assertThat(rule.type()).as(rule.id()).isEqualTo(EntryType.POSTING);
            assertThat(rule.purposes()).as(rule.id()).doesNotContain("CUSTOMER_WALLET", "MERCHANT_PAYABLE");
        });
    }
}
