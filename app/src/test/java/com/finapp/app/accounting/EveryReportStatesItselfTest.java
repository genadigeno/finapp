package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.ReportKind;
import com.finapp.accounting.reporting.OperationalReportDefinition;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The acceptance criterion against <strong>the wired registry</strong> (`P14-TSK-017`: "every report's source, rule, date, currency
 * semantics and freshness stated in its definition"): exactly the definitions this phase has built are the beans, each code a {@link
 * ReportKind}'s name, each stating all five facts in full ({@code ReportDefinition.statement()} is abstract, {@code ReportStatement}
 * refuses an empty one - a definition omitting one fails to compile or to start, and this one to pass). Which two never stand as a run, and
 * which may be translated, is pinned here too: a later change to either is a decision, not a drift.
 */
@Tag("slice")
// Pointed at a database that is not there (PlannedMetersExistTest's idiom): the registry is the beans', from startup.
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every report states its source, rule, date, currency semantics and freshness (P14-TSK-017)")
class EveryReportStatesItselfTest {

    private static final List<String> BUILT = List.of("BNPL_EXPOSURE", "CLOSE_STATUS", "FX_RESULTS", "GL_ACTIVITY", "LOAN_PORTFOLIO",
            "RECEIVABLES_PAYABLES", "RECONCILIATION_STATUS", "REVENUE", "SETTLEMENT_POSITIONS", "SUSPENSE", "TRIAL_BALANCE");

    @Autowired private ReportDefinitions definitions;

    @Test
    @DisplayName("the registry holds every report built - the ten and the trial balance - each a ReportKind's name, and each states all five facts")
    void everyDefinitionStatesItself() {
        assertThat(definitions.codes()).containsAll(BUILT);
        for (String code : definitions.codes()) {
            ReportDefinition definition = definitions.current(code).orElseThrow();
            assertThat(ReportKind.valueOf(code).name()).isEqualTo(definition.code());
            assertThat(definition.statement().facts()).as(code).hasSize(5)
                    .allSatisfy(fact -> assertThat(fact).as(code).hasSizeGreaterThan(40));
        }
    }

    @Test
    @DisplayName("the labels are a decision: exactly two are operational, and each of those is an OperationalReportDefinition")
    void theLabelsAreADecision() {
        assertThat(definitions.codes().stream().filter(code -> definitions.current(code).orElseThrow().operational()).toList())
                .containsExactly("CLOSE_STATUS", "RECONCILIATION_STATUS");
        for (String code : definitions.codes()) {
            ReportDefinition definition = definitions.current(code).orElseThrow();
            assertThat(definition instanceof OperationalReportDefinition).as(code).isEqualTo(definition.operational());
        }
    }

    @Test
    @DisplayName("translation is a decision too: the owner has not settled the translated layout, so none of the ten takes a rate set")
    void noneOfTheTenTranslates() {
        assertThat(definitions.codes().stream().filter(code -> definitions.current(code).orElseThrow().takesValuationRateSet()).toList())
                .containsExactly("TRIAL_BALANCE");
    }
}
