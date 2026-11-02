package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.SortedMap;

/**
 * Which of the entries valued at or before a cutoff and posted after it were <em>floored</em>, and which are a rule's own
 * dating (`P14-TSK-017`; `P14-TSK-011`'s review, M-2; `P14-TSK-006`'s review, M-2): {@code BatchAcceptance} posts its
 * {@code acceptedOn} valued at the batch's {@code businessDate}, so an ordinary batch accepted on the 1st for the 31st stands
 * past the cutoff with nothing floored, and {@code ReconciliationDisclosure.postedPastTheCutoff}'s count cannot tell it from a
 * posting a lock pushed out. The journal's dates cannot either; the entry's own audit record can ({@code flooredFrom=}).
 *
 * <p>Reported by {@code RECONCILIATION_STATUS}; the check's read is not narrowed (whether it should be is routed to
 * {@code -012}). Moving: every later posting can add to it, so an operational read. Read-only, on the caller's snapshot.
 */
@FunctionalInterface
public interface PastTheCutoffPostings {

    /**
     * Per key namespace of the floored rules other than {@code LND-2}, whose entries are {@code ACCRUALS_COMPLETE}'s; a namespace with
     * none is absent.
     */
    SortedMap<String, Split> pastTheCutoff(Connection snapshot, LocalDate cutoff);

    /**
     * One namespace's entries past the cutoff.
     *
     * @param entries how many, {@code ReconciliationDisclosure.postedPastTheCutoff}'s number
     * @param floored how many carry {@code flooredFrom=}: a lock covered the date they stated, and the ledger posted them on
     *     the first open date
     */
    record Split(long entries, long floored) {

        public Split {
            if (entries < 0 || floored < 0 || floored > entries) {
                throw new IllegalArgumentException("a namespace's floored entries are among its entries");
            }
        }

        /**
         * The rest: taken to be the rule's own dating, stated past the cutoff and posted as stated, valued earlier. <strong>Inferred from
         * the absence of {@code flooredFrom=}</strong>, and stated by nothing: an entry whose audit record had gone would read here too.
         */
        public long ownValueDate() {
            return entries - floored;
        }

        /** The content's member for this namespace. */
        public CanonicalJson.Obj asValue() {
            return CanonicalJson.object().put("entries", entries).put("floored", floored)
                    .put("ownValueDate", ownValueDate());
        }
    }
}
