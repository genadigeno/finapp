package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Assessments = judged commands, per operation and day (`P13-TSK-023`; {@code INV-RSK-01}; F8; {@code DESIGN-023} D2):
 * over a fake owner whose judged commands are planted apart from risk's assessments - so the census is shown to read the
 * owner's side, never to count risk's own table twice. Equal sets hold; a judged command with no seal is missing; a seal
 * with no command is orphaned; a command and its seal either side of midnight reconcile; a refused cross-border command,
 * which crossborder keeps no row of, is unrecordable, never an orphan; the census refuses a connection that could write
 * and writes nothing. The database's half is {@code RiskProofsDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("the assessment census: assessments = the owners' judged commands, both directions, a difference named"
        + " (P13-TSK-023)")
class AssessmentCensusTest {

    private static final RiskDay DAY = RiskDay.of(LocalDate.of(2026, 10, 23));
    private static final Instant NOON = Instant.parse("2026-10-23T12:00:00Z");

    private final FakeRiskProofReads reads = new FakeRiskProofReads();
    private final FakeOwners owners = new FakeOwners();
    private final AssessmentCensus census = new AssessmentCensus(reads, owners);

    @Test
    @DisplayName("every judged command has its assessment and every assessment its command: HELD, per operation,"
            + " the owner's side read through the port")
    void equalSetsHold() {
        for (RiskOperation operation : AssessmentCensus.JUDGED) {
            judgedAndSealed(operation, NOON);
            judgedAndSealed(operation, NOON.plusSeconds(60));
        }

        AssessmentCensus.Result result = census.run(StandInConnections.snapshot(), DAY);
        assertThat(result.verdict()).isEqualTo(ProofVerdict.HELD);
        assertThat(result.operations()).hasSize(5).allSatisfy(operation -> {
            assertThat(operation.assessments()).isEqualTo(2);
            assertThat(operation.commands()).isEqualTo(2);
            assertThat(operation.matched()).isEqualTo(2);
            assertThat(operation.missingCount()).isZero();
            assertThat(operation.orphanedCount()).isZero();
            assertThat(operation.verdict()).isEqualTo(ProofVerdict.HELD);
        });
        assertThat(owners.asked).as("the owners were asked, operation by operation")
                .containsExactlyInAnyOrder(AssessmentCensus.JUDGED.toArray(RiskOperation[]::new));
    }

    @Test
    @DisplayName("a command the owner committed as judged with no assessment at any day is missing - named, DIVERGED;"
            + " risk's own table alone would never see it (the census is no tautology)")
    void aJudgedCommandWithoutItsSealIsMissing() {
        judgedAndSealed(RiskOperation.TRANSFER, NOON);
        CommandRef unsealed = CommandRef.of(FakeRiskProofReads.mintedAt(NOON));
        owners.judged(RiskOperation.TRANSFER, unsealed, NOON);

        AssessmentCensus.Operation transfers = operation(census.run(StandInConnections.snapshot(), DAY),
                RiskOperation.TRANSFER);
        assertThat(transfers.verdict()).isEqualTo(ProofVerdict.DIVERGED);
        assertThat(transfers.missing()).containsExactly(unsealed);
        assertThat(transfers.missingCount()).isEqualTo(1);
        assertThat(transfers.assessments()).as("risk's side agrees with itself").isEqualTo(1);
        assertThat(transfers.commands()).isEqualTo(2);
    }

    @Test
    @DisplayName("an assessment whose command the owner holds no record of at any day is orphaned - named, DIVERGED")
    void aSealWithoutItsCommandIsOrphaned() {
        CommandRef orphan = CommandRef.of(FakeRiskProofReads.mintedAt(NOON));
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(NOON), RiskOperation.WITHDRAWAL,
                orphan, true));

        AssessmentCensus.Result result = census.run(StandInConnections.snapshot(), DAY);
        AssessmentCensus.Operation withdrawals = operation(result, RiskOperation.WITHDRAWAL);
        assertThat(withdrawals.orphaned()).containsExactly(orphan);
        assertThat(result.verdict()).isEqualTo(ProofVerdict.DIVERGED);
        assertThat(result.differences()).isEqualTo(1);
    }

    @Test
    @DisplayName("a command minted before midnight whose seal was minted after it - and an owner's state the judged set"
            + " leaves out - reconcile by lookup on both days: counted, never a difference")
    void acrossTheDayReconciles() {
        Instant beforeMidnight = DAY.end().minusMillis(2);
        Instant afterMidnight = DAY.end().plusMillis(3);
        CommandRef command = CommandRef.of(FakeRiskProofReads.mintedAt(beforeMidnight));
        owners.judged(RiskOperation.TRANSFER, command, beforeMidnight);
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(afterMidnight),
                RiskOperation.TRANSFER, command, true));
        // A transfer refused DESTINATION_NOT_POSTABLE after its seams: sealed, recorded by the owner, not in its judged set.
        CommandRef ambiguous = CommandRef.of(FakeRiskProofReads.mintedAt(NOON));
        owners.recordedOnly(RiskOperation.TRANSFER, ambiguous);
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(NOON), RiskOperation.TRANSFER,
                ambiguous, true));

        AssessmentCensus.Operation today = operation(census.run(StandInConnections.snapshot(), DAY),
                RiskOperation.TRANSFER);
        assertThat(today.verdict()).isEqualTo(ProofVerdict.HELD);
        assertThat(today.reconciled()).as("the command sealed tomorrow, the ambiguous one recorded").isEqualTo(2);
        AssessmentCensus.Operation tomorrow = operation(census.run(StandInConnections.snapshot(),
                RiskDay.of(DAY.date().plusDays(1))), RiskOperation.TRANSFER);
        assertThat(tomorrow.verdict()).isEqualTo(ProofVerdict.HELD);
        assertThat(tomorrow.reconciled()).isEqualTo(1);
    }

    @Test
    @DisplayName("a refused cross-border command leaves crossborder no row: its assessment is unrecordable, never an"
            + " orphan; a permitted one without its payment is")
    void aRefusedCrossBorderSealIsUnrecordable() {
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(NOON), RiskOperation.CROSS_BORDER,
                CommandRef.of(FakeRiskProofReads.mintedAt(NOON)), false));
        CommandRef permittedQuote = CommandRef.of(FakeRiskProofReads.mintedAt(NOON));
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(NOON), RiskOperation.CROSS_BORDER,
                permittedQuote, true));

        AssessmentCensus.Operation crossBorder = operation(census.run(StandInConnections.snapshot(), DAY),
                RiskOperation.CROSS_BORDER);
        assertThat(crossBorder.unrecordable()).isEqualTo(1);
        assertThat(crossBorder.orphaned()).containsExactly(permittedQuote);
    }

    @Test
    @DisplayName("a census reads one read-only REPEATABLE READ snapshot - refused a connection that could write, or"
            + " another isolation; nothing is ever written (the owners and the reads hold no write)")
    void oneReadOnlySnapshot() {
        assertThatThrownBy(() -> census.run(StandInConnections.declaring(false, false,
                Connection.TRANSACTION_REPEATABLE_READ), DAY)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only REPEATABLE READ");
        assertThatThrownBy(() -> census.run(StandInConnections.declaring(true, false,
                Connection.TRANSACTION_READ_COMMITTED), DAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> census.run(StandInConnections.declaring(true, true,
                Connection.TRANSACTION_REPEATABLE_READ), DAY)).isInstanceOf(IllegalStateException.class);
        assertThat(reads.calls).isEmpty();
        assertThat(census.run(StandInConnections.snapshot(), DAY).verdict()).isEqualTo(ProofVerdict.VACUOUS);
    }

    // ------------------------------------------------------------------

    private void judgedAndSealed(RiskOperation operation, Instant at) {
        CommandRef ref = CommandRef.of(FakeRiskProofReads.mintedAt(at));
        owners.judged(operation, ref, at);
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(at), operation, ref, true));
    }

    private static AssessmentCensus.Operation operation(AssessmentCensus.Result result, RiskOperation operation) {
        return result.operations().stream().filter(one -> one.operation() == operation).findFirst().orElseThrow();
    }

    /** The owners' side - planted apart from risk's: judged commands by their minting day, and every recorded one. */
    static final class FakeOwners implements JudgedCommands {
        final Map<RiskOperation, List<Object[]>> judged = new EnumMap<>(RiskOperation.class);
        final Map<RiskOperation, Set<CommandRef>> recorded = new EnumMap<>(RiskOperation.class);
        final List<RiskOperation> asked = new ArrayList<>();

        void judged(RiskOperation operation, CommandRef ref, Instant minted) {
            judged.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(new Object[] {ref, minted});
            recordedOnly(operation, ref);
        }

        void recordedOnly(RiskOperation operation, CommandRef ref) {
            recorded.computeIfAbsent(operation, ignored -> new LinkedHashSet<>()).add(ref);
        }

        @Override
        public Set<CommandRef> judged(Connection snapshot, RiskOperation operation, RiskDay day) {
            asked.add(operation);
            Set<CommandRef> refs = new LinkedHashSet<>();
            for (Object[] row : judged.getOrDefault(operation, List.of())) {
                if (RiskDay.containing((Instant) row[1]).equals(day)) {
                    refs.add((CommandRef) row[0]);
                }
            }
            return refs;
        }

        @Override
        public Set<CommandRef> recorded(Connection snapshot, RiskOperation operation, Collection<CommandRef> refs) {
            Set<CommandRef> found = new LinkedHashSet<>(refs);
            found.retainAll(recorded.getOrDefault(operation, Set.of()));
            return found;
        }
    }
}
