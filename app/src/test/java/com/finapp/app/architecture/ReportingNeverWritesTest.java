package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <strong>Reporting never writes</strong> - the static half of {@code INV-ACC-14} (`P14-TSK-001`; ADR-0113 decision 5;
 * owner decision C10). The database half is {@code -008}'s: every report and drill-down read runs in a
 * {@code REPEATABLE READ READ ONLY} transaction, which refuses a write at the database; this guard refuses one in the
 * code, before it can be attempted.
 *
 * <p><strong>Report code</strong> is every class of {@code com.finapp.accounting.reporting} and its sub-packages - the
 * report definitions and the reads that compute a run's content. Persisting a run, recording the audited read and the
 * door itself live outside that package (`P14-TSK-008` builds them there), which is what lets the rule be absolute.
 *
 * <p><strong>The reach.</strong> From every report class, every {@code accounting} class it depends on and on from
 * those - and, for an accounting interface or abstract class reached, every accounting implementation of it. The reach
 * stays inside {@code accounting}: a port's {@code app} adapter is held by the read-only transaction, not by this rule,
 * and the read ports ({@link JournalReads} and its siblings) are the only way out. No class on the reach may
 * <ul>
 *   <li>depend on a {@code com.finapp.ledger} type - the ledger's writers above all ({@code PostingService},
 *       {@code ReversalService}, {@code AdjustmentService}); {@code accounting} has no ledger edge, so this is the
 *       belt to {@code AccountingModuleIsolationTest}'s braces;</li>
 *   <li>depend on accounting's own <strong>writer ports</strong> - {@link PostingCutoff}, {@link PriorPeriodPostings},
 *       {@link RegulatoryReportingProvider};</li>
 *   <li>depend on the platform's writers - {@link AuditWriter}, {@link OutboxWriter}, their JDBC implementations and
 *       {@link IdempotencyRecordStore};</li>
 *   <li>call a JDBC write - {@code Statement} / {@code PreparedStatement} {@code executeUpdate},
 *       {@code executeLargeUpdate}, {@code executeBatch}, {@code addBatch} or {@code execute} (a read is
 *       {@code executeQuery}).</li>
 * </ul>
 *
 * <p><strong>Armed over an empty package.</strong> {@code com.finapp.accounting.reporting} holds no class today, so the
 * rule passes vacuously on production code - and is phrased over every imported class rather than a {@code that()}
 * clause that would select nothing (the {@code ModuleBoundaryRulesTest} convention: no {@code allowEmptyShould}), so it
 * gains reach the moment `P14-TSK-008` adds a class, with no edit here. The census proves the guard sees the module and
 * every writer it names; the planted probes in this source prove each refusal able to fail, one hop and two, and a
 * reading report class not flagged.
 */
@Tag("architecture")
// ArchUnit's own engine reads ArchTag, not JUnit's Tag (P1-TSK-025; TestTaxonomyTest requires the pair).
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class ReportingNeverWritesTest {

    private static final String ACCOUNTING = "com.finapp.accounting";
    private static final String REPORTING = ACCOUNTING + ".reporting";
    private static final String LEDGER = "com.finapp.ledger";

    /** The writer types a report class may never reach, besides the ledger's package. */
    static final Set<String> WRITERS = Set.of(
            PostingCutoff.class.getName(),
            PriorPeriodPostings.class.getName(),
            RegulatoryReportingProvider.class.getName(),
            AuditWriter.class.getName(),
            JdbcAuditWriter.class.getName(),
            OutboxWriter.class.getName(),
            JdbcOutboxWriter.class.getName(),
            IdempotencyRecordStore.class.getName());

    /** The JDBC calls that write; a report reads with {@code executeQuery}. */
    static final Set<String> JDBC_WRITES = Set.of("executeUpdate", "executeLargeUpdate", "executeBatch", "addBatch",
            "execute");

    @ArchTest
    static void noReportingClassReachesAWriter(JavaClasses imported) {
        assertThat(violations(imported, ReportingNeverWritesTest::isReportingClass,
                        candidate -> candidate.getPackageName().startsWith(ACCOUNTING)))
                .as("a report class, or an accounting class it reaches, that can write (INV-ACC-14)")
                .isEmpty();
    }

    /** The census: the guard sees accounting, every writer it names, and the ledger's writers it refuses by package. */
    @ArchTest
    static void theReportingGuardSeesTheAccountingModule(JavaClasses imported) {
        assertThat(imported.containPackage(ACCOUNTING))
                .as("the accounting module's classes are imported - a guard over a module it cannot see is vacuous")
                .isTrue();
        for (String writer : WRITERS) {
            assertThat(imported.contain(writer)).as("the named writer %s exists in the import", writer).isTrue();
        }
        assertThat(imported.contain(PostingService.class.getName()))
                .as("the ledger's writers the package refusal stands for are imported")
                .isTrue();
        // How much report code is guarded is deliberately NOT asserted: none until P14-TSK-008, and the guard needs no
        // edit when the first report class lands.
    }

    @Test
    @DisplayName("the report package matcher takes the package and its sub-packages, and no lookalike")
    void thePackageMatcherIsExact() {
        assertThat(isReportingPackage(REPORTING)).isTrue();
        assertThat(isReportingPackage(REPORTING + ".trialbalance")).isTrue();
        assertThat(isReportingPackage(ACCOUNTING)).as("the module root is not report code").isFalse();
        assertThat(isReportingPackage(ACCOUNTING + ".reportingx")).as("a lookalike prefix").isFalse();
        assertThat(isReportingPackage("com.finapp.app.accounting.reporting")).as("app's desks are not").isFalse();
    }

    @Test
    @DisplayName("a report class holding a ledger writer is refused")
    void aLedgerWriterIsRefused() {
        assertThat(plantedViolations(PlantedReportPosting.class, PostingService.class))
                .singleElement().asString().contains("PlantedReportPosting").contains("PostingService");
    }

    @Test
    @DisplayName("a report class calling a JDBC update is refused")
    void aJdbcWriteIsRefused() {
        assertThat(plantedViolations(PlantedReportUpdating.class, PreparedStatement.class))
                .singleElement().asString().contains("PlantedReportUpdating").contains("executeUpdate");
    }

    @Test
    @DisplayName("a report class holding the audit writer or accounting's prior-period door is refused")
    void aPlatformWriterOrAWriterPortIsRefused() {
        assertThat(plantedViolations(PlantedReportAuditing.class, AuditWriter.class))
                .singleElement().asString().contains("AuditWriter");
        assertThat(plantedViolations(PlantedReportAdjusting.class, PriorPeriodPostings.class))
                .singleElement().asString().contains("PriorPeriodPostings");
    }

    @Test
    @DisplayName("a writer two hops from a report class - through a helper it calls - is refused at the helper")
    void aWriterTwoHopsAwayIsRefused() {
        assertThat(plantedViolations(PlantedReportDefinition.class, PlantedHelper.class, OutboxWriter.class))
                .singleElement().asString().contains("PlantedHelper").contains("OutboxWriter");
    }

    @Test
    @DisplayName("a report class that only reads - executeQuery, the journal's read port - is not flagged")
    void aReadingReportClassPasses() {
        assertThat(plantedViolations(PlantedReportReading.class, PreparedStatement.class, JournalReads.class))
                .isEmpty();
    }

    // ------------------------------------------------------------------ the rule

    static boolean isReportingClass(JavaClass candidate) {
        return isReportingPackage(candidate.getPackageName());
    }

    static boolean isReportingPackage(String pkg) {
        return pkg.equals(REPORTING) || pkg.startsWith(REPORTING + ".");
    }

    /**
     * Every class on a report class's reach that can write, with what it reaches. {@code reporting} selects the report
     * classes; {@code followed} is the reach's boundary (accounting's classes, in production).
     */
    static Set<String> violations(JavaClasses classes, Predicate<JavaClass> reporting, Predicate<JavaClass> followed) {
        Set<String> reached = new TreeSet<>();
        Set<String> violations = new TreeSet<>();
        Deque<JavaClass> pending = new ArrayDeque<>();
        classes.stream().filter(reporting).forEach(pending::add);
        while (!pending.isEmpty()) {
            JavaClass current = pending.pop();
            if (!reached.add(current.getName())) {
                continue;
            }
            List<String> writers = current.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .filter(target -> target.getPackageName().startsWith(LEDGER) || WRITERS.contains(target.getName()))
                    .map(JavaClass::getName).distinct().sorted().toList();
            List<String> writes = current.getMethodCallsFromSelf().stream()
                    .filter(ReportingNeverWritesTest::isJdbcWrite)
                    .map(call -> call.getTarget().getOwner().getSimpleName() + "." + call.getName())
                    .distinct().sorted().toList();
            if (!writers.isEmpty() || !writes.isEmpty()) {
                violations.add(current.getName() + " -> " + writers + " " + writes);
            }
            current.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .filter(followed)
                    .forEach(pending::add);
            if (current.isInterface() || current.getModifiers().contains(JavaModifier.ABSTRACT)) {
                current.getAllSubclasses().stream().filter(followed).forEach(pending::add);
            }
        }
        return violations;
    }

    private static boolean isJdbcWrite(JavaMethodCall call) {
        JavaClass owner = call.getTarget().getOwner();
        boolean statement = owner.isAssignableTo(java.sql.Statement.class);
        return statement && JDBC_WRITES.contains(call.getName());
    }

    private static Set<String> plantedViolations(Class<?>... classes) {
        JavaClasses planted = new ClassFileImporter().importClasses(classes);
        return violations(planted, candidate -> candidate.getSimpleName().startsWith("PlantedReport"),
                candidate -> candidate.getSimpleName().startsWith("Planted"));
    }

    // ------------------------------------------------------------------ the planted probes

    /** A report class holding the ledger's posting service. */
    static class PlantedReportPosting {
        PostingService posting;
    }

    /** A report class running an UPDATE. */
    static class PlantedReportUpdating {
        int touch(PreparedStatement statement) throws SQLException {
            return statement.executeUpdate();
        }
    }

    /** A report class recording an audit row itself. */
    static class PlantedReportAuditing {
        AuditWriter<?> audit;
    }

    /** A report class holding accounting's one journal-writing door. */
    static class PlantedReportAdjusting {
        PriorPeriodPostings postings;
    }

    /** A report class whose helper reaches the outbox - two hops. */
    static class PlantedReportDefinition {
        PlantedHelper helper;
    }

    static class PlantedHelper {
        OutboxWriter<?> outbox;
    }

    /** A report class that only reads: a query and the journal's read port. */
    static class PlantedReportReading {
        JournalReads journal;

        boolean any(PreparedStatement statement) throws SQLException {
            return statement.executeQuery().next();
        }
    }
}
