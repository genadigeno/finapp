package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.accounting.AccountingBreakBeans;
import com.finapp.app.accounting.PayableCheckpointCensus;
import com.finapp.app.accounting.ProductSourceProofs;
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
 * the classes that may name {@link CheckpointedPositionBreakdown} are the payable query's wiring, {@link MerchantBeans}, and since
 * `X-TSK-021` the census beside the proofs, {@link PayableCheckpointCensus}, which compares the API's read with the whole fold and never
 * stores. Two more hold the census wired: the proofs' wiring takes the census's {@link ProductSourceProofs.PayableReads}, and no other
 * class makes one, so a checkpoint is always compared.
 * The teeth plant a proof's wiring that names the checkpointed read beside the real classes, and the permitted ones, through the very
 * rule the sweep runs.
 */
@Tag("architecture")
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("only the merchant API's payable read is answered from a checkpoint; every proof folds the whole history, and the census"
        + " compares the two (P16-REM-001, X-TSK-021)")
class PositionCheckpointsAreTheApisAloneTest {

    @ArchTest
    static final ArchRule onlyThePayableQueryReadsACheckpoint =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(MerchantBeans.class.getName())
                    .and().doNotHaveFullyQualifiedName(PayableCheckpointCensus.class.getName())
                    .and().doNotHaveFullyQualifiedName(CheckpointedPositionBreakdown.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(CheckpointedPositionBreakdown.class.getName())
                    .because("a checkpoint never verifies itself: the proofs fold the whole history (ADR-0133 decision 3,"
                            + " P16-REM-001); the merchant API's payable query, wired in MerchantBeans, reads from one, and the"
                            + " census, PayableCheckpointCensus, compares that read with the whole fold (X-TSK-021)");

    @ArchTest
    static final ArchRule theProofsTakeTheCensussReads =
            classes()
                    .that().haveFullyQualifiedName(AccountingBreakBeans.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(ProductSourceProofs.PayableReads.class.getName())
                    .because("a checkpoint the proofs never compare is one a forged row could hide behind for good: the source"
                            + " proofs read the merchant payables through the census's two reads (X-TSK-021)");

    @ArchTest
    static final ArchRule onlyTheCensusMakesThePayableReads =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(PayableCheckpointCensus.class.getName())
                    .should().callConstructor(ProductSourceProofs.PayableReads.class, MerchantPayable.class, MerchantPayable.class)
                    .because("the census's two reads are the whole fold and the API's checkpointed read; a pair made elsewhere"
                            + " could compare the whole fold with itself and pass every checkpoint (X-TSK-021)");

    /** Two reads made outside the census - the whole fold compared with itself. Never wired; imported only below. */
    static final class ReadsMadeElsewhere {

        ProductSourceProofs.PayableReads reads() {
            MerchantPayable whole = new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown());
            return new ProductSourceProofs.PayableReads(whole, whole);
        }
    }

    @Test
    @DisplayName("the census alone makes the payable reads: a pair made elsewhere is refused, the census's own permitted")
    void onlyTheCensusMakesThePayableReadsBites() {
        assertThatThrownBy(() -> onlyTheCensusMakesThePayableReads.check(new ClassFileImporter().importClasses(
                ReadsMadeElsewhere.class, ProductSourceProofs.PayableReads.class, MerchantPayable.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ReadsMadeElsewhere.class.getSimpleName());
        assertThatCode(() -> onlyTheCensusMakesThePayableReads.check(new ClassFileImporter().importClasses(
                PayableCheckpointCensus.class, ProofWiredToTheWholeHistory.class, ProductSourceProofs.PayableReads.class)))
                .doesNotThrowAnyException();
    }

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
                MerchantBeans.class, PayableCheckpointCensus.class, ProofWiredToTheWholeHistory.class, CheckpointedPositionBreakdown.class,
                JdbcPositionBreakdown.class, MerchantPayable.class, JdbcLedgerAccountStore.class);
        assertThatCode(() -> onlyThePayableQueryReadsACheckpoint.check(wiring)).doesNotThrowAnyException();
    }
}
