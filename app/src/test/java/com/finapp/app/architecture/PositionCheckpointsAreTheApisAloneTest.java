package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.merchant.MerchantBeans;
import com.finapp.ledger.CheckpointedPositionBreakdown;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.merchant.MerchantPayable;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Only the merchant API's payable read is answered from a commit-order checkpoint; every proof reads the whole history (`P16-REM-001`;
 * ADR-0133 decision 3).
 *
 * <p>A checkpoint is derived data the read itself writes. A proof that read one would compare the checkpoint's own figure with the
 * product's records, and a checkpoint stored wrong - by a defect, or by a holder of the application's credential - would verify
 * itself. So {@code ProductSourceProofs} and the BNPL three-way build {@link MerchantPayable} over {@link JdbcPositionBreakdown}, and
 * the one class that may name {@link CheckpointedPositionBreakdown} is the payable query's wiring, {@link MerchantBeans}. The teeth
 * below plant a proof's wiring that names it beside the real classes, and the wiring itself, through the very rule the sweep runs.
 */
@Tag("architecture")
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("only the merchant API's payable read is answered from a checkpoint; every proof folds the whole history (P16-REM-001)")
class PositionCheckpointsAreTheApisAloneTest {

    @ArchTest
    static final ArchRule onlyThePayableQueryReadsACheckpoint =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(MerchantBeans.class.getName())
                    .and().doNotHaveFullyQualifiedName(CheckpointedPositionBreakdown.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(CheckpointedPositionBreakdown.class.getName())
                    .because("a checkpoint never verifies itself: the proofs fold the whole history (ADR-0133 decision 3,"
                            + " P16-REM-001), and only the merchant API's payable query, wired in MerchantBeans, reads from one");

    /** A proof's wiring that would read the payable from a checkpoint. Never wired; imported only below. */
    static final class ProofWiredToACheckpoint {

        MerchantPayable drillDown() {
            return new MerchantPayable(new JdbcLedgerAccountStore(), new CheckpointedPositionBreakdown());
        }
    }

    /** A proof's wiring as the proofs are wired: the whole history. Never wired; imported only below. */
    static final class ProofWiredToTheWholeHistory {

        MerchantPayable drillDown() {
            return new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown());
        }
    }

    @Test
    @DisplayName("the rule refuses a proof's wiring that names the checkpointed read, and permits the payable query's own and a proof on"
            + " the whole history")
    void theRuleRefusesAProofOnACheckpoint() {
        JavaClasses planted = new ClassFileImporter().importClasses(
                ProofWiredToACheckpoint.class, CheckpointedPositionBreakdown.class, MerchantPayable.class, JdbcLedgerAccountStore.class);
        assertThatThrownBy(() -> onlyThePayableQueryReadsACheckpoint.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ProofWiredToACheckpoint.class.getSimpleName());

        JavaClasses wiring = new ClassFileImporter().importClasses(
                MerchantBeans.class, ProofWiredToTheWholeHistory.class, CheckpointedPositionBreakdown.class, JdbcPositionBreakdown.class,
                MerchantPayable.class, JdbcLedgerAccountStore.class);
        assertThatCode(() -> onlyThePayableQueryReadsACheckpoint.check(wiring)).doesNotThrowAnyException();
    }
}
