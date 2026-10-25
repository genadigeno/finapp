package com.finapp.risk;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the investigator's two queues share (`P13-TSK-018`; {@code PHASE_13_PLAN.md} sections 9, 11; {@code INV-RSK-08};
 * {@code DESIGN-018} D3, D4, D6): the page's size and bound, the refusal of a read the queue cannot take as asked, and
 * <strong>the self-dealing scan</strong>.
 *
 * <p>A queue serves what its reader may see. The reader is a person placed in a party (else the page is refused,
 * fail closed); every row whose subject's accountable party is the reader's own, or cannot be placed, is
 * <em>withheld</em> - the same judgement every act on the row takes ({@link RiskSubjectParties#accountablePartyOf}: a
 * party is its own, a merchant its organisation's). The statements already leave out the reader's own
 * {@code PARTY}-subject rows; the scan judges the rest, reading on past withheld rows chunk by chunk until the page is
 * full, the queue is exhausted, or {@link #SCAN_BOUND} rows were read. The cursor a page answers is always the last row
 * it <em>served</em> - never a withheld row's id, which would tell an insider that something of their own party is
 * queued. A page ended by the bound says so ({@code bounded}).
 */
public final class Queues {

    /** A page's rows when the reader names no size. */
    public static final int DEFAULT_PAGE = 20;

    /** The most rows one page serves - a larger size asked is answered with this. */
    public static final int PAGE_BOUND = 50;

    /** The most rows one page reads, served and withheld together - the bound against a wall of withheld rows. */
    static final int SCAN_BOUND = 1_000;

    private Queues() {
    }

    /** The queue read is not admitted as asked ({@code 422 risk.QueueRequestInvalid}) - nothing was served. */
    public static final class QueueRequestInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        QueueRequestInvalid(String detail) {
            super(detail);
        }
    }

    /** The page's size: {@link #DEFAULT_PAGE} unless named; above {@link #PAGE_BOUND} the bound; below one refused. */
    public static int size(Optional<Integer> asked) {
        Objects.requireNonNull(asked, "asked");
        if (asked.isEmpty()) {
            return DEFAULT_PAGE;
        }
        if (asked.get() < 1) {
            throw new QueueRequestInvalid("a queue page holds at least one row");
        }
        return Math.min(asked.get(), PAGE_BOUND);
    }

    /** {@code raw} as one of {@code values}' names, exactly - or the queue's refusal. */
    static <E extends Enum<E>> Optional<E> named(Optional<String> raw, Class<E> vocabulary, String what) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        for (E value : vocabulary.getEnumConstants()) {
            if (value.name().equals(raw.get())) {
                return Optional.of(value);
            }
        }
        throw new QueueRequestInvalid("a queue's " + what + " is one of "
                + java.util.Arrays.toString(vocabulary.getEnumConstants()));
    }

    /** The reader: a person ({@link Cases#person}), placed in a party - else refused before anything is read. */
    static UUID reader(Connection snapshot, RiskActingParty actingParties, Actor actor, String act) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(actor, "actor");
        if (!Cases.person(actor)) {
            throw new IllegalArgumentException("a queue is read by a person - never the platform, a service or a"
                    + " merchant");
        }
        return actingParties.partyOf(snapshot, actor).orElseThrow(() -> new RiskAssessments.SelfDealingRefused(
                RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE, act));
    }

    /** The party accountable for {@code subject} - a party is its own; every other kind through the accountable join. */
    static Optional<UUID> accountableParty(Connection snapshot, RiskSubjectParties subjectParties, SignalSubject subject) {
        if (subject.kind() == SubjectKind.PARTY) {
            return Optional.of(UUID.fromString(subject.ref()));
        }
        return subjectParties.accountablePartyOf(snapshot, subject);
    }

    /** True when {@code subject} may be shown to the reader of party {@code own}: placed, and not theirs. */
    static boolean visibleTo(Connection snapshot, RiskSubjectParties subjectParties, SignalSubject subject, UUID own) {
        return accountableParty(snapshot, subjectParties, subject).filter(party -> !party.equals(own)).isPresent();
    }

    /** A scan's answer: the rows served, the cursor to the next page, how many were withheld, whether the bound ended it. */
    record Scanned<T, K>(List<T> served, Optional<K> next, int withheld, boolean bounded) {
        Scanned {
            served = List.copyOf(served);
            Objects.requireNonNull(next, "next");
        }
    }

    /**
     * Up to {@code limit} visible rows after {@code after}: {@code chunk} reads up to {@code limit + 1} rows after a
     * cursor, oldest first; every row is judged by {@code visible}; reading continues past withheld rows until the page
     * and one more visible row are found (so {@code next} is answered only when more remain), the queue is exhausted, or
     * {@link #SCAN_BOUND} rows were read.
     */
    static <T, K> Scanned<T, K> scan(int limit, Optional<K> after, Function<Optional<K>, List<T>> chunk,
            Function<T, K> keyOf, Predicate<T> visible) {
        if (limit < 1 || limit > PAGE_BOUND) {
            throw new IllegalArgumentException("a page is 1 to " + PAGE_BOUND + " rows: " + limit);
        }
        List<T> served = new ArrayList<>();
        int withheld = 0;
        int scanned = 0;
        boolean more = false;
        boolean bounded = false;
        Optional<K> cursor = after;
        reading:
        while (true) {
            List<T> read = chunk.apply(cursor);
            for (T row : read) {
                scanned++;
                if (!visible.test(row)) {
                    withheld++;
                    continue;
                }
                if (served.size() == limit) {
                    more = true;
                    break reading;
                }
                served.add(row);
            }
            if (read.size() <= limit) {
                break; // the chunk asked limit + 1 and got fewer: the queue is exhausted
            }
            if (scanned >= SCAN_BOUND) {
                bounded = true;
                more = true;
                break;
            }
            cursor = Optional.of(keyOf.apply(read.get(read.size() - 1)));
        }
        Optional<K> next = more && !served.isEmpty() ? Optional.of(keyOf.apply(served.get(served.size() - 1)))
                : Optional.empty();
        return new Scanned<>(served, next, withheld, bounded);
    }
}
