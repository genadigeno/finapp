package com.finapp.ledger;

import com.finapp.ledger.JdbcPositionBreakdown.AccountTerms;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryOverflowException;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The merchant API's payable read, bounded by a commit-order checkpoint (`P16-REM-001`; ADR-0133 decision 3, placed by the owner at
 * {@code THREAT_MODEL.md} section 6 item 55 (i)): the latest valid checkpoint's buckets, plus every line at or above its watermark,
 * folded by {@link BucketFold} exactly as {@link JdbcPositionBreakdown} folds the whole history. The answer is the whole fold's -
 * the same position, the same buckets, the same refusals - and its cost is the lines since the checkpoint, not the account's age.
 *
 * <h2>Why a watermark below {@code pg_snapshot_xmin} is final</h2>
 *
 * <p>A line carries its entry's top-level transaction ({@code journal_line.txid}, ledger {@code V038}, stamped by trigger as
 * {@code V034} stamps the entry's). Every transaction below a snapshot's {@code xmin} has finished, and every committed line of one is
 * visible to that snapshot; no transaction can start below it afterwards, since {@code xmin} is the oldest still running. So the
 * lines below a watermark <em>W</em> taken as the {@code xmin} of the snapshot that read them are every such line there will ever be,
 * and their fold never changes. No clock and no writer chooses <em>W</em>: it is read in the same statement as the lines.
 *
 * <h2>When a checkpoint is valid</h2>
 *
 * <p>When it was taken for this account under this build's classification rules ({@link #CLASSIFICATION_VERSION}), for the same
 * precedence and namespaces, and on this cluster: a logical restore restarts the transaction counter below the restored lines'
 * {@code txid}s ({@code V034}'s warning), so a checkpoint taken before it would read valid again once the counter passed its
 * watermark and miss every line written in between. It records {@code pg_control_system()}'s {@code system_identifier}, which a
 * restore into another cluster changes, and a read refuses any other and folds whole.
 *
 * <h2>Best-effort, never in the way</h2>
 *
 * <p>Reading a checkpoint and storing one each run in a savepoint: a failure of either - a read-only transaction, a refused grant, an
 * unreadable row, a connection in auto-commit - rolls back to it and the read answers from the whole fold, or answers without
 * storing. A store is {@code INSERT ... ON CONFLICT DO NOTHING}: ten instances reaching the same watermark write one row, and
 * different watermarks are different rows, each correct. Deleting every checkpoint changes no figure, only the read's cost.
 *
 * <h2>Never the proofs'</h2>
 *
 * <p>{@code ProductSourceProofs} and the BNPL three-way read {@link JdbcPositionBreakdown}, the whole history, so a checkpoint never
 * verifies itself; only the merchant API's payable query is wired with this class ({@code PositionCheckpointsAreTheApisAloneTest}),
 * and the census beside the proofs reads it {@link #readingOnly()} to compare it with the whole fold (`X-TSK-021`).
 */
@Slf4j
public final class CheckpointedPositionBreakdown implements PositionBreakdown<Connection> {

    /**
     * The classification rules' version: {@link JdbcPositionBreakdown#CLASSIFIED_LINES}, {@link JdbcPositionBreakdown#line},
     * {@link PositionBreakdown.Bucket#of} and {@link BucketFold#key}. A change to any of them bumps it, and so retires every stored
     * checkpoint; {@code PositionClassificationIsVersionedTest} refuses a change that does not.
     */
    public static final int CLASSIFICATION_VERSION = 1;

    /** The lines a read folds past its checkpoint, below its own watermark, before it stores a new one. */
    public static final int DEFAULT_THRESHOLD = 1_000;

    /** The latest valid checkpoint, and its buckets, from a statement that also names this cluster. */
    static final String LATEST =
            "SELECT c.id, c.lines, b.direction, b.counterparty, b.reconciliation_attributed, b.accounting_origin, b.namespace,"
                    + " b.amount_minor, b.currency, b.scale, c.watermark::text AS watermark"
                    + " FROM (SELECT id, lines, watermark FROM ledger.position_checkpoint"
                    + "        WHERE ledger_account_id = ? AND classification_version = ? AND precedence = ? AND namespaces = ?"
                    + "          AND cluster_identity = (SELECT system_identifier::text FROM pg_catalog.pg_control_system())"
                    + "          AND watermark <= pg_catalog.pg_snapshot_xmin(pg_catalog.pg_current_snapshot())"
                    + "        ORDER BY watermark DESC LIMIT 1) c"
                    + " LEFT JOIN ledger.position_checkpoint_bucket b ON b.checkpoint_id = c.id";

    /**
     * The lines at or above a watermark, each classified, each saying whether it lies below this statement's own snapshot's
     * {@code xmin}, which the statement also returns: the watermark a checkpoint taken from it would carry.
     */
    static final String SINCE =
            JdbcPositionBreakdown.CLASSIFIED_LINES
                    + ", line.txid < pg_catalog.pg_snapshot_xmin(pg_catalog.pg_current_snapshot()) AS settled,"
                    + " pg_catalog.pg_snapshot_xmin(pg_catalog.pg_current_snapshot())::text AS watermark"
                    + JdbcPositionBreakdown.OF_THE_ACCOUNT
                    + " AND line.txid >= ?::xid8";

    static final String STORE =
            "INSERT INTO ledger.position_checkpoint"
                    + " (ledger_account_id, classification_version, precedence, namespaces, cluster_identity, watermark, lines)"
                    + " VALUES (?, ?, ?, ?, (SELECT system_identifier::text FROM pg_catalog.pg_control_system()), ?::xid8, ?)"
                    + " ON CONFLICT ON CONSTRAINT position_checkpoint_one_per_watermark DO NOTHING"
                    + " RETURNING id";

    static final String STORE_BUCKET =
            "INSERT INTO ledger.position_checkpoint_bucket"
                    + " (checkpoint_id, bucket, direction, counterparty, reconciliation_attributed, accounting_origin, namespace,"
                    + " amount_minor, currency, scale)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** The position of the journal below every line: a read with no checkpoint folds from here, the whole history. */
    private static final String THE_BEGINNING = "0";

    private final int threshold;
    private final boolean stores;

    public CheckpointedPositionBreakdown() {
        this(DEFAULT_THRESHOLD);
    }

    /** @param threshold the lines past a checkpoint that make a read store a new one; positive */
    public CheckpointedPositionBreakdown(int threshold) {
        this(threshold, true);
    }

    private CheckpointedPositionBreakdown(int threshold, boolean stores) {
        if (threshold < 1) {
            throw new IllegalArgumentException("a checkpoint's threshold must be positive: " + threshold);
        }
        this.threshold = threshold;
        this.stores = stores;
    }

    /**
     * The read exactly as the merchant API makes it, from the latest valid checkpoint and the lines since, that never stores one
     * (`X-TSK-021`): the census among the source proofs reads with it on their {@code READ ONLY} snapshot, to compare it with the
     * whole fold, and a census that wrote what it judges would judge its own writing.
     */
    public static CheckpointedPositionBreakdown readingOnly() {
        return new CheckpointedPositionBreakdown(DEFAULT_THRESHOLD, false);
    }

    /** A stored checkpoint: the lines it folded, the watermark below which it folded them, and its buckets. */
    private record Checkpoint(String watermark, long lines, List<Bucket> buckets) {}

    @Override
    public Breakdown breakdown(
            Connection unitOfWork,
            LedgerAccountId account,
            List<AccountPurpose> precedence,
            List<String> namespaces) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        JdbcPositionBreakdown.checkedPrecedence(precedence);
        List<String> keyPrefixes = PositionBreakdown.checkedNamespaces(namespaces);
        try {
            AccountTerms terms = JdbcPositionBreakdown.accountTerms(unitOfWork, account);
            Optional<Checkpoint> from = latest(unitOfWork, account, precedence, keyPrefixes, terms.currency());
            BucketFold answer = new BucketFold(terms.currency());
            BucketFold next = new BucketFold(terms.currency());
            String watermark = null;
            long settled = 0;
            try {
                for (Bucket stored : from.map(Checkpoint::buckets).orElse(List.of())) {
                    answer.add(stored);
                    next.add(stored);
                }
                try (PreparedStatement select = unitOfWork.prepareStatement(SINCE)) {
                    JdbcPositionBreakdown.bind(unitOfWork, select, precedence, keyPrefixes, account);
                    select.setString(6, from.map(Checkpoint::watermark).orElse(THE_BEGINNING));
                    try (ResultSet row = select.executeQuery()) {
                        while (row.next()) {
                            Bucket line = JdbcPositionBreakdown.line(row, account, terms.currency());
                            answer.add(line);
                            watermark = row.getString("watermark");
                            if (row.getBoolean("settled")) {
                                next.add(line);
                                settled++;
                            }
                        }
                    }
                }
            } catch (ScaleMismatchException mixedScales) {
                throw JdbcPositionBreakdown.refused(account, "its history mixes scales within " + terms.currency().code()
                        + " (INV-MON-03)");
            } catch (MonetaryOverflowException overflow) {
                throw JdbcPositionBreakdown.refused(account, "a side's sum in " + terms.currency().code()
                        + " left the representable range (INV-MON-06)");
            }
            Breakdown breakdown = JdbcPositionBreakdown.settled(answer, account, terms);
            if (stores && settled >= threshold && watermark != null) {
                long lines = from.map(Checkpoint::lines).orElse(0L) + settled;
                store(unitOfWork, account, precedence, keyPrefixes, watermark, lines, next);
            }
            return breakdown;
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("breaking down the position of " + account + " from its checkpoint", failure));
        }
    }

    /** The latest valid checkpoint, or none: none stored, none valid here, or none readable - each folds the whole history. */
    private static Optional<Checkpoint> latest(
            Connection unitOfWork, LedgerAccountId account, List<AccountPurpose> precedence, List<String> namespaces,
            CurrencyCode currency) {
        Savepoint savepoint = savepoint(unitOfWork, account);
        if (savepoint == null) {
            return Optional.empty();
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(LATEST)) {
            select.setObject(1, account.value());
            select.setInt(2, CLASSIFICATION_VERSION);
            select.setArray(3, unitOfWork.createArrayOf("text", precedence.stream().map(AccountPurpose::name).toArray()));
            select.setArray(4, unitOfWork.createArrayOf("text", namespaces.toArray()));
            String watermark = null;
            long lines = 0;
            List<Bucket> buckets = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    watermark = row.getString("watermark");
                    lines = row.getLong("lines");
                    if (row.getString("direction") != null) {
                        buckets.add(stored(row, currency));
                    }
                }
            }
            unitOfWork.releaseSavepoint(savepoint);
            return watermark == null ? Optional.empty() : Optional.of(new Checkpoint(watermark, lines, buckets));
        } catch (SQLException | RuntimeException unreadable) {
            rollBack(unitOfWork, savepoint, account, "read", unreadable);
            return Optional.empty();
        }
    }

    /** One stored bucket, refused by its own constructor if it is not a bucket the classification can make. */
    private static Bucket stored(ResultSet row, CurrencyCode currency) throws SQLException {
        CurrencyCode stored = CurrencyCode.of(row.getString("currency").stripTrailing());
        if (!stored.equals(currency)) {
            throw new IllegalStateException("a checkpoint's bucket in " + stored.code() + " for an account in " + currency.code());
        }
        return new Bucket(
                Direction.valueOf(row.getString("direction")),
                Optional.ofNullable(row.getString("counterparty")).map(JdbcPositionBreakdown::counterparty),
                Money.ofPersisted(row.getLong("amount_minor"), stored, row.getShort("scale")),
                row.getBoolean("reconciliation_attributed"),
                Optional.ofNullable(row.getString("namespace")),
                row.getBoolean("accounting_origin"));
    }

    /** Stores the fold below {@code watermark} as a checkpoint, once whoever races it; a failure costs only the next read's work. */
    private static void store(
            Connection unitOfWork, LedgerAccountId account, List<AccountPurpose> precedence, List<String> namespaces,
            String watermark, long lines, BucketFold fold) {
        Savepoint savepoint = savepoint(unitOfWork, account);
        if (savepoint == null) {
            return;
        }
        try {
            Optional<UUID> stored = Optional.empty();
            try (PreparedStatement insert = unitOfWork.prepareStatement(STORE)) {
                insert.setObject(1, account.value());
                insert.setInt(2, CLASSIFICATION_VERSION);
                insert.setArray(3, unitOfWork.createArrayOf("text", precedence.stream().map(AccountPurpose::name).toArray()));
                insert.setArray(4, unitOfWork.createArrayOf("text", namespaces.toArray()));
                insert.setString(5, watermark);
                insert.setLong(6, lines);
                try (ResultSet row = insert.executeQuery()) {
                    if (row.next()) {
                        stored = Optional.of(row.getObject("id", UUID.class));
                    }
                }
            }
            if (stored.isPresent()) {
                try (PreparedStatement insert = unitOfWork.prepareStatement(STORE_BUCKET)) {
                    for (Map.Entry<String, Bucket> bucket : fold.buckets().entrySet()) {
                        Bucket total = bucket.getValue();
                        insert.setObject(1, stored.get());
                        insert.setString(2, bucket.getKey());
                        insert.setString(3, total.direction().name());
                        insert.setString(4, total.counterparty().map(c -> c.purpose().name() + "|" + c.direction().name()).orElse(null));
                        insert.setBoolean(5, total.reconciliationAttributed());
                        insert.setBoolean(6, total.accountingOrigin());
                        insert.setString(7, total.namespace().orElse(null));
                        insert.setLong(8, total.total().minorUnits());
                        insert.setString(9, total.total().currency().code());
                        insert.setShort(10, (short) total.total().scale());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            unitOfWork.releaseSavepoint(savepoint);
        } catch (SQLException | RuntimeException refused) {
            rollBack(unitOfWork, savepoint, account, "store", refused);
        }
    }

    private static Savepoint savepoint(Connection unitOfWork, LedgerAccountId account) {
        try {
            return unitOfWork.setSavepoint();
        } catch (SQLException noUnitOfWork) {
            log.debug("no savepoint for {}'s position checkpoint, so the whole history is folded: {}", account,
                    DatabaseFailure.describe("setting a savepoint", noUnitOfWork));
            return null;
        }
    }

    private static void rollBack(Connection unitOfWork, Savepoint savepoint, LedgerAccountId account, String what, Exception why) {
        log.warn("the position checkpoint of {} could not be {}; the read answers from the lines: {}", account,
                "read".equals(what) ? "read" : "stored",
                why instanceof SQLException failure ? DatabaseFailure.describe(what, failure) : why.getClass().getSimpleName());
        try {
            unitOfWork.rollback(savepoint);
        } catch (SQLException lost) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("returning to the savepoint before the position checkpoint of " + account, lost));
        }
    }
}
