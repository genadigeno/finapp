package com.finapp.ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger's operator collections (`P17-TSK-022`, gaps G-110, G-111, G-112; finapp-admin-panel B5.1-B5.3, ADR-C133): the
 * account register, the journal entry search, and the two approvers' queues - the manual adjustments and the projection rebuilds
 * awaiting a second person. Each a keyset page in a declared total order, strictly after the cursor's row, read lock-free on the
 * caller's snapshot: a row committed meanwhile lands where its own key puts it, a proposal decided meanwhile is gone from the next
 * page, and nothing is served twice.
 *
 * <p><strong>Reads, and only reads.</strong> No method here locks a row, takes an advisory key or writes; none computes a
 * balance. An entry's {@code reference} ({@code RESTRICTED-FINANCIAL}: free text on a manual adjustment), {@code reason} and
 * {@code actor_id} are never selected - the reference is matched, never served; a proposal's or a rebuild's reason, a person's
 * prose, is never selected either. A port, as every ledger store is (ADR-0033), so {@code app}'s desk names no {@code Jdbc} class.
 */
public interface LedgerCollections {

    // ------------------------------------------------------------------ one account's lines (P17-TSK-027, G-32)

    /** Where a page of an account's lines ended: the last line it served, by its entry and its place in it. */
    record LineCursor(UUID entryId, int seq) {
        public LineCursor {
            Objects.requireNonNull(entryId, "entryId must not be null");
        }
    }

    /**
     * One line on the account: its entry - id, type, posting and value dates, when it was posted - its direction and its amount,
     * exact at the line's own scale, in the account's currency. Never the entry's {@code reference}, {@code reason} or actor.
     */
    record AccountLine(
            UUID entryId,
            int seq,
            JournalEntryType entryType,
            java.time.LocalDate postingDate,
            java.time.LocalDate valueDate,
            Instant postedAt,
            String direction,
            String currency,
            java.math.BigDecimal amount) {}

    /**
     * A page of one ledger account's lines, newest entry first - the entry id, a UUIDv7, then the line's place in the entry -
     * strictly after {@code after} when given, at most {@code limit} (`P17-TSK-027`: a customer agreement's lines, under
     * {@code ACCOUNT_READ}, recorded at app's door). One backward range of {@code journal_line_by_account}.
     */
    List<AccountLine> accountLines(Connection unitOfWork, LedgerAccountId account, Optional<LineCursor> after, int limit);

    // ------------------------------------------------------------------ the account register (G-110)

    /** The register's filters, each optional: one purpose, one currency. */
    record AccountFilter(Optional<AccountPurpose> purpose, Optional<String> currency) {
        public AccountFilter {
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
        }
    }

    /** Where a page of the register ended: the last account it served, in the register's order. */
    record AccountCursor(AccountPurpose purpose, String currency, UUID accountId) {
        public AccountCursor {
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(accountId, "accountId must not be null");
        }
    }

    /**
     * One ledger account as the register serves it: its classification, its owner by reference - a customer account, a merchant,
     * a loan, an obligation or a counterparty id, never a name; empty for the platform's own accounts - its currency, its status
     * and when it was opened.
     */
    record AccountRow(
            UUID id,
            AccountPurpose purpose,
            OwnerKind ownerKind,
            Optional<UUID> ownerRef,
            String currency,
            AccountType accountType,
            NormalBalance normalBalance,
            LedgerAccountStatus status,
            Instant openedAt) {}

    /**
     * A page of the register in its declared order - purpose, then currency, then the account's id - strictly after
     * {@code after}: a chart of accounts, the platform's own accounts and the owned ones of each purpose and currency together.
     * Served by ledger {@code V039}'s {@code ledger_account_register}.
     */
    List<AccountRow> accounts(Connection unitOfWork, AccountFilter filter, Optional<AccountCursor> after, int limit);

    // ------------------------------------------------------------------ the journal entry search (G-111)

    /**
     * The search's filters: a required window of posting dates, {@code from} to {@code to} inclusive - the caller bounds it - and
     * optionally the economic event's reference, matched exactly, and one ledger account the entry has a line on.
     */
    record EntryFilter(LocalDate from, LocalDate to, Optional<String> reference, Optional<LedgerAccountId> account) {
        public EntryFilter {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(account, "account must not be null");
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("a window's end is not before its start");
            }
        }
    }

    /** Where a page of the search ended: the last entry it served. */
    record EntryCursor(LocalDate postingDate, UUID entryId) {
        public EntryCursor {
            Objects.requireNonNull(postingDate, "postingDate must not be null");
            Objects.requireNonNull(entryId, "entryId must not be null");
        }
    }

    /**
     * One currency's movement in an entry: the sum of its debit lines in that currency - equal to its credit lines' sum, the entry
     * balancing per currency ({@code INV-LED-01}) - exact, at the currency's scale as the lines carry it.
     */
    record EntryAmount(String currency, BigDecimal amount) {}

    /**
     * One journal entry's head: its type, its two dates, when it was posted, the entry it reverses, how many lines it has and its
     * movement per currency. Never its reference, reason or actor.
     */
    record EntryHead(
            UUID id,
            JournalEntryType entryType,
            LocalDate postingDate,
            LocalDate valueDate,
            Instant postedAt,
            Optional<UUID> reverses,
            int lines,
            List<EntryAmount> amounts) {

        public EntryHead {
            amounts = List.copyOf(amounts);
        }
    }

    /**
     * A page of the entries posted on a date of the window, newest posting date first, the entry's id breaking the tie (an id is
     * UUIDv7, so a day's entries read newest first), strictly after {@code after}. Served by ledger {@code V029}'s
     * {@code journal_entry_by_posting_date}, {@code V039}'s {@code journal_entry_by_reference} for a reference, and {@code V004}'s
     * {@code journal_line_by_account} for an account.
     */
    List<EntryHead> entries(Connection unitOfWork, EntryFilter filter, Optional<EntryCursor> after, int limit);

    // ------------------------------------------------------------------ the approvers' queues (G-112)

    /** Where a page of a queue ended: the last proposal it served. */
    record PendingCursor(Instant proposedAt, UUID id) {
        public PendingCursor {
            Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * A manual adjustment awaiting a second person: who proposed it and when, its reason code and the posting date it would land
     * on. Never its reason, its reference or its lines - the proposal's own read serves those to the person deciding it.
     */
    record PendingAdjustment(
            AdjustmentProposalId id,
            String proposedBy,
            Instant proposedAt,
            AdjustmentReasonCode reasonCode,
            LocalDate postingDate) {}

    /**
     * The {@code PROPOSED} manual adjustments ({@code origin = 'MANUAL'}: the only ones {@code /v1/ledger/adjustments} decides -
     * a reconciliation's and an accounting correction's are decided through their own domains' doors and queues), oldest first,
     * the id breaking the tie, strictly after {@code after}. Served by ledger {@code V039}'s partial
     * {@code adjustment_proposal_pending_manual}.
     */
    List<PendingAdjustment> pendingAdjustments(Connection unitOfWork, Optional<PendingCursor> after, int limit);

    /**
     * A projection rebuild awaiting a second person: the account and currency, who proposed it and when, and which figures the
     * proposer saw drift - whether a projection row existed at all, and whether its posted, held and applied-entries figures
     * differed from what the journal derives. Never an amount and never the reason.
     */
    record PendingRebuild(
            ProjectionRebuildId id,
            LedgerAccountId account,
            String currency,
            String proposedBy,
            Instant proposedAt,
            boolean rowObserved,
            boolean postedDrifted,
            boolean holdsDrifted,
            boolean appliedEntriesDrifted) {}

    /**
     * The {@code PROPOSED} projection rebuilds, oldest first, the id breaking the tie, strictly after {@code after}. Served by
     * ledger {@code V039}'s partial {@code projection_rebuild_pending}.
     */
    List<PendingRebuild> pendingRebuilds(Connection unitOfWork, Optional<PendingCursor> after, int limit);
}
