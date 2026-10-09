package com.finapp.credit;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Replays every decision, per reading (`P10-TSK-019`; PHASE_10_PLAN.md sections 7 and 12.8; {@code INV-CRD-01}): one
 * {@code REPEATABLE READ} read-only snapshot, every decision it holds replayed by {@link DecisionReplayer}, the
 * transaction rolled back by the caller. Every instance reading one committed state reaches the same verdicts - the
 * proof writes nothing and reads one snapshot, so there is nothing for two instances to race over.
 *
 * <p>The snapshot's shape is checked, not trusted: a reading on a connection that could write, or that sees commits
 * made after it began, is refused before anything is replayed.
 */
@RequiredArgsConstructor
public final class CreditReplayProof {

    @NonNull private final DecisionReplayer replayer;

    /** One reading's verdicts, oldest decision first. */
    public record Report(List<DecisionReplayer.Replay> replays) {
        public Report {
            replays = List.copyOf(Objects.requireNonNull(replays, "replays"));
        }

        /** How many decisions reached {@code verdict}. */
        public long count(DecisionReplayer.Verdict verdict) {
            return replays.stream().filter(replay -> replay.verdict() == verdict).count();
        }

        /** The decisions that diverged - identifiers only. */
        public List<CreditDecisionId> diverged() {
            return replays.stream()
                    .filter(replay -> replay.verdict() == DecisionReplayer.Verdict.DIVERGED)
                    .map(DecisionReplayer.Replay::decision)
                    .toList();
        }
    }

    /** Proves every decision on {@code snapshot} - a read-only {@code REPEATABLE READ} transaction the caller rolls back. */
    public Report prove(Connection snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            if (!snapshot.isReadOnly() || snapshot.getAutoCommit()
                    || snapshot.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException(
                        "the replay proof reads one read-only REPEATABLE READ snapshot - never a connection that could write");
            }
        } catch (SQLException failure) {
            throw new CreditStorageException("could not inspect the replay proof's connection: " + failure.getSQLState());
        }
        List<DecisionReplayer.Replay> replays = new ArrayList<>();
        for (CreditDecisionId id : replayer.decisionIds(snapshot)) {
            replayer.replay(snapshot, id).ifPresent(replays::add);
        }
        return new Report(replays);
    }
}
