package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The verification job's core (`P3-TSK-010`, ADR-0041 rule 2, {@code INV-BAL-02}): every
 * balance recomputed from postings through the derivation that defines it (`P3-TSK-008`) and
 * compared to the projection (`P3-TSK-009`). <strong>The comparison, not anybody's
 * confidence, is the evidence the projection is right.</strong>
 *
 * <h2>The first reader of {@code ledger.account_balance}, and what kind of number it returns</h2>
 *
 * <p>{@code BalanceProjection}'s javadoc requires every arriving reader to say what it
 * returns. This one returns <strong>verdicts and counts, never a balance</strong> — no public
 * method hands {@link Money} or a {@link DerivedBalance} out, pinned by
 * {@code BalanceProjectionTest} — so {@code INV-BAL-05} survives the read's arrival: nothing
 * here can become a decision's input.
 *
 * <h2>Safe while postings continue: the seq-bracketed read</h2>
 *
 * <p>Per account the protocol is read the row's {@code last_entry_seq}, count
 * {@code DISTINCT entry_id} over the account's lines, derive {@code Latest}, read the row
 * again. A domain-path entry commits <em>atomically</em> with its row's seq bump
 * (`P3-TSK-009`), so if the two seq readings agree, no entry committed mid-comparison and
 * every read in the bracket saw one applied set; if they disagree, the account was posted to
 * mid-comparison and the verdict is {@link Verdict#IN_FLIGHT} — tolerated by the watermark,
 * never by a time window ({@code PHASE_3_PLAN} §14.6), and settled by the next run. No lock
 * is taken anywhere: the verifier must never contend with the write path it audits.
 *
 * <h2>What counts as drift</h2>
 *
 * <p>With the bracket stable: a row absent while lines exist (the raw-SQL writer bypasses the
 * projection — `P3-TSK-009`'s recorded limit, detected here); {@code last_entry_seq} differing
 * from the applied-entry count; the settled numbers differing under {@code Money}'s
 * scale-including equality ({@code INV-MON-05}); or a history the derivation refuses while a
 * projection number stands — a number nobody can verify is not clean. <strong>Drift is
 * reported, never repaired</strong>: a ledger that corrected itself would destroy the
 * evidence of what went wrong (the §14.12 rule). Repair is a reasoned adjustment,
 * `P3-TSK-017`'s.
 */
@RequiredArgsConstructor
public final class ProjectionVerification {

    /** One account's answer. */
    public enum Verdict {
        /** Projection and derivation agree, and the watermark equals the applied count. */
        CLEAN,
        /** They disagree, or the projection cannot be verified at all. Alerting territory. */
        DRIFTING,
        /** An entry committed mid-comparison; nothing is known to be wrong. Next run settles. */
        IN_FLIGHT
    }

    /**
     * The sweep's tally. {@code driftingAccounts} is capped at {@link #REPORTED_ACCOUNTS} —
     * identifiers only, never an amount ({@code INV-AUD-02}), enough for an operator to know
     * where to look.
     */
    public record Report(
            long verified, long drifting, long inFlight, List<LedgerAccountId> driftingAccounts) {
        public Report {
            driftingAccounts = List.copyOf(driftingAccounts);
        }
    }

    /** Enough to point an investigation, small enough for a log line. */
    static final int REPORTED_ACCOUNTS = 20;

    private static final String LINE_TABLE = "ledger.journal_line";
    private static final String BALANCE_TABLE = "ledger.account_balance";

    /** The derivation is a seam so a test can interleave a commit mid-comparison. */
    @NonNull private final BalanceDerivation<Connection> derivation;

    /** Every account that has lines or a projection row, each given {@link #verdictOf}. */
    public Report verify(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        long verified = 0;
        long drifting = 0;
        long inFlight = 0;
        List<LedgerAccountId> driftingAccounts = new ArrayList<>();
        try {
            for (LedgerAccountId account : postedAccounts(unitOfWork)) {
                switch (verdictOf(unitOfWork, account)) {
                    case CLEAN -> verified++;
                    case IN_FLIGHT -> inFlight++;
                    case DRIFTING -> {
                        drifting++;
                        if (driftingAccounts.size() < REPORTED_ACCOUNTS) {
                            driftingAccounts.add(account);
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("sweeping the balance projection", failure));
        }
        return new Report(verified, drifting, inFlight, driftingAccounts);
    }

    /** One account's verdict, by the seq-bracketed protocol in the class doc. */
    public Verdict verdictOf(Connection unitOfWork, LedgerAccountId account) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        try {
            OptionalLong seqBefore = seqOf(unitOfWork, account);
            long appliedEntries = entriesOn(unitOfWork, account);

            Money derived;
            try {
                derived = derivation.derive(unitOfWork, account, AsOf.latest()).settled();
            } catch (UnderivableBalanceException underivable) {
                // A history the definition refuses. If nothing claims a number for it and it
                // has no lines to summarise, there is nothing to verify - but any projection
                // row, or any lines, make this an account whose stated or owed balance
                // CANNOT be verified, and unverifiable is not clean.
                derived = null;
            }

            Optional<Row> after = rowOf(unitOfWork, account);
            OptionalLong seqAfter =
                    after.map(row -> OptionalLong.of(row.lastEntrySeq()))
                            .orElse(OptionalLong.empty());
            if (!seqBefore.equals(seqAfter)) {
                return Verdict.IN_FLIGHT;
            }

            if (derived == null) {
                return after.isEmpty() && appliedEntries == 0
                        ? Verdict.CLEAN
                        : Verdict.DRIFTING;
            }
            if (after.isEmpty()) {
                // No lines and no row is simply an unposted account; lines with no row is
                // the projection bypassed.
                return appliedEntries == 0 ? Verdict.CLEAN : Verdict.DRIFTING;
            }
            Row row = after.get();
            if (row.lastEntrySeq() != appliedEntries) {
                return Verdict.DRIFTING;
            }
            if (!derived.equals(row.settled())) {
                return Verdict.DRIFTING;
            }
            return holdsAgree(row) ? Verdict.CLEAN : Verdict.DRIFTING;
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "verifying the projection of account " + account, failure));
        }
    }

    private static List<LedgerAccountId> postedAccounts(Connection unitOfWork)
            throws SQLException {
        List<LedgerAccountId> accounts = new ArrayList<>();
        try (PreparedStatement select =
                        unitOfWork.prepareStatement(
                                "SELECT DISTINCT ledger_account_id FROM " + LINE_TABLE
                                        + " UNION"
                                        + " SELECT ledger_account_id FROM " + BALANCE_TABLE);
                ResultSet row = select.executeQuery()) {
            while (row.next()) {
                accounts.add(
                        LedgerAccountId.of(row.getObject("ledger_account_id", UUID.class)));
            }
        }
        return accounts;
    }

    private static OptionalLong seqOf(Connection unitOfWork, LedgerAccountId account)
            throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT last_entry_seq FROM " + BALANCE_TABLE
                                + " WHERE ledger_account_id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? OptionalLong.of(row.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    private static long entriesOn(Connection unitOfWork, LedgerAccountId account)
            throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT COUNT(DISTINCT entry_id) FROM " + LINE_TABLE
                                + " WHERE ledger_account_id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /**
     * Whether {@code holds_minor} equals the fold of the {@code ACTIVE} hold rows —
     * `P3-TSK-015`'s owned remainder, landed by `P3-TSK-020`.
     *
     * <p>The fold goes through the kernel ({@code JournalEntry.sum}), never a SQL
     * {@code SUM} — `P3-TSK-008`'s argument, and the same one {@code HoldService} makes for
     * the availability decision itself. A fold the kernel refuses (a hold at a scale the
     * row's zero cannot adopt) makes the holds <em>unverifiable</em>, and unverifiable is
     * not clean.
     *
     * <p><strong>No watermark protects this comparison, and none is needed</strong>: the
     * row's {@code holds_minor} and its hold rows were read in one statement — one
     * snapshot — and a hold transaction updates both atomically under the account lock
     * (`P3-TSK-015`), so no interleaving can present a half-applied hold. (A hold row on an
     * account with no projection row is unreachable through the service, whose placement
     * writes {@code holds_minor} in the same transaction; a raw-SQL writer could fabricate
     * one — the bypassed-projection honest limit — and it surfaces when the account gains
     * its row.)
     */
    private static boolean holdsAgree(Row row) {
        Money expected = Money.ofPersisted(0, CurrencyCode.of(row.currency()), row.scale());
        try {
            for (Money hold : row.activeHolds()) {
                expected = JournalEntry.sum(expected, hold);
            }
        } catch (RuntimeException unverifiable) {
            return false;
        }
        Money held =
                Money.ofPersisted(row.holdsMinor(), CurrencyCode.of(row.currency()), row.scale());
        return held.equals(expected);
    }

    private static Optional<Row> rowOf(Connection unitOfWork, LedgerAccountId account)
            throws SQLException {
        // The projection row AND its ACTIVE holds in one statement: one snapshot, which is
        // what makes the holds comparison need no bracket (see holdsAgree).
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT b.posted_minor, b.holds_minor, b.scale, b.currency,"
                                + " b.last_entry_seq,"
                                + " h.amount_minor AS hold_minor, h.scale AS hold_scale,"
                                + " h.currency AS hold_currency"
                                + " FROM " + BALANCE_TABLE + " b"
                                + " LEFT JOIN ledger.hold h"
                                + " ON h.ledger_account_id = b.ledger_account_id"
                                + " AND h.status = 'ACTIVE'"
                                + " WHERE b.ledger_account_id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                Row projection = null;
                List<Money> activeHolds = new ArrayList<>();
                while (row.next()) {
                    if (projection == null) {
                        projection =
                                new Row(
                                        row.getLong("posted_minor"),
                                        row.getLong("holds_minor"),
                                        row.getShort("scale"),
                                        row.getString("currency").stripTrailing(),
                                        row.getLong("last_entry_seq"),
                                        activeHolds);
                    }
                    long holdMinor = row.getLong("hold_minor");
                    if (!row.wasNull()) {
                        activeHolds.add(
                                Money.ofPersisted(
                                        holdMinor,
                                        CurrencyCode.of(
                                                row.getString("hold_currency")
                                                        .stripTrailing()),
                                        row.getShort("hold_scale")));
                    }
                }
                return Optional.ofNullable(projection);
            }
        }
    }

    private record Row(
            long postedMinor,
            long holdsMinor,
            short scale,
            String currency,
            long lastEntrySeq,
            List<Money> activeHolds) {
        Money settled() {
            // ofPersisted, exactly: the stored number at its stored scale (INV-MON-05), so
            // the comparison is under Money's scale-including equality.
            return Money.ofPersisted(postedMinor, CurrencyCode.of(currency), scale);
        }
    }
}
