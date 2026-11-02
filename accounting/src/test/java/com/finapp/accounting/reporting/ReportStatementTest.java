package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The acceptance criterion made mechanical (`P14-TSK-017`): a report's source, rule, date, currency semantics and freshness can
 * neither be omitted (the method is abstract, so a definition without it does not compile) nor left empty.
 */
@DisplayName("a report states its source, rule, date, currency semantics and freshness (P14-TSK-017)")
class ReportStatementTest {

    private static final String FACT = "a fact stated at some length";

    @Test
    @DisplayName("every fact is refused empty, blank, too short or a placeholder - each of the five")
    void noFactMayBeLeftOut() {
        for (String bad : List.of("", "   ", "short", "n/a", "N/A", "TODO", "none", "unknown", "see above  ")) {
            for (int fact = 0; fact < 5; fact++) {
                List<String> facts = new ArrayList<>(List.of(FACT, FACT, FACT, FACT, FACT));
                facts.set(fact, bad);
                assertThatThrownBy(() -> new ReportStatement(facts.get(0), facts.get(1), facts.get(2), facts.get(3), facts.get(4)))
                        .as("%s as fact %s", bad, fact)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> new ReportStatement(null, FACT, FACT, FACT, FACT)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the five are written under their names, and only them")
    void theFiveAreWritten() {
        ReportStatement statement = new ReportStatement("the source named", "the rule named", "the date named",
                "the currency named", "the freshness named");
        assertThat(CanonicalJson.text(statement.asValue())).isEqualTo("{\"currencySemantics\":\"the currency named\","
                + "\"date\":\"the date named\",\"freshness\":\"the freshness named\",\"rule\":\"the rule named\","
                + "\"source\":\"the source named\"}");
        assertThat(statement.facts()).hasSize(5);
    }
}
