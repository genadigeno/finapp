package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.GlEqualsLedger;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.RuleConformance;
import com.finapp.accounting.SourceCompleteness;
import com.finapp.app.accounting.AccountingBreakController;
import com.finapp.app.accounting.AccountingBreakDesk;
import com.finapp.app.accounting.LedgerJournalShapes;
import com.finapp.app.accounting.ProductSourceProofs;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ReversalService;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <strong>Breaks never post</strong> - the static half of "detect, never correct" (`P14-TSK-010`; ADR-0117 decision 3;
 * {@code INV-ACC-12}): the break machinery - the three judges, the checks' callable, the break's machine and store, the
 * journal-shape and source-proof adapters, the doors - reaches no door that can write the journal or move the cutoff: not
 * the ledger's {@code PostingService}, {@code ReversalService}, {@code AdjustmentService}, its journal store or its
 * {@code PostingCutoffs}, and not accounting's own writer ports {@link PostingCutoff} and {@link PriorPeriodPostings}. A
 * break is resolved by <em>naming</em> the correcting entry the owning domain posted, never by posting one.
 *
 * <p><strong>The reach.</strong> From each class named below, every class it depends on, and on from every
 * {@code accounting} class reached (an interface's accounting implementations included) - so a writer two hops away,
 * through a helper the machine calls, is refused at the helper. The {@code app} adapters are checked at their own
 * dependencies (they hold ledger <em>readers</em> - {@code ProjectionVerification}, {@code LedgerAccountId} - which is
 * their job). The database half - the journal's count unchanged by a check run and a resolution - is
 * {@code AccountingBreakDatabaseTest#aResolutionPostsNothing}'s (owed, C5).
 *
 * <p><strong>What this does not see</strong> (the independent review's M-9): the reach follows {@code accounting}
 * classes only, never {@code app}'s - so the proofs {@code ProductSourceProofs} is handed are not walked, and one of them,
 * reconciliation's {@code PositionProof}, holds the ledger's {@code JournalEntryStore} (for its line keys - a read). The
 * runtime defence there is the snapshot every judge reads on: {@code REPEATABLE READ READ ONLY}, verified by its first
 * statement ({@code GlSnapshots}), so a write a proof attempted would be refused by PostgreSQL ({@code 25006}) and the
 * check would answer {@code UNAVAILABLE}, never post - {@code AccountingSourceProofsDatabaseTest} proves no real source
 * needs more than that snapshot (owed, C5).
 */
@Tag("architecture")
@DisplayName("the break machinery never posts: no ledger writer, no cutoff, no prior-period door (P14-TSK-010)")
class BreaksNeverPostTest {

    private static final String ACCOUNTING = "com.finapp.accounting";

    /** The doors that write the journal or move the cutoff. */
    static final Set<String> WRITERS = Set.of(
            PostingService.class.getName(),
            ReversalService.class.getName(),
            AdjustmentService.class.getName(),
            JournalEntryStore.class.getName(),
            JdbcJournalEntryStore.class.getName(),
            PostingCutoffs.class.getName(),
            PostingCutoff.class.getName(),
            PriorPeriodPostings.class.getName());

    /** The break machinery, by class. */
    static final List<Class<?>> MACHINERY = List.of(AccountingBreaks.class, AccountingChecks.class,
            RuleConformance.class, GlEqualsLedger.class, SourceCompleteness.class, JdbcAccountingBreakStore.class,
            AccountingBreakDesk.class, AccountingBreakController.class, LedgerJournalShapes.class,
            ProductSourceProofs.class);

    @Test
    @DisplayName("no class of the break machinery reaches a journal writer, the cutoff or the prior-period door")
    void theMachineryReachesNoWriter() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp.accounting", "com.finapp.app.accounting", "com.finapp.ledger");
        for (Class<?> machine : MACHINERY) {
            assertThat(imported.contain(machine.getName())).as("%s is imported - the guard is armed over it", machine)
                    .isTrue();
        }
        for (String writer : WRITERS) {
            assertThat(imported.contain(writer)).as("the writer %s exists in the import", writer).isTrue();
        }
        assertThat(violations(imported, MACHINERY.stream().map(Class::getName).toList()))
                .as("a break class, or an accounting class it reaches, that can post (INV-ACC-12: detect, never correct)")
                .isEmpty();
    }

    @Test
    @DisplayName("a planted break class holding a ledger writer - directly or through a helper - is refused")
    void aPlantedPosterIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedResolver.class, PlantedHelper.class,
                PostingService.class, PriorPeriodPostings.class);
        assertThat(violations(planted, List.of(PlantedResolver.class.getName())))
                .anySatisfy(violation -> assertThat(violation).contains("PlantedResolver").contains("PostingService"))
                .anySatisfy(violation -> assertThat(violation).contains("PlantedHelper")
                        .contains("PriorPeriodPostings"));
    }

    // ------------------------------------------------------------------ the rule

    /** Every (class -> writer) dependency reachable from {@code roots}, following accounting classes and test plants. */
    static Set<String> violations(JavaClasses imported, List<String> roots) {
        Set<String> found = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        Deque<JavaClass> work = new ArrayDeque<>();
        roots.stream().filter(imported::contain).map(imported::get).forEach(work::add);
        while (!work.isEmpty()) {
            JavaClass current = work.pop();
            if (!seen.add(current.getName())) {
                continue;
            }
            for (Dependency dependency : current.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass().getBaseComponentType();
                if (WRITERS.contains(target.getName())) {
                    found.add(current.getSimpleName() + " -> " + target.getName());
                    continue;
                }
                if (followed(target) && imported.contain(target.getName())) {
                    work.add(imported.get(target.getName()));
                    if (target.isInterface()) {
                        imported.stream().filter(candidate -> followed(candidate)
                                && candidate.isAssignableTo(target.getName())).forEach(work::add);
                    }
                }
            }
        }
        return found;
    }

    private static boolean followed(JavaClass candidate) {
        return candidate.getPackageName().startsWith(ACCOUNTING) || candidate.getName().contains("BreaksNeverPostTest$");
    }

    // ------------------------------------------------------------------ plants

    /** A resolution that posts its own correction - the defect. */
    static final class PlantedResolver {
        @SuppressWarnings("unused")
        private PostingService postings;
        @SuppressWarnings("unused")
        private PlantedHelper helper;
    }

    /** A helper two hops from the plant, holding accounting's prior-period door. */
    static final class PlantedHelper {
        @SuppressWarnings("unused")
        private PriorPeriodPostings adjustments;
    }
}
