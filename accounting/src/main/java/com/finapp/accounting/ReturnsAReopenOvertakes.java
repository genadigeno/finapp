package com.finapp.accounting;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * What a reopen overtakes, as its two receipts list it (`P14-TSK-023`, the owner's ruling on `P14-TSK-018`'s item (a); ADR-0118's As built; {@code INV-ACC-13}): each return {@code SUBMITTED} or {@code ACKNOWLEDGED} for the month, its id, form and status, whatever revision it read. Those are exactly what reads
 * {@code overtaken} once the approval commits, since a new revision holds no acknowledgement. <strong>A list and no more:</strong> it stops nothing, nothing has to be named back, and it is no gate. {@code PriorPeriodAdjustments.requireNamed} is the contrast: that set is named and compared, because correcting a month twice is an error; a return overtaken is information, and whether a correction is owed is a person's call afterwards.
 *
 * <p><strong>Read on the act's own transaction, after the act, holding the period's row, and taking no lock.</strong> So it adds no edge to the plan's order and cannot invert against {@code RegulatorySubmissions.permitResend}, a submit or {@code accounting V010}'s trigger, each of which takes the period {@code FOR SHARE} and then the row, and no row lock delays it. A submit committed before the act held the period is on the list; one waiting on the period is not, and on an approval is then refused at the new revision. A conclusion takes no period, so a status listed can be overtaken a moment later. <strong>A stale list is information and decides nothing</strong>, and the receipt says so.
 *
 * <p><strong>Bounded.</strong> {@code regulatory_submission_one_live} holds at most one {@code SUBMITTED} per form and month, but an acknowledged return is not live, a correction can follow one at the same revision, and forms are configuration, so a month's list is not small by construction. It serves the newest {@link #NEWEST} and how many there are, and a receipt is stored for its replay.
 *
 * <p><strong>What is not on it:</strong> a return not yet sent, {@code APPROVED} and about to send among them, and one {@code UNDELIVERED} or {@code REJECTED}. So an empty list is not "nothing in flight", and the receipt says that too. A failed read throws, the act's transaction with it: never an empty list for what could not be read.
 */
public interface ReturnsAReopenOvertakes {

    /** The most a receipt lists: {@code P14-TSK-018}'s number for what a read serves of a list that can grow. */
    int NEWEST = 20;

    /** The month's returns {@code SUBMITTED} or {@code ACKNOWLEDGED}: the newest {@link #NEWEST} by when each was sent, and how many there are, one statement. A month with none reads an empty list and zero. */
    Overtaken of(Connection unitOfWork, AccountingPeriodId period);

    /**
     * The newest of a month's overtaken returns, and how many there are.
     *
     * @param newest at most {@link #NEWEST}, newest first
     * @param total how many there are, at least {@code newest}'s size
     */
    record Overtaken(List<Item> newest, long total) {

        public Overtaken {
            newest = List.copyOf(newest);
        }
    }

    /** One return as a receipt lists it: never a figure, a person, a reason or the provider's reference. */
    record Item(RegulatorySubmissionId id, String form, SubmissionStatus status) {

        public Item {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(form, "form");
            Objects.requireNonNull(status, "status");
        }
    }
}
