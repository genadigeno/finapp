package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The posting rule register held to the documented catalogues, one family at a time (`P14-TSK-002`; ADR-0117 decision
 * 1; {@code PHASE_14_PLAN.md} section 13.2): each expected shape below is typed from the document the rule names
 * ({@code LEDGER_MODEL.md} sections 9 to 11, ADR-0096 section 2, ADR-0103, ADR-0050, ADR-0061, ADR-0071, the settlement
 * and dispute lifecycles), as corrected by the task's reading of the code - so a line dropped, a side flipped, a purpose
 * renamed or a dating class changed in the register is a failing test, never a silent change to what `P14-TSK-010` will
 * judge. A shape renders as {@code DR}/{@code CR}/{@code DC} (either side) and the purposes it admits, {@code ?} when
 * the line is optional. Hermetic: the register is code; that its purposes are the ledger's and its namespaces the
 * source's is {@code app}'s {@code PostingRuleRegisterTest}.
 */
@DisplayName("the posting rule register against the documented catalogues (P14-TSK-002)")
class PostingRuleCatalogueTest {

    private static final PostingRuleRegister REGISTER = PostingRuleRegister.CURRENT;

    // ------------------------------------------------------------------ the register as a whole

    @Nested
    @DisplayName("the register")
    class TheRegister {

        @Test
        @DisplayName("catalogue version 1: 58 rules, each at version 1 from the origin, each id once")
        void isVersionOneOfFiftyEightRules() {
            assertThat(REGISTER.catalogueVersion()).isEqualTo(PostingRuleRegister.CATALOGUE_VERSION).isEqualTo(1);
            assertThat(REGISTER.rules()).hasSize(58);
            assertThat(REGISTER.rules()).allSatisfy(rule -> {
                assertThat(rule.version()).isEqualTo(1);
                assertThat(rule.effectiveFrom()).isEqualTo(PostingRuleRegister.ORIGIN);
            });
            assertThat(REGISTER.rules().stream().map(PostingRule::id).distinct()).hasSize(58);
        }

        @Test
        @DisplayName("every namespace the platform posts, reverses or adjusts under - 42, 7 and 2 - and no other")
        void registersEveryNamespace() {
            assertThat(REGISTER.namespaces(EntryType.POSTING)).containsExactly(
                    "bnpl.accrual:", "bnpl.billing:", "bnpl.closure-refund:", "bnpl.credit-application:", "bnpl.fee:",
                    "bnpl.financing:", "bnpl.refund:", "bnpl.repayment:", "bnpl.reversal-fee-release:",
                    "crossborder-return-fee:", "crossborder-return:", "dispute-attribution:", "dispute-chargeback:",
                    "dispute-fee:", "dispute-loss:", "dispute-reattribution:", "dispute-restoration:", "dispute-won:",
                    "fx-cover:", "fx-trade:", "lending.accrual:", "lending.amendment:", "lending.billing:",
                    "lending.closure-refund:", "lending.credit-application:", "lending.disbursement:", "lending.draw:",
                    "lending.fee:", "lending.repayment:", "lending.statement:", "lending.waiver:",
                    "merchant-payout-return:", "merchant-payout:", "outbound-credit:", "payment-capture:",
                    "payment-execution:", "payment-refund:", "recon-suspense:", "settlement-batch:", "transfer:",
                    "unmatched-confirmation:", "wallet-withdrawal:");
            assertThat(REGISTER.namespaces(EntryType.REVERSAL)).containsExactly(
                    "bnpl.repayment-reversal:", "bnpl.waiver:", "fx-trade:", "lending.repayment-reversal:",
                    "lending.waiver:", "settlement-batch:", "transfer:");
            assertThat(REGISTER.namespaces(EntryType.ADJUSTMENT)).containsExactly("MANUAL", "RECONCILIATION");
        }

        @Test
        @DisplayName("the back-dating posters are named: ECONOMIC_DATE lending's accrual and the manual adjustment;"
                + " STORED_ROW the settlement, suspense, payout-return, fx and reconciliation-resolution rules")
        void namesTheBackDatingPosters() {
            assertThat(ids(DatingClass.ECONOMIC_DATE)).containsExactly("ADJ-MANUAL", "LND-2");
            assertThat(REGISTER.rules().stream().filter(rule -> rule.dating() == DatingClass.ECONOMIC_DATE)
                    .flatMap(rule -> rule.posters().stream())).containsExactlyInAnyOrder("LoanServicing",
                    "LedgerAdjustments");
            assertThat(ids(DatingClass.STORED_ROW)).containsExactly(
                    "FX-COVER", "FX-TRADE", "MERCHANT-PAYOUT-RETURN", "RECON-GAIN", "RECON-SUSPENSE",
                    "RECON-TRANSFER", "RECON-WRITE-OFF", "SETTLEMENT-REPORT", "SETTLEMENT-STATEMENT");
            assertThat(REGISTER.rules().stream().filter(PostingRule::mayBackDate).map(PostingRule::id))
                    .hasSize(11);
            // Lending dates by the database clock in its servicing zone except the accrual; bnpl always does.
            assertThat(ids(DatingClass.TODAY_DATABASE)).allMatch(id -> id.startsWith("LND-") || id.startsWith("BNPL-"))
                    .hasSize(24);
            assertThat(REGISTER.rules().stream().filter(rule -> rule.id().startsWith("BNPL-"))
                    .map(PostingRule::dating)).containsOnly(DatingClass.TODAY_DATABASE);
            assertThat(ids(DatingClass.TODAY_CLOCK)).hasSize(23)
                    .noneMatch(id -> id.startsWith("LND-") || id.startsWith("BNPL-"));
        }

        @Test
        @DisplayName("a namespace resolves to the rules in force for its type; an unregistered one to none")
        void resolvesByTypeAndNamespace() {
            LocalDate today = LocalDate.of(2026, 10, 30);
            assertThat(REGISTER.inForce(EntryType.POSTING, "payment-capture:", today)).extracting(PostingRule::id)
                    .containsExactly("CARD-CAPTURE-TOPUP", "CARD-CAPTURE-MERCHANT");
            assertThat(REGISTER.inForce(EntryType.REVERSAL, "transfer:", today)).extracting(PostingRule::id)
                    .containsExactly("TRANSFER-REVERSAL");
            assertThat(REGISTER.inForce(EntryType.POSTING, "lending.waiver:", today)).extracting(PostingRule::id)
                    .containsExactly("LND-8a", "LND-9");
            assertThat(REGISTER.inForce(EntryType.ADJUSTMENT, "RECONCILIATION", today)).hasSize(3);
            assertThat(REGISTER.inForce(EntryType.POSTING, "rogue:", today)).isEmpty();
            assertThat(REGISTER.inForce(EntryType.REVERSAL, "payment-capture:", today))
                    .as("a namespace is registered per type - no capture is ever reversed through the ledger door")
                    .isEmpty();
            assertThat(PostingRuleRegister.namespaceOf("lending.accrual:0190-loan:2026-10-30"))
                    .isEqualTo("lending.accrual:");
            assertThat(PostingRuleRegister.namespaceOf("no-colon")).isEqualTo("no-colon");
        }
    }

    // ------------------------------------------------------------------ versioning (ADR-0117 decision 1)

    @Nested
    @DisplayName("versioning")
    class Versioning {

        private final LocalDate change = LocalDate.of(2026, 11, 1);

        @Test
        @DisplayName("an entry is judged by the version in force at its posting date, per rule id")
        void judgesByTheVersionInForce() {
            PostingRuleRegister register = new PostingRuleRegister(2, List.of(
                    rule("R-1", 1, PostingRuleRegister.ORIGIN, "sample:", LineShape.required(Side.DEBIT, "A_X")),
                    rule("R-1", 2, change, "sample:", LineShape.required(Side.DEBIT, "B_X")),
                    rule("R-2", 1, change, "sample:", LineShape.required(Side.DEBIT, "C_X"))));
            assertThat(register.inForce(EntryType.POSTING, "sample:", change.minusDays(1)))
                    .extracting(PostingRule::id, PostingRule::version).containsExactly(
                            org.assertj.core.groups.Tuple.tuple("R-1", 1));
            assertThat(register.inForce(EntryType.POSTING, "sample:", change))
                    .extracting(PostingRule::id, PostingRule::version).containsExactly(
                            org.assertj.core.groups.Tuple.tuple("R-1", 2), org.assertj.core.groups.Tuple.tuple("R-2", 1));
            assertThat(register.rule("R-1", 1)).isPresent();
            assertThat(register.rule("R-1", 3)).isEmpty();
        }

        @Test
        @DisplayName("an incoherent catalogue is refused at construction: a repeated version, an id over two"
                + " namespaces, a version not later than its predecessor")
        void refusesAnIncoherentCatalogue() {
            LineShape line = LineShape.required(Side.DEBIT, "A_X");
            assertThatThrownBy(() -> new PostingRuleRegister(1, List.of(
                    rule("R-1", 1, PostingRuleRegister.ORIGIN, "sample:", line),
                    rule("R-1", 1, change, "sample:", line))))
                    .hasMessageContaining("version 1 twice");
            assertThatThrownBy(() -> new PostingRuleRegister(1, List.of(
                    rule("R-1", 1, PostingRuleRegister.ORIGIN, "sample:", line),
                    rule("R-1", 2, change, "other:", line))))
                    .hasMessageContaining("one type and namespace");
            assertThatThrownBy(() -> new PostingRuleRegister(1, List.of(
                    rule("R-1", 1, change, "sample:", line),
                    rule("R-1", 2, change, "sample:", line))))
                    .hasMessageContaining("must take effect after");
            assertThatThrownBy(() -> new PostingRuleRegister(1, List.of())).hasMessageContaining("registers something");
        }

        @Test
        @DisplayName("a rule is refused when its namespace is not its type's form, or no line may take a side")
        void refusesAMalformedRule() {
            assertThatThrownBy(() -> rule("R-1", 1, PostingRuleRegister.ORIGIN, "Not A Key",
                    LineShape.required(Side.DEBIT, "A_X"))).hasMessageContaining("namespace must match");
            assertThatThrownBy(() -> new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, EntryType.ADJUSTMENT,
                    "sample:", "m", List.of("Poster"), "e", "r", DatingClass.TODAY_CLOCK,
                    List.of(LineShape.required(Side.EITHER, "A_X")), "c"))
                    .hasMessageContaining("namespace must match");
            assertThatThrownBy(() -> new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, EntryType.POSTING,
                    "sample:", "m", List.of("Poster"), "e", "r", DatingClass.TODAY_CLOCK,
                    List.of(LineShape.required(Side.DEBIT, "A_X")), "c"))
                    .hasMessageContaining("admits a debit and a credit");
            // INV-LED-05: a rule nobody can attribute - no authoritative reference, no source - is no rule.
            assertThatThrownBy(() -> new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, EntryType.POSTING,
                    "sample:", "m", List.of("Poster"), "e", " ", DatingClass.TODAY_CLOCK,
                    List.of(LineShape.required(Side.DEBIT, "A_X"), LineShape.required(Side.CREDIT, "B_X")), "c"))
                    .hasMessageContaining("states its source, event, reference and catalogue");
            assertThatThrownBy(() -> new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, EntryType.POSTING,
                    "sample:", " ", List.of("Poster"), "e", "r", DatingClass.TODAY_CLOCK,
                    List.of(LineShape.required(Side.DEBIT, "A_X"), LineShape.required(Side.CREDIT, "B_X")), "c"))
                    .hasMessageContaining("states its source, event, reference and catalogue");
            assertThat(REGISTER.rules()).allSatisfy(rule -> {
                assertThat(rule.authoritativeReference()).isNotBlank();
                assertThat(rule.sourceModule()).isIn("transfers", "payments", "merchant", "settlement",
                        "reconciliation", "fx", "lending", "bnpl", "ledger", "app");
            });
            assertThatThrownBy(() -> LineShape.required(Side.DEBIT, "A_X", "A_X")).hasMessageContaining("once");
            assertThatThrownBy(() -> LineShape.required(Side.DEBIT, "lower_case"))
                    .hasMessageContaining("ledger purpose's name");
        }

        private PostingRule rule(String id, int version, LocalDate from, String namespace, LineShape line) {
            return new PostingRule(id, version, from, EntryType.POSTING, namespace, "sample", List.of("SamplePoster"),
                    "a sample event", "a sample record", DatingClass.TODAY_CLOCK,
                    List.of(line, LineShape.required(Side.CREDIT, "Z_X")), "a sample catalogue");
        }
    }

    // ------------------------------------------------------------------ the families

    @Nested
    @DisplayName("transfers and payments (PAYMENT_LIFECYCLES.md, ADR-0050, ADR-0059, the settlement lifecycle section 2)")
    class TransfersAndPayments {

        @Test
        @DisplayName("each rule's shape, source and dating")
        void shapes() {
            assertShape("TRANSFER", "transfers", "DR CUSTOMER_WALLET", "CR CUSTOMER_WALLET");
            assertShape("TRANSFER-REVERSAL", "transfers", "DR CUSTOMER_WALLET", "CR CUSTOMER_WALLET");
            assertShape("CARD-CAPTURE-TOPUP", "payments", "DR SETTLEMENT_CLEARING", "CR CUSTOMER_WALLET");
            assertShape("CARD-CAPTURE-MERCHANT", "payments", "DR SETTLEMENT_CLEARING", "CR MERCHANT_PAYABLE",
                    "DR? MERCHANT_PAYABLE", "CR? FEE_REVENUE");
            assertShape("PUSH-EXECUTION-TOPUP", "payments", "DR INSTANT_CLEARING", "CR CUSTOMER_WALLET");
            assertShape("PUSH-EXECUTION-MERCHANT", "payments", "DR INSTANT_CLEARING/CUSTOMER_WALLET",
                    "CR MERCHANT_PAYABLE", "DR? MERCHANT_PAYABLE", "CR? FEE_REVENUE");
            assertShape("REFUND-TOPUP", "payments", "DR CUSTOMER_WALLET", "CR SETTLEMENT_CLEARING/INSTANT_CLEARING");
            assertShape("REFUND-MERCHANT", "payments", "DR MERCHANT_PAYABLE",
                    "CR SETTLEMENT_CLEARING/INSTANT_CLEARING/CUSTOMER_WALLET", "DR? FEE_REVENUE",
                    "CR? MERCHANT_PAYABLE");
            assertShape("UNMATCHED-CONFIRMATION", "payments", "DR INSTANT_CLEARING", "CR SUSPENSE_UNMATCHED");
            assertShape("WALLET-WITHDRAWAL", "payments", "DR CUSTOMER_WALLET", "CR INSTANT_CLEARING");
            for (String id : List.of("TRANSFER", "TRANSFER-REVERSAL", "CARD-CAPTURE-TOPUP", "CARD-CAPTURE-MERCHANT",
                    "PUSH-EXECUTION-TOPUP", "PUSH-EXECUTION-MERCHANT", "REFUND-TOPUP", "REFUND-MERCHANT",
                    "UNMATCHED-CONFIRMATION", "WALLET-WITHDRAWAL")) {
                assertThat(rule(id).dating()).as(id + ": the instance's UTC clock").isEqualTo(DatingClass.TODAY_CLOCK);
            }
        }
    }

    @Nested
    @DisplayName("disputes (RAIL_AND_DISPUTE_LIFECYCLES.md stage table, ADR-0061)")
    class Disputes {

        @Test
        @DisplayName("seven stages, each its own namespace, the rail's clearing SETTLEMENT_CLEARING")
        void shapes() {
            assertShape("DISPUTE-CHARGEBACK", "payments", "DR CHARGEBACK_RECOVERABLE", "CR SETTLEMENT_CLEARING");
            assertShape("DISPUTE-ATTRIBUTION", "payments", "DR MERCHANT_PAYABLE/CUSTOMER_WALLET",
                    "CR CHARGEBACK_RECOVERABLE");
            assertShape("DISPUTE-WON", "payments", "DR SETTLEMENT_CLEARING", "CR CHARGEBACK_RECOVERABLE");
            assertShape("DISPUTE-RESTORATION", "payments", "DR CHARGEBACK_RECOVERABLE",
                    "CR MERCHANT_PAYABLE/CUSTOMER_WALLET");
            assertShape("DISPUTE-LOSS", "payments", "DR DISPUTE_COSTS", "CR CHARGEBACK_RECOVERABLE");
            assertShape("DISPUTE-FEE", "payments", "DR DISPUTE_COSTS", "CR SETTLEMENT_CLEARING");
            assertShape("DISPUTE-REATTRIBUTION", "payments", "DR MERCHANT_PAYABLE/CUSTOMER_WALLET",
                    "CR CHARGEBACK_RECOVERABLE/DISPUTE_COSTS");
            assertThat(REGISTER.rules().stream().filter(rule -> rule.id().startsWith("DISPUTE-")))
                    .hasSize(7).allSatisfy(rule -> {
                        assertThat(rule.posters()).containsExactly("ChargebackAccounting");
                        assertThat(rule.dating()).isEqualTo(DatingClass.TODAY_CLOCK);
                    });
        }
    }

    @Nested
    @DisplayName("fx and cross-border (LEDGER_MODEL.md section 9)")
    class FxAndCrossBorder {

        @Test
        @DisplayName("conversion legs meet on FX_POSITION; the conversion and cover dated from stored rows")
        void shapes() {
            assertShape("FX-TRADE", "fx", "DR CUSTOMER_WALLET", "CR FX_POSITION", "DR FX_POSITION",
                    "CR CUSTOMER_WALLET", "CR? FX_SPREAD_REVENUE", "DC? ROUNDING_RESIDUAL");
            assertShape("FX-COVER", "fx", "DR FX_POSITION", "CR FX_PROVIDER_CLEARING", "DR FX_PROVIDER_CLEARING",
                    "CR FX_POSITION", "CR? FX_REALISED_GAINS", "DR? FX_REALISED_LOSSES");
            assertShape("FX-TRADE-REVERSAL", "fx", "DR FX_POSITION", "CR CUSTOMER_WALLET", "DR CUSTOMER_WALLET",
                    "CR FX_POSITION", "DR? FX_SPREAD_REVENUE", "DC? ROUNDING_RESIDUAL");
            assertShape("OUTBOUND-CREDIT", "payments", "DR CUSTOMER_WALLET", "CR? FEE_REVENUE", "CR FX_POSITION",
                    "DR FX_POSITION", "CR CORRIDOR_CLEARING", "CR? FX_SPREAD_REVENUE", "DC? ROUNDING_RESIDUAL");
            assertShape("CROSSBORDER-RETURN", "payments", "DR CORRIDOR_CLEARING", "CR CUSTOMER_WALLET",
                    "DR? FEE_REVENUE", "CR? CUSTOMER_WALLET");
            assertShape("CROSSBORDER-RETURN-FEE", "app", "DR FEE_REVENUE", "CR CUSTOMER_WALLET");
            assertThat(rule("FX-TRADE").dating()).as("trade.booked_on").isEqualTo(DatingClass.STORED_ROW);
            assertThat(rule("FX-COVER").dating()).as("cover_execution.recorded_on").isEqualTo(DatingClass.STORED_ROW);
            assertThat(rule("FX-TRADE-REVERSAL").dating()).isEqualTo(DatingClass.TODAY_CLOCK);
            assertThat(rule("OUTBOUND-CREDIT").dating()).isEqualTo(DatingClass.TODAY_CLOCK);
        }
    }

    @Nested
    @DisplayName("merchant, settlement and reconciliation (the settlement lifecycle 3.3, ADR-0065, ADR-0070, ADR-0071)")
    class MerchantSettlementAndReconciliation {

        @Test
        @DisplayName("the payout pair, the two recognitions and their reversal, suspense, the three resolution kinds")
        void shapes() {
            String positions = "SETTLEMENT_CLEARING/INSTANT_CLEARING/PAYOUT_CLEARING/FX_PROVIDER_CLEARING"
                    + "/CORRIDOR_CLEARING";
            assertShape("MERCHANT-PAYOUT", "merchant", "DR MERCHANT_PAYABLE", "CR PAYOUT_CLEARING");
            assertShape("MERCHANT-PAYOUT-RETURN", "merchant", "DR PAYOUT_CLEARING", "CR MERCHANT_PAYABLE");
            assertShape("SETTLEMENT-REPORT", "settlement", "DC PROCESSING_COSTS", "DC " + positions);
            assertShape("SETTLEMENT-STATEMENT", "settlement", "DC? CASH_AT_BANK", "DC? " + positions,
                    "DC? PROCESSING_COSTS", "CR? SUSPENSE_UNMATCHED", "DR? SUSPENSE_UNMATCHED", "CR? LENDING_CAPITAL");
            assertShape("SETTLEMENT-REPUDIATION", "reconciliation", "DC? CASH_AT_BANK", "DC? " + positions,
                    "DC? PROCESSING_COSTS", "DR? SUSPENSE_UNMATCHED", "CR? SUSPENSE_UNMATCHED", "DR? LENDING_CAPITAL");
            assertShape("RECON-SUSPENSE", "reconciliation", "DC SUSPENSE_UNMATCHED", "DC " + positions);
            assertShape("RECON-WRITE-OFF", "reconciliation", "DR RECONCILIATION_LOSSES",
                    "CR SUSPENSE_UNMATCHED/" + positions);
            assertShape("RECON-TRANSFER", "reconciliation", "DR SUSPENSE_UNMATCHED/" + positions,
                    "CR CUSTOMER_WALLET/MERCHANT_PAYABLE");
            assertShape("RECON-GAIN", "reconciliation", "DR SUSPENSE_UNMATCHED", "CR RECONCILIATION_GAINS");
            assertThat(rule("SETTLEMENT-STATEMENT").economicEvent()).as("rule 0 rides the statement")
                    .contains("ADR-0096 rule 0");
            assertThat(rule("MERCHANT-PAYOUT-RETURN").dating()).as("the item's batch accepted_on")
                    .isEqualTo(DatingClass.STORED_ROW);
        }
    }

    @Nested
    @DisplayName("lending (ADR-0096 section 2, rules 1 to 13)")
    class Lending {

        @Test
        @DisplayName("every rule that posts, each under its own key; rule 0 is the statement's, 11 posts nothing, 12 is"
                + " the withdrawal's, 14 is not built")
        void shapes() {
            assertShape("LND-1", "lending", "DR LOAN_PRINCIPAL", "CR CUSTOMER_WALLET", "CR? LOAN_FEE_INCOME");
            assertShape("LND-1D", "lending", "DR LOAN_PRINCIPAL", "CR CUSTOMER_WALLET");
            assertShape("LND-2", "lending", "DR LOAN_INTEREST_ACCRUED", "CR LOAN_INTEREST_INCOME");
            assertShape("LND-3", "lending", "DR? LOAN_INTEREST_DUE", "CR? LOAN_INTEREST_ACCRUED",
                    "DR? LOAN_PRINCIPAL_DUE", "CR? LOAN_PRINCIPAL");
            assertShape("LND-3S", "lending", "DR? LOAN_INTEREST_DUE", "CR? LOAN_INTEREST_ACCRUED",
                    "DR? LOAN_PRINCIPAL_DUE", "CR? LOAN_PRINCIPAL");
            assertShape("LND-4", "lending", "DR LOAN_FEES_DUE", "CR LOAN_FEE_INCOME");
            assertShape("LND-5", "lending", "DR CUSTOMER_WALLET", "CR? LOAN_FEES_DUE", "CR? LOAN_INTEREST_DUE",
                    "CR? LOAN_PRINCIPAL_DUE", "CR? LOAN_PRINCIPAL", "CR? LOAN_CREDIT_BALANCE");
            assertShape("LND-6", "lending", "DR LOAN_CREDIT_BALANCE", "CR? LOAN_FEES_DUE", "CR? LOAN_INTEREST_DUE",
                    "CR? LOAN_PRINCIPAL_DUE");
            assertShape("LND-7", "lending", "DR LOAN_CREDIT_BALANCE", "CR CUSTOMER_WALLET");
            assertShape("LND-8", "lending", "DR LOAN_FEE_INCOME", "CR LOAN_FEES_DUE");
            assertShape("LND-8a", "lending", "DR LOAN_FEE_INCOME", "CR LOAN_CREDIT_BALANCE");
            assertShape("LND-9", "lending", "DR LOAN_INTEREST_INCOME", "CR LOAN_INTEREST_DUE");
            assertShape("LND-10", "lending", "DR? LOAN_FEES_DUE", "DR? LOAN_INTEREST_DUE", "DR? LOAN_PRINCIPAL_DUE",
                    "DR? LOAN_PRINCIPAL", "DR? LOAN_CREDIT_BALANCE", "CR CUSTOMER_WALLET");
            assertShape("LND-13", "lending", "DR LOAN_PRINCIPAL", "CR LOAN_PRINCIPAL_DUE");
            assertThat(rule("LND-8").type()).as("a fee waived is the ledger's reversal of rule 4")
                    .isEqualTo(EntryType.REVERSAL);
            assertThat(rule("LND-10").type()).isEqualTo(EntryType.REVERSAL);
            assertThat(rule("LND-2").dating()).as("posting = value = the accrual date").isEqualTo(
                    DatingClass.ECONOMIC_DATE);
            assertThat(REGISTER.rules().stream().filter(rule -> rule.id().startsWith("LND-"))).hasSize(14)
                    .allSatisfy(rule -> assertThat(rule.purposes()).noneMatch(purpose ->
                            purpose.equals("LOAN_WRITE_OFF_EXPENSE") || purpose.startsWith("BNPL_")));
        }
    }

    @Nested
    @DisplayName("bnpl (ADR-0103, LEDGER_MODEL.md section 11, F1 to F8a)")
    class Bnpl {

        @Test
        @DisplayName("every rule that posts; F6 has no key of its own, F9 is F5, F10 is the merchant payout, F11 is not"
                + " built")
        void shapes() {
            assertShape("BNPL-F1", "bnpl", "DR BNPL_PRINCIPAL", "CR MERCHANT_PAYABLE", "DR? MERCHANT_PAYABLE",
                    "CR? BNPL_MERCHANT_FEE_INCOME");
            assertShape("BNPL-F2", "bnpl", "DR? BNPL_PRINCIPAL_DUE", "CR? BNPL_PRINCIPAL", "DR? BNPL_INTEREST_DUE",
                    "CR? BNPL_INTEREST_ACCRUED");
            assertShape("BNPL-F2a", "bnpl", "DR BNPL_INTEREST_ACCRUED", "CR BNPL_INTEREST_INCOME");
            assertShape("BNPL-F3", "bnpl", "DR CUSTOMER_WALLET", "CR? BNPL_PRINCIPAL_DUE", "CR? BNPL_INTEREST_DUE",
                    "CR? BNPL_FEES_DUE", "CR? BNPL_CREDIT_BALANCE");
            assertShape("BNPL-F3a", "bnpl", "DR BNPL_CREDIT_BALANCE", "CR? BNPL_PRINCIPAL_DUE",
                    "CR? BNPL_INTEREST_DUE", "CR? BNPL_FEES_DUE");
            assertShape("BNPL-CLOSURE", "bnpl", "DR BNPL_CREDIT_BALANCE", "CR CUSTOMER_WALLET");
            assertShape("BNPL-F4", "bnpl", "DR BNPL_FEES_DUE", "CR BNPL_FEE_INCOME");
            assertShape("BNPL-F5", "bnpl", "DR MERCHANT_PAYABLE", "DR? BNPL_MERCHANT_FEE_INCOME", "CR? BNPL_PRINCIPAL",
                    "CR? BNPL_PRINCIPAL_DUE", "CR? CUSTOMER_WALLET/BNPL_CREDIT_BALANCE", "CR? MERCHANT_PAYABLE",
                    "DR? BNPL_INTEREST_INCOME", "CR? BNPL_INTEREST_ACCRUED", "CR? BNPL_INTEREST_DUE",
                    "DR? BNPL_FEE_INCOME", "CR? BNPL_FEES_DUE");
            assertShape("BNPL-F7", "bnpl", "DR BNPL_FEE_INCOME", "CR BNPL_FEES_DUE");
            assertShape("BNPL-F8", "bnpl", "DR? BNPL_PRINCIPAL_DUE", "DR? BNPL_INTEREST_DUE", "DR? BNPL_FEES_DUE",
                    "DR? BNPL_CREDIT_BALANCE", "CR CUSTOMER_WALLET");
            assertShape("BNPL-F8a", "bnpl", "DR BNPL_FEE_INCOME", "CR BNPL_FEES_DUE");
            assertThat(rule("BNPL-F2").posters()).containsExactly("BnplAcceptances", "BnplServicing");
            assertThat(rule("BNPL-F3").posters()).containsExactly("BnplAcceptances", "BnplRepayments");
            assertThat(REGISTER.rules().stream().filter(rule -> rule.id().startsWith("BNPL-"))).hasSize(11)
                    .allSatisfy(rule -> assertThat(rule.purposes()).noneMatch(purpose ->
                            purpose.equals("BNPL_WRITE_OFF_EXPENSE") || purpose.startsWith("LOAN_")
                                    || purpose.equals("LENDING_CAPITAL")));
        }
    }

    @Nested
    @DisplayName("adjustments (INV-REV-04, ledger V010 and V015, ADR-0071)")
    class Adjustments {

        @Test
        @DisplayName("MANUAL on the five purposes open to free adjustment, dated as its proposer states; the three"
                + " reconciliation kinds dated their resolution's stored proposal date")
        void shapes() {
            String open = "CUSTOMER_WALLET/MERCHANT_PAYABLE/CHARGEBACK_RECOVERABLE/DISPUTE_COSTS/FEE_REVENUE";
            assertShape("ADJ-MANUAL", "ledger", "DR " + open, "CR " + open);
            assertThat(rule("ADJ-MANUAL").dating()).isEqualTo(DatingClass.ECONOMIC_DATE);
            assertThat(REGISTER.rules().stream().filter(rule -> rule.namespace().equals("RECONCILIATION")))
                    .extracting(PostingRule::id).containsExactly("RECON-WRITE-OFF", "RECON-TRANSFER", "RECON-GAIN");
            assertThat(REGISTER.rules().stream().filter(rule -> rule.type() == EntryType.ADJUSTMENT))
                    .as("the ACCOUNTING origin arrives with P14-TSK-013, its rule with it")
                    .noneMatch(rule -> rule.namespace().equals("ACCOUNTING"));
        }
    }

    // ------------------------------------------------------------------

    private static PostingRule rule(String id) {
        return REGISTER.rule(id, 1).orElseThrow(() -> new AssertionError("no rule " + id));
    }

    private static void assertShape(String id, String sourceModule, String... expected) {
        PostingRule rule = rule(id);
        assertThat(rule.sourceModule()).as(id + "'s source module").isEqualTo(sourceModule);
        assertThat(render(rule)).as(id + "'s line shape").containsExactly(expected);
    }

    private static List<String> render(PostingRule rule) {
        List<String> rendered = new ArrayList<>();
        for (LineShape line : rule.lines()) {
            String side = switch (line.side()) {
                case DEBIT -> "DR";
                case CREDIT -> "CR";
                case EITHER -> "DC";
            };
            rendered.add(side + (line.required() ? "" : "?") + " " + String.join("/", line.purposes()));
        }
        return rendered;
    }

    private static Set<String> ids(DatingClass dating) {
        return REGISTER.rules().stream().filter(rule -> rule.dating() == dating).map(PostingRule::id)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
