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
 * Reconciliation's suspense register as of a period's cutoff, <strong>through a read that cannot move after the lock</strong>
 * (`P14-TSK-017`; {@code INV-ACC-04}, {@code INV-ACC-05}): what a FINAL body may read, and {@link
 * ReconciliationDisclosure#asOfCutoff} is not. An adopted parking's {@code opened_on} is its entry's posting date, so an
 * adoption after the lock adds an item dated inside the period to the disclosure's read (which the close wants: it is
 * {@code suspenseUnowned} there). Here an item counts only when its owning break was raised before the instant: a frozen
 * fact, stamped at the adoption, so a later adoption, release or resolution changes no byte. The instant is at least the
 * lock's grace in the past ({@code LockGrace.MIN_BUSINESS_DAYS}), so no transaction straddles it.
 *
 * <p>Read-only, on the caller's snapshot; a read that fails throws. Gross per currency and scale: a credit item never nets
 * a debit item.
 */
@FunctionalInterface
public interface SuspenseAsOfCutoff {

    /** The register of {@code period}'s cutoff. */
    Register ofCutoff(Connection snapshot, YearMonth period);

    /** One currency and scale: items and remainders per side, their ageing in the operator reports' bands and the oldest. */
    record Group(String currency, int scale, long creditItems, BigInteger creditUnreleasedMinor, long debitItems,
            BigInteger debitUnreleasedMinor, SortedMap<String, Long> ageing, Optional<LocalDate> oldestOpenedOn) {

        public Group {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(creditUnreleasedMinor, "creditUnreleasedMinor");
            Objects.requireNonNull(debitUnreleasedMinor, "debitUnreleasedMinor");
            ageing = new TreeMap<>(ageing);
            Objects.requireNonNull(oldestOpenedOn, "oldestOpenedOn");
        }

        CanonicalJson.Obj asValue() {
            CanonicalJson.Obj bands = CanonicalJson.object();
            ageing.forEach(bands::put);
            CanonicalJson.Obj out = CanonicalJson.object().put("currency", currency).put("scale", scale)
                    .put("creditItems", creditItems).put("creditUnreleasedMinor", creditUnreleasedMinor.toString())
                    .put("debitItems", debitItems).put("debitUnreleasedMinor", debitUnreleasedMinor.toString())
                    .put("ageing", bands);
            return oldestOpenedOn.isPresent() ? out.put("oldestOpenedOn", oldestOpenedOn.get().toString())
                    : out.putNull("oldestOpenedOn");
        }
    }

    /** The whole register: its cutoff, the instant it is as of, and each currency's group, sorted. */
    record Register(LocalDate cutoff, Instant asOf, List<Group> groups) {

        public Register {
            Objects.requireNonNull(cutoff, "cutoff");
            Objects.requireNonNull(asOf, "asOf");
            groups = List.copyOf(groups);
        }

        /** The content's {@code register} member. */
        public CanonicalJson.Obj asValue() {
            CanonicalJson.Arr items = CanonicalJson.array();
            groups.forEach(group -> items.add(group.asValue()));
            return CanonicalJson.object().put("cutoff", cutoff.toString()).put("asOf", asOf.toString()).put("groups", items);
        }
    }
}
