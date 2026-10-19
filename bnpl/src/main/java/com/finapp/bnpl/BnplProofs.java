package com.finapp.bnpl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * BNPL's three continuous proofs, in ONE snapshot (`P12-TSK-025`; {@code PHASE_12_PLAN.md} sections 12.12 and 15, gate
 * criterion 5; lending's {@code LendingProofs}): every obligation's {@link BnplRecord} read once, then proven by the
 * {@link BnplSubledgerProof} (its six balances explained by its rows), replayed by the {@link BnplReplayProof} (every figure
 * re-derived by its pinned engine), and every currency proven three ways by the {@link BnplThreeWayProof} - merchant leg,
 * customer leg, economic identity, ledger.
 *
 * <p><strong>One read-only {@code REPEATABLE READ} snapshot per reading</strong>, the caller's - refused otherwise, so the
 * proofs can never write and never read a torn state: a servicing step, a refund or a payout committing while the reading
 * runs is wholly invisible to it, and ten instances reading at one instant compute the same verdicts. A difference is an
 * alert ({@code finapp.bnpl.threeway.proof}, {@code finapp.bnpl.subledger.proof}, {@code finapp.bnpl.replay}) - nothing is
 * corrected. Holds no state between readings.
 */
@RequiredArgsConstructor
public final class BnplProofs {

    @NonNull private final BnplRecordStore records;
    @NonNull private final BnplSubledgerProof subledger;
    @NonNull private final BnplReplayProof replay;
    @NonNull private final BnplThreeWayProof threeWay;

    /** One obligation proven: the subledger verdict and the replay. */
    public record Proven(BnplSubledgerProof.Verdict subledger, BnplReplayProof.Replay replay) {

        public Proven {
            Objects.requireNonNull(subledger, "subledger");
            Objects.requireNonNull(replay, "replay");
        }
    }

    /** One reading: every obligation proven, oldest first, and every currency's three-way verdict. */
    public record Reading(List<Proven> obligations, List<BnplThreeWayProof.CurrencyVerdict> currencies) {

        public Reading {
            obligations = List.copyOf(obligations);
            currencies = List.copyOf(currencies);
        }

        /** Obligations whose subledger proof holds. */
        public long holding() {
            return obligations.stream().filter(proven -> proven.subledger().holds()).count();
        }

        /** Obligations whose subledger proof fails. */
        public long failing() {
            return obligations.size() - holding();
        }

        /** Obligations that replay to {@code verdict}. */
        public long replaying(BnplReplayProof.Verdict verdict) {
            return obligations.stream().filter(proven -> proven.replay().verdict() == verdict).count();
        }

        /** Currencies whose three-way proof holds ({@code true}) or fails. */
        public long currencies(boolean holds) {
            return currencies.stream().filter(currency -> currency.holds() == holds).count();
        }

        /** The failing obligations' verdicts - identifiers and arm names only. */
        public List<BnplSubledgerProof.Verdict> failures() {
            return obligations.stream().map(Proven::subledger).filter(verdict -> !verdict.holds()).toList();
        }

        /** The diverged obligations' replays - identifiers and kinds only. */
        public List<BnplReplayProof.Replay> divergences() {
            return obligations.stream().map(Proven::replay)
                    .filter(replayed -> replayed.verdict() == BnplReplayProof.Verdict.DIVERGED).toList();
        }
    }

    /** Every obligation and every currency, on {@code snapshot}. */
    public Reading prove(Connection snapshot) {
        requireSnapshot(snapshot);
        List<BnplRecord> read = new ArrayList<>();
        for (BnplObligationId obligation : records.obligationIds(snapshot)) {
            records.read(snapshot, obligation).ifPresent(read::add);
        }
        List<Proven> proven = new ArrayList<>();
        Map<BnplObligationId, BnplSubledgerProof.Verdict> verdicts = new LinkedHashMap<>();
        for (BnplRecord record : read) {
            Proven one = prove(record);
            proven.add(one);
            verdicts.put(record.id(), one.subledger());
        }
        return new Reading(proven, threeWay.prove(snapshot, read, verdicts));
    }

    /** One obligation, on {@code snapshot}; empty when no such obligation exists. */
    public Optional<Proven> prove(Connection snapshot, BnplObligationId obligation) {
        requireSnapshot(snapshot);
        return records.read(snapshot, Objects.requireNonNull(obligation, "obligation")).map(this::prove);
    }

    /** One obligation's record proven. */
    public Proven prove(BnplRecord record) {
        return new Proven(subledger.prove(record), replay.replay(record));
    }

    /** The proofs read one read-only {@code REPEATABLE READ} snapshot - never a connection that could write. */
    static void requireSnapshot(Connection snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            if (!snapshot.isReadOnly() || snapshot.getAutoCommit()
                    || snapshot.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException(
                        "the BNPL proofs read one read-only REPEATABLE READ snapshot - never a connection that could write");
            }
        } catch (SQLException failure) {
            throw new BnplStorageException("could not inspect the BNPL proofs' connection: " + failure.getSQLState());
        }
    }
}
