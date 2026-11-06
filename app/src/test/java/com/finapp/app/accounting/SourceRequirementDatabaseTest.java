package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.JdbcReportReadings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a month requires of a source, through the real statement over real rows (`P14-TSK-025`; the owner's rulings of 2026-11-06 on `P14-TSK-012`'s
 * (c), (e) and (k); settlement {@code V018}, reconciliation's own {@code rule_set}): {@link ReconciliationMonthReads#SOURCES_SQL} and the mapping, then
 * {@link ReconciliationRunCoverage.Coverage#required()}, the one list the check, the re-judge and the gauge read. <strong>Written and owed</strong>
 * (owner decision C5); the rule's half is {@code ReconciliationRunCoverageTest} and {@code OneRuleForTheMonthTest}, the mapping's
 * {@code ReconciliationMonthReadsTest}, on every build.
 *
 * <p><strong>Under any order, and leaving nothing</strong>: each case writes its throwaway sources and rule sets on one migrator connection in one transaction,
 * reads on it, and rolls it back, so no case reads another's and no other suite sees a row. The rule sets are raw rows on their machine's own edges -
 * born {@code PROPOSED}, then {@code ACTIVE} by another person at the instant the case names - which reconciliation's administration stamps with the
 * platform's clock in production; the deferred "a retirement beside its successor" trigger never fires, the transaction never committing.
 *
 * <p><strong>The seeded sources</strong>: the four of reconciliation {@code V002} are required in May 1999 by their seed. The four counterparty sources are
 * not: every suite activates their rule sets at its own {@code Instant.now()}, years after it, or not at all.
 */
@Tag("database")
@DisplayName("what a month requires of a source, through the real statement (P14-TSK-025)")
class SourceRequirementDatabaseTest {

    private static final YearMonth MAY = YearMonth.of(1999, 5);
    private static final Set<String> SEEDED_BY_V002 = Set.of("simulated-psp.settlement", "simulated-scheme.cycle-report", "simulated-payout.settlement",
            "simulated-bank.statement");
    private static final Set<String> COUNTERPARTIES = Set.of("fx-sim-a.trade-report", "corridor-sim-a.settlement", "fx-sim-b.trade-report",
            "corridor-sim-b.settlement");

    @Test
    @DisplayName("admission, retirement and the rule set's first activation, each on both sides of its boundary, through SOURCES_SQL: only a source that served May is required in it")
    void onlyASourceThatServedTheMonthIsRequired() throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                Map<String, Boolean> expected = new TreeMap<>();
                expected.put(source(migrator, "admitted-last-day", suffix, "1999-05-31", null, activated("1999-05-01T00:00:00Z")), true);
                expected.put(source(migrator, "admitted-after", suffix, "1999-06-01", null, activated("1999-05-01T00:00:00Z")), false);
                expected.put(source(migrator, "retired-before", suffix, "1970-01-01", "1999-04-30", activated("1999-01-01T00:00:00Z")), false);
                expected.put(source(migrator, "retired-first-day", suffix, "1970-01-01", "1999-05-01", activated("1999-01-01T00:00:00Z")), true);
                expected.put(source(migrator, "no-rule-set", suffix, "1970-01-01", null, Optional.empty()), false);
                expected.put(source(migrator, "activated-last-instant", suffix, "1970-01-01", null, activated("1999-05-31T23:59:59Z")), true);
                expected.put(source(migrator, "activated-after", suffix, "1970-01-01", null, activated("1999-06-01T00:00:00Z")), false);
                String proposedOnly = source(migrator, "proposed-only", suffix, "1970-01-01", null, Optional.empty());
                ruleSet(migrator, proposedOnly, 1, Optional.empty());
                expected.put(proposedOnly, false);
                String superseded = source(migrator, "superseded", suffix, "1970-01-01", null, activated("1999-05-10T12:00:00Z"));
                supersede(migrator, superseded, Instant.parse("1999-07-01T00:00:00Z"));
                expected.put(superseded, true);

                Set<String> declared = new HashSet<>(AccountingPeriodFixture.DECLARED_SOURCES);
                declared.addAll(expected.keySet());
                ReconciliationRunCoverage.Coverage may = new ReconciliationMonthReads(new JdbcReportReadings(), declared).ofMonth(migrator, MAY);
                Set<String> required = may.required().stream().map(ReconciliationRunCoverage.Source::code).collect(Collectors.toSet());

                expected.forEach((code, requires) -> assertThat(required.contains(code)).as(code).isEqualTo(requires));
                assertThat(required).as("the seeded four, by their seed").containsAll(SEEDED_BY_V002);
                assertThat(required).as("the counterparty sources, activated years after May 1999 if at all").doesNotContainAnyElementsOf(COUNTERPARTIES);
                ReconciliationRunCoverage.Source superseding = may.sources().stream().filter(source -> source.code().equals(superseded)).findFirst().orElseThrow();
                assertThat(superseding.ruleSetActiveOn()).as("the first activation's day, which the retirement kept").contains(LocalDate.of(1999, 5, 10));

                ReconciliationRunCoverage.Coverage april = new ReconciliationMonthReads(new JdbcReportReadings(), declared).ofMonth(migrator, MAY.minusMonths(1));
                assertThat(april.required().stream().map(ReconciliationRunCoverage.Source::code))
                        .as("retired on 30 April, it served April").contains(code("retired-before", suffix))
                        .as("admitted on 31 May, not yet").doesNotContain(code("admitted-last-day", suffix));
            } finally {
                migrator.rollback();
            }
        }
    }

    // ------------------------------------------------------------------

    private static Optional<Instant> activated(String instant) {
        return Optional.of(Instant.parse(instant));
    }

    private static String code(String name, String suffix) {
        return name + "-" + suffix + ".settlement";
    }

    /** A throwaway source, admitted and retired as named, with one rule set version activated at {@code activatedAt} when present. */
    private static String source(Connection migrator, String name, String suffix, String admittedOn, String retiredOn, Optional<Instant> activatedAt)
            throws SQLException {
        String code = code(name, suffix);
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = migrator.prepareStatement("INSERT INTO settlement.source (id, code, kind, status, next_sequence, admitted_on)"
                + " VALUES (?, ?, 'PSP_SETTLEMENT_REPORT', 'ACTIVE', 1, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, code);
            insert.setObject(3, LocalDate.parse(admittedOn));
            insert.executeUpdate();
        }
        if (retiredOn != null) {
            try (PreparedStatement retire = migrator.prepareStatement("UPDATE settlement.source SET status = 'RETIRED', retired_on = ? WHERE id = ?")) {
                retire.setObject(1, LocalDate.parse(retiredOn));
                retire.setObject(2, id);
                retire.executeUpdate();
            }
        }
        if (activatedAt.isPresent()) {
            ruleSet(migrator, code, 1, activatedAt);
        }
        return code;
    }

    /** A version born {@code PROPOSED} by one person and, when {@code activatedAt} is present, made {@code ACTIVE} by another at that instant. */
    private static UUID ruleSet(Connection migrator, String code, int version, Optional<Instant> activatedAt) throws SQLException {
        UUID ruleSet = UUID.randomUUID();
        try (PreparedStatement insert = migrator.prepareStatement("INSERT INTO reconciliation.rule_set (id, source_id, version, status, funding_lag_days,"
                + " gain_min_age_days, effective_from, proposed_by, reason, created_at, correlation_id)"
                + " SELECT ?, s.id, ?, 'PROPOSED', 2, 90, DATE '1999-01-01', 'suite-proposer', 'the suite proposes a version', ?, 'source-requirement-suite'"
                + " FROM settlement.source s WHERE s.code = ?")) {
            insert.setObject(1, ruleSet);
            insert.setInt(2, version);
            insert.setObject(3, OffsetDateTime.ofInstant(activatedAt.orElse(Instant.parse("1999-01-01T00:00:00Z")), ZoneOffset.UTC));
            insert.setString(4, code);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        if (activatedAt.isPresent()) {
            try (PreparedStatement activate = migrator.prepareStatement("UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'suite-approver',"
                    + " decided_at = ? WHERE id = ?")) {
                activate.setObject(1, OffsetDateTime.ofInstant(activatedAt.get(), ZoneOffset.UTC));
                activate.setObject(2, ruleSet);
                activate.executeUpdate();
            }
        }
        return ruleSet;
    }

    /** A second version activated at {@code at}, its predecessor retired beside it on the machine's edge, the first decision kept. */
    private static void supersede(Connection migrator, String code, Instant at) throws SQLException {
        UUID second = ruleSet(migrator, code, 2, Optional.empty());
        try (Statement retire = migrator.createStatement()) {
            retire.execute("UPDATE reconciliation.rule_set r SET status = 'RETIRED' FROM settlement.source s WHERE s.code = '" + code
                    + "' AND r.source_id = s.id AND r.status = 'ACTIVE'");
        }
        try (PreparedStatement activate = migrator.prepareStatement("UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'suite-approver',"
                + " decided_at = ? WHERE id = ?")) {
            activate.setObject(1, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            activate.setObject(2, second);
            activate.executeUpdate();
        }
    }
}
