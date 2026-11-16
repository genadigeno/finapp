package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.JdbcOpeningCheckpointStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.OpeningCheckpoint;
import com.finapp.accounting.OpeningCheckpointStore;
import com.finapp.accounting.OpeningCheckpoints;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.app.accounting.AccountingCloseBeans;
import com.finapp.app.accounting.LedgerCounterpartyPositionReads;
import com.finapp.app.accounting.LedgerTrialBalanceReads;
import com.finapp.app.accounting.OperationalReportBeans;
import com.finapp.app.accounting.TrialBalanceBeans;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Only report generation's opening reads start from a checkpoint, and only the openings leg's writer stores one (`P16-REM-003`;
 * {@code THREAT_MODEL.md} section 6 item 64).
 *
 * <p>A checkpoint is derived data. A proof or a close check that read one would compare the checkpoint's own sums with the journal,
 * and a checkpoint stored wrong - by a defect, or by a holder of the application's credential - would verify itself. So the classes
 * that may name {@link OpeningCheckpointStore} are the two read adapters, the writer, the store itself and the three wirings; the
 * close's GL = ledger check, the source proofs and every other read of the journal fold it whole. And the writer is the one class that
 * claims, finds the pending month and stores - the store's insert taking the lock it was folded under again - so a checkpoint is
 * written by the leg that folds it and never otherwise. The teeth plant a proof reading a checkpoint and a second writer beside the real
 * classes, through the very rules the sweep runs.
 */
@Tag("architecture")
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("only report generation's opening reads start from a checkpoint, and only the openings leg stores one (P16-REM-003)")
class OpeningCheckpointsAreTheReportsAloneTest {

    /** The classes that may name the store: the reads, the writer, the store and its own types, and their wirings. */
    private static final Set<String> MAY_NAME_THE_STORE = Set.of(LedgerTrialBalanceReads.class.getName(),
            LedgerCounterpartyPositionReads.class.getName(), OpeningCheckpoints.class.getName(), OpeningCheckpointStore.class.getName(),
            OpeningCheckpointStore.Pending.class.getName(), JdbcOpeningCheckpointStore.class.getName(), TrialBalanceBeans.class.getName(),
            OperationalReportBeans.class.getName(), AccountingCloseBeans.class.getName());

    private static final Set<String> WRITES = Set.of("store", "claim", "pending");

    @ArchTest
    static final ArchRule onlyTheOpeningReadsTakeACheckpoint =
            noClasses()
                    .that(DescribedPredicate.describe("are not the opening reads, the writer, the store or their wirings",
                            javaClass -> !MAY_NAME_THE_STORE.contains(javaClass.getName())))
                    .should().dependOnClassesThat().areAssignableTo(OpeningCheckpointStore.class)
                    .because("a checkpoint never verifies itself: the close's GL = ledger check and the proofs fold the whole journal;"
                            + " only report generation's opening reads start from a checkpoint (P16-REM-003)");

    @ArchTest
    static final ArchRule onlyTheOpeningsLegWritesACheckpoint =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(OpeningCheckpoints.class.getName())
                    .should().callMethodWhere(DescribedPredicate.describe("store, claim or pending of an opening checkpoint store",
                            (JavaCall<?> call) -> call.getTargetOwner().isAssignableTo(OpeningCheckpointStore.class)
                                    && WRITES.contains(call.getName())))
                    .because("a checkpoint is written by the leg that folds it, after the month's lock, and never otherwise - its insert"
                            + " reads the lock again (P16-REM-003)");

    /** A proof's wiring that would read the journal from a checkpoint - the concrete store, never the port. Never wired; imported only below. */
    static final class ProofReadingACheckpoint {

        TrialBalanceReads reads() {
            return new LedgerTrialBalanceReads(null, new JdbcOpeningCheckpointStore());
        }
    }

    /** A proof's wiring as the proofs are wired: the whole journal, no store. Never wired; imported only below. */
    static final class ProofWiredToTheWholeJournal {

        TrialBalanceReads reads() {
            return new LedgerTrialBalanceReads(null);
        }
    }

    /** A second writer, storing a checkpoint it made itself. Never wired; imported only below. */
    static final class WriterElsewhere {

        boolean forge(Connection unitOfWork, OpeningCheckpointStore store) {
            return store.store(unitOfWork, UUID.randomUUID(), new OpeningCheckpoint(null, LocalDate.MIN, Instant.EPOCH, Optional.empty(),
                    List.<JournalReads.Aggregate>of(), List.of(), List.of()));
        }
    }

    @Test
    @DisplayName("a proof reading a checkpoint is refused; one folding the whole journal, and the opening reads themselves, are permitted")
    void onlyTheOpeningReadsTakeACheckpointBites() {
        assertThatThrownBy(() -> onlyTheOpeningReadsTakeACheckpoint.check(new ClassFileImporter().importClasses(
                ProofReadingACheckpoint.class, OpeningCheckpointStore.class, JdbcOpeningCheckpointStore.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ProofReadingACheckpoint.class.getSimpleName());
        assertThatCode(() -> onlyTheOpeningReadsTakeACheckpoint.check(new ClassFileImporter().importClasses(
                ProofWiredToTheWholeJournal.class, LedgerTrialBalanceReads.class, LedgerCounterpartyPositionReads.class,
                OpeningCheckpointStore.class)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a second writer is refused; the openings leg's own is permitted")
    void onlyTheOpeningsLegWritesACheckpointBites() {
        assertThatThrownBy(() -> onlyTheOpeningsLegWritesACheckpoint.check(new ClassFileImporter().importClasses(
                WriterElsewhere.class, OpeningCheckpointStore.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(WriterElsewhere.class.getSimpleName());
        assertThatCode(() -> onlyTheOpeningsLegWritesACheckpoint.check(new ClassFileImporter().importClasses(
                OpeningCheckpoints.class, OpeningCheckpointStore.class)))
                .doesNotThrowAnyException();
    }
}
