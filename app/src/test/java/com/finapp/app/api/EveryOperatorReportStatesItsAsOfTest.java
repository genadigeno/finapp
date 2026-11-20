package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every operator report states how fresh it is (`P17-TSK-039`; finapp-admin-panel ADR-C133 decision 5, gap G-04): its answer is a
 * record with an {@code asOf} - the database's instant of what it read, the console's freshness for every tile - and the reports
 * are exactly {@link #REGISTER}'s, so a new one reaches a reviewer named here, its {@code asOf} with it.
 *
 * <p>A report is a handler under {@code /v1/operator/reports/}, the dead-letter queues and the accounting operational reports. The
 * instant itself is each desk's: {@code DatabaseTime.now} as the report transaction's first statement - in a snapshot, the
 * snapshot's instant - and the risk and accounting reports' own database reads; never an instance's clock (ADR-0063). The
 * database tests of each report hold the value; this guard holds that none is born without one.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("every operator report states its as-of instant (P17-TSK-039)")
class EveryOperatorReportStatesItsAsOfTest {

    /** {@code Controller#method} of every operator report. */
    static final Set<String> REGISTER = Set.of(
            // The overview: PLATFORM_OVERVIEW_READ (P17-TSK-032's payments report, P17-TSK-039's customers and transfers).
            "PaymentReadController#readPaymentsReport",
            "OverviewReportController#readCustomersReport",
            "OverviewReportController#readTransfersReport",
            "OperatorReportController#readChargebackRatioReport",
            "ReconciliationReportController#readReconciliationPositionsReport",
            "ReconciliationReportController#readReconciliationSuspenseReport",
            "ReconciliationReportController#readReconciliationUnmatchedReport",
            "ReconciliationReportController#readReconciliationDailySummaryReport",
            "ReconciliationReportController#readReconciliationProviderCostsReport",
            "Phase9ReportController#readFxPositionReport",
            "Phase9ReportController#readFxRevenueReport",
            "Phase9ReportController#readCorridorReport",
            "Phase10ReportController#readCreditOutcomesReport",
            "Phase10ReportController#readCreditReasonsReport",
            "Phase10ReportController#readCreditSourcesReport",
            "LendingReportController#readLendingPortfolioReport",
            "LendingReportController#readLendingDelinquencyReport",
            "LendingReportController#readLendingAccrualReport",
            "LendingReportController#readLendingCapitalReport",
            "BnplReportController#readBnplPortfolioReport",
            "BnplReportController#readBnplMerchantFinancingReport",
            "BnplReportController#readBnplRefundReport",
            "BnplReportController#readBnplDelinquencyReport",
            "RiskOperationsReportController#readRiskDecisionsReport",
            "RiskOperationsReportController#readRiskAlertsReport",
            "RiskOperationsReportController#readRiskCasesReport",
            "RiskOperationsReportController#readRiskVelocityReport",
            "DeadLetterController#readDeadLetters",
            "OperationalReportController#readOperationalReport");

    /** The MVC mapping, by name: actuator registers a second one ({@code EveryEndpointDeclaresARuleTest}'s note). */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("the operator reports are exactly the register's, and each answers a record with an asOf instant")
    void everyReportStatesItsAsOf() {
        Map<String, Class<?>> reports = new TreeMap<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getName().startsWith("com.finapp.")
                    || !info.getMethodsCondition().getMethods().contains(org.springframework.web.bind.annotation.RequestMethod.GET)) {
                return;
            }
            boolean report = info.getPatternValues().stream().anyMatch(pattern -> pattern.contains("/operator/reports/")
                    || pattern.endsWith("/operator/events/dead-letters")
                    || pattern.contains("/operational-reports/"));
            if (report) {
                reports.put(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName(),
                        method.getReturnType().getParameterType());
            }
        });
        assertThat(reports.keySet()).as("every operator report is named in the register").isEqualTo(new TreeSet<>(REGISTER));
        reports.forEach((handler, answer) -> {
            assertThat(answer.isRecord()).as("%s answers a record", handler).isTrue();
            RecordComponent asOf = Arrays.stream(answer.getRecordComponents())
                    .filter(component -> component.getName().equals("asOf"))
                    .findFirst()
                    .orElse(null);
            assertThat(asOf).as("%s states its asOf", handler).isNotNull();
            assertThat(asOf.getType()).as("%s's asOf is an ISO instant string", handler).isEqualTo(String.class);
        });
    }
}
