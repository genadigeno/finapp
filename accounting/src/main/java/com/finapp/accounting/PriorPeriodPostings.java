package com.finapp.accounting;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The one door through which accounting writes the journal (`P14-TSK-001`, `P14-TSK-013`; {@code PHASE_14_PLAN.md} sections 4.2, 13.5;
 * ADR-0113 decision 4; {@code INV-ACC-10}, {@code -14}) - a prior-period adjustment posted as the ledger's own {@code ACCOUNTING}-origin
 * adjustment (ledger {@code V030}), four eyes, on the date its proposal stored, in an open month. Never a free posting, never a balance
 * write. Implemented once, by {@code app}'s {@code LedgerPriorPeriodPostings} (ledger's accounting-owned door); every method runs on the
 * caller's transaction, so accounting's row and the ledger's proposal move together.
 *
 * <p><strong>One date</strong>: {@link #propose} takes one, for both of the entry's dates - nothing about the entry may point into the month it
 * corrects. The ledger's own refusals (the cutoff, an unknown or unpostable account, the second person) are the ledger's exceptions and pass
 * through unchanged; accounting has no ledger edge to catch them by, and the door's error handler maps them.
 *
 * <p><strong>A writer port</strong> - so {@code ReportingNeverWritesTest} refuses it to every reporting class, and {@code BreaksNeverPostTest} to the
 * break machinery.
 */
public interface PriorPeriodPostings {

    /**
     * One line of a proposal, in minor units with its currency and scale.
     *
     * @param ledgerAccount the ledger account, by identifier
     * @param side {@link Side#DEBIT} or {@link Side#CREDIT}, never {@link Side#EITHER}: an entry's line has one
     * @param amount a positive amount
     */
    record Line(UUID ledgerAccount, Side side, Money amount) {

        public Line {
            Objects.requireNonNull(ledgerAccount, "ledgerAccount");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(amount, "amount");
            if (side == Side.EITHER) {
                throw new IllegalArgumentException("a journal line is a debit or a credit");
            }
        }

        /** Never an amount ({@code INV-AUD-02}): a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "Line[" + ledgerAccount + ", " + side + "]";
        }
    }

    /** A ledger account's purpose and owner kind, the ledger's names as strings - {@link MappingRule#key()}'s shape. */
    record Classified(String purpose, String ownerKind) {

        public Classified {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
        }

        public String key() {
            return purpose + "/" + ownerKind;
        }
    }

    /**
     * What an approval came to.
     *
     * @param entry the journal entry
     * @param replayed the ledger found its proposal already approved by this person and wrote nothing
     */
    record Posted(UUID entry, boolean replayed) {

        public Posted {
            Objects.requireNonNull(entry, "entry");
        }
    }

    /** The accounts' purposes and owner kinds, read on {@code unitOfWork}; an account the ledger does not hold is absent. Frozen at each account's birth. */
    Map<UUID, Classified> classify(Connection unitOfWork, Collection<UUID> ledgerAccounts);

    /**
     * Records the ledger's proposal - origin {@code ACCOUNTING}, reason {@code PRIOR_PERIOD_ADJUSTMENT} - on {@code unitOfWork}. Nothing posts.
     *
     * @param date the entry's posting and value date
     * @param reference the entry's reference, served on a statement line to whoever holds the account: it names no adjustment, month or proposal
     * @param reason identifiers only - the person's reason stays on accounting's row
     * @return the ledger's proposal
     */
    UUID propose(Connection unitOfWork, LocalDate date, String reference, String reason, List<Line> lines);

    /** Approves the ledger's proposal as the acting person and posts its entry on the date it stored, on {@code unitOfWork}. */
    Posted approve(Connection unitOfWork, UUID proposal);

    /** Rejects the ledger's proposal on {@code unitOfWork}. */
    void reject(Connection unitOfWork, UUID proposal);

    /** The proposal's frozen lines, unlocked - what an approver reads, and what an approval judges again; empty when the ledger holds no such proposal of this origin. */
    List<Line> linesOf(Connection unitOfWork, UUID proposal);
}
