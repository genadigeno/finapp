package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        @DisplayName("catalogue version 4: 61 rules in 68 versions - every rule at version 1 from the origin, and C3's"
                + " seven floored system rules at version 2 from 2026-10-30 (P14-TSK-006)")
        void isVersionTwoOfFiftyEightRules() {
            assertThat(REGISTER.catalogueVersion()).isEqualTo(PostingRuleRegister.CATALOGUE_VERSION).isEqualTo(4);
            assertThat(REGISTER.rules()).hasSize(68);
            assertThat(REGISTER.rules().stream().map(PostingRule::id).distinct()).hasSize(61);
            assertThat(REGISTER.rules().stream().filter(rule -> rule.version() == 1)).hasSize(61)
                    .allSatisfy(rule -> assertThat(rule.effectiveFrom()).isEqualTo(PostingRuleRegister.ORIGIN));
            assertThat(REGISTER.rules().stream().filter(rule -> rule.version() == 2)).hasSize(7)
                    .allSatisfy(rule -> assertThat(rule.effectiveFrom()).isEqualTo(PostingRuleRegister.C3_FLOOR));
            assertThat(PostingRuleRegister.C3_FLOOR).isEqualTo(LocalDate.of(2026, 10, 30));
            assertThat(REGISTER.rules()).allSatisfy(rule -> assertThat(rule.version()).isBetween(1, 2));
        }

        @Test
        @DisplayName("every namespace the platform posts, reverses or adjusts under - 44, 7 and 3 - and no other")
        void registersEveryNamespace() {
            assertThat(REGISTER.namespaces(EntryType.POSTING)).containsExactly(
                    "bnpl.accrual:", "bnpl.billing:", "bnpl.closure-refund:", "bnpl.credit-application:", "bnpl.fee:",
                    "bnpl.financing:", "bnpl.refund:", "bnpl.repayment:", "bnpl.reversal-fee-release:", "bnpl.write-off:",
                    "crossborder-return-fee:", "crossborder-return:", "dispute-attribution:", "dispute-chargeback:",
                    "dispute-fee:", "dispute-loss:", "dispute-reattribution:", "dispute-restoration:", "dispute-won:",
                    "fx-cover:", "fx-trade:", "lending.accrual:", "lending.amendment:", "lending.billing:",
                    "lending.closure-refund:", "lending.credit-application:", "lending.disbursement:", "lending.draw:",
                    "lending.fee:", "lending.repayment:", "lending.statement:", "lending.waiver:", "lending.write-off:",
                    "merchant-payout-return:", "merchant-payout:", "outbound-credit:", "payment-capture:",
                    "payment-execution:", "payment-refund:", "recon-suspense:", "settlement-batch:", "transfer:",
                    "unmatched-confirmation:", "wallet-withdrawal:");
            assertThat(REGISTER.namespaces(EntryType.REVERSAL)).containsExactly(
                    "bnpl.repayment-reversal:", "bnpl.waiver:", "fx-trade:", "lending.repayment-reversal:",
                    "lending.waiver:", "settlement-batch:", "transfer:");
            assertThat(REGISTER.namespaces(EntryType.ADJUSTMENT)).containsExactly("ACCOUNTING", "MANUAL", "RECONCILIATION");
        }

        @Test
        @DisplayName("the back-dating posters are named: ECONOMIC_DATE lending's accrual and the manual adjustment;"
                + " STORED_ROW the settlement, suspense, payout-return, fx, reconciliation-resolution and prior-period rules")
        void namesTheBackDatingPosters() {
            assertThat(ids(DatingClass.ECONOMIC_DATE)).containsExactly("ADJ-MANUAL", "LND-2");
            assertThat(REGISTER.rules().stream().filter(rule -> rule.dating() == DatingClass.ECONOMIC_DATE)
                    .flatMap(rule -> rule.posters().stream()).distinct()).containsExactlyInAnyOrder("LoanServicing",
                    "LedgerAdjustments");
            assertThat(ids(DatingClass.STORED_ROW)).containsExactly(
                    "ACC-PRIOR-PERIOD", "FX-COVER", "FX-TRADE", "MERCHANT-PAYOUT-RETURN", "RECON-GAIN", "RECON-SUSPENSE",
                    "RECON-TRANSFER", "RECON-WRITE-OFF", "SETTLEMENT-REPORT", "SETTLEMENT-STATEMENT");
            assertThat(REGISTER.rules().stream().filter(PostingRule::mayBackDate).map(PostingRule::id).distinct())
                    .hasSize(12);
            // Lending dates by the database clock in its servicing zone except the accrual; bnpl always does.
            assertThat(ids(DatingClass.TODAY_DATABASE)).allMatch(id -> id.startsWith("LND-") || id.startsWith("BNPL-"))
                    .hasSize(26);
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
                    "sample:", "m", List.of("Poster"), "e", "r", DatingClass.TODAY_CLOCK, CutoffReaction.NOT_REACHED,
                    List.of(LineShape.required(Side.EITHER, "A_X")), "c"))
                    .hasMessageContaining("namespace must match");
            assertThatThrownBy(() -> new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, EntryType.POSTING,
                    "sample:", "m", List.of("Poster"), "e", "r", DatingClass.TODAY_CLOCK, CutoffReaction.NOT_REACHED,
                    List.of(LineShape.required(Side.DEBIT, "A_X")), "c"))
                    .hasMessageContaining("admits a debit and a credit");
            // INV-LED-05: a rule nobody can attribute - no authoritative reference, no source - is no rule.
            assertThatThrownBy(() -> new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, EntryType.POSTING,
                    "sample:", "m", List.of("Poster"), "e", " ", DatingClass.TODAY_CLOCK, CutoffReaction.NOT_REACHED,
                    List.of(LineShape.required(Side.DEBIT, "A_X"), LineShape.required(Side.CREDIT, "B_X")), "c"))
                    .hasMessageContaining("states its source, event, reference and catalogue");
            assertThatThrownBy(() -> new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, EntryType.POSTING,
                    "sample:", " ", List.of("Poster"), "e", "r", DatingClass.TODAY_CLOCK, CutoffReaction.NOT_REACHED,
                    List.of(LineShape.required(Side.DEBIT, "A_X"), LineShape.required(Side.CREDIT, "B_X")), "c"))
                    .hasMessageContaining("states its source, event, reference and catalogue");
            assertThat(REGISTER.rules()).allSatisfy(rule -> {
                assertThat(rule.authoritativeReference()).isNotBlank();
                assertThat(rule.sourceModule()).isIn("transfers", "payments", "merchant", "settlement",
                        "reconciliation", "fx", "lending", "bnpl", "ledger", "app", "accounting");
            });
            assertThatThrownBy(() -> LineShape.required(Side.DEBIT, "A_X", "A_X")).hasMessageContaining("once");
            assertThatThrownBy(() -> LineShape.required(Side.DEBIT, "lower_case"))
                    .hasMessageContaining("ledger purpose's name");
        }

        private PostingRule rule(String id, int version, LocalDate from, String namespace, LineShape line) {
            return new PostingRule(id, version, from, EntryType.POSTING, namespace, "sample", List.of("SamplePoster"),
                    "a sample event", "a sample record", DatingClass.TODAY_CLOCK, CutoffReaction.NOT_REACHED,
                    List.of(line, LineShape.required(Side.CREDIT, "Z_X")), "a sample catalogue");
        }
    }

    // ------------------------------------------------------------------ the cutoff reactions (C3, P14-TSK-006)

    @Nested
    @DisplayName("the cutoff reactions (owner decision C3, P14-TSK-006)")
    class CutoffReactions {

        private final LocalDate floor = PostingRuleRegister.C3_FLOOR;

        @Test
        @DisplayName("each of the eleven back-dating rules names its reaction: seven system rules floored from 2026-10-30"
                + " (refused before), four adjustments refused at approval; every other rule is dated today - no lock"
                + " reaches it")
        void theElevenBackDatingRulesNameTheirReaction() {
            Map<String, CutoffReaction> inForce = new TreeMap<>();
            for (PostingRule rule : REGISTER.rules()) {
                REGISTER.inForce(rule.type(), rule.namespace(), floor).stream()
                        .filter(judging -> judging.id().equals(rule.id()))
                        .forEach(judging -> inForce.put(judging.id(), judging.cutoff()));
            }
            assertThat(inForce).hasSize(61);
            assertThat(inForce).containsAllEntriesOf(Map.of(
                    "LND-2", CutoffReaction.FLOORED_AT_FIRST_OPEN,
                    "SETTLEMENT-REPORT", CutoffReaction.FLOORED_AT_FIRST_OPEN,
                    "SETTLEMENT-STATEMENT", CutoffReaction.FLOORED_AT_FIRST_OPEN,
                    "MERCHANT-PAYOUT-RETURN", CutoffReaction.FLOORED_AT_FIRST_OPEN,
                    "RECON-SUSPENSE", CutoffReaction.FLOORED_AT_FIRST_OPEN,
                    "FX-TRADE", CutoffReaction.FLOORED_AT_FIRST_OPEN,
                    "FX-COVER", CutoffReaction.FLOORED_AT_FIRST_OPEN));
            assertThat(inForce).containsAllEntriesOf(Map.of(
                    "ADJ-MANUAL", CutoffReaction.REFUSED_AT_APPROVAL,
                    "RECON-WRITE-OFF", CutoffReaction.REFUSED_AT_APPROVAL,
                    "RECON-TRANSFER", CutoffReaction.REFUSED_AT_APPROVAL,
                    "RECON-GAIN", CutoffReaction.REFUSED_AT_APPROVAL,
                    "ACC-PRIOR-PERIOD", CutoffReaction.REFUSED_AT_APPROVAL));
            assertThat(inForce.entrySet().stream().filter(entry -> entry.getValue() != CutoffReaction.NOT_REACHED))
                    .as("exactly the twelve back-dating rules are reached by a lock").hasSize(12);
            assertThat(inForce.values()).as("nothing in force is still refused by a lock").doesNotContain(
                    CutoffReaction.REFUSED);
            assertThat(REGISTER.rules()).allSatisfy(rule -> assertThat(rule.cutoff().reachedByALock())
                    .as(rule.id() + " v" + rule.version()).isEqualTo(rule.mayBackDate()));
        }

        @Test
        @DisplayName("a floored version is version 1 re-dated, nothing else: the same type, namespace, posters, dating and"
                + " lines; the day before 2026-10-30 judges by version 1 (REFUSED), from it by version 2 (FLOORED)")
        void aFlooredVersionChangesOnlyTheReaction() {
            assertThat(REGISTER.rules().stream().filter(rule -> rule.version() == 2).map(PostingRule::id))
                    .containsExactlyInAnyOrder("LND-2", "SETTLEMENT-REPORT", "SETTLEMENT-STATEMENT",
                            "MERCHANT-PAYOUT-RETURN", "RECON-SUSPENSE", "FX-TRADE", "FX-COVER");
            for (PostingRule two : REGISTER.rules().stream().filter(rule -> rule.version() == 2).toList()) {
                PostingRule one = REGISTER.rule(two.id(), 1).orElseThrow();
                assertThat(one.cutoff()).as(two.id() + " v1").isEqualTo(CutoffReaction.REFUSED);
                assertThat(two.cutoff()).as(two.id() + " v2").isEqualTo(CutoffReaction.FLOORED_AT_FIRST_OPEN);
                assertThat(two.floored()).isTrue();
                assertThat(one.floored()).isFalse();
                assertThat(two.type()).isEqualTo(EntryType.POSTING).isEqualTo(one.type());
                assertThat(two.namespace()).isEqualTo(one.namespace());
                assertThat(two.posters()).isEqualTo(one.posters());
                assertThat(two.dating()).isEqualTo(one.dating());
                assertThat(two.lines()).isEqualTo(one.lines());
                assertThat(two.sourceModule()).isEqualTo(one.sourceModule());
                assertThat(two.authoritativeReference()).isEqualTo(one.authoritativeReference());
                assertThat(two.economicEvent()).contains("first open date");
                assertThat(versionInForce(two, floor.minusDays(1))).isEqualTo(1);
                assertThat(versionInForce(two, floor)).isEqualTo(2);
            }
            assertThat(REGISTER.rule("LND-2", 2).orElseThrow().economicEvent())
                    .startsWith("A day's interest earned - value-dated the accrual date;")
                    .doesNotContain("posted dated the accrual date itself");
        }

        @Test
        @DisplayName("an incoherent reaction is refused at construction: a dated-today rule reached by a lock, a"
                + " back-dating rule not reached, a floored reversal or adjustment, a back-dating adjustment not refused"
                + " at approval, a posting refused at approval")
        void anIncoherentReactionIsRefused() {
            assertThatThrownBy(() -> sample(EntryType.POSTING, "sample:", DatingClass.TODAY_CLOCK,
                    CutoffReaction.FLOORED_AT_FIRST_OPEN)).hasMessageContaining("cutoff reaction cannot be");
            assertThatThrownBy(() -> sample(EntryType.POSTING, "sample:", DatingClass.STORED_ROW,
                    CutoffReaction.NOT_REACHED)).hasMessageContaining("cutoff reaction cannot be");
            assertThatThrownBy(() -> sample(EntryType.REVERSAL, "sample:", DatingClass.STORED_ROW,
                    CutoffReaction.FLOORED_AT_FIRST_OPEN)).hasMessageContaining("only a POSTING asks");
            assertThatThrownBy(() -> sample(EntryType.ADJUSTMENT, "SAMPLE", DatingClass.ECONOMIC_DATE,
                    CutoffReaction.FLOORED_AT_FIRST_OPEN)).hasMessageContaining("only a POSTING asks");
            assertThatThrownBy(() -> sample(EntryType.ADJUSTMENT, "SAMPLE", DatingClass.ECONOMIC_DATE,
                    CutoffReaction.REFUSED)).hasMessageContaining("refused at approval");
            assertThatThrownBy(() -> sample(EntryType.POSTING, "sample:", DatingClass.ECONOMIC_DATE,
                    CutoffReaction.REFUSED_AT_APPROVAL)).hasMessageContaining("refused at approval");
            // The coherent ones build.
            assertThat(sample(EntryType.POSTING, "sample:", DatingClass.ECONOMIC_DATE,
                    CutoffReaction.FLOORED_AT_FIRST_OPEN).floored()).isTrue();
            assertThat(sample(EntryType.ADJUSTMENT, "SAMPLE", DatingClass.ECONOMIC_DATE,
                    CutoffReaction.REFUSED_AT_APPROVAL).floored()).isFalse();
            assertThat(sample(EntryType.ADJUSTMENT, "SAMPLE", DatingClass.TODAY_CLOCK, CutoffReaction.NOT_REACHED)
                    .mayBackDate()).isFalse();
        }

        private int versionInForce(PostingRule rule, LocalDate postingDate) {
            return REGISTER.inForce(rule.type(), rule.namespace(), postingDate).stream()
                    .filter(judging -> judging.id().equals(rule.id())).findFirst().orElseThrow().version();
        }

        private PostingRule sample(EntryType type, String namespace, DatingClass dating, CutoffReaction cutoff) {
            return new PostingRule("R-1", 1, PostingRuleRegister.ORIGIN, type, namespace, "sample",
                    List.of("SamplePoster"), "a sample event", "a sample record", dating, cutoff,
                    List.of(LineShape.required(Side.DEBIT, "A_X"), LineShape.required(Side.CREDIT, "Z_X")),
                    "a sample catalogue");
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
            assertThat(REGISTER.rules().stream().filter(rule -> rule.id().startsWith("LND-") && rule.version() == 1))
                    .hasSize(15)
                    .allSatisfy(rule -> assertThat(rule.purposes()).noneMatch(purpose -> purpose.startsWith("BNPL_")));
            // Rule 14 (P14-TSK-021): the one rule on the expense - every receivable optional, because a zero takes no line; the expense
            // required; never the credit balance, the customer's money; dated today, never floored; from the origin, so no entry of
            // the namespace can predate its rule.
            assertShape("LND-14", "lending", "DR LOAN_WRITE_OFF_EXPENSE", "CR? LOAN_PRINCIPAL", "CR? LOAN_PRINCIPAL_DUE",
                    "CR? LOAN_INTEREST_ACCRUED", "CR? LOAN_INTEREST_DUE", "CR? LOAN_FEES_DUE");
            assertThat(rule("LND-14").posters()).containsExactly("LoanWriteOffs");
            assertThat(rule("LND-14").dating()).isEqualTo(DatingClass.TODAY_DATABASE);
            assertThat(rule("LND-14").cutoff()).isEqualTo(CutoffReaction.NOT_REACHED);
            assertThat(rule("LND-14").effectiveFrom()).isEqualTo(PostingRuleRegister.ORIGIN);
            assertThat(REGISTER.rules().stream().filter(rule -> rule.purposes().contains("LOAN_WRITE_OFF_EXPENSE"))
                    .map(PostingRule::id)).containsExactly("LND-14");
        }
    }

    @Nested
    @DisplayName("bnpl (ADR-0103, LEDGER_MODEL.md section 11, F1 to F8a)")
    class Bnpl {

        @Test
        @DisplayName("every rule that posts; F6 has no key of its own, F9 is F5, F10 is the merchant payout, F11 the write-off"
                + " (P14-TSK-021)")
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
            assertThat(REGISTER.rules().stream().filter(rule -> rule.id().startsWith("BNPL-"))).hasSize(12)
                    .allSatisfy(rule -> assertThat(rule.purposes()).noneMatch(purpose -> purpose.startsWith("LOAN_")
                            || purpose.equals("LENDING_CAPITAL")));
            assertShape("BNPL-F11", "bnpl", "DR BNPL_WRITE_OFF_EXPENSE", "CR? BNPL_PRINCIPAL", "CR? BNPL_PRINCIPAL_DUE",
                    "CR? BNPL_INTEREST_ACCRUED", "CR? BNPL_INTEREST_DUE", "CR? BNPL_FEES_DUE");
            assertThat(rule("BNPL-F11").posters()).containsExactly("BnplWriteOffs");
            assertThat(rule("BNPL-F11").cutoff()).isEqualTo(CutoffReaction.NOT_REACHED);
            assertThat(REGISTER.rules().stream().filter(rule -> rule.purposes().contains("BNPL_WRITE_OFF_EXPENSE"))
                    .map(PostingRule::id)).containsExactly("BNPL-F11");
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
            // P14-TSK-013: the ACCOUNTING origin, with its rule. Stored, refused at approval, never floored; effective from the register's origin,
            // so no adjustment of the origin can predate its rule.
            assertShape("ACC-PRIOR-PERIOD", "accounting", "DR " + open, "CR " + open);
            PostingRule priorPeriod = rule("ACC-PRIOR-PERIOD");
            assertThat(priorPeriod.namespace()).isEqualTo("ACCOUNTING");
            assertThat(priorPeriod.dating()).isEqualTo(DatingClass.STORED_ROW);
            assertThat(priorPeriod.cutoff()).isEqualTo(CutoffReaction.REFUSED_AT_APPROVAL);
            assertThat(priorPeriod.floored()).as("C3's floor is a POSTING's and cannot reach this origin").isFalse();
            assertThat(priorPeriod.effectiveFrom()).isEqualTo(PostingRuleRegister.ORIGIN);
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
