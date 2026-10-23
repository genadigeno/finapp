package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The posting-key namespace register and the classification of one journal entry (`P13-TSK-019`; ADR-0111 decision 2;
 * {@code INV-RSK-09}): every namespace the ledger carries today maps to its kind, exactly; an unknown one - and a key
 * with no namespace at all - is {@code UNCLASSIFIED}, never a refusal; a reversal and an adjustment classify by their
 * entry type; the subject, direction, amount and counterparty are read from the journal's own owners. Nothing passes
 * unseen: every entry yields a row.
 */
@DisplayName("the monitoring classification and the namespace register (P13-TSK-019)")
class MonitoringClassificationTest {

    private static final IdGenerator IDS = new IdGenerator(
            Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC), new Random(19));
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Instant POSTED = Instant.parse("2026-10-23T09:59:30Z");
    private static final LocalDate VALUE = LocalDate.of(2026, 10, 23);

    /** The register as the design read it from the code ({@code DESIGN-019} section 26), pinned member by member. */
    private static final Map<String, MonitoredKind> REGISTER = Map.ofEntries(
            Map.entry("transfer:", MonitoredKind.TRANSFER),
            Map.entry("wallet-withdrawal:", MonitoredKind.WITHDRAWAL),
            Map.entry("payment-capture:", MonitoredKind.CARD_PAYMENT),
            Map.entry("payment-execution:", MonitoredKind.PAYMENT_EXECUTION),
            Map.entry("payment-refund:", MonitoredKind.PAYMENT_REFUND),
            Map.entry("unmatched-confirmation:", MonitoredKind.UNMATCHED_CONFIRMATION),
            Map.entry("outbound-credit:", MonitoredKind.OUTBOUND_CREDIT),
            Map.entry("crossborder-return:", MonitoredKind.CROSS_BORDER_RETURN),
            Map.entry("crossborder-return-fee:", MonitoredKind.CROSS_BORDER_RETURN),
            Map.entry("merchant-payout:", MonitoredKind.MERCHANT_PAYOUT),
            Map.entry("merchant-payout-return:", MonitoredKind.MERCHANT_PAYOUT_RETURN),
            Map.entry("dispute-chargeback:", MonitoredKind.DISPUTE),
            Map.entry("dispute-fee:", MonitoredKind.DISPUTE),
            Map.entry("dispute-loss:", MonitoredKind.DISPUTE),
            Map.entry("dispute-won:", MonitoredKind.DISPUTE),
            Map.entry("dispute-restoration:", MonitoredKind.DISPUTE),
            Map.entry("dispute-attribution:", MonitoredKind.DISPUTE),
            Map.entry("dispute-reattribution:", MonitoredKind.DISPUTE),
            Map.entry("settlement-batch:", MonitoredKind.SETTLEMENT),
            Map.entry("recon-suspense:", MonitoredKind.RECONCILIATION_SUSPENSE),
            Map.entry("fx-trade:", MonitoredKind.FX_TRADE),
            Map.entry("fx-cover:", MonitoredKind.FX_COVER),
            Map.entry("lending.disbursement:", MonitoredKind.LOAN_DISBURSEMENT),
            Map.entry("lending.draw:", MonitoredKind.LOAN_DISBURSEMENT),
            Map.entry("lending.repayment:", MonitoredKind.LOAN_REPAYMENT),
            Map.entry("lending.accrual:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.accrual-correction:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.billing:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.statement:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.fee:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.credit-application:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.amendment:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.closure-refund:", MonitoredKind.LOAN_SERVICING),
            Map.entry("lending.capital-contribution:", MonitoredKind.LENDING_CAPITAL),
            Map.entry("bnpl.financing:", MonitoredKind.BNPL_FINANCING),
            Map.entry("bnpl.repayment:", MonitoredKind.BNPL_REPAYMENT),
            Map.entry("bnpl.refund:", MonitoredKind.BNPL_REFUND),
            Map.entry("bnpl.closure-refund:", MonitoredKind.BNPL_REFUND),
            Map.entry("bnpl.billing:", MonitoredKind.BNPL_SERVICING),
            Map.entry("bnpl.accrual:", MonitoredKind.BNPL_SERVICING),
            Map.entry("bnpl.accrual-correction:", MonitoredKind.BNPL_SERVICING),
            Map.entry("bnpl.fee:", MonitoredKind.BNPL_SERVICING),
            Map.entry("bnpl.credit-application:", MonitoredKind.BNPL_SERVICING),
            Map.entry("bnpl.reversal-fee-release:", MonitoredKind.BNPL_SERVICING));

    @Test
    @DisplayName("the register is exactly the forty-four namespaces the ledger carries today, each to its kind")
    void theRegisterIsExact() {
        assertThat(MonitoredKind.registeredNamespaces()).containsExactlyInAnyOrderElementsOf(REGISTER.keySet());
        REGISTER.forEach((namespace, kind) -> {
            assertThat(MonitoredKind.ofNamespace(namespace)).as(namespace).contains(kind);
            MonitoredTransaction row = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                    posting(namespace + UUID.randomUUID(), walletDebit(100_00), operationalCredit(100_00)));
            assertThat(row.kind()).as(namespace).isEqualTo(kind);
            assertThat(row.postingNamespace()).isEqualTo(namespace);
        });
        assertThat(MonitoredKind.REVERSAL.namespaces()).isEmpty();
        assertThat(MonitoredKind.ADJUSTMENT.namespaces()).isEmpty();
        assertThat(MonitoredKind.UNCLASSIFIED.namespaces()).isEmpty();
    }

    @Test
    @DisplayName("a namespace the register does not know is UNCLASSIFIED - recorded with what it saw, never refused")
    void anUnknownNamespaceIsUnclassified() {
        MonitoredTransaction row = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("crypto-swap:" + UUID.randomUUID(), walletDebit(100_00), operationalCredit(100_00)));
        assertThat(row.kind()).isEqualTo(MonitoredKind.UNCLASSIFIED);
        assertThat(row.postingNamespace()).isEqualTo("crypto-swap:");
        assertThat(row.subjectKind()).contains(MonitoredSubjectKind.ACCOUNT);
        assertThat(row.amountMinor()).isEqualTo(100_00);
    }

    @Test
    @DisplayName("a key with no ':' is its own namespace and unknown; a namespace longer than the column is cut, not refused")
    void aKeyWithoutANamespace() {
        assertThat(MonitoredKind.namespaceOf("no-colon-at-all")).isEqualTo("no-colon-at-all");
        assertThat(MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("no-colon-at-all", walletDebit(1), operationalCredit(1))).kind())
                .isEqualTo(MonitoredKind.UNCLASSIFIED);
        String longKey = "x".repeat(80) + ":" + UUID.randomUUID();
        MonitoredTransaction row = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting(longKey, walletDebit(1), operationalCredit(1)));
        assertThat(row.kind()).isEqualTo(MonitoredKind.UNCLASSIFIED);
        assertThat(row.postingNamespace()).hasSize(64);
    }

    @Test
    @DisplayName("a REVERSAL and an ADJUSTMENT classify by their type whatever their key, the reversal naming its original")
    void reversalsAndAdjustmentsByType() {
        UUID original = IDS.next();
        MonitoredTransaction reversal = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                new MonitoredEntry(IDS.next(), 1L, MonitoredEntry.EntryType.REVERSAL, "transfer:" + UUID.randomUUID(),
                        Optional.of(original), POSTED, VALUE, List.of(operationalDebit(50_00), walletCredit(50_00))));
        assertThat(reversal.kind()).isEqualTo(MonitoredKind.REVERSAL);
        assertThat(reversal.reversesJournalEntryId()).contains(original);
        assertThat(reversal.postingNamespace()).isEqualTo("transfer:");
        assertThat(reversal.direction()).contains(MonitoredDirection.IN);

        MonitoredTransaction adjustment = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                new MonitoredEntry(IDS.next(), 1L, MonitoredEntry.EntryType.ADJUSTMENT, "adjust:" + UUID.randomUUID(),
                        Optional.empty(), POSTED, VALUE, List.of(operationalDebit(5), operationalCredit(5))));
        assertThat(adjustment.kind()).isEqualTo(MonitoredKind.ADJUSTMENT);
        assertThat(adjustment.subjectKind()).isEmpty();
    }

    @Test
    @DisplayName("the subject is the first customer account; OUT when debited, the counterparty the other side with its"
            + " owner and purpose; a merchant is the subject only when no customer moved")
    void theSubjectAndTheCounterparty() {
        UUID receiver = UUID.randomUUID();
        MonitoredTransaction transfer = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("transfer:" + UUID.randomUUID(), walletDebit(250_00),
                        line("CUSTOMER", Optional.of(receiver), "CUSTOMER_WALLET", MonitoredEntry.Side.CREDIT, 250_00)));
        assertThat(transfer.subjectKind()).contains(MonitoredSubjectKind.ACCOUNT);
        assertThat(transfer.subjectRef()).contains(WALLET_OWNER);
        assertThat(transfer.direction()).contains(MonitoredDirection.OUT);
        assertThat(transfer.amountMinor()).isEqualTo(250_00);
        assertThat(transfer.counterpartyKind()).isEqualTo(MonitoredCounterpartyKind.CUSTOMER);
        assertThat(transfer.counterpartyRef()).contains(receiver);
        assertThat(transfer.counterpartyPurpose()).contains("CUSTOMER_WALLET");

        UUID merchant = UUID.randomUUID();
        MonitoredTransaction payout = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("merchant-payout:" + UUID.randomUUID(),
                        line("MERCHANT", Optional.of(merchant), "MERCHANT_PAYABLE", MonitoredEntry.Side.DEBIT, 900_00),
                        line("OPERATIONAL", Optional.empty(), "PAYOUT_CLEARING", MonitoredEntry.Side.CREDIT, 900_00)));
        assertThat(payout.subjectKind()).contains(MonitoredSubjectKind.MERCHANT);
        assertThat(payout.subjectRef()).contains(merchant);
        assertThat(payout.direction()).contains(MonitoredDirection.OUT);
        assertThat(payout.counterpartyKind()).isEqualTo(MonitoredCounterpartyKind.OPERATIONAL);
        assertThat(payout.counterpartyRef()).isEmpty();
        assertThat(payout.counterpartyPurpose()).contains("PAYOUT_CLEARING");

        UUID loan = UUID.randomUUID();
        MonitoredTransaction disbursement = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("lending.disbursement:" + UUID.randomUUID(),
                        line("LOAN", Optional.of(loan), "LOAN_PRINCIPAL", MonitoredEntry.Side.DEBIT, 5_000_00),
                        walletCredit(5_000_00)));
        assertThat(disbursement.kind()).isEqualTo(MonitoredKind.LOAN_DISBURSEMENT);
        assertThat(disbursement.subjectKind()).contains(MonitoredSubjectKind.ACCOUNT);
        assertThat(disbursement.direction()).contains(MonitoredDirection.IN);
        assertThat(disbursement.counterpartyKind()).isEqualTo(MonitoredCounterpartyKind.LOAN);
        assertThat(disbursement.counterpartyRef()).contains(loan);
    }

    @Test
    @DisplayName("an entry with no owned side has no subject and carries its debit total against its first line's owner")
    void anEntryWithNoOwnedSide() {
        MonitoredTransaction cover = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("fx-cover:" + UUID.randomUUID(),
                        line("OPERATIONAL", Optional.empty(), "FX_PROVIDER_CLEARING", MonitoredEntry.Side.DEBIT, 70_00),
                        line("OPERATIONAL", Optional.empty(), "CASH_AT_BANK", MonitoredEntry.Side.CREDIT, 70_00)));
        assertThat(cover.kind()).isEqualTo(MonitoredKind.FX_COVER);
        assertThat(cover.subjectKind()).isEmpty();
        assertThat(cover.subjectRef()).isEmpty();
        assertThat(cover.direction()).isEmpty();
        assertThat(cover.amountMinor()).isEqualTo(70_00);
        assertThat(cover.counterpartyKind()).isEqualTo(MonitoredCounterpartyKind.OPERATIONAL);
        assertThat(cover.counterpartyPurpose()).contains("FX_PROVIDER_CLEARING");

        UUID obligation = UUID.randomUUID();
        MonitoredTransaction billing = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("bnpl.billing:" + UUID.randomUUID(),
                        line("BNPL_OBLIGATION", Optional.of(obligation), "BNPL_PRINCIPAL_DUE", MonitoredEntry.Side.DEBIT, 30_00),
                        line("BNPL_OBLIGATION", Optional.of(obligation), "BNPL_PRINCIPAL", MonitoredEntry.Side.CREDIT, 30_00)));
        assertThat(billing.kind()).isEqualTo(MonitoredKind.BNPL_SERVICING);
        assertThat(billing.subjectKind()).isEmpty();
        assertThat(billing.counterpartyKind()).isEqualTo(MonitoredCounterpartyKind.BNPL_OBLIGATION);
        assertThat(billing.counterpartyRef()).contains(obligation);
    }

    @Test
    @DisplayName("the amount is the subject's net in its own currency, the sign the direction; a self-netting entry nets to"
            + " zero with the first line's side")
    void theAmountIsTheSubjectsNet() {
        MonitoredTransaction netted = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("payment-refund:" + UUID.randomUUID(), walletCredit(80_00), walletDebit(30_00),
                        operationalDebit(50_00)));
        assertThat(netted.direction()).contains(MonitoredDirection.IN);
        assertThat(netted.amountMinor()).isEqualTo(50_00);
        assertThat(netted.currency()).isEqualTo(EUR);
        assertThat(netted.scale()).isEqualTo(2);

        MonitoredTransaction zero = MonitoringClassification.classify(MonitoredTransactionId.next(IDS),
                posting("transfer:" + UUID.randomUUID(), walletDebit(10_00), walletCredit(10_00)));
        assertThat(zero.amountMinor()).isZero();
        assertThat(zero.direction()).contains(MonitoredDirection.OUT);
        assertThat(zero.counterpartyKind()).isEqualTo(MonitoredCounterpartyKind.NONE);
    }

    @Test
    @DisplayName("the same entry classifies into the same row on every instance - the fields are a function of the entry")
    void deterministic() {
        MonitoredEntry entry = posting("transfer:" + UUID.randomUUID(), walletDebit(1_00), operationalCredit(1_00));
        MonitoredTransactionId id = MonitoredTransactionId.next(IDS);
        assertThat(MonitoringClassification.classify(id, entry))
                .isEqualTo(MonitoringClassification.classify(id, entry));
    }

    // ------------------------------------------------------------------ fixtures

    private static final UUID WALLET_OWNER = UUID.fromString("00000000-0000-7000-8000-00000000c0de");

    private static MonitoredEntry posting(String key, MonitoredEntry.Line... lines) {
        UUID id = IDS.next();
        return new MonitoredEntry(id, MonitoringWatermark.sequenceOf(id), MonitoredEntry.EntryType.POSTING, key,
                Optional.empty(), POSTED, VALUE, List.of(lines));
    }

    private static MonitoredEntry.Line walletDebit(long minor) {
        return line("CUSTOMER", Optional.of(WALLET_OWNER), "CUSTOMER_WALLET", MonitoredEntry.Side.DEBIT, minor);
    }

    private static MonitoredEntry.Line walletCredit(long minor) {
        return line("CUSTOMER", Optional.of(WALLET_OWNER), "CUSTOMER_WALLET", MonitoredEntry.Side.CREDIT, minor);
    }

    private static MonitoredEntry.Line operationalCredit(long minor) {
        return line("OPERATIONAL", Optional.empty(), "CASH_AT_BANK", MonitoredEntry.Side.CREDIT, minor);
    }

    private static MonitoredEntry.Line operationalDebit(long minor) {
        return line("OPERATIONAL", Optional.empty(), "CASH_AT_BANK", MonitoredEntry.Side.DEBIT, minor);
    }

    private static MonitoredEntry.Line line(String ownerKind, Optional<UUID> ownerRef, String purpose,
            MonitoredEntry.Side side, long minor) {
        return new MonitoredEntry.Line(UUID.randomUUID(), ownerKind, ownerRef, purpose, side, minor, 2, EUR);
    }
}
