package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import java.sql.Connection;
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
 *       {@code executeQuery});</li>
 *   <li><em>(`P14-TSK-008`'s review, I-2)</em> reach a <strong>writable path</strong> - depend on
 *       {@code javax.sql.DataSource} or {@code java.sql.DriverManager} (a connection of its own, outside the read-only
 *       snapshot), or call a {@code Connection} mutator: {@code setAutoCommit}, {@code setReadOnly},
 *       {@code setTransactionIsolation}, {@code commit}, {@code rollback}, {@code setSavepoint},
 *       {@code releaseSavepoint}, {@code abort}, {@code setSchema}, {@code setCatalog}, or open a plain or callable
 *       statement ({@code createStatement}, {@code prepareCall} - a plain statement runs any string, a second statement
 *       that ends the transaction included). At run time the definitions are handed {@code ReadOnlySnapshot}'s view,
 *       which refuses the same set and screens every prepared string; this rule holds the report code that builds
 *       it.</li>
 * </ul>
 *
 * <p><strong>Armed over an empty package.</strong> {@code com.finapp.accounting.reporting} held no class when armed;
 * its first report code is the GL fold (`P14-TSK-007`: {@code GeneralLedger} and its values), then the report-run code
 * (`P14-TSK-008`: the canonical writer, the definitions, the generation) - and the rule is phrased over every imported
 * class rather than a {@code that()} clause that would select nothing (the {@code ModuleBoundaryRulesTest} convention:
 * no {@code allowEmptyShould}), so it gained reach the moment the first class landed, with no edit here. The census
 * proves the guard sees the module, every writer it names and the report code it is armed over (the GL fold); the
 * planted probes in this source prove each refusal able to fail, one hop and two, and a reading report class not
 * flagged.
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

    /**
     * The JDK's ways to a connection of one's own - outside the read-only snapshot (`P14-TSK-008`'s review, I-2). Held
     * apart from {@link #WRITERS} because the census imports {@code com.finapp} only.
     */
    static final Set<String> WRITABLE_PATHS = Set.of("javax.sql.DataSource", "java.sql.DriverManager");

    /**
     * The {@code Connection} calls that would end, reopen or widen the read-only transaction, or run an unscreened string
     * (`P14-TSK-008`'s review, I-2) - a report prepares its reads and nothing else.
     */
    static final Set<String> CONNECTION_MUTATORS = Set.of("setAutoCommit", "setReadOnly", "setTransactionIsolation",
            "commit", "rollback", "setSavepoint", "releaseSavepoint", "abort", "setSchema", "setCatalog",
            "createStatement", "prepareCall");

    @ArchTest
    static void noReportingClassReachesAWriter(JavaClasses imported) {
        assertThat(violations(imported, ReportingNeverWritesTest::isReportingClass,
                        candidate -> candidate.getPackageName().startsWith(ACCOUNTING)))
                .as("a report class, or an accounting class it reaches, that can write (INV-ACC-14)")
                .isEmpty();
    }

    /**
     * <strong>A run's content never depends on a JDK's currency table</strong> (`P14-TSK-015`; `P14-TSK-009`'s review, M-3;
     * ADR-0003; {@code INV-ACC-04}): a JDK's data changing would otherwise change a FINAL run's regeneration. No report class
     * depends on {@code java.util.Currency} or reaches {@link CurrencyCode#minorUnits()}, by a call or a method reference
     * (the review's M-6); scales are the data's own, and the
     * reporting minor unit is {@code Translation.REPORTING_SCALE}, a literal. {@link GeneralLedger} is the one exception, and
     * it is not a run's content: the live GL doors' zero at a currency's minor unit.
     */
    @ArchTest
    static void noReportContentReadsTheJdksCurrencyTable(JavaClasses imported) {
        assertThat(currencyTableReaders(imported, candidate -> isReportingClass(candidate)
                && !candidate.getName().equals(GeneralLedger.class.getName())))
                .as("a report class reading a JDK's currency table (INV-ACC-04)")
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
        // The report code the guard is armed over (P14-TSK-007's review, M-1): the GL fold is report code, and a move
        // out of the package - or a package renamed under the rule - would leave the reach rule passing over nothing it
        // was written for. How much MORE report code exists is deliberately not asserted: the next class lands with no
        // edit here.
        assertThat(imported.stream().filter(ReportingNeverWritesTest::isReportingClass).map(JavaClass::getName))
                .as("the GL fold is report code - the reach rule is armed over it (INV-ACC-14)")
                .contains(GeneralLedger.class.getName());
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
    @DisplayName("a report class writing by a method reference, not a call, is refused (P14-TSK-015's review, M-6)")
    void aJdbcWriteByReferenceIsRefused() {
        assertThat(plantedViolations(PlantedReportUpdatingByReference.class, PreparedStatement.class))
                .singleElement().asString().contains("PlantedReportUpdatingByReference").contains("executeUpdate");
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
    @DisplayName("a report class ending or reopening its transaction - commit, rollback, setAutoCommit, setReadOnly - is"
            + " refused (P14-TSK-008's review, I-2)")
    void aConnectionMutatorIsRefused() {
        assertThat(plantedViolations(PlantedReportCommitting.class, Connection.class))
                .singleElement().asString().contains("PlantedReportCommitting").contains("Connection.commit");
        assertThat(plantedViolations(PlantedReportReopening.class, Connection.class))
                .singleElement().asString().contains("Connection.setAutoCommit").contains("Connection.setReadOnly")
                .contains("Connection.rollback");
    }

    @Test
    @DisplayName("a report class opening a plain statement - any string, a second statement included - is refused")
    void aPlainStatementIsRefused() {
        assertThat(plantedViolations(PlantedReportStatement.class, Connection.class))
                .singleElement().asString().contains("Connection.createStatement");
    }

    @Test
    @DisplayName("a report class reaching a connection of its own - a DataSource or the DriverManager - is refused")
    void aWritablePathIsRefused() {
        assertThat(plantedViolations(ReportingWritablePathProbes.PlantedReportDataSource.class,
                ReportingWritablePathProbes.DATA_SOURCE))
                .singleElement().asString().contains("javax.sql.DataSource");
        assertThat(plantedViolations(ReportingWritablePathProbes.PlantedReportDriverManager.class,
                ReportingWritablePathProbes.DRIVER_MANAGER))
                .singleElement().asString().contains("java.sql.DriverManager");
    }

    @Test
    @DisplayName("a report class reading a JDK's currency table - java.util.Currency, or CurrencyCode.minorUnits - is refused"
            + " (P14-TSK-015), and one holding a CurrencyCode only as a code is not")
    void aCurrencyTableReaderIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedReportCurrencyTable.class,
                PlantedReportMinorUnits.class, PlantedReportCurrencyCode.class, PlantedReportMinorUnitsReference.class,
                PlantedReportCurrencyTableReference.class, CurrencyCode.class);
        assertThat(currencyTableReaders(planted, candidate -> candidate.getSimpleName().startsWith("PlantedReport")))
                .hasSize(4)
                .anySatisfy(found -> assertThat(found).contains("PlantedReportCurrencyTable"))
                .anySatisfy(found -> assertThat(found).contains("PlantedReportMinorUnits"))
                .anySatisfy(found -> assertThat(found).contains("PlantedReportMinorUnitsReference"))
                .anySatisfy(found -> assertThat(found).contains("PlantedReportCurrencyTableReference"));
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
                    .filter(target -> target.getPackageName().startsWith(LEDGER) || WRITERS.contains(target.getName())
                            || WRITABLE_PATHS.contains(target.getName()))
                    .map(JavaClass::getName).distinct().sorted().toList();
            // Code unit accesses, not calls alone: a method reference (PreparedStatement::executeUpdate) is no call.
            List<String> writes = current.getCodeUnitAccessesFromSelf().stream()
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

    /** Every {@code reporting} class that depends on {@code java.util.Currency} or asks a currency for its minor units. */
    static Set<String> currencyTableReaders(JavaClasses classes, Predicate<JavaClass> reporting) {
        Set<String> readers = new TreeSet<>();
        classes.stream().filter(reporting).forEach(candidate -> {
            boolean table = candidate.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().getName().equals("java.util.Currency"));
            // Code unit accesses, not calls alone: CurrencyCode::minorUnits is a method reference, which is no call.
            boolean minorUnits = candidate.getCodeUnitAccessesFromSelf().stream()
                    .anyMatch(call -> call.getName().equals("minorUnits")
                            && call.getTarget().getOwner().getName().equals(CurrencyCode.class.getName()));
            if (table || minorUnits) {
                readers.add(candidate.getName());
            }
        });
        return readers;
    }

    private static boolean isJdbcWrite(JavaCodeUnitAccess<?> call) {
        JavaClass owner = call.getTarget().getOwner();
        if (owner.isAssignableTo(java.sql.Connection.class)) {
            return CONNECTION_MUTATORS.contains(call.getName());
        }
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

    /** A statement's act, by a method reference. */
    @FunctionalInterface
    interface PlantedStatementAct {
        int act(PreparedStatement statement) throws SQLException;
    }

    /** A report class running an UPDATE by a method reference - no call in its bytecode. */
    static class PlantedReportUpdatingByReference {
        PlantedStatementAct touch() {
            return PreparedStatement::executeUpdate;
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

    /** A report class committing its snapshot - ending the read-only transaction. */
    static class PlantedReportCommitting {
        void end(Connection snapshot) throws SQLException {
            snapshot.commit();
        }
    }

    /** A report class reopening its transaction writable. */
    static class PlantedReportReopening {
        void reopen(Connection snapshot) throws SQLException {
            snapshot.rollback();
            snapshot.setAutoCommit(true);
            snapshot.setReadOnly(false);
        }
    }

    /** A report class running a plain statement's string. */
    static class PlantedReportStatement {
        boolean run(Connection snapshot) throws SQLException {
            return snapshot.createStatement().executeQuery("SELECT 1").next();
        }
    }

    /** A report class asking the JDK's table for a currency's digits. */
    static class PlantedReportCurrencyTable {
        int digits(String code) {
            return java.util.Currency.getInstance(code).getDefaultFractionDigits();
        }
    }

    /** A report class asking a currency code for its minor units - the JDK's table, one hop away. */
    static class PlantedReportMinorUnits {
        int digits(CurrencyCode code) {
            return code.minorUnits();
        }
    }

    /** A report class asking a currency code for its minor units by a method reference, not a call. */
    static class PlantedReportMinorUnitsReference {
        java.util.function.ToIntFunction<CurrencyCode> digits() {
            return CurrencyCode::minorUnits;
        }
    }

    /** A report class reaching the JDK's table by a method reference, not a call. */
    static class PlantedReportCurrencyTableReference {
        java.util.function.Function<String, Object> table() {
            return java.util.Currency::getInstance;
        }
    }

    /** A report class holding a currency only as a code. */
    static class PlantedReportCurrencyCode {
        String code(CurrencyCode code) {
            return code.code();
        }
    }

    /** A report class that only reads: a query and the journal's read port. */
    static class PlantedReportReading {
        JournalReads journal;

        boolean any(PreparedStatement statement) throws SQLException {
            return statement.executeQuery().next();
        }
    }
}
