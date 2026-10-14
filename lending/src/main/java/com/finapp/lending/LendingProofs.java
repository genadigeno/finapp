package com.finapp.lending;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Lending's two continuous proofs over every loan account, in ONE snapshot (`P11-TSK-029`; PHASE_11_PLAN.md sections 12.8,
 * 13.1 and 15; {@code INV-LND-01}, {@code INV-LND-05}, {@code INV-LND-09}): each account's {@link LoanRecord} read once, then
 * proven by the {@link LoanSubledgerProof} (its six balances explained by its rows) and replayed by the
 * {@link LoanReplayProof} (every figure re-derived by its pinned engine).
 *
 * <p><strong>One read-only {@code REPEATABLE READ} snapshot per reading</strong>, the caller's - refused otherwise, so the
 * proofs can never write and never read a torn state: a servicing step, a draw or a repayment committing while the
 * reading runs is wholly invisible to it, and ten instances reading at one instant compute the same verdicts. A
 * difference is an alert ({@code finapp.lending.subledger.proof}, {@code finapp.lending.replay}) - nothing is corrected.
 *
 * <p>Cost: one record per account - a {@code SELECT} per lending table, the account's journal lines in one statement -
 * and a pure walk over it; linear in the accounts' rows. The reading is taken behind a refresh floor by the gauges and on
 * demand by the suites.
 */
@RequiredArgsConstructor
public final class LendingProofs {

    @NonNull private final LoanRecordStore records;
    @NonNull private final LoanSubledgerProof subledger;
    @NonNull private final LoanReplayProof replay;

    /** One account proven: the subledger verdict and the replay. */
    public record Proven(LoanSubledgerProof.Verdict subledger, LoanReplayProof.Replay replay) {

        public Proven {
            Objects.requireNonNull(subledger, "subledger");
            Objects.requireNonNull(replay, "replay");
        }
    }

    /** One reading: every account proven, oldest first. */
    public record Reading(List<Proven> accounts) {

        public Reading {
            accounts = List.copyOf(accounts);
        }

        /** Accounts whose subledger proof holds. */
        public long holding() {
            return accounts.stream().filter(proven -> proven.subledger().holds()).count();
        }

        /** Accounts whose subledger proof fails. */
        public long failing() {
            return accounts.size() - holding();
        }

        /** Accounts that replay to {@code verdict}. */
        public long replaying(LoanReplayProof.Verdict verdict) {
            return accounts.stream().filter(proven -> proven.replay().verdict() == verdict).count();
        }

        /** The failing accounts' verdicts - identifiers and arm names only. */
        public List<LoanSubledgerProof.Verdict> failures() {
            return accounts.stream().map(Proven::subledger).filter(verdict -> !verdict.holds()).toList();
        }

        /** The diverged accounts' replays - identifiers and kinds only. */
        public List<LoanReplayProof.Replay> divergences() {
            return accounts.stream().map(Proven::replay)
                    .filter(replayed -> replayed.verdict() == LoanReplayProof.Verdict.DIVERGED).toList();
        }
    }

    /** Every account, on {@code snapshot}. */
    public Reading prove(Connection snapshot) {
        requireSnapshot(snapshot);
        List<Proven> proven = new ArrayList<>();
        for (LoanId loan : records.loanIds(snapshot)) {
            records.read(snapshot, loan).ifPresent(record -> proven.add(prove(record)));
        }
        return new Reading(proven);
    }

    /** One account, on {@code snapshot}; empty when no such loan exists. */
    public Optional<Proven> prove(Connection snapshot, LoanId loan) {
        requireSnapshot(snapshot);
        return records.read(snapshot, Objects.requireNonNull(loan, "loan")).map(this::prove);
    }

    /** One account's record proven. */
    public Proven prove(LoanRecord record) {
        return new Proven(subledger.prove(record), replay.replay(record));
    }

    /** The proofs read one read-only {@code REPEATABLE READ} snapshot - never a connection that could write. */
    static void requireSnapshot(Connection snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            if (!snapshot.isReadOnly() || snapshot.getAutoCommit()
                    || snapshot.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException(
                        "the lending proofs read one read-only REPEATABLE READ snapshot - never a connection that could write");
            }
        } catch (SQLException failure) {
            throw new LendingStorageException("could not inspect the lending proofs' connection: " + failure.getSQLState());
        }
    }
}
