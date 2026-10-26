package com.finapp.app.risk;

import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ReversalService;
import com.finapp.risk.MonitoredEntry;
import com.finapp.risk.MonitoredLedger;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link MonitoredLedger} over the ledger's own stores (`P13-TSK-019`; ADR-0111 decision 1; {@code INV-RSK-09},
 * {@code INV-RSK-02}): the journal walked by {@code JournalEntryStore.entriesAfter} - by id, within the settle horizon
 * on the database clock - and each line's account read by {@code LedgerAccountStore.findAllById}, one statement per
 * page, so the subject and the counterparty come from the journal and its chart alone and never from a business
 * module's table. The ledger's own scope prefix ({@code ledger.post:}, {@code ledger.reverse:}, {@code ledger.adjust:})
 * is stripped from {@code idempotency_scope} to yield the command key whose namespace classifies the entry; the
 * sequence is the entry id's minting millisecond (ADR-0013).
 *
 * <p>Every statement this adapter causes is a {@code SELECT} under the application role's existing grant: it locks no
 * journal row, writes nothing of the ledger's and reads no balance - the "read-only principal" the task asks for, as
 * the platform's single application role can provide it ({@code DESIGN-019} section 17).
 *
 * <p>{@link #entryIds} (`P13-TSK-023`, the monitoring census's side of the journal) is this adapter's own statement over
 * {@code ledger.journal_entry}'s primary key - ids only, the {@code LendingCreditExposure} precedent of an {@code app}
 * read over another schema - because the ledger's store has no range read and a census is no reason to grow the
 * ledger's port.
 */
public final class LedgerMonitoredLedger implements MonitoredLedger {

    /** One day of the journal by sequence: the entry ids of a primary-key range, ascending. */
    static final String ENTRY_IDS = "SELECT id FROM ledger.journal_entry WHERE id >= ? AND id < ? ORDER BY id";

    private static final String POSTING_PREFIX = PostingService.IDEMPOTENCY_SCOPE + ":";
    private static final String REVERSAL_PREFIX = ReversalService.IDEMPOTENCY_SCOPE + ":";
    private static final String ADJUSTMENT_PREFIX = AdjustmentService.IDEMPOTENCY_SCOPE + ":";

    private final JournalEntryStore<Connection> journal;
    private final LedgerAccountStore<Connection> accounts;

    public LedgerMonitoredLedger(JournalEntryStore<Connection> journal, LedgerAccountStore<Connection> accounts) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override
    public List<MonitoredEntry> entriesAfter(Connection unitOfWork, UUID afterEntryId, Duration settleHorizon,
            int limit) {
        List<JournalEntryStore.PostedEntry> page = journal.entriesAfter(unitOfWork, afterEntryId, settleHorizon, limit);
        if (page.isEmpty()) {
            return List.of();
        }
        Set<LedgerAccountId> accountIds = new LinkedHashSet<>();
        for (JournalEntryStore.PostedEntry posted : page) {
            for (JournalLine line : posted.entry().lines()) {
                accountIds.add(line.account());
            }
        }
        Map<LedgerAccountId, LedgerAccount> chart = new HashMap<>();
        for (LedgerAccount account : accounts.findAllById(unitOfWork, accountIds)) {
            chart.put(account.id(), account);
        }
        List<MonitoredEntry> entries = new ArrayList<>(page.size());
        for (JournalEntryStore.PostedEntry posted : page) {
            List<MonitoredEntry.Line> lines = new ArrayList<>(posted.entry().lines().size());
            for (JournalLine line : posted.entry().lines()) {
                LedgerAccount account = chart.get(line.account());
                if (account == null) {
                    // The line's account is a real foreign key (ledger V004): its absence is a defect, never a skip.
                    throw new IllegalStateException("a journal line names an account the chart does not hold");
                }
                lines.add(new MonitoredEntry.Line(line.account().value(), account.ownerKind().name(),
                        account.ownerRef(), account.purpose().name(),
                        line.direction() == Direction.DEBIT ? MonitoredEntry.Side.DEBIT : MonitoredEntry.Side.CREDIT,
                        line.amount().minorUnits(), line.amount().scale(), line.amount().currency()));
            }
            UUID id = posted.entry().id().value();
            entries.add(new MonitoredEntry(id, posted.entry().id().createdAt().toEpochMilli(),
                    switch (posted.attribution().entryType()) {
                        case POSTING -> MonitoredEntry.EntryType.POSTING;
                        case REVERSAL -> MonitoredEntry.EntryType.REVERSAL;
                        case ADJUSTMENT -> MonitoredEntry.EntryType.ADJUSTMENT;
                    },
                    commandKey(posted.attribution().idempotencyScope()),
                    posted.attribution().reverses().map(original -> original.value()),
                    posted.entry().createdAt(), posted.entry().valueDate(), lines));
        }
        return entries;
    }

    @Override
    public long entriesBeyond(Connection unitOfWork, UUID afterEntryId, long cap) {
        return journal.countBeyond(unitOfWork, afterEntryId, cap);
    }

    @Override
    public List<UUID> entryIds(Connection snapshot, UUID fromInclusive, UUID toExclusive) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<UUID> ids = new ArrayList<>();
        try (java.sql.PreparedStatement select = snapshot.prepareStatement(ENTRY_IDS)) {
            select.setObject(1, Objects.requireNonNull(fromInclusive, "fromInclusive"));
            select.setObject(2, Objects.requireNonNull(toExclusive, "toExclusive"));
            try (java.sql.ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    ids.add(row.getObject(1, UUID.class));
                }
            }
        } catch (java.sql.SQLException failure) {
            throw new com.finapp.risk.RiskStorageException(
                    "the journal's day could not be read: SQLSTATE " + failure.getSQLState());
        }
        return ids;
    }

    /** The command key behind the ledger's scope-and-key pair; a scope of another shape is served as it is. */
    static String commandKey(String idempotencyScope) {
        for (String prefix : List.of(POSTING_PREFIX, REVERSAL_PREFIX, ADJUSTMENT_PREFIX)) {
            if (idempotencyScope.startsWith(prefix) && idempotencyScope.length() > prefix.length()) {
                return idempotencyScope.substring(prefix.length());
            }
        }
        return idempotencyScope;
    }
}
