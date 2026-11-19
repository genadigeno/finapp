package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingCollections;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.ValuationRateSetId;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.app.api.OperatorCollection;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting lists' request and views, without a database (`P17-TSK-023`): each filter's vocabulary, a period a well-formed
 * identifier, each collection's cursor its own and exact to the microsecond, the FINAL statements' list admitting exactly what the
 * controller's statement door serves, and the proposer marked on each row. The reads, their order, the permissions and the
 * records are {@code AccountingListsApiDatabaseTest}'s.
 */
@DisplayName("the accounting lists' request and views (P17-TSK-023)")
class AccountingCollectionDeskTest {

    /** Accounting's identifiers are UUIDv7 (ADR-0013); its typed ids refuse any other. */
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("each status is its own resource's vocabulary, and a period is an accounting period's identifier")
    void theFiltersAreTheDomainsWords() {
        assertThat(AccountingCollectionDesk.runStatus("FINAL")).isEqualTo(RunStatus.FINAL);
        assertThat(AccountingCollectionDesk.versionStatus("PROPOSED")).isEqualTo(GlMappingVersionStatus.PROPOSED);
        assertThat(AccountingCollectionDesk.setStatus("APPROVED")).isEqualTo(ValuationRateSetStatus.APPROVED);
        UUID period = IDS.next();
        assertThat(AccountingCollectionDesk.period(period.toString())).contains(AccountingPeriodId.of(period));
        assertThat(AccountingCollectionDesk.period(null)).isEmpty();

        assertRefused(() -> AccountingCollectionDesk.runStatus("APPROVED"));
        assertRefused(() -> AccountingCollectionDesk.runStatus("final"));
        assertRefused(() -> AccountingCollectionDesk.versionStatus("APPROVED"));
        assertRefused(() -> AccountingCollectionDesk.setStatus("ACTIVE"));
        assertRefused(() -> AccountingCollectionDesk.period("2026-11"));
        assertRefused(() -> AccountingCollectionDesk.period(UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("each collection's cursor is its own, carries the instant to the microsecond, and is refused anywhere else")
    void eachCursorIsItsOwn() {
        Instant claimed = Instant.parse("2026-11-19T10:15:30.123456Z");
        UUID run = IDS.next();
        String cursor = OperatorCollection.cursor(AccountingCollectionDesk.REPORT_RUNS, AccountingCollectionDesk.micros(claimed),
                run.toString());
        assertThat(AccountingCollectionDesk.runCursor(AccountingCollectionDesk.REPORT_RUNS, cursor))
                .contains(new AccountingCollections.RunCursor(claimed, run));
        assertThat(AccountingCollectionDesk.runCursor(AccountingCollectionDesk.REPORT_RUNS, null)).isEmpty();

        assertRefused(() -> AccountingCollectionDesk.runCursor(AccountingCollectionDesk.FINAL_STATEMENTS, cursor));
        assertRefused(() -> AccountingCollectionDesk.setCursor(AccountingCollectionDesk.RATE_SETS, cursor));
        String ownSets = OperatorCollection.cursor(AccountingCollectionDesk.OWN_RATE_SETS,
                AccountingCollectionDesk.micros(claimed), run.toString());
        assertThat(AccountingCollectionDesk.setCursor(AccountingCollectionDesk.OWN_RATE_SETS, ownSets))
                .contains(new AccountingCollections.SetCursor(claimed, run));
        assertRefused(() -> AccountingCollectionDesk.setCursor(AccountingCollectionDesk.RATE_SETS, ownSets));
        assertRefused(() -> AccountingCollectionDesk.runCursor(AccountingCollectionDesk.REPORT_RUNS,
                OperatorCollection.cursor(AccountingCollectionDesk.REPORT_RUNS, "yesterday", run.toString())));

        assertThat(AccountingCollectionDesk.versionCursor(OperatorCollection.cursor(AccountingCollectionDesk.MAPPING_VERSIONS, "7")))
                .contains(7);
        assertRefused(() -> AccountingCollectionDesk.versionCursor(
                OperatorCollection.cursor(AccountingCollectionDesk.MAPPING_VERSIONS, "0")));
        assertRefused(() -> AccountingCollectionDesk.versionCursor(
                OperatorCollection.cursor(AccountingCollectionDesk.MAPPING_VERSIONS, "seven")));
        assertRefused(() -> AccountingCollectionDesk.versionCursor("not-a-cursor"));
    }

    @Test
    @DisplayName("the FINAL statements' list admits exactly what the controller's statement door serves")
    void theStatementsListIsTheStatementDoors() {
        assertThat(FinalStatementDesk.STATEMENTS)
                .containsExactlyInAnyOrder(BalanceSheetDefinition.CODE, IncomeStatementDefinition.CODE);
        UUID period = IDS.next();
        AccountingCollections.RunFilter statements = AccountingCollectionDesk.statementsFilter(period.toString());
        assertThat(statements.status()).contains(RunStatus.FINAL);
        assertThat(statements.definitions()).isEqualTo(FinalStatementDesk.STATEMENTS);
        assertThat(statements.period()).contains(AccountingPeriodId.of(period));
        assertThat(AccountingCollectionDesk.statementsFilter(null).period()).isEmpty();
        AccountingCollections.RunFilter runs = AccountingCollectionDesk.runFilter(null, null);
        assertThat(runs.definitions()).as("the accountant's list admits every definition").isEmpty();
        assertThat(runs.status()).isEmpty();
        assertThat(AccountingCollectionDesk.runFilter(null, "SUPERSEDED").status()).contains(RunStatus.SUPERSEDED);

        ReportRunStore.RunRow run = run(BalanceSheetDefinition.CODE, RunStatus.FINAL);
        AccountingCollectionDesk.FinalStatementHead head = AccountingCollectionDesk.statementHead(run);
        assertThat(head.runId()).isEqualTo(run.id().value().toString());
        assertThat(head.definition()).isEqualTo("BALANCE_SHEET");
        assertThat(head.period()).isEqualTo("2026-10");
        assertThat(head.periodId()).isEqualTo(run.key().periodId().value().toString());
        assertThat(head.periodRevision()).isEqualTo(2);
        assertThat(head.revision()).isEqualTo(3);
        assertThat(head.completedAt()).isEqualTo("2026-11-02T09:00:00Z");
    }

    @Test
    @DisplayName("a mapping version's row names its proposer, marks the caller's own, and carries no reason")
    void aMappingVersionsRow() {
        GlMappingStore.VersionRow row = new GlMappingStore.VersionRow(GlMappingVersionId.of(IDS.next()), 4,
                GlMappingVersionStatus.PROPOSED, YearMonth.of(2026, 12), Optional.empty(), "accountant-1",
                Instant.parse("2026-11-18T08:00:00Z"), Optional.empty(), Optional.empty(), Optional.empty());

        AccountingCollectionDesk.MappingVersionItem mine = AccountingCollectionDesk.versionItem(row, "accountant-1");
        assertThat(mine.proposedByCaller()).isTrue();
        assertThat(mine.version()).isEqualTo(4);
        assertThat(mine.status()).isEqualTo("PROPOSED");
        assertThat(mine.effectiveFrom()).isEqualTo("2026-12");
        assertThat(mine.effectiveTo()).isNull();
        assertThat(mine.decidedAt()).isNull();
        assertThat(AccountingCollectionDesk.versionItem(row, "controller-1").proposedByCaller()).isFalse();
        assertThat(AccountingCollectionDesk.MappingVersionItem.class.getRecordComponents())
                .extracting(component -> component.getName()).doesNotContain("proposalReason", "decisionReason", "reason");
    }

    @Test
    @DisplayName("a rate set's row counts its rates and reference gaps, marks the caller's capture, and carries no rate or reason")
    void aRateSetsRow() {
        ValuationRateSetStore.SetRow row = new ValuationRateSetStore.SetRow(ValuationRateSetId.of(IDS.next()),
                AccountingPeriodId.of(IDS.next()), YearMonth.of(2026, 10), Instant.parse("2026-10-31T23:59:59Z"), 72, 5,
                ValuationRateSetStatus.APPROVED, "accountant-1", Instant.parse("2026-11-01T07:00:00Z"),
                Optional.of("controller-1"), Optional.of(Instant.parse("2026-11-01T09:30:00Z")), 1);

        AccountingCollectionDesk.RateSetItem item = AccountingCollectionDesk.setItem(row, "controller-1");
        assertThat(item.capturedByCaller()).isFalse();
        assertThat(item.capturedBy()).isEqualTo("accountant-1");
        assertThat(item.period()).isEqualTo("2026-10");
        assertThat(item.rateCount()).isEqualTo(5);
        assertThat(item.referenceGaps()).isEqualTo(1);
        assertThat(item.decidedAt()).isEqualTo("2026-11-01T09:30:00Z");
        assertThat(AccountingCollectionDesk.setItem(row, "accountant-1").capturedByCaller()).isTrue();

        AccountingCollections.SetFilter own = AccountingCollectionDesk.ownSetFilter("accountant-1", "PROPOSED");
        assertThat(own.capturedBy()).as("the proposer's own is the caller's alone").contains("accountant-1");
        assertThat(own.period()).isEmpty();
        assertThat(own.status()).contains(ValuationRateSetStatus.PROPOSED);
        assertThat(AccountingCollectionDesk.setFilter(null, null).capturedBy()).as("the approver's list is everyone's").isEmpty();
        assertThat(AccountingCollectionDesk.RateSetItem.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("rates", "captureReason", "decisionReason", "decidedBy");
    }

    private static ReportRunStore.RunRow run(String definition, RunStatus status) {
        AccountingPeriodId period = AccountingPeriodId.of(IDS.next());
        return new ReportRunStore.RunRow(ReportRunId.of(IDS.next()),
                new ReportRunStore.RunKey(definition, 1, period, "0".repeat(64)), 3, YearMonth.of(2026, 10), 2,
                RunParameters.none(), RunBasis.FINAL, status, Optional.of(Instant.parse("2026-11-01T00:00:00Z")),
                Optional.empty(), Optional.empty(), Instant.parse("2026-11-01T00:00:00Z"), "accountant-1", "EMPLOYEE",
                Instant.parse("2026-11-02T08:59:00Z"), 1, Instant.parse("2026-11-02T09:09:00Z"), Optional.of("a".repeat(64)),
                Optional.of(Instant.parse("2026-11-02T09:00:00Z")), Optional.empty(), Optional.empty(),
                CorrelationId.of("corr-1"));
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }
}
