package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Park, unpark and the release primitive (`P8-TSK-010`, ADR-0070 §§2–3) — the only movers
 * of value between a counterparty's position and {@code SUSPENSE_UNMATCHED}.
 *
 * <p>One further opener records value that enters suspense from CASH, not from a position
 * (`P8-TSK-016`, ADR-0070 §2's {@code BANK_UNATTRIBUTED} row): a bank statement's line no
 * remittance pattern attributes. It posts nothing itself — the statement's recognition already
 * put the line in {@code SUSPENSE_UNMATCHED} against {@code CASH_AT_BANK} — so it only moves the
 * item {@code PENDING → PARKED} beside its freshly raised owner ({@link #bornParked}) and, after
 * the posting, opens the owned item carrying the recognition's entry
 * ({@link #openUnattributed}). No park row exists, and {@link #unpark} refuses the item: it
 * leaves only through a resolution's {@link #release}.
 *
 * <p><strong>{@code INV-REC-09} by the API's shape:</strong> every parked item names its
 * owning break, the command refuses a break that is missing or {@code RESOLVED}, and the
 * caller raised that break on this same connection — value enters suspense only in the
 * transaction that records its owner.
 *
 * <p><strong>The one lock order</strong> (`DISTRIBUTED_EXECUTION.md` §3's Phase 8 row):
 * the source's advisory namespace 4, break rows sorted by id, external item rows sorted by
 * id (the conditional {@code PENDING | UNMATCHED → PARKED} is the ten-way arbiter, with
 * {@code UNIQUE (external_item_id)} on the item beneath it), then — when one call posts
 * several entries — {@link PostingService#lockBalancesInOrder} over the union of the
 * accounts it will touch, then the postings. The {@code park} and {@code suspense_item}
 * rows are inserted AFTER the posting, carrying their entry id whole (the design's D1: the
 * posting stays the last CONTENDED write; these inserts are the transaction's own rows).
 *
 * <p>Parks aggregate per (position, value date) into one entry of at most four lines —
 * items of different settlement dates never share an entry — posting-dated the park row's
 * {@code decided_on} and value-dated the items' settlement date (a line without one uses
 * its business date, the honest available date — the design's D10). An unpark posts the
 * EXACT inverse — the item's frozen side, position and original value date — under a new
 * {@code recon-suspense:<parkId>} key.
 */
@RequiredArgsConstructor
public final class Suspense {

    static final String POSTING_KEY_PREFIX = "recon-suspense:";

    @NonNull private final PostingService posting;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final IdGenerator ids;

    /** One item to park: the remainder is the deciding leg's, never re-derived here. */
    public record ParkedItem(
            UUID externalItemId, UUID breakId, Money remainder, UUID positionAccountId) {

        public ParkedItem {
            Objects.requireNonNull(externalItemId, "externalItemId must not be null");
            Objects.requireNonNull(breakId, "breakId must not be null");
            Objects.requireNonNull(remainder, "remainder must not be null");
            Objects.requireNonNull(positionAccountId, "positionAccountId must not be null");
            if (remainder.minorUnits() <= 0) {
                throw new IllegalArgumentException("a park moves a positive remainder");
            }
        }
    }

    public record ParkCommand(
            UUID sourceId,
            LocalDate decidedOn,
            List<ParkedItem> items,
            Actor actor,
            Instant at,
            CorrelationId correlation) {

        public ParkCommand {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(decidedOn, "decidedOn must not be null");
            Objects.requireNonNull(items, "items must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (items.isEmpty()) {
                throw new IllegalArgumentException("a park command names its items");
            }
            items = List.copyOf(items);
        }
    }

    public record ParkedOutcome(
            UUID externalItemId, UUID suspenseItemId, UUID parkId, UUID entryId) {}

    /** {@code converged}: items another instance already parked — no second effect. */
    public record ParkResult(List<ParkedOutcome> parked, List<UUID> converged) {}

    public record Unparked(UUID parkId, UUID entryId) {}

    // ------------------------------------------------------- bank-unattributed

    /** One unattributed bank line's item and the {@code UNKNOWN_EXTERNAL} break that owns it. */
    public record Unattributed(UUID externalItemId, UUID breakId) {

        public Unattributed {
            Objects.requireNonNull(externalItemId, "externalItemId must not be null");
            Objects.requireNonNull(breakId, "breakId must not be null");
        }
    }

    /**
     * Moves each unattributed bank line's item {@code PENDING → PARKED} with its whole value,
     * in the acceptance transaction that birthed it and raised its owner — the item is this
     * transaction's own row, so the conditional edge has no racer; a miss is a defect and
     * throws. The owning break is verified like a park's ({@code INV-REC-09}'s domain half).
     */
    public void bornParked(
            Connection unitOfWork,
            List<Unattributed> items,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        items.stream()
                .sorted(Comparator.comparing(Unattributed::breakId))
                .forEach(item -> verifyOwner(unitOfWork, item.breakId(), item.externalItemId()));
        List<Unattributed> byItemId =
                items.stream().sorted(Comparator.comparing(Unattributed::externalItemId)).toList();
        for (Unattributed item : byItemId) {
            try (PreparedStatement update =
                    unitOfWork.prepareStatement(
                            "UPDATE reconciliation.external_item SET status = 'PARKED',"
                                    + " parked_minor = amount_minor, status_changed_at = ?"
                                    + " WHERE id = ? AND status = 'PENDING'"
                                    + " AND attributed_source_id IS NULL"
                                    + " AND line_type IN ('BANK_CREDIT', 'BANK_DEBIT')")) {
                update.setTimestamp(1, Timestamp.from(at));
                update.setObject(2, item.externalItemId());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "item " + item.externalItemId() + " is not an unattributed bank"
                                    + " line born in this acceptance");
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException(
                        "could not park the unattributed line", failure);
            }
            appendItemEvent(
                    unitOfWork, item.externalItemId(), "PENDING", "PARKED", actor, at,
                    correlation);
        }
    }

    /**
     * Opens the owned {@code BANK_UNATTRIBUTED} suspense item of every unattributed bank line
     * {@link #bornParked} moved in this run — AFTER the recognition posted, carrying its entry
     * whole ({@code suspense_item.entry_id}). Side from the direction, never netted; value the
     * line's whole amount; no park, no position. Returns the number opened.
     */
    public int openUnattributed(
            Connection unitOfWork,
            UUID runId,
            java.time.LocalDate openedOn,
            UUID entryId,
            Instant at,
            CorrelationId correlation) {
        record Row(UUID itemId, UUID breakId, String direction, long amount, String currency,
                int scale) {}
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT i.id, b.id AS break_id, i.direction, i.amount_minor,"
                                + " i.currency, i.scale"
                                + " FROM reconciliation.external_item i"
                                + " JOIN reconciliation.break b ON b.external_item_id = i.id"
                                + " AND b.type = 'UNKNOWN_EXTERNAL'"
                                + " AND b.cause = 'BANK_LINE_UNATTRIBUTED'"
                                + " AND b.status <> 'RESOLVED'"
                                + " WHERE i.run_id = ? AND i.status = 'PARKED'"
                                + " AND i.attributed_source_id IS NULL"
                                + " AND i.line_type IN ('BANK_CREDIT', 'BANK_DEBIT')"
                                + " ORDER BY i.id")) {
            read.setObject(1, runId);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(
                            new Row(
                                    row.getObject("id", UUID.class),
                                    row.getObject("break_id", UUID.class),
                                    row.getString("direction"),
                                    row.getLong("amount_minor"),
                                    row.getString("currency").trim(),
                                    row.getInt("scale")));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the unattributed lines", failure);
        }
        for (Row row : rows) {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO reconciliation.suspense_item (id, break_id,"
                                    + " external_item_id, origin, origin_ref, side,"
                                    + " amount_minor, currency, scale, released_minor, status,"
                                    + " opened_on, entry_id, park_id, position_account_id,"
                                    + " status_changed_at, correlation_id)"
                                    + " VALUES (?, ?, ?, 'BANK_UNATTRIBUTED', ?, ?, ?, ?, ?, 0,"
                                    + " 'OPEN', ?, ?, NULL, NULL, ?, ?)")) {
                insert.setObject(1, ids.next());
                insert.setObject(2, row.breakId());
                insert.setObject(3, row.itemId());
                insert.setString(4, row.itemId().toString());
                insert.setString(
                        5, SuspenseSide.of(ExpectationDirection.valueOf(row.direction())).name());
                insert.setLong(6, row.amount());
                insert.setString(7, row.currency());
                insert.setInt(8, row.scale());
                insert.setObject(9, openedOn);
                insert.setObject(10, entryId);
                insert.setTimestamp(11, Timestamp.from(at));
                insert.setString(12, correlation.value());
                insert.executeUpdate();
            } catch (SQLException failure) {
                throw new ReconciliationStorageException(
                        "could not open the unattributed suspense item", failure);
            }
        }
        return rows.size();
    }

    /** The owner exists, is open, parks, and stands on THIS item (its subject). */
    private static void verifyOwner(Connection unitOfWork, UUID breakId, UUID externalItemId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT status, type, external_item_id FROM reconciliation.break"
                                + " WHERE id = ?")) {
            read.setObject(1, breakId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()
                        || "RESOLVED".equals(row.getString("status"))
                        || !BreakType.valueOf(row.getString("type")).mayOwnSuspense()
                        || !externalItemId.equals(
                                row.getObject("external_item_id", UUID.class))) {
                    throw new IllegalStateException(
                            "an unattributed line parks owned by the open break raised in its"
                                    + " own transaction (INV-REC-09): break " + breakId);
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not verify the owning break", failure);
        }
    }

    // ----------------------------------------------------------------- park

    public ParkResult park(Connection unitOfWork, ParkCommand command) {
        advisorySourceLock(unitOfWork, command.sourceId());
        lockAndVerifyBreaks(unitOfWork, command.items());

        List<Winner> winners = new ArrayList<>();
        List<UUID> converged = new ArrayList<>();
        List<ParkedItem> byItemId =
                command.items().stream()
                        .sorted(Comparator.comparing(ParkedItem::externalItemId))
                        .toList();
        for (ParkedItem item : byItemId) {
            claimItem(unitOfWork, command, item)
                    .ifPresentOrElse(winners::add, () -> converged.add(item.externalItemId()));
        }
        if (winners.isEmpty()) {
            return new ParkResult(List.of(), List.copyOf(converged));
        }

        // One entry per (position, value date); items of different settlement dates never
        // share an entry (ADR-0070 section 2).
        Map<GroupKey, List<Winner>> groups = new TreeMap<>();
        for (Winner winner : winners) {
            groups.computeIfAbsent(
                            new GroupKey(
                                    winner.item().positionAccountId(), winner.valueDate()),
                            key -> new ArrayList<>())
                    .add(winner);
        }
        if (groups.size() > 1) {
            // Several entries over the platform's shared rows: pre-lock the union in the
            // projection's own order before the first posting (the multi-entry rule).
            TreeSet<UUID> union = new TreeSet<>();
            for (Map.Entry<GroupKey, List<Winner>> group : groups.entrySet()) {
                union.add(group.getKey().positionAccountId());
                union.add(
                        suspenseAccount(unitOfWork, group.getValue().get(0).currency())
                                .value());
            }
            posting.lockBalancesInOrder(
                    unitOfWork, union.stream().map(LedgerAccountId::of).toList());
        }

        List<ParkedOutcome> parked = new ArrayList<>();
        for (Map.Entry<GroupKey, List<Winner>> group : groups.entrySet()) {
            parked.addAll(postGroup(unitOfWork, command, group.getKey(), group.getValue()));
        }
        return new ParkResult(List.copyOf(parked), List.copyOf(converged));
    }

    private record GroupKey(UUID positionAccountId, LocalDate valueDate)
            implements Comparable<GroupKey> {

        @Override
        public int compareTo(GroupKey other) {
            int byAccount = positionAccountId.compareTo(other.positionAccountId);
            return byAccount != 0 ? byAccount : valueDate.compareTo(other.valueDate);
        }
    }

    private record Winner(
            ParkedItem item,
            ExpectationDirection direction,
            CurrencyCode currency,
            int scale,
            LocalDate valueDate) {}

    private void advisorySourceLock(Connection unitOfWork, UUID sourceId) {
        try (PreparedStatement lock =
                unitOfWork.prepareStatement(
                        "SELECT pg_advisory_xact_lock(4, hashtext(?::text))")) {
            lock.setObject(1, sourceId);
            lock.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not take the source's advisory lock", failure);
        }
    }

    /**
     * Break rows first, sorted by id (the §3 order): each must exist and not be
     * {@code RESOLVED}, and its type must park — the domain's half of {@code INV-REC-09};
     * `V004`'s owner trigger is the every-writer half beneath it.
     */
    private void lockAndVerifyBreaks(Connection unitOfWork, List<ParkedItem> items) {
        TreeSet<UUID> breakIds = new TreeSet<>();
        for (ParkedItem item : items) {
            breakIds.add(item.breakId());
        }
        for (UUID breakId : breakIds) {
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT status, type FROM reconciliation.break WHERE id = ?"
                                    + " FOR UPDATE")) {
                read.setObject(1, breakId);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        throw new IllegalStateException(
                                "a park is owned by the break raised in its own transaction"
                                        + " (INV-REC-09): break " + breakId + " does not"
                                        + " exist");
                    }
                    if ("RESOLVED".equals(row.getString("status"))) {
                        throw new IllegalStateException(
                                "a resolved break cannot take new value (INV-REC-09):"
                                        + " break " + breakId);
                    }
                    if (!BreakType.valueOf(row.getString("type")).mayOwnSuspense()) {
                        throw new IllegalStateException(
                                "a " + row.getString("type") + " break never owns suspense"
                                        + " (ADR-0070 section 2)");
                    }
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException(
                        "could not lock the owning break", failure);
            }
        }
    }

    /** The ten-way arbiter: the item's conditional transition, judged on the locked row. */
    private Optional<Winner> claimItem(
            Connection unitOfWork, ParkCommand command, ParkedItem item) {
        String status;
        String direction;
        long amount;
        long allocated;
        long parkedAlready;
        long offset;
        String currency;
        int scale;
        LocalDate valueDate;
        UUID rowSource;
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT status, direction, amount_minor, allocated_minor,"
                                + " parked_minor, offset_minor, currency, scale, source_id,"
                                + " COALESCE(settlement_date, business_date) AS value_date"
                                + " FROM reconciliation.external_item WHERE id = ?"
                                + " FOR UPDATE")) {
            read.setObject(1, item.externalItemId());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException(
                            "no external item " + item.externalItemId() + " exists");
                }
                status = row.getString("status");
                direction = row.getString("direction");
                amount = row.getLong("amount_minor");
                allocated = row.getLong("allocated_minor");
                parkedAlready = row.getLong("parked_minor");
                offset = row.getLong("offset_minor");
                currency = row.getString("currency").trim();
                scale = row.getInt("scale");
                rowSource = row.getObject("source_id", UUID.class);
                valueDate = row.getObject("value_date", LocalDate.class);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not claim the item", failure);
        }
        if (!command.sourceId().equals(rowSource)) {
            throw new IllegalArgumentException(
                    "a park command is one source's: item " + item.externalItemId()
                            + " belongs to " + rowSource);
        }
        if (!(("PENDING".equals(status) || "UNMATCHED".equals(status))
                && parkedAlready == 0)) {
            return Optional.empty(); // Another instance parked or disposed it: converge.
        }
        if (!item.remainder().currency().code().equals(currency)
                || item.remainder().scale() != scale) {
            throw new IllegalArgumentException(
                    "a park is in the item's own currency (INV-MON-04)");
        }
        if (item.remainder().minorUnits() > amount - allocated - offset) {
            throw new IllegalArgumentException(
                    "a park moves at most the unallocated remainder");
        }
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET status = 'PARKED',"
                                + " parked_minor = ?, status_changed_at = ? WHERE id = ?")) {
            update.setLong(1, item.remainder().minorUnits());
            update.setTimestamp(2, Timestamp.from(command.at()));
            update.setObject(3, item.externalItemId());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not park the item", failure);
        }
        appendItemEvent(
                unitOfWork, item.externalItemId(), status, "PARKED", command.actor(),
                command.at(), command.correlation());
        return Optional.of(
                new Winner(
                        item,
                        ExpectationDirection.valueOf(direction),
                        CurrencyCode.of(currency),
                        scale,
                        valueDate));
    }

    private List<ParkedOutcome> postGroup(
            Connection unitOfWork, ParkCommand command, GroupKey key, List<Winner> winners) {
        CurrencyCode currency = winners.get(0).currency();
        int scale = winners.get(0).scale();
        Money inbound = Money.ofPersisted(0, currency, scale);
        Money outbound = Money.ofPersisted(0, currency, scale);
        for (Winner winner : winners) {
            if (!winner.currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "one park entry is one currency (INV-MON-04)");
            }
            if (winner.direction() == ExpectationDirection.INBOUND) {
                inbound = inbound.plus(winner.item().remainder());
            } else {
                outbound = outbound.plus(winner.item().remainder());
            }
        }
        LedgerAccountId position = LedgerAccountId.of(key.positionAccountId());
        LedgerAccountId suspense = suspenseAccount(unitOfWork, currency);

        List<JournalLine> lines = new ArrayList<>(4);
        if (inbound.minorUnits() > 0) {
            lines.add(new JournalLine(position, Direction.DEBIT, inbound));
            lines.add(new JournalLine(suspense, Direction.CREDIT, inbound));
        }
        if (outbound.minorUnits() > 0) {
            lines.add(new JournalLine(suspense, Direction.DEBIT, outbound));
            lines.add(new JournalLine(position, Direction.CREDIT, outbound));
        }

        UUID parkId = ids.next();
        PostingResult posted =
                posting.post(
                        unitOfWork,
                        new PostingCommand(
                                POSTING_KEY_PREFIX + parkId,
                                command.decidedOn(),
                                key.valueDate(),
                                parkId.toString(),
                                lines));
        UUID entryId = posted.entryId().value();
        insertPark(
                unitOfWork, parkId, command.sourceId(), ParkKind.PARK,
                key.positionAccountId(), currency, command.decidedOn(), key.valueDate(),
                entryId, command.actor(), command.at(), command.correlation());

        List<ParkedOutcome> outcomes = new ArrayList<>(winners.size());
        for (Winner winner : winners) {
            UUID suspenseItemId = ids.next();
            insertSuspenseItem(unitOfWork, suspenseItemId, winner, parkId, entryId, command);
            outcomes.add(
                    new ParkedOutcome(
                            winner.item().externalItemId(), suspenseItemId, parkId,
                            entryId));
        }
        return outcomes;
    }

    // ----------------------------------------------------------------- unpark

    /**
     * The park's exact inverse for {@code amount} of the item — a partial unpark leaves it
     * {@code PARTIALLY_RELEASED} and its break open over the remainder. The position
     * identity's other half — the item back into the fold by allocation — is the caller's,
     * in this same transaction (`P8-TSK-013`'s rematch; the suite's simulated pairing).
     */
    public Unparked unpark(
            Connection unitOfWork,
            UUID suspenseItemId,
            Money amount,
            ReleaseCause cause,
            String causeRef,
            LocalDate decidedOn,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        Objects.requireNonNull(suspenseItemId, "suspenseItemId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        Objects.requireNonNull(causeRef, "causeRef must not be null");
        Objects.requireNonNull(decidedOn, "decidedOn must not be null");
        if (cause != ReleaseCause.UNPARK
                && cause != ReleaseCause.CORRECTION_OFFSET
                && cause != ReleaseCause.REPUDIATION) {
            // The inverse-posting exits only; RESOLUTION and OFFSET_SUSPENSE release
            // through their own paths (-015). A repudiation's unpark returns a parked
            // value still held to its position (-023, ADR-0070 section 10).
            throw new IllegalArgumentException(
                    "an unpark's cause is UNPARK, CORRECTION_OFFSET or REPUDIATION"
                            + " (ADR-0070 sections 3 and 10)");
        }
        if (amount.minorUnits() <= 0) {
            throw new IllegalArgumentException("an unpark moves a positive amount");
        }

        ItemRow item = itemRow(unitOfWork, suspenseItemId);
        if (item.origin() != SuspenseOrigin.RECON_PARK) {
            throw new IllegalStateException(
                    "only a RECON_PARK item has a park to invert (ADR-0070 section 3):"
                            + " a " + item.origin() + " item leaves by its own exits");
        }
        advisorySourceLock(unitOfWork, breakSource(unitOfWork, item.breakId()));
        lockBreakOpen(unitOfWork, item.breakId());
        ItemRow locked = lockItem(unitOfWork, suspenseItemId);
        if (!amount.currency().equals(locked.currency())
                || amount.scale() != locked.scale()) {
            throw new IllegalArgumentException(
                    "an unpark is in the item's own currency (INV-MON-04)");
        }
        if (amount.minorUnits() > locked.amountMinor() - locked.releasedMinor()) {
            throw new IllegalArgumentException(
                    "an unpark releases at most the unreleased remainder");
        }

        LocalDate valueDate = parkValueDate(unitOfWork, locked.parkId());
        LedgerAccountId position = LedgerAccountId.of(locked.positionAccountId());
        LedgerAccountId suspense = suspenseAccount(unitOfWork, locked.currency());
        List<JournalLine> lines =
                locked.side() == SuspenseSide.CREDIT
                        ? List.of(
                                new JournalLine(suspense, Direction.DEBIT, amount),
                                new JournalLine(position, Direction.CREDIT, amount))
                        : List.of(
                                new JournalLine(position, Direction.DEBIT, amount),
                                new JournalLine(suspense, Direction.CREDIT, amount));

        UUID parkId = ids.next();
        PostingResult posted =
                posting.post(
                        unitOfWork,
                        new PostingCommand(
                                POSTING_KEY_PREFIX + parkId,
                                decidedOn,
                                valueDate,
                                parkId.toString(),
                                lines));
        UUID entryId = posted.entryId().value();
        insertPark(
                unitOfWork, parkId, breakSource(unitOfWork, item.breakId()), ParkKind.UNPARK,
                locked.positionAccountId(), locked.currency(), decidedOn, valueDate, entryId,
                actor, at, correlation);
        releaseLocked(
                unitOfWork, locked, amount.minorUnits(), cause, causeRef,
                Optional.of(parkId), actor, at, correlation);
        return new Unparked(parkId, entryId);
    }

    // ----------------------------------------------------------------- repudiation

    /**
     * A repudiation's answer to a parked value a resolution already released
     * (`P8-TSK-023`, ADR-0070 §10): the park's EXACT inverse for {@code amount} — the value
     * returned to the position as if it were still parked — but no release, because the
     * item has nothing left to release. Its suspense line lands on the side opposite the
     * item, and the caller opens the {@code REPUDIATION} item that owns it
     * ({@link #openRepudiation}) with a new owner, in this same transaction
     * ({@code INV-REC-09}). The caller holds the source's advisory and the item's row.
     */
    public Unparked repostReleased(
            Connection unitOfWork,
            UUID suspenseItemId,
            long amountMinor,
            LocalDate decidedOn,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        ItemRow item = lockItem(unitOfWork, suspenseItemId);
        if (item.origin() != SuspenseOrigin.RECON_PARK) {
            throw new IllegalStateException(
                    "only a RECON_PARK item has a park to invert (ADR-0070 section 3)");
        }
        if (amountMinor <= 0 || amountMinor > item.releasedMinor()) {
            throw new IllegalArgumentException(
                    "a repost answers a positive part of what was already released");
        }
        Money amount = Money.ofPersisted(amountMinor, item.currency(), item.scale());
        LocalDate valueDate = parkValueDate(unitOfWork, item.parkId());
        LedgerAccountId position = LedgerAccountId.of(item.positionAccountId());
        LedgerAccountId suspense = suspenseAccount(unitOfWork, item.currency());
        List<JournalLine> lines =
                item.side() == SuspenseSide.CREDIT
                        ? List.of(
                                new JournalLine(suspense, Direction.DEBIT, amount),
                                new JournalLine(position, Direction.CREDIT, amount))
                        : List.of(
                                new JournalLine(position, Direction.DEBIT, amount),
                                new JournalLine(suspense, Direction.CREDIT, amount));
        UUID parkId = ids.next();
        PostingResult posted =
                posting.post(
                        unitOfWork,
                        new PostingCommand(
                                POSTING_KEY_PREFIX + parkId,
                                decidedOn,
                                valueDate,
                                parkId.toString(),
                                lines));
        UUID entryId = posted.entryId().value();
        insertPark(
                unitOfWork, parkId, breakSource(unitOfWork, item.breakId()), ParkKind.UNPARK,
                item.positionAccountId(), item.currency(), decidedOn, valueDate, entryId,
                actor, at, correlation);
        return new Unparked(parkId, entryId);
    }

    /** One {@code REPUDIATION} item: the line a repudiation's posting put in suspense. */
    public record Repudiated(
            UUID suspenseItemId,
            UUID breakId,
            UUID releasedItemId,
            SuspenseSide side,
            Money amount,
            LocalDate openedOn,
            UUID entryId) {

        public Repudiated {
            Objects.requireNonNull(suspenseItemId, "suspenseItemId must not be null");
            Objects.requireNonNull(breakId, "breakId must not be null");
            Objects.requireNonNull(releasedItemId, "releasedItemId must not be null");
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(openedOn, "openedOn must not be null");
            Objects.requireNonNull(entryId, "entryId must not be null");
            if (amount.minorUnits() <= 0) {
                throw new IllegalArgumentException("a suspense item holds a positive value");
            }
        }
    }

    /**
     * The fourth opener (ADR-0070 §2, `V013`): the item a repudiation's posting carries,
     * owned by the {@code PROCESSING_ERROR} break the caller raised on this connection,
     * its {@code origin_ref} the released item — {@code UNIQUE (origin_ref)} makes it once.
     * No external item, no park, no position; the entry is the line's.
     */
    public void openRepudiation(
            Connection unitOfWork, Repudiated item, Instant at, CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.suspense_item (id, break_id,"
                                + " external_item_id, origin, origin_ref, side,"
                                + " amount_minor, currency, scale, released_minor, status,"
                                + " opened_on, entry_id, park_id, position_account_id,"
                                + " status_changed_at, correlation_id)"
                                + " VALUES (?, ?, NULL, 'REPUDIATION', ?, ?, ?, ?, ?, 0,"
                                + " 'OPEN', ?, ?, NULL, NULL, ?, ?)")) {
            insert.setObject(1, item.suspenseItemId());
            insert.setObject(2, item.breakId());
            insert.setString(3, item.releasedItemId().toString());
            insert.setString(4, item.side().name());
            insert.setLong(5, item.amount().minorUnits());
            insert.setString(6, item.amount().currency().code());
            insert.setInt(7, item.amount().scale());
            insert.setObject(8, item.openedOn());
            insert.setObject(9, item.entryId());
            insert.setTimestamp(10, Timestamp.from(at));
            insert.setString(11, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not open the repudiation's suspense item", failure);
        }
    }

    /**
     * The multi-entry pre-lock, for a caller whose one transaction will drive SEVERAL
     * postings through this class (`P8-TSK-012`: a chunk's parks beside its offset
     * unparks): the union of the projection rows, in the projection's own order, before
     * the first posting — {@code park}'s internal pre-lock covers only its own groups.
     */
    public void lockBalancesInOrder(Connection unitOfWork, List<UUID> accountIds) {
        posting.lockBalancesInOrder(
                unitOfWork,
                accountIds.stream().sorted().map(LedgerAccountId::of).toList());
    }

    // ----------------------------------------------------------------- release

    /**
     * The primitive the exits share (ADR-0070 §3): conditional under the item's lock,
     * appended to {@code suspense_release}, the owning break's {@code residual_version}
     * bumped — a proposal frozen against the old remainder is refused at approval.
     * Posts nothing: an unpark posts its inverse first, an offset needs no posting.
     */
    public void release(
            Connection unitOfWork,
            UUID suspenseItemId,
            long amountMinor,
            ReleaseCause cause,
            String causeRef,
            Optional<UUID> parkId,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        ItemRow item = itemRow(unitOfWork, suspenseItemId);
        lockBreakOpen(unitOfWork, item.breakId());
        ItemRow locked = lockItem(unitOfWork, suspenseItemId);
        releaseLocked(
                unitOfWork, locked, amountMinor, cause, causeRef, parkId, actor, at,
                correlation);
    }

    private void releaseLocked(
            Connection unitOfWork,
            ItemRow locked,
            long amountMinor,
            ReleaseCause cause,
            String causeRef,
            Optional<UUID> parkId,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        if (amountMinor <= 0 || amountMinor > locked.amountMinor() - locked.releasedMinor()) {
            throw new IllegalArgumentException(
                    "a release moves a positive amount within the unreleased remainder");
        }
        long newReleased = locked.releasedMinor() + amountMinor;
        String newStatus =
                newReleased == locked.amountMinor() ? "RELEASED" : "PARTIALLY_RELEASED";
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.suspense_item SET released_minor = ?,"
                                + " status = ?, status_changed_at = ? WHERE id = ?")) {
            update.setLong(1, newReleased);
            update.setString(2, newStatus);
            update.setTimestamp(3, Timestamp.from(at));
            update.setObject(4, locked.id());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not release the item", failure);
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.suspense_release (item_id, amount_minor,"
                                + " park_id, cause, cause_ref, actor, actor_type,"
                                + " released_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, locked.id());
            insert.setLong(2, amountMinor);
            insert.setObject(3, parkId.orElse(null));
            insert.setString(4, cause.name());
            insert.setString(5, causeRef);
            insert.setString(6, actor.id());
            insert.setString(7, actor.type().name());
            insert.setTimestamp(8, Timestamp.from(at));
            insert.setString(9, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the release", failure);
        }
        try (PreparedStatement bump =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET residual_version ="
                                + " residual_version + 1 WHERE id = ?")) {
            bump.setObject(1, locked.breakId());
            bump.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not bump the residual version", failure);
        }
    }

    // ----------------------------------------------------------------- plumbing

    private record ItemRow(
            UUID id,
            UUID breakId,
            SuspenseOrigin origin,
            SuspenseSide side,
            long amountMinor,
            long releasedMinor,
            CurrencyCode currency,
            int scale,
            UUID parkId,
            UUID positionAccountId) {}

    private ItemRow itemRow(Connection unitOfWork, UUID suspenseItemId) {
        return readItem(unitOfWork, suspenseItemId, "");
    }

    private ItemRow lockItem(Connection unitOfWork, UUID suspenseItemId) {
        return readItem(unitOfWork, suspenseItemId, " FOR UPDATE");
    }

    private ItemRow readItem(Connection unitOfWork, UUID suspenseItemId, String lock) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT break_id, origin, side, amount_minor, released_minor,"
                                + " currency, scale, park_id, position_account_id"
                                + " FROM reconciliation.suspense_item WHERE id = ?" + lock)) {
            read.setObject(1, suspenseItemId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException(
                            "no suspense item " + suspenseItemId + " exists");
                }
                return new ItemRow(
                        suspenseItemId,
                        row.getObject("break_id", UUID.class),
                        SuspenseOrigin.valueOf(row.getString("origin")),
                        SuspenseSide.valueOf(row.getString("side")),
                        row.getLong("amount_minor"),
                        row.getLong("released_minor"),
                        CurrencyCode.of(row.getString("currency").trim()),
                        row.getInt("scale"),
                        row.getObject("park_id", UUID.class),
                        row.getObject("position_account_id", UUID.class));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the suspense item", failure);
        }
    }

    private void lockBreakOpen(Connection unitOfWork, UUID breakId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT status FROM reconciliation.break WHERE id = ? FOR UPDATE")) {
            read.setObject(1, breakId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "a suspense item's break exists (INV-REC-09): " + breakId);
                }
                if ("RESOLVED".equals(row.getString("status"))) {
                    throw new IllegalStateException(
                            "a resolved break's item takes no release through this"
                                    + " primitive: the case continues on its successor");
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not lock the owning break", failure);
        }
    }

    private UUID breakSource(Connection unitOfWork, UUID breakId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT source_id FROM reconciliation.break WHERE id = ?")) {
            read.setObject(1, breakId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "a suspense item's break exists (INV-REC-09): " + breakId);
                }
                return row.getObject("source_id", UUID.class);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the break's source", failure);
        }
    }

    private LocalDate parkValueDate(Connection unitOfWork, UUID parkId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT value_date FROM reconciliation.park WHERE id = ?")) {
            read.setObject(1, parkId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "a RECON_PARK item names its park (V004's CHECK): " + parkId);
                }
                return row.getObject("value_date", LocalDate.class);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the park's value date", failure);
        }
    }

    private LedgerAccountId suspenseAccount(Connection unitOfWork, CurrencyCode currency) {
        return accounts
                .findOperational(unitOfWork, AccountPurpose.SUSPENSE_UNMATCHED, currency)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the chart seeds SUSPENSE_UNMATCHED per currency"))
                .id();
    }

    private void insertPark(
            Connection unitOfWork,
            UUID parkId,
            UUID sourceId,
            ParkKind kind,
            UUID positionAccountId,
            CurrencyCode currency,
            LocalDate decidedOn,
            LocalDate valueDate,
            UUID entryId,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.park (id, source_id, kind,"
                                + " position_account_id, currency, decided_on, value_date,"
                                + " journal_entry_id, actor, actor_type, created_at,"
                                + " correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, parkId);
            insert.setObject(2, sourceId);
            insert.setString(3, kind.name());
            insert.setObject(4, positionAccountId);
            insert.setString(5, currency.code());
            insert.setObject(6, decidedOn);
            insert.setObject(7, valueDate);
            insert.setObject(8, entryId);
            insert.setString(9, actor.id());
            insert.setString(10, actor.type().name());
            insert.setTimestamp(11, Timestamp.from(at));
            insert.setString(12, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not record the park", failure);
        }
    }

    private void insertSuspenseItem(
            Connection unitOfWork,
            UUID suspenseItemId,
            Winner winner,
            UUID parkId,
            UUID entryId,
            ParkCommand command) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.suspense_item (id, break_id,"
                                + " external_item_id, origin, origin_ref, side,"
                                + " amount_minor, currency, scale, released_minor, status,"
                                + " opened_on, entry_id, park_id, position_account_id,"
                                + " status_changed_at, correlation_id)"
                                + " VALUES (?, ?, ?, 'RECON_PARK', ?, ?, ?, ?, ?, 0, 'OPEN',"
                                + " ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, suspenseItemId);
            insert.setObject(2, winner.item().breakId());
            insert.setObject(3, winner.item().externalItemId());
            insert.setString(4, winner.item().externalItemId().toString());
            insert.setString(5, SuspenseSide.of(winner.direction()).name());
            insert.setLong(6, winner.item().remainder().minorUnits());
            insert.setString(7, winner.currency().code());
            insert.setInt(8, winner.scale());
            insert.setObject(9, command.decidedOn());
            insert.setObject(10, entryId);
            insert.setObject(11, parkId);
            insert.setObject(12, winner.item().positionAccountId());
            insert.setTimestamp(13, Timestamp.from(command.at()));
            insert.setString(14, command.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not open the suspense item", failure);
        }
    }

    private void appendItemEvent(
            Connection unitOfWork,
            UUID itemId,
            String fromStatus,
            String toStatus,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.external_item_event (item_id,"
                                + " from_status, to_status, actor, actor_type, reason,"
                                + " occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, NULL, ?, ?)")) {
            insert.setObject(1, itemId);
            insert.setString(2, fromStatus);
            insert.setString(3, toStatus);
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.setString(7, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the item's history", failure);
        }
    }

}
