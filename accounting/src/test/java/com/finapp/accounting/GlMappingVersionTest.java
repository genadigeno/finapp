package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.ReferenceGlMapping;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A GL mapping version's own coherence, judged before anything is locked or written (`P14-TSK-003`; ADR-0113 decision
 * 3): every account well-formed and presented in a section that admits its classification, codes unique, one rule per
 * (purpose, owner kind), every rule naming an account of the version - and the reviewer's diff, pure.
 */
@DisplayName("a GL mapping version is coherent before it is proposed (P14-TSK-003)")
class GlMappingVersionTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 11);
    private static final GlAccount CASH =
            new GlAccount("1000", "Cash at bank", GlClassification.ASSET, StatementSection.CASH_AND_BANK);

    @Test
    @DisplayName("the reference is coherent: 35 accounts, 40 rules, every section admitting its account, the normal"
            + " side the classification's")
    void theReferenceIsCoherent() {
        GlMappingVersion reference = ReferenceGlMapping.version();
        assertThat(reference.accounts()).hasSize(35);
        assertThat(reference.rules()).hasSize(40);
        assertThat(reference.effectiveFromPeriod()).hasToString("2026-11-01");
        assertThat(reference.accounts()).allSatisfy(account -> {
            assertThat(account.section().admits(account.classification())).isTrue();
            assertThat(account.normalBalance()).isEqualTo(account.classification().debitNormal() ? "DEBIT" : "CREDIT");
        });
    }

    @Test
    @DisplayName("a section that does not admit its account's classification is refused - a revenue on the balance"
            + " sheet, an asset in the income statement")
    void aSectionAdmitsOnlyItsStatement() {
        assertThatThrownBy(() -> new GlAccount("4000", "Fees", GlClassification.REVENUE, StatementSection.OTHER_ASSETS))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class).hasMessageContaining("does not admit a REVENUE");
        assertThatThrownBy(() -> new GlAccount("1000", "Cash", GlClassification.ASSET, StatementSection.FX_RESULT))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        assertThat(new GlAccount("2900", "Suspense", GlClassification.ASSET, StatementSection.SUSPENSE).normalBalance())
                .isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("malformed accounts and rules are refused: the code's, the name's, the purpose's and the owner kind's"
            + " shapes, the classification and section required")
    void shapes() {
        for (String code : new String[] {"100", "123456789", "10A0", null}) {
            assertThatThrownBy(() -> new GlAccount(code, "x", GlClassification.ASSET, StatementSection.CASH_AND_BANK))
                    .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        }
        for (String name : new String[] {"", " padded", "x".repeat(121), null}) {
            assertThatThrownBy(() -> new GlAccount("1000", name, GlClassification.ASSET, StatementSection.CASH_AND_BANK))
                    .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        }
        assertThatThrownBy(() -> new GlAccount("1000", "Cash", null, StatementSection.CASH_AND_BANK))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        assertThatThrownBy(() -> new MappingRule("cash_at_bank", "OPERATIONAL", "1000"))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        assertThatThrownBy(() -> new MappingRule("CASH_AT_BANK", "operational", "1000"))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        assertThatThrownBy(() -> new MappingRule("CASH_AT_BANK", "OPERATIONAL", "x"))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        assertThat(new MappingRule("CASH_AT_BANK", "OPERATIONAL", "1000").key()).isEqualTo("CASH_AT_BANK/OPERATIONAL");
    }

    @Test
    @DisplayName("a version is refused when a code repeats, two rules map one purpose, a rule names an absent account,"
            + " or it carries no account, no rule or no month")
    void coherence() {
        MappingRule rule = new MappingRule("CASH_AT_BANK", "OPERATIONAL", "1000");
        assertThatThrownBy(() -> new GlMappingVersion(MONTH, List.of(CASH, CASH), List.of(rule)))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class).hasMessageContaining("appears twice");
        assertThatThrownBy(() -> new GlMappingVersion(MONTH, List.of(CASH), List.of(rule, rule)))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class).hasMessageContaining("exactly one GL account");
        assertThatThrownBy(() -> new GlMappingVersion(MONTH, List.of(CASH),
                List.of(new MappingRule("CASH_AT_BANK", "OPERATIONAL", "1001"))))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class).hasMessageContaining("does not hold");
        assertThatThrownBy(() -> new GlMappingVersion(MONTH, List.of(), List.of(rule)))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        assertThatThrownBy(() -> new GlMappingVersion(MONTH, List.of(CASH), List.of()))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        assertThatThrownBy(() -> new GlMappingVersion(null, List.of(CASH), List.of(rule)))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
        List<MappingRule> many = new ArrayList<>();
        for (int i = 0; i <= GlMappingVersion.MAX_ENTRIES; i++) {
            many.add(new MappingRule("P" + i, "OPERATIONAL", "1000"));
        }
        assertThatThrownBy(() -> new GlMappingVersion(MONTH, List.of(CASH), many))
                .isInstanceOf(GlMappingVersion.MappingInvalid.class);
    }

    @Test
    @DisplayName("the reviewer's diff: against nothing every account and rule is added; against the reference a remap"
            + " is one rule changed and one account added, a removal one rule removed - each list ordered")
    void theDiff() {
        GlMappings.Version reference = version(ReferenceGlMapping.version(), 1);
        LedgerChart.Chart chart = ReferenceGlMapping.agreeingChart(purpose -> true);
        GlMappings.Diff first = GlMappings.between(reference, Optional.empty(), chart);
        assertThat(first.accountsAdded()).hasSize(35);
        assertThat(first.rulesAdded()).hasSize(40);
        assertThat(first.judgement().total()).isTrue();
        assertThat(first.counterparties()).extracting(LedgerChart.ChartCounterparty::code).containsExactly("fx-sim-a");

        GlMappings.Version remapped = version(ReferenceGlMapping.remapped(MONTH, "FX_POSITION",
                new GlAccount("1150", "FX position - other", GlClassification.ASSET, StatementSection.OTHER_ASSETS)), 2);
        GlMappings.Diff second = GlMappings.between(remapped, Optional.of(reference), chart);
        assertThat(second.accountsAdded()).extracting(GlAccount::code).containsExactly("1150");
        assertThat(second.rulesChanged()).containsExactly(new GlMappings.RuleChange("FX_POSITION/OPERATIONAL", "1140", "1150"));
        assertThat(second.rulesAdded()).isEmpty();
        assertThat(second.accountsRemoved()).isEmpty();

        GlMappings.Version partial = version(ReferenceGlMapping.without(MONTH, Set.of("FX_POSITION")), 3);
        GlMappings.Diff third = GlMappings.between(partial, Optional.of(reference), chart);
        assertThat(third.rulesRemoved()).extracting(MappingRule::key).containsExactly("FX_POSITION/OPERATIONAL");
        assertThat(third.judgement().unmapped()).containsExactly("FX_POSITION/OPERATIONAL");
    }

    private static GlMappings.Version version(GlMappingVersion content, int number) {
        return new GlMappings.Version(new GlMappingStore.VersionRow(GlMappingVersionId.next(new com.finapp.sharedkernel.id
                .IdGenerator(java.time.Clock.systemUTC(), new java.security.SecureRandom())),
                number, GlMappingVersionStatus.PROPOSED, content.effectiveFrom(), Optional.empty(), "accountant",
                java.time.Instant.EPOCH, Optional.empty(), Optional.empty(), Optional.empty()), content);
    }
}
