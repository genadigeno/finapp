package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The rebuild's reach into {@code ledger.account_balance}, the journal and the holds (`P14-TSK-026`, ADR-0119 decision 1) -
 * a seam so the lock protocol is provable without a database, and so the rebuild's SQL lives in one class the guard
 * {@code ProjectionRebuildNeverPostsTest} reads. <strong>Every journal access is a read</strong>: nothing here writes a
 * journal entry or line ({@code INV-LED-03}, {@code INV-LED-04}).
 *
 * <p><strong>The protocol the approval follows, in this order</strong> (the posting's own: {@code PostingEffect} takes the
 * account's key share at its line insert and the projection row last):
 *
 * <ol>
 *   <li>{@link #shareAccount} - the account row {@code FOR KEY SHARE}: a posting's own mode, compatible with every
 *       posting, conflicting with the {@code FOR UPDATE} every hold placement and release takes first;
 *   <li>{@link #lockRow} - the projection row {@code FOR UPDATE}: every posting to the account must take it before it
 *       commits;
 *   <li>{@link #derive} - in statements that start after that grant, under {@code READ COMMITTED}: the derivation, the
 *       applied-entry count, the {@code ACTIVE} holds;
 *   <li>{@link #writeRow} (or {@link #insertRow} for a row a bypass never created).
 * </ol>
 *
 * <p>So nothing that changes what step 3 reads can commit while the rebuild holds steps 1 and 2: a posting committed
 * before the grant is in the sum, one committing after it composes on the written row.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface ProjectionRebuildRows<T> {

    /** A projection row as read: its currency and scale (identity, never written here) and its three figures. */
    record Row(CurrencyCode currency, int scale, ProjectionFigures figures) {

        public Row {
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(figures, "figures must not be null");
        }
    }

    /**
     * What the journal and the {@code ACTIVE} holds derive for an account, at one scale: the settled balance's
     * ({@link BalanceDerivation}, the definition), the holds folded at it, and the applied-entry count.
     */
    record Derived(CurrencyCode currency, int scale, ProjectionFigures figures) {

        public Derived {
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(figures, "figures must not be null");
        }
    }

    /**
     * The proposer's reading, unlocked, by the verification's seq bracket: {@code stable} false when an entry committed
     * to the account mid-reading (nothing is known to be wrong; read again).
     */
    record Observation(Optional<Row> row, Derived derived, boolean stable) {

        public Observation {
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(derived, "derived must not be null");
        }
    }

    /**
     * The proposer's bracketed reading, locking nothing - {@code ProjectionVerification}'s protocol: the row's
     * {@code last_entry_seq}, the derivation and the applied-entry count, then the row and its {@code ACTIVE} holds in one
     * statement.
     *
     * @throws ProjectionRebuildAccountNotFoundException no ledger account has this id
     * @throws ProjectionNotRebuildableException the history cannot be derived (mixed scales, a hold at another scale)
     */
    Observation observe(T unitOfWork, LedgerAccountId account);

    /**
     * Step 1: the account row {@code FOR KEY SHARE}.
     *
     * @return false when no ledger account has this id
     */
    boolean shareAccount(T unitOfWork, LedgerAccountId account);

    /**
     * Step 2: the projection row {@code FOR UPDATE}, held to commit - empty when the account has none (nothing is locked
     * then).
     *
     * @throws IllegalStateException when the transaction is not {@code READ COMMITTED}: the derivation must read a
     *     snapshot taken after this grant
     */
    Optional<Row> lockRow(T unitOfWork, LedgerAccountId account);

    /**
     * Step 3: the derivation, the applied-entry count and the {@code ACTIVE} holds, each a fresh statement.
     *
     * @throws ProjectionNotRebuildableException the history cannot be derived
     */
    Derived derive(T unitOfWork, LedgerAccountId account);

    /**
     * Step 4 for an account with lines and no row: {@code INSERT ... ON CONFLICT DO NOTHING}.
     *
     * @return true when this call inserted the row (and holds its lock); false when a posting created it first - the
     *     caller re-locks and re-derives
     */
    boolean insertRow(T unitOfWork, LedgerAccountId account, Derived derived, Instant at);

    /**
     * Step 4: the row's {@code posted_minor}, {@code holds_minor}, {@code last_entry_seq} and {@code updated_at} set to
     * {@code derived}'s, at the row's currency and scale - V006's column grant, nothing else.
     *
     * @throws IllegalStateException when no row at that scale and currency was updated: the lock protocol was bypassed
     */
    void writeRow(T unitOfWork, LedgerAccountId account, Derived derived, Instant at);
}
