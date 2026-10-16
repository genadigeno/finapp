package com.finapp.creditmath;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The engine conventions and version identities {@code creditmath} declares (`P12-TSK-002`), each pinned to exactly the
 * members lending declared them with in `P11-TSK-002` - the cases moved here from lending's
 * {@code LendingVocabulariesTest} with the types, unedited - so a member added, dropped or renamed is a reviewed change
 * to this test as well as to the enum, and a persisted name ({@code SCHEDULE_ENGINE_V1}, {@code ACT_365F},
 * {@code HOLD_AS_CREDIT}, ...) can never move with the module.
 */
@DisplayName("creditmath's engine conventions and version identities (P12-TSK-002, moved from P11-TSK-002)")
class CreditmathVocabulariesTest {

    @Test
    @DisplayName("only implemented conventions are nameable: the day count is ACT_365F alone, implemented by the"
            + " accrual engine version 1 (INV-LND-09, A22)")
    void onlyImplementedDayCountsAreNameable() {
        assertThat(DayCountConvention.values())
                .as("a convention no accrual engine implements must not be nameable on terms (INV-LND-09)")
                .containsExactly(DayCountConvention.ACT_365F);
        for (DayCountConvention convention : DayCountConvention.values()) {
            assertThat(convention.implementedBy().engine())
                    .as("%s is implemented by an accrual engine", convention)
                    .isEqualTo(LendingEngine.ACCRUAL);
            assertThat(EngineVersion.of(LendingEngine.ACCRUAL, convention.implementedBy().number()))
                    .as("%s's implementing engine is held by this build", convention)
                    .contains(convention.implementedBy());
        }
    }

    @Test
    @DisplayName("the four engines each stand at version 1, which is the current one; an unheld version is empty,"
            + " never a fallback")
    void theFourEnginesStandAtVersionOne() {
        assertThat(LendingEngine.values())
                .containsExactly(
                        LendingEngine.SCHEDULE, LendingEngine.ACCRUAL, LendingEngine.ALLOCATION, LendingEngine.STATEMENT);
        assertThat(EngineVersion.values())
                .containsExactly(
                        EngineVersion.SCHEDULE_ENGINE_V1,
                        EngineVersion.ACCRUAL_ENGINE_V1,
                        EngineVersion.ALLOCATION_ENGINE_V1,
                        EngineVersion.STATEMENT_ENGINE_V1);
        for (LendingEngine engine : LendingEngine.values()) {
            EngineVersion current = EngineVersion.current(engine);
            assertThat(current.engine()).isEqualTo(engine);
            assertThat(current.number()).as("%s's current version", engine).isEqualTo(1);
            assertThat(current.name()).isEqualTo(engine.name() + "_ENGINE_V1");
            assertThat(EngineVersion.of(engine, 1)).contains(current);
            assertThat(EngineVersion.of(engine, 2)).as("version 2 of %s is not held", engine).isEmpty();
            assertThat(EngineVersion.of(engine, 0)).isEmpty();
        }
    }

    @Test
    @DisplayName("L6's allocation components in their default order; two overpayment treatments; one sequence - only"
            + " implemented members")
    void theAllocationVocabularies() {
        assertThat(names(AllocationComponent.values())).containsExactly("FEES", "INTEREST", "PRINCIPAL");
        assertThat(names(OverpaymentTreatment.values())).containsExactly("HOLD_AS_CREDIT", "PAY_DOWN_PRINCIPAL");
        assertThat(names(AllocationSequence.values())).containsExactly("OLDEST_DUE_FIRST");
    }

    @Test
    @DisplayName("each engine version is named by the engine that implements it, and every moved engine still names"
            + " the version it was - no identity moved with the module")
    void theEnginesNameTheirVersions() {
        assertThat(ScheduleEngineV1.VERSION).isEqualTo(EngineVersion.SCHEDULE_ENGINE_V1);
        assertThat(AccrualEngineV1.VERSION).isEqualTo(EngineVersion.ACCRUAL_ENGINE_V1);
        assertThat(AllocationEngineV1.VERSION).isEqualTo(EngineVersion.ALLOCATION_ENGINE_V1);
        assertThat(Arrays.stream(EngineVersion.values()).map(version -> version.name() + "=" + version.number()))
                .containsExactly("SCHEDULE_ENGINE_V1=1", "ACCRUAL_ENGINE_V1=1", "ALLOCATION_ENGINE_V1=1",
                        "STATEMENT_ENGINE_V1=1");
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
