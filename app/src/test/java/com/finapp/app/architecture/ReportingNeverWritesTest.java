package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseStatusReads;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PastTheCutoffPostings;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.OperationalReportDefinition;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.app.lending.LendingReports;
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
 * stays inside {@code accounting}: a port's {@code app} adapter is held by the read-only transaction and, by its own accesses, by
 * {@link #noReportPortAdapterWrites} (`P14-TSK-017`), not by this rule,
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

    /**
     * The ports whose answers keep moving after a period's lock (`P14-TSK-017`): a report that reads one is never FINAL. A port
     * added here is a decision; a new moving read outside it is a reviewer's to catch, and is why a FINAL body's sources are named on
     * its statement.
     */
    static final Set<String> MOVING = Set.of(
            ReconciliationDisclosure.class.getName(),
            ReconciliationRunCoverage.class.getName(),
            PastTheCutoffPostings.class.getName(),
            CloseStatusReads.class.getName());

    /** The ledger's writers by name - an adapter may read the ledger's tables, and hold none of these. */
    static final Set<String> LEDGER_WRITERS = Set.of(PostingService.class.getName());

    /** What a report port's adapter may call of a store or of {@code CloseRuns}: reads, on the snapshot it is handed. */
    static final Set<String> ADAPTER_STORE_READS = Set.of("find", "view", "content", "rates", "approvedFor", "countByStatus", "ofPeriod");

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
     * it is not a run's content: the live GL doors' zero at a currency's minor unit - and
     * {@link #noReportDefinitionReachesTheGeneralLedger} is what makes that so (`P14-TSK-017`).
     */
    @ArchTest
    static void noReportContentReadsTheJdksCurrencyTable(JavaClasses imported) {
        assertThat(currencyTableReaders(imported, candidate -> isReportingClass(candidate)
                && !candidate.getName().equals(GeneralLedger.class.getName())))
                .as("a report class reading a JDK's currency table (INV-ACC-04)")
                .isEmpty();
    }

    /**
     * <strong>No report definition has a path to {@link GeneralLedger}</strong> (`P14-TSK-017`; `P14-TSK-015`'s review, M-6;
     * {@code INV-ACC-04}): {@code GeneralLedger.fold} writes {@code GeneralLedger.zero}'s scale - {@code CurrencyCode.minorUnits},
     * a JDK's table - so a definition calling it would put that scale into FINAL content, and the rule above, which checks a report
     * class's own accesses and names {@code GeneralLedger} as its exception, would not see it. Here the exception's reach is closed:
     * from every definition, through every {@code accounting} class it depends on - {@code GlEqualsLedger} folds through it too, and
     * is no report class - and every accounting implementation of an interface it reaches, by a call or a method reference.
     */
    @ArchTest
    static void noReportDefinitionReachesTheGeneralLedger(JavaClasses imported) {
        assertThat(definitionsReaching(imported, ReportDefinition.class.getName(), GeneralLedger.class.getName(),
                candidate -> candidate.getPackageName().startsWith(ACCOUNTING)))
                .as("a report definition with a path to GeneralLedger (INV-ACC-04, the JDK's scale in FINAL content)")
                .isEmpty();
    }

    /**
     * <strong>What moves after a lock is read only by a report that is never FINAL</strong> (`P14-TSK-017`; {@code INV-ACC-04}): a
     * report class that is not an {@link OperationalReportDefinition} - nor reached only from one - depends on none of {@link #MOVING}.
     * {@link SuspenseAsOfCutoff} is not among them: it reads the frozen stamps, and is what a FINAL body has instead.
     */
    @ArchTest
    static void onlyAnOperationalDefinitionReadsWhatMoves(JavaClasses imported) {
        assertThat(finalReadersOfWhatMoves(imported, ReportingNeverWritesTest::isReportingClass))
                .as("a FINAL report class reading what keeps moving after a lock (INV-ACC-04)")
                .isEmpty();
    }

    /**
     * <strong>A report port's adapter never writes, and reads no product's reports</strong> (`P14-TSK-017`; {@code INV-ACC-14},
     * {@code INV-BAL-05}): the reach above stops at {@code accounting}, because a port's {@code app} adapter sees the ledger by design.
     * So each adapter - every {@code app} class implementing a port report code depends on - is held here, by its own accesses (calls and
     * method references alike): no JDBC write and no {@code Connection} mutator; no writer type; no {@code com.finapp.lending},
     * {@code com.finapp.bnpl} or their {@code app} desks, so the portfolio and the exposure cannot be sourced from live rows; and of a
     * store or {@code CloseRuns}, only the reads {@link #ADAPTER_STORE_READS} names.
     */
    @ArchTest
    static void noReportPortAdapterWrites(JavaClasses imported) {
        Set<String> adapters = new TreeSet<>(reportPortAdapters(imported, ReportingNeverWritesTest::isReportingClass));
        // P14-TSK-012: RECONCILIATION_STATUS reports the disclosure's own split, so no definition names this port now; the adapter behind
        // that disclosure reads through it, so it stays held by name rather than falling out of the rule.
        adapters.add("com.finapp.app.accounting.LedgerFlooredPostings");
        assertThat(adapters).as("the census: the adapters this rule is armed over").contains(
                "com.finapp.app.accounting.LedgerTrialBalanceReads", "com.finapp.app.accounting.LedgerCounterpartyPositionReads",
                "com.finapp.app.accounting.ReconciliationMonthReads",
                "com.finapp.app.accounting.AccountingCloseStatusReads", "com.finapp.app.accounting.LedgerFlooredPostings",
                "com.finapp.app.accounting.ProductReconciliationDisclosure");
        assertThat(adapterViolations(imported, adapters)).as("a report port's adapter that can write, or reads a product's reports")
                .isEmpty();
    }

    /**
     * <strong>The frozen register ages in its own bands</strong> (`P14-TSK-017`'s review, M-10; {@code INV-ACC-04}): what a FINAL body holds
     * cannot move with a table it does not own. {@code ReconciliationMonthReads} and its fold depend on no part of {@code
     * ProductReconciliationDisclosure}, whose table the close's disclosure may change; {@code SuspenseAsOfCutoff.AGEING_BANDS} is {@code
     * SUSPENSE} version 1's.
     */
    @ArchTest
    static void theFrozenRegisterAgesInItsOwnBands(JavaClasses imported) {
        String reads = "com.finapp.app.accounting.ReconciliationMonthReads";
        List<JavaClass> found = imported.stream()
                .filter(candidate -> candidate.getName().equals(reads) || candidate.getName().startsWith(reads + "$"))
                .toList();
        assertThat(found).as("the census: the class and its fold").hasSizeGreaterThanOrEqualTo(2);
        assertThat(found.stream().flatMap(candidate -> candidate.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass().getName()))
                .as("a FINAL body's ageing reading the close disclosure's table (INV-ACC-04)")
                .doesNotContain("com.finapp.app.accounting.ProductReconciliationDisclosure");
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
    @DisplayName("M-6: a definition calling GeneralLedger is refused - directly, by a method reference, through a helper - and one folding its"
            + " own reads is not (P14-TSK-017)")
    void aDefinitionReachingTheGeneralLedgerIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedDefinition.class, PlantedDefinitionCalling.class,
                PlantedDefinitionByReference.class, PlantedDefinitionThroughAHelper.class, PlantedReportHelper.class,
                PlantedDefinitionFoldingItself.class, PlantedLedgerFold.class);
        assertThat(definitionsReaching(planted, PlantedDefinition.class.getName(), PlantedLedgerFold.class.getName(),
                candidate -> candidate.getSimpleName().startsWith("Planted")))
                .hasSize(3)
                .anySatisfy(found -> assertThat(found).contains("PlantedDefinitionCalling"))
                .anySatisfy(found -> assertThat(found).contains("PlantedDefinitionByReference"))
                .anySatisfy(found -> assertThat(found).contains("PlantedDefinitionThroughAHelper"));
    }

    @Test
    @DisplayName("a FINAL report class holding a read that moves is refused; an operational definition holding it, and a FINAL one holding the"
            + " frozen register, are not (P14-TSK-017)")
    void aFinalReaderOfWhatMovesIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedFinalReadingWhatMoves.class,
                PlantedOperationalReadingWhatMoves.class, PlantedFinalReadingTheFrozenRegister.class,
                ReconciliationRunCoverage.class, CloseStatusReads.class, SuspenseAsOfCutoff.class,
                OperationalReportDefinition.class, ReportDefinition.class);
        assertThat(finalReadersOfWhatMoves(planted, candidate -> candidate.getSimpleName().startsWith("Planted")))
                .singleElement().asString().contains("PlantedFinalReadingWhatMoves").contains("ReconciliationRunCoverage");
    }

    @Test
    @DisplayName("a report port's adapter writing, holding a product's report or calling a store's write is refused - by a call or a method"
            + " reference; one reading a store's read is not (P14-TSK-017)")
    void anAdapterThatCanWriteIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedPort.class, PlantedPortUser.class,
                PlantedAdapterUpdating.class, PlantedAdapterByReference.class, PlantedAdapterOnALendingReport.class,
                PlantedAdapterWritingAStore.class, PlantedAdapterReadingAStore.class, CloseRunStore.class, PreparedStatement.class,
                LendingReports.class);
        Set<String> adapters = reportPortAdapters(planted, candidate -> candidate.getSimpleName().equals("PlantedPortUser"));
        assertThat(adapters).as("every implementor of the port report code depends on").hasSize(5);
        assertThat(adapterViolations(planted, adapters)).hasSize(4)
                .anySatisfy(found -> assertThat(found).contains("PlantedAdapterUpdating").contains("executeUpdate"))
                .anySatisfy(found -> assertThat(found).contains("PlantedAdapterByReference").contains("executeUpdate"))
                .anySatisfy(found -> assertThat(found).contains("PlantedAdapterOnALendingReport").contains("LendingReports"))
                .anySatisfy(found -> assertThat(found).contains("PlantedAdapterWritingAStore").contains("insertRunIfAbsent"));
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

    /**
     * Every class implementing {@code definitionType} (and selected by {@code followed}) from which {@code target} is reachable,
     * through the classes {@code followed} selects and their implementations.
     */
    static Set<String> definitionsReaching(JavaClasses classes, String definitionType, String target,
            Predicate<JavaClass> followed) {
        Set<String> reaching = new TreeSet<>();
        classes.stream()
                .filter(candidate -> !candidate.getName().equals(definitionType) && followed.test(candidate)
                        && candidate.isAssignableTo(definitionType) && !candidate.isInterface())
                .forEach(definition -> {
                    Set<String> reached = new TreeSet<>();
                    Deque<JavaClass> pending = new ArrayDeque<>(List.of(definition));
                    while (!pending.isEmpty()) {
                        JavaClass current = pending.pop();
                        if (!reached.add(current.getName())) {
                            continue;
                        }
                        // The definition's own interface is no path: from it every other definition is an implementation.
                        current.getDirectDependenciesFromSelf().stream()
                                .map(Dependency::getTargetClass)
                                .filter(next -> !next.getName().equals(definitionType))
                                .filter(next -> next.getName().equals(target) || followed.test(next))
                                .forEach(pending::add);
                        current.getCodeUnitAccessesFromSelf().stream()
                                .map(access -> access.getTarget().getOwner())
                                .filter(next -> !next.getName().equals(definitionType))
                                .filter(next -> next.getName().equals(target) || followed.test(next))
                                .forEach(pending::add);
                        if (current.isInterface() || current.getModifiers().contains(JavaModifier.ABSTRACT)) {
                            current.getAllSubclasses().stream().filter(followed).forEach(pending::add);
                        }
                    }
                    if (reached.contains(target)) {
                        reaching.add(definition.getName());
                    }
                });
        return reaching;
    }

    /** Every report class, not an operational definition, that depends on a read that moves. */
    static Set<String> finalReadersOfWhatMoves(JavaClasses classes, Predicate<JavaClass> reports) {
        Set<String> readers = new TreeSet<>();
        classes.stream().filter(reports)
                .filter(candidate -> !candidate.isAssignableTo(OperationalReportDefinition.class.getName()))
                .forEach(candidate -> {
                    List<String> moving = candidate.getDirectDependenciesFromSelf().stream()
                            .map(dependency -> dependency.getTargetClass().getName())
                            .filter(MOVING::contains).distinct().sorted().toList();
                    if (!moving.isEmpty()) {
                        readers.add(candidate.getName() + " -> " + moving);
                    }
                });
        return readers;
    }

    /** Every {@code app} class - or, for a planted suite, any class - implementing an interface a report class depends on. */
    static Set<String> reportPortAdapters(JavaClasses classes, Predicate<JavaClass> reports) {
        Set<String> adapters = new TreeSet<>();
        classes.stream().filter(reports).forEach(report -> report.getDirectDependenciesFromSelf().stream()
                .map(Dependency::getTargetClass)
                .filter(JavaClass::isInterface)
                .filter(port -> port.getName().startsWith("com.finapp."))
                .forEach(port -> port.getAllSubclasses().stream()
                        .filter(candidate -> !candidate.isInterface())
                        .filter(candidate -> !candidate.getPackageName().startsWith(ACCOUNTING))
                        .filter(candidate -> !candidate.isAnonymousClass())
                        .forEach(adapter -> adapters.add(adapter.getName()))));
        return adapters;
    }

    /** What each adapter's own accesses and direct dependencies break of {@link #noReportPortAdapterWrites}. */
    static Set<String> adapterViolations(JavaClasses classes, Set<String> adapters) {
        Set<String> violations = new TreeSet<>();
        for (String name : adapters) {
            JavaClass adapter = classes.get(name);
            List<String> forbidden = adapter.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .filter(target -> WRITERS.contains(target.getName()) || WRITABLE_PATHS.contains(target.getName())
                            || LEDGER_WRITERS.contains(target.getName()) || isAProductsReport(target))
                    .map(JavaClass::getName).distinct().sorted().toList();
            List<String> writes = adapter.getCodeUnitAccessesFromSelf().stream()
                    .filter(access -> isJdbcWrite(access) || isAStoreWrite(access))
                    .map(access -> access.getTarget().getOwner().getSimpleName() + "." + access.getName())
                    .distinct().sorted().toList();
            if (!forbidden.isEmpty() || !writes.isEmpty()) {
                violations.add(name + " -> " + forbidden + " " + writes);
            }
        }
        return violations;
    }

    private static boolean isAProductsReport(JavaClass target) {
        String pkg = target.getPackageName();
        return pkg.equals("com.finapp.lending") || pkg.startsWith("com.finapp.lending.")
                || pkg.equals("com.finapp.bnpl") || pkg.startsWith("com.finapp.bnpl.")
                || pkg.equals("com.finapp.app.lending") || pkg.startsWith("com.finapp.app.lending.")
                || pkg.equals("com.finapp.app.bnpl") || pkg.startsWith("com.finapp.app.bnpl.");
    }

    /** A call on an accounting store or on {@code CloseRuns} that is not one of the reads named. */
    private static boolean isAStoreWrite(JavaCodeUnitAccess<?> access) {
        JavaClass owner = access.getTarget().getOwner();
        boolean store = owner.getPackageName().equals(ACCOUNTING)
                && (owner.getSimpleName().endsWith("Store") || owner.getSimpleName().equals("CloseRuns"));
        return store && !ADAPTER_STORE_READS.contains(access.getName());
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

    // ------------------------------------------------------------------ P14-TSK-017's planted classes

    /** Stands for {@code ReportDefinition}. */
    interface PlantedDefinition {}

    /** Stands for {@code GeneralLedger}: the fold a definition must not reach. */
    static class PlantedLedgerFold {
        static int fold() {
            return 1;
        }
    }

    /** A definition calling the fold. */
    static class PlantedDefinitionCalling implements PlantedDefinition {
        int body() {
            return PlantedLedgerFold.fold();
        }
    }

    /** A definition holding the fold by a method reference - no call in its bytecode. */
    static class PlantedDefinitionByReference implements PlantedDefinition {
        java.util.function.IntSupplier body() {
            return PlantedLedgerFold::fold;
        }
    }

    /** A definition one hop from the fold. */
    static class PlantedDefinitionThroughAHelper implements PlantedDefinition {
        PlantedReportHelper helper;
    }

    static class PlantedReportHelper {
        int body() {
            return PlantedLedgerFold.fold();
        }
    }

    /** A definition folding what it read itself. */
    static class PlantedDefinitionFoldingItself implements PlantedDefinition {
        int body() {
            return 2;
        }
    }

    /** A FINAL report class reading the month's runs, which a late batch moves. */
    static class PlantedFinalReadingWhatMoves implements ReportDefinitionShape {
        ReconciliationRunCoverage coverage;
    }

    /** An operational definition may. */
    abstract static class PlantedOperationalReadingWhatMoves implements OperationalReportDefinition {
        CloseStatusReads status;
    }

    /** A FINAL report class reading the frozen register may. */
    static class PlantedFinalReadingTheFrozenRegister implements ReportDefinitionShape {
        SuspenseAsOfCutoff register;
    }

    /** No {@code ReportDefinition}: its abstract methods would only be noise here. */
    interface ReportDefinitionShape {}

    /** Stands for a report port. */
    interface PlantedPort {}

    /** Report code depending on the port. */
    static class PlantedPortUser {
        PlantedPort port;
    }

    /** An adapter running an UPDATE. */
    static class PlantedAdapterUpdating implements PlantedPort {
        int touch(PreparedStatement statement) throws SQLException {
            return statement.executeUpdate();
        }
    }

    /** An adapter running an UPDATE by a method reference - no call in its bytecode. */
    static class PlantedAdapterByReference implements PlantedPort {
        PlantedStatementAct touch() {
            return PreparedStatement::executeUpdate;
        }
    }

    /** An adapter sourcing the loan book from lending's live report. */
    static class PlantedAdapterOnALendingReport implements PlantedPort {
        LendingReports reports;
    }

    /** An adapter calling a store's write. */
    static class PlantedAdapterWritingAStore implements PlantedPort {
        boolean touch(CloseRunStore store, Connection connection, CloseRunStore.NewRun run) {
            return store.insertRunIfAbsent(connection, run);
        }
    }

    /** An adapter calling only a read of a store - not flagged. */
    static class PlantedAdapterReadingAStore implements PlantedPort {
        Object touch(AccountingPeriodStore store, Connection connection, AccountingPeriodId id) {
            return store.find(connection, id);
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
