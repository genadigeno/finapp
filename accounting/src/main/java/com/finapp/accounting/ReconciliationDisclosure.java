package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Reconciliation's open breaks, suspense balance and ageing as of the cutoff (`P14-TSK-001`; {@code PHASE_14_PLAN.md}
 * sections 3, 13.3, 13.5; owner decision C11; {@code INV-ACC-05}) - read and <strong>disclosed</strong> at close and in
 * the statements' notes, never copied into accounting's breaks and never resolved from here. *(Declared empty by
 * `P14-TSK-001`; given its method by `P14-TSK-011`; {@code app}'s {@code ProductReconciliationDisclosure} implements
 * it.)*
 *
 * <p><strong>As of the cutoff.</strong> The cutoff is the period's last day; its instant the first one after it, UTC.
 * Every figure is the stored facts' answer at that instant, so a re-run reads the same: a break standing open then
 * (raised before it, resolved at or after it or not at all), a suspense item opened by then less what had been released
 * by then, the {@code SUSPENSE_UNMATCHED} lines dated through the last day. What the grace days brought is named apart,
 * never folded in: the breaks raised since and still open.
 *
 * <p><strong>Never netted</strong> ({@code INV-ACC-05}): a suspense item's sides are gross; figures per currency and
 * scale.
 *
 * <p><strong>Read-only</strong>, on the caller's {@code REPEATABLE READ READ ONLY} snapshot - one reading of one
 * instant.
 */
public interface ReconciliationDisclosure {

    /** The version an attempt names - v2 from `P14-TSK-012`: narrowed to the floored, and a month's runs judged beside it. */
    String JUDGED_BY = "reconciliation-disclosure:v2";

    /** The disclosure of the {@code period}'s cutoff; throws when it cannot be read whole - never an empty answer for it. */
    Disclosure asOfCutoff(Connection snapshot, YearMonth period);

    /**
     * The breaks standing open at the cutoff, one currency and scale: how many, their value at issue and their ageing.
     *
     * @param ageing per the operator reports' bands, days from each break's raise to the cutoff
     */
    record OpenBreaks(String currency, int scale, long breaks, BigInteger valueAtIssueMinor, SortedMap<String, Long> ageing) {

        public OpenBreaks {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(valueAtIssueMinor, "valueAtIssueMinor");
            ageing = new TreeMap<>(ageing);
        }

        CanonicalJson.Obj evidence() {
            return CanonicalJson.object().put("currency", currency).put("scale", scale).put("breaks", breaks)
                    .put("valueAtIssueMinor", valueAtIssueMinor.toString()).put("ageing", counts(ageing));
        }
    }

    /**
     * {@code SUSPENSE_UNMATCHED} at the cutoff, one currency and scale: the ledger's debits and credits through the
     * last day, the register's items unreleased at that instant - CREDIT and DEBIT gross, never netted - their ageing
     * and the oldest.
     */
    record Suspense(String currency, int scale, BigInteger ledgerDebitsMinor, BigInteger ledgerCreditsMinor, long creditItems,
            BigInteger creditUnreleasedMinor, long debitItems, BigInteger debitUnreleasedMinor,
            SortedMap<String, Long> ageing, Optional<LocalDate> oldestOpenedOn) {

        public Suspense {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(ledgerDebitsMinor, "ledgerDebitsMinor");
            Objects.requireNonNull(ledgerCreditsMinor, "ledgerCreditsMinor");
            Objects.requireNonNull(creditUnreleasedMinor, "creditUnreleasedMinor");
            Objects.requireNonNull(debitUnreleasedMinor, "debitUnreleasedMinor");
            ageing = new TreeMap<>(ageing);
            Objects.requireNonNull(oldestOpenedOn, "oldestOpenedOn");
        }

        /** True when the ledger's account nets to zero and the register holds no item. */
        boolean clear() {
            return creditItems == 0 && debitItems == 0 && ledgerDebitsMinor.equals(ledgerCreditsMinor);
        }

        CanonicalJson.Obj evidence() {
            CanonicalJson.Obj out = CanonicalJson.object().put("currency", currency).put("scale", scale)
                    .put("ledgerDebitsMinor", ledgerDebitsMinor.toString())
                    .put("ledgerCreditsMinor", ledgerCreditsMinor.toString()).put("creditItems", creditItems)
                    .put("creditUnreleasedMinor", creditUnreleasedMinor.toString()).put("debitItems", debitItems)
                    .put("debitUnreleasedMinor", debitUnreleasedMinor.toString()).put("ageing", counts(ageing));
            return oldestOpenedOn.isPresent() ? out.put("oldestOpenedOn", oldestOpenedOn.get().toString())
                    : out.putNull("oldestOpenedOn");
        }
    }

    /**
     * The whole disclosure.
     *
     * @param raisedSinceStillOpen breaks raised after the cutoff's instant and open at the reading - apart, never
     * folded into {@code breaks}
     * @param suspenseUnowned items unreleased at the cutoff whose owning break was not standing open then - the {@code
     * suspense.unowned} defect, as of the cutoff (`P14-TSK-010`'s review, M-10): frozen facts, so disclosed, never
     * passed
     * @param pastTheCutoff entries of the floored rules other than {@code LND-2} (that one is {@code ACCRUALS_COMPLETE}'s) whose value date is at or
     * before the cutoff and whose posting date is after it, per key namespace, each told floored, unrecorded or its rule's own dating ({@link
     * PastTheCutoffPostings}): the journal holds them in a later period while a report grouped by the stated date ({@code
     * ReconciliationReports.providerCosts}) can hold them in this one (`P14-TSK-006`'s review, M-2)
     */
    record Disclosure(LocalDate cutoff, Instant asOf, List<OpenBreaks> breaks, long raisedSinceStillOpen, List<Suspense> suspense,
            long suspenseUnowned, SortedMap<String, PastTheCutoffPostings.Split> pastTheCutoff) {

        public Disclosure {
            Objects.requireNonNull(cutoff, "cutoff");
            Objects.requireNonNull(asOf, "asOf");
            breaks = List.copyOf(breaks);
            suspense = List.copyOf(suspense);
            pastTheCutoff = new TreeMap<>(pastTheCutoff);
        }

        /**
         * True only when the whole reading found nothing to disclose - anything else is {@code DISCLOSED}, never {@code PASSED}. A posting past the cutoff
         * under its rule's own dating asks for nothing (the owner's decision (b), 2026-11-03); a floored one does, and so does one whose
         * audit record is missing.
         */
        public boolean nothingToDisclose() {
            return breaks.isEmpty() && raisedSinceStillOpen == 0 && suspenseUnowned == 0
                    && pastTheCutoff.values().stream().allMatch(split -> split.countsAsFloored() == 0)
                    && suspense.stream().allMatch(Suspense::clear);
        }

        /** The attempt's evidence: figures per currency in minor units, as text. */
        public CanonicalJson.Obj evidence() {
            CanonicalJson.Arr openBreaks = CanonicalJson.array();
            breaks.forEach(group -> openBreaks.add(group.evidence()));
            CanonicalJson.Arr items = CanonicalJson.array();
            suspense.forEach(group -> items.add(group.evidence()));
            CanonicalJson.Obj past = CanonicalJson.object();
            pastTheCutoff.forEach((namespace, split) -> past.put(namespace, split.asValue()));
            return CanonicalJson.object().put("cutoff", cutoff.toString()).put("asOf", asOf.toString())
                    .put("openBreaks", openBreaks).put("raisedSinceStillOpen", raisedSinceStillOpen)
                    .put("suspense", items).put("suspenseUnowned", suspenseUnowned)
                    .put("pastTheCutoff", past);
        }
    }

    private static CanonicalJson.Obj counts(SortedMap<String, Long> counts) {
        CanonicalJson.Obj out = CanonicalJson.object();
        counts.forEach(out::put);
        return out;
    }
}
