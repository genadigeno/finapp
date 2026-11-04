package com.finapp.accounting;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What corrected earlier months in a month (`P14-TSK-013`, for `P14-TSK-016`'s notes; {@code INV-ACC-10}; {@code PHASE_14_PLAN.md} section 13.6). A correction of a closed month is one of two things, and this read
 * classifies both: a <strong>prior-period adjustment</strong>, posted in the month and traced to the period and revision it corrects; or <strong>a domain's own correction</strong>, a reversal whose original is dated before the
 * month - the register's "by derivation", the domains' doors being theirs and the purposes closed to free adjustments reachable only through them.
 *
 * <p><strong>By date, frozen, on purpose</strong>: an entry's dates and its {@code reverses_entry_id} never move, and a period's status does - a reopen would change the answer for a FINAL report. "Dated in a closed period" is exact
 * at any FINAL read of the month: a lock needs its previous period {@code CLOSED}, so with the month's lock standing every month before it is {@code CLOSED}. A {@code PROVISIONAL} read of an open month reads the same dates, some of
 * those months possibly still open.
 *
 * <p>{@code untraced}: entries of this origin dated in the month with no {@code POSTED} adjustment naming them - a raw writer, the two rows having no foreign key between them. Zero unless something bypassed the door, and never assumed so.
 *
 * <p>Read-only, on the caller's {@code REPEATABLE READ READ ONLY} snapshot: a reader port, for report code. `P14-TSK-016`'s notes read it; the bean is {@code app}'s {@code StatementBeans}.
 */
@FunctionalInterface
public interface PriorPeriodCorrections {

    /** What corrected earlier months in {@code month}, on the snapshot. */
    Corrections ofMonth(Connection snapshot, YearMonth month);

    /** A prior-period adjustment posted in the month, traced to what it corrects. */
    record Adjustment(UUID id, YearMonth correctedMonth, int correctedRevision, UUID entry, LocalDate postingDate) {

        public Adjustment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(correctedMonth, "correctedMonth");
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(postingDate, "postingDate");
        }
    }

    /** The month's reversals of entries dated in an earlier month, per key namespace and the original's month. */
    record DomainReversals(String namespace, YearMonth originalMonth, long entries) {

        public DomainReversals {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(originalMonth, "originalMonth");
            if (entries < 1) {
                throw new IllegalArgumentException("a row counts its reversals");
            }
        }
    }

    /** What the read found. Each list in a stable order, for a report's bytes. */
    record Corrections(List<Adjustment> adjustments, List<DomainReversals> reversals, long untraced) {

        public Corrections {
            adjustments = List.copyOf(adjustments);
            reversals = List.copyOf(reversals);
            if (untraced < 0) {
                throw new IllegalArgumentException("a count is not negative");
            }
        }
    }
}
