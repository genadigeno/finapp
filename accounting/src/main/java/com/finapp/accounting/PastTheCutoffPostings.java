package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.SortedMap;

/**
 * Which of the entries valued at or before a cutoff and posted after it were <em>floored</em>, and which are a rule's own
 * dating (`P14-TSK-017`; `P14-TSK-011`'s review, M-2; `P14-TSK-006`'s review, M-2; the owner's decision (b) of 2026-11-03, `P14-TSK-012`):
 * {@code BatchAcceptance} posts its {@code acceptedOn} valued at the batch's {@code businessDate}, so an ordinary batch accepted on the 1st for
 * the 31st stands past the cutoff with nothing floored. The journal's dates cannot tell them apart; the entry's own audit record can ({@code
 * flooredFrom=}).
 *
 * <p><strong>One read, for the check and the report</strong>: {@code RECONCILIATION_DISCLOSURE} and {@code RECONCILIATION_STATUS} both read it through
 * {@link ReconciliationDisclosure.Disclosure#pastTheCutoff()}, so the two cannot disagree. Moving: every later posting can add to it, so an
 * operational read. Read-only, on the caller's snapshot.
 *
 * <p><strong>An entry whose audit record is missing counts as floored</strong> ({@link Split#countsAsFloored()}; the owner's decision (b), the review's
 * M-3): "its rule's own dating" is inferred from a mark's absence, so "no mark" is believed only where the record stands to carry one. No retention sweep of
 * {@code platform.audit_record} exists today, and the table is append-only by trigger, so one would need its own migration. If one ever did, every swept entry would
 * read {@link Split#unrecorded()}: {@code DISCLOSED} with a person's reason, never a silent narrowing. That volume would grow, so such a sweep must keep the
 * posting records at least as long as a month can be reopened.
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
     * @param entries how many
     * @param floored how many carry {@code flooredFrom=} on the posting's audit record: a lock covered the date they stated, and the ledger
     *     posted them on the first open date
     * @param unrecorded how many have no posting audit record at all: whether they were floored cannot be told, so they count as if they were
     */
    record Split(long entries, long floored, long unrecorded) {

        public Split {
            if (entries < 0 || floored < 0 || unrecorded < 0 || floored + unrecorded > entries) {
                throw new IllegalArgumentException("a namespace's floored and unrecorded entries are among its entries");
            }
        }

        /** What the check discloses: the floored and those that cannot be told from floored. */
        public long countsAsFloored() {
            return floored + unrecorded;
        }

        /**
         * The rest: a posting record present, with no mark - the rule's own dating, stated past the cutoff and posted as stated, valued earlier. An ordinary
         * late posting, which asks for no person's reason (the owner's decision (b), 2026-11-03).
         */
        public long ownValueDate() {
            return entries - floored - unrecorded;
        }

        /** The content's member for this namespace. */
        public CanonicalJson.Obj asValue() {
            return CanonicalJson.object().put("entries", entries).put("floored", floored).put("unrecorded", unrecorded)
                    .put("ownValueDate", ownValueDate());
        }
    }
}
