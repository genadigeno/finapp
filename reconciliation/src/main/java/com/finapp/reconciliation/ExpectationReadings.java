package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The register's read side for the proofs (`P8-TSK-007`, ADR-0067 §9, {@code INV-REC-06}):
 * what the position proof and the completeness verifier need, and nothing that decides —
 * every method is lock-free, read in the caller's snapshot (the verifiers run one
 * {@code REPEATABLE READ} transaction), and a row read here is immutable in every field
 * these readings touch except the disposition the proof is precisely measuring.
 *
 * <p><strong>Row-level {@code Money}, caller-level fold</strong>: a remainder is computed
 * per row through the kernel ({@code amount − allocated − resolved}) and the signed sum is
 * the caller's {@code Money} fold — never a SQL {@code SUM} (`P3-TSK-008`'s rule: an
 * aggregate in SQL is a second monetary arithmetic outside the kernel). The recorded scale
 * path is incremental watermarks (ADR-0067 §9); until then the honest cost is streaming the
 * open rows.
 */
public interface ExpectationReadings<T> {

    /** One open expectation's remainder: what has not yet settled, on its position. */
    record OpenRemainder(
            UUID sourceId, AccountPurpose position, ExpectationDirection direction, Money remainder) {
        public OpenRemainder {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(remainder, "remainder must not be null");
        }
    }

    /** One line the register explains: the pair the completeness verifier joins on. */
    record KnownLine(UUID journalEntryId, UUID ledgerAccountId) {
        public KnownLine {
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(ledgerAccountId, "ledgerAccountId must not be null");
        }
    }

    /** Every {@code OPEN}/{@code PARTIALLY_SETTLED} expectation's remainder row. */
    List<OpenRemainder> openRemainders(T unitOfWork);

    /**
     * One undisposed item's remainder (`P8-TSK-009`): what an accepted line still claims of
     * its position — {@code amount − allocated − parked − offset}, per row through the
     * kernel. The identity's items term (`INV-REC-06` extended at acceptance):
     * balance = open remainders − open item remainders, at every commit.
     */
    record OpenItemRemainder(
            UUID sourceId,
            AccountPurpose position,
            ExpectationDirection direction,
            Money remainder) {
        public OpenItemRemainder {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(remainder, "remainder must not be null");
        }
    }

    /**
     * Every ALLOCATING item's remainder while it is {@code PENDING} or {@code UNMATCHED}
     * (§5.4): fee items are excluded because their effect IS the recognition entry, and a
     * parked remainder is excluded because the park posted it to suspense — where the
     * position identity already sees it.
     */
    List<OpenItemRemainder> openItemRemainders(T unitOfWork);

    /**
     * Every {@code (journal_entry_id, ledger_account_id)} pair an expectation names — the
     * known-entry list's first class (ADR-0067 §9); suspense items and the Phase 8 records
     * join it with their tasks.
     */
    List<KnownLine> knownLines(T unitOfWork);

    /** Open expectations per source — the {@code expectation.open} gauge's reading. */
    Map<UUID, Long> openCountBySource(T unitOfWork);
}
