package com.finapp.risk;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assessments = judged commands, per operation and day (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 13.3, 17;
 * {@code INV-RSK-01}; F8; {@code DESIGN-023} D2) - a <strong>set</strong> comparison by command reference, in both
 * directions, against the commands the <strong>owners</strong> hold ({@link JudgedCommands}, implemented in {@code app}
 * over transfers', payments' and crossborder's own tables): counting the judged commands from risk's own table would be
 * a tautology.
 *
 * <ul>
 *   <li><strong>Missing</strong> - a command the owner committed as judged (minted that day) for which the operation
 *       holds no assessment <em>at any day</em>: a judged movement committed without its seal - {@code INV-RSK-01}
 *       broken.</li>
 *   <li><strong>Orphaned</strong> - an assessment (minted that day) whose command the owner holds no record of <em>at
 *       any day</em>: a seal without its command.</li>
 *   <li><strong>Unrecordable</strong> - a refused {@code CROSS_BORDER} assessment: crossborder keeps no row of a command
 *       risk refused (only the seal stands), so it is counted, never judged an orphan.</li>
 *   <li><strong>Reconciled</strong> - found by the lookups: a command and its seal minted either side of midnight, or
 *       an owner's state the judged set leaves out because it cannot tell it judged (a transfer's
 *       {@code DESTINATION_NOT_POSTABLE}, refused before the seams and after them) - counted, never a difference.</li>
 * </ul>
 *
 * One read-only {@code REPEATABLE READ} snapshot ({@link RiskProofs#requireSnapshot}): the seal and its command commit in
 * one transaction, so in one snapshot they are both visible or neither. Nothing is written; a difference is named
 * (at most {@link RiskProofs#NAMED} references a list) and corrected by nobody here.
 *
 * <p><strong>A command's day is its id's</strong>, and for a payment intent that is its creation, not its judgement
 * (the review's M4, recorded in {@code app}'s {@code PlatformJudgedCommands}): an intent created on {@code D} and confirmed
 * later without its seal is named missing only by the census of {@code D} - the meters' reading of today and yesterday
 * stops seeing it after two days; the decisions report for {@code D} does not.
 */
public final class AssessmentCensus {

    /** The operations the census judges - the five that move money. */
    public static final Set<RiskOperation> JUDGED = EnumSet.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL,
            RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER);

    /** One operation's census for the day. */
    public record Operation(RiskOperation operation, long assessments, long commands, long matched, long reconciled,
            long unrecordable, List<CommandRef> missing, long missingCount, List<RiskProofReads.Assessed> orphaned,
            long orphanedCount) {
        public Operation {
            Objects.requireNonNull(operation, "operation");
            missing = List.copyOf(missing);
            orphaned = List.copyOf(orphaned);
        }

        /** {@code DIVERGED} when a judged command lacks its seal or a seal its command. */
        public ProofVerdict verdict() {
            return ProofVerdict.of(assessments + commands, missingCount + orphanedCount);
        }
    }

    /** The day's census, every judged operation. */
    public record Result(RiskDay day, List<Operation> operations) {
        public Result {
            Objects.requireNonNull(day, "day");
            operations = List.copyOf(operations);
        }

        public ProofVerdict verdict() {
            ProofVerdict verdict = ProofVerdict.VACUOUS;
            for (Operation operation : operations) {
                verdict = verdict.and(operation.verdict());
            }
            return verdict;
        }

        /** Missing and orphaned, every operation. */
        public long differences() {
            return operations.stream().mapToLong(operation -> operation.missingCount() + operation.orphanedCount()).sum();
        }
    }

    private final RiskProofReads reads;
    private final JudgedCommands owners;

    public AssessmentCensus(RiskProofReads reads, JudgedCommands owners) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.owners = Objects.requireNonNull(owners, "owners");
    }

    /** {@code day}'s census on {@code snapshot}. */
    public Result run(Connection snapshot, RiskDay day) {
        RiskProofs.requireSnapshot(snapshot);
        Objects.requireNonNull(day, "day");
        List<Operation> operations = new ArrayList<>();
        for (RiskOperation operation : JUDGED) {
            operations.add(census(snapshot, operation, day));
        }
        return new Result(day, operations);
    }

    private Operation census(Connection snapshot, RiskOperation operation, RiskDay day) {
        List<RiskProofReads.Assessed> assessed = reads.assessed(snapshot, operation, day);
        Set<CommandRef> commands = owners.judged(snapshot, operation, day);
        Set<CommandRef> sealed = new LinkedHashSet<>();
        Map<CommandRef, RiskProofReads.Assessed> recordable = new LinkedHashMap<>();
        long unrecordable = 0;
        for (RiskProofReads.Assessed one : assessed) {
            sealed.add(one.commandRef());
            if (operation == RiskOperation.CROSS_BORDER && !one.permits()) {
                // crossborder keeps no row of a command risk refused - only the seal stands (DESIGN-023 D2).
                unrecordable++;
            } else {
                recordable.put(one.commandRef(), one);
            }
        }
        Set<CommandRef> unsealed = new LinkedHashSet<>(commands);
        unsealed.removeAll(sealed);
        Set<CommandRef> sealedElsewhere = unsealed.isEmpty() ? Set.of()
                : reads.assessedAmong(snapshot, operation, unsealed);
        List<CommandRef> missing = new ArrayList<>();
        for (CommandRef ref : unsealed) {
            if (!sealedElsewhere.contains(ref)) {
                missing.add(ref);
            }
        }
        Set<CommandRef> uncommanded = new LinkedHashSet<>(recordable.keySet());
        uncommanded.removeAll(commands);
        Set<CommandRef> commandedElsewhere = uncommanded.isEmpty() ? Set.of()
                : owners.recorded(snapshot, operation, uncommanded);
        List<RiskProofReads.Assessed> orphaned = new ArrayList<>();
        for (CommandRef ref : uncommanded) {
            if (!commandedElsewhere.contains(ref)) {
                orphaned.add(recordable.get(ref));
            }
        }
        long matched = commands.size() - unsealed.size();
        long reconciled = sealedElsewhere.size() + commandedElsewhere.size();
        return new Operation(operation, assessed.size(), commands.size(), matched, reconciled, unrecordable,
                RiskProofs.named(missing), missing.size(), RiskProofs.named(orphaned), orphaned.size());
    }
}
