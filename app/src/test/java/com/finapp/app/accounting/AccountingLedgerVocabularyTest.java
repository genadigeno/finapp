package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.EntryType;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.StatementSection;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.PurposeReportDefinition;
import com.finapp.accounting.reporting.SuspenseDefinition;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.NormalBalance;
import com.finapp.ledger.OwnerKind;
import com.finapp.platform.telemetry.Spans;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Accounting's vocabularies held to the ledger's, where they must agree (`P14-TSK-001`; ADR-0113 decision 3;
 * {@code INV-ACC-06}) - in {@code app}, the one module that sees both, because {@code accounting} has no ledger edge and
 * compares by name. A GL account is classification-compatible with a purpose when the GL classification and the
 * purpose's ledger account type carry the same name; that comparison is only sound while the two vocabularies are the
 * same five names with the same normal side, which is what this holds. Beside it, {@link AccountingFlowScope}'s door leg.
 */
@DisplayName("accounting's vocabularies agree with the ledger's (P14-TSK-001)")
class AccountingLedgerVocabularyTest {

    @Test
    @DisplayName("GlClassification is exactly the ledger's AccountType names, in order")
    void theClassificationsAreTheLedgersAccountTypes() {
        assertThat(Arrays.stream(GlClassification.values()).map(Enum::name))
                .as("a type added to the ledger without a GL classification makes a purpose unmappable")
                .containsExactlyElementsOf(Arrays.stream(AccountType.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("each classification's normal side is the ledger type's normal balance")
    void theNormalSidesAgree() {
        for (AccountType type : AccountType.values()) {
            assertThat(GlClassification.valueOf(type.name()).debitNormal())
                    .as("%s is %s-normal in the ledger", type, type.normalBalance())
                    .isEqualTo(type.normalBalance() == NormalBalance.DEBIT);
        }
    }

    @Test
    @DisplayName("a posting rule's EntryType is exactly the ledger's JournalEntryType names, in order (P14-TSK-002)")
    void theRuleEntryTypesAreTheLedgersEntryTypes() {
        assertThat(Arrays.stream(EntryType.values()).map(Enum::name))
                .as("a rule is keyed by the door that wrote the entry; a type the ledger adds is a door no rule names")
                .containsExactlyElementsOf(Arrays.stream(JournalEntryType.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("every purpose a report names is the ledger's, with its own owner kind - a renamed purpose would read an empty row forever"
            + " (P14-TSK-017)")
    void theReportsPurposesAreTheLedgers() {
        for (PurposeReportDefinition definition : reports()) {
            for (PurposeReportDefinition.Section section : definition.sections()) {
                assertThat(section.keys()).as("%s %s", definition.code(), section.name()).allSatisfy(key -> {
                    String[] parts = key.split("/", 2);
                    assertThat(AccountPurpose.valueOf(parts[0]).ownerKind().name()).as(key).isEqualTo(parts[1]);
                });
            }
        }
        String[] suspense = SuspenseDefinition.LEDGER_KEY.split("/", 2);
        assertThat(AccountPurpose.valueOf(suspense[0]).ownerKind().name()).isEqualTo(suspense[1]);
    }

    @Test
    @DisplayName("every ledger purpose reaches a statement section under the reference chart, on the statement its ledger type belongs to: write-offs in CREDIT_LOSSES, suspense in SUSPENSE - a purpose the ledger adds lands here or the statements refuse it (P14-TSK-016)")
    void everyPurposeReachesAStatementSection() {
        GlMappingVersion chart = ReferenceGlMapping.version(ReferenceGlMapping.EFFECTIVE_FROM);
        Map<String, MappingRule> rules = chart.rulesByKey();
        for (AccountPurpose purpose : AccountPurpose.values()) {
            MappingRule rule = rules.get(purpose.name() + "/" + purpose.ownerKind().name());
            assertThat(rule).as("%s has a rule", purpose).isNotNull();
            GlAccount account = chart.account(rule.glAccountCode()).orElseThrow();
            assertThat(account.section().admits(account.classification())).as("%s's section admits its account", purpose).isTrue();
            assertThat(account.section().statement() == StatementSection.Statement.BALANCE_SHEET).as("%s is on one statement, by its classification", purpose).isEqualTo(account.classification().balanceSheet());
        }
        assertThat(sectionOf(chart, AccountPurpose.LOAN_WRITE_OFF_EXPENSE)).isEqualTo(StatementSection.CREDIT_LOSSES);
        assertThat(sectionOf(chart, AccountPurpose.BNPL_WRITE_OFF_EXPENSE)).isEqualTo(StatementSection.CREDIT_LOSSES);
        assertThat(sectionOf(chart, AccountPurpose.SUSPENSE_UNMATCHED)).isEqualTo(StatementSection.SUSPENSE);
    }

    private static StatementSection sectionOf(GlMappingVersion chart, AccountPurpose purpose) {
        return chart.account(chart.rulesByKey().get(purpose.name() + "/" + purpose.ownerKind().name()).glAccountCode()).orElseThrow().section();
    }

    @Test
    @DisplayName("the income statement's net realised FX names the ledger's two purposes, each with its own owner kind: renamed, it would read nothing forever (P14-TSK-016)")
    void netRealisedFxNamesTheLedgersPurposes() {
        for (String key : List.of(IncomeStatementDefinition.FX_GAINS, IncomeStatementDefinition.FX_LOSSES)) {
            String[] parts = key.split("/", 2);
            assertThat(AccountPurpose.valueOf(parts[0]).ownerKind().name()).as(key).isEqualTo(parts[1]);
        }
    }

    /**
     * The purposes no report's section names, each with why. {@code GL_ACTIVITY} reads every line, these among them, and {@code
     * P14-TSK-016}'s statements carry them; none is silently absent. A purpose the ledger adds lands in a section or here, by a decision.
     */
    private static final Map<String, String> NOT_IN_A_SECTION = Map.of(
            "DISPUTE_COSTS", "a cost: REVENUE reports income, and the statements carry the expense",
            "PROCESSING_COSTS", "a cost: REVENUE reports income, and the statements carry the expense",
            "RECONCILIATION_LOSSES", "a reconciliation's own result: RECONCILIATION_STATUS reports the breaks, and the statements the expense",
            "RECONCILIATION_GAINS", "a reconciliation's own result, no fee, interest or spread: the statements carry it as other income",
            "LOAN_WRITE_OFF_EXPENSE", "a credit loss: LOAN_PORTFOLIO is the book's balances, and the statements carry the expense",
            "BNPL_WRITE_OFF_EXPENSE", "a credit loss: BNPL_EXPOSURE is the book's balances, and the statements carry the expense",
            "FX_POSITION", "the platform's own currency inventory: a balance, so no FX result, and neither clearing nor cash",
            "LENDING_CAPITAL", "equity: no report here covers it, and the balance sheet does");

    @Test
    @DisplayName("M-4, the census: every ledger purpose is in a section of some report - SUSPENSE's among them - or on a named list with its reason,"
            + " never both, and the list names only what the ledger holds (P14-TSK-017)")
    void everyPurposeIsReportedOrSaidNotToBe() {
        Set<String> inASection = new TreeSet<>(Set.of(SuspenseDefinition.LEDGER_KEY));
        reports().forEach(definition -> inASection.addAll(keys(definition)));
        for (AccountPurpose purpose : AccountPurpose.values()) {
            assertThat(inASection.contains(purpose.name() + "/" + purpose.ownerKind().name())
                    ^ NOT_IN_A_SECTION.containsKey(purpose.name()))
                    .as("%s is in a section of a report or named as not, and never both", purpose)
                    .isTrue();
        }
        assertThat(NOT_IN_A_SECTION.keySet()).allSatisfy(name -> assertThat(AccountPurpose.valueOf(name)).isNotNull());
        assertThat(NOT_IN_A_SECTION.values()).allSatisfy(reason -> assertThat(reason).hasSizeGreaterThan(20));
    }

    @Test
    @DisplayName("LOAN_PORTFOLIO is every purpose of owner kind LOAN and BNPL_EXPOSURE every one of BNPL_OBLIGATION: a purpose the ledger adds to"
            + " either book cannot be left out of its restatement (P14-TSK-017)")
    void theBooksAreWhole() {
        assertThat(keys(PurposeReportDefinition.loanPortfolio(NO_READS)))
                .containsExactlyInAnyOrderElementsOf(keysOf(OwnerKind.LOAN));
        assertThat(keys(PurposeReportDefinition.bnplExposure(NO_READS)))
                .containsExactlyInAnyOrderElementsOf(keysOf(OwnerKind.BNPL_OBLIGATION));
    }

    private static final TrialBalanceReads NO_READS = (TrialBalanceReads) java.lang.reflect.Proxy.newProxyInstance(
            TrialBalanceReads.class.getClassLoader(), new Class<?>[] {TrialBalanceReads.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the sections are read, not the journal");
            });

    private static final CounterpartyPositionReads NO_POSITIONS = (CounterpartyPositionReads) java.lang.reflect.Proxy.newProxyInstance(
            CounterpartyPositionReads.class.getClassLoader(), new Class<?>[] {CounterpartyPositionReads.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the sections are read, not the journal");
            });

    private static List<PurposeReportDefinition> reports() {
        return List.of(PurposeReportDefinition.revenue(NO_READS), PurposeReportDefinition.receivablesPayables(NO_READS),
                PurposeReportDefinition.settlementPositions(NO_READS, NO_POSITIONS), PurposeReportDefinition.fxResults(NO_READS),
                PurposeReportDefinition.loanPortfolio(NO_READS), PurposeReportDefinition.bnplExposure(NO_READS));
    }

    private static List<String> keys(PurposeReportDefinition definition) {
        return definition.sections().stream().flatMap(section -> section.keys().stream()).toList();
    }

    private static List<String> keysOf(OwnerKind kind) {
        return Arrays.stream(AccountPurpose.values()).filter(purpose -> purpose.ownerKind() == kind)
                .map(purpose -> purpose.name() + "/" + kind.name()).toList();
    }

    @Test
    @DisplayName("AccountingFlowScope's door runs the work in a span with no attribute at all")
    void theDoorRecordsASpanWithoutAttributes() {
        List<String> names = new ArrayList<>();
        List<Map<String, String>> attributes = new ArrayList<>();
        Spans recording = new Spans() {
            @Override
            public <T> T within(String name, Map<String, String> identifiers, java.util.function.Supplier<T> work) {
                names.add(name);
                attributes.add(identifiers);
                return work.get();
            }
        };
        assertThat(new AccountingFlowScope(recording).door("accounting.door", () -> 42)).isEqualTo(42);
        assertThat(names).containsExactly("accounting.door");
        assertThat(attributes).singleElement().satisfies(map -> assertThat(map).isEmpty());
        assertThat(AccountingFlowScope.NONE.door("accounting.door", () -> "ran")).isEqualTo("ran");
    }
}
