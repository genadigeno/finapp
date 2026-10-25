package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The party behind a monitored transaction's customer account, and the accounts behind a party (`P13-TSK-020`;
 * {@code PHASE_13_PLAN.md} sections 3.1, 12.7; ADR-0107 decision 3; {@code DESIGN-020} F1, F2) - the port the
 * monitoring rules read, declared here and implemented in {@code app} over accounts and party, because the journal
 * names a customer account by its opaque {@code owner_ref} and never the party (`-019`'s
 * {@link MonitoredSubjectKind}). Read on the caller's unit of work, never cached; it answers identifiers, never another
 * module's row.
 *
 * <p><strong>Why the party.</strong> The customer, not the account, is the unit of an AML judgement: the profile head
 * the plan locks (R8) is the party's, and an alert whose subject the platform cannot place is withheld from every
 * investigator's queue (`-018`, fail closed). So the rules judge the party over all its accounts, and every AML alert's
 * subject is the {@link SubjectKind#PARTY}.
 */
public interface MonitoredParties {

    /** The most accounts one party's readings read - a party holds a handful of agreements; more is refused. */
    int BOUND = 50;

    /** No account is ever placed - the suites' default: every party unplaced, every journal reading {@code ABSENT}. */
    MonitoredParties NONE = new MonitoredParties() {
        @Override
        public Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId) {
            return Optional.empty();
        }

        @Override
        public Accounts accountsOf(Connection unitOfWork, UUID partyId) {
            return Accounts.NONE;
        }
    };

    /** The party {@code customerAccountId} belongs to - empty when this build cannot place it. */
    Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId);

    /** The customer accounts {@code partyId} holds or has held, with the earliest opening among them. */
    Accounts accountsOf(Connection unitOfWork, UUID partyId);

    /**
     * A party's customer accounts and the instant the first of them opened - the dormancy's anchor when the party has
     * never moved money ({@code DESIGN-020} D7). Empty for a party this build cannot place.
     */
    record Accounts(Set<UUID> ids, Optional<Instant> firstOpenedAt) {

        /** No account at all. */
        public static final Accounts NONE = new Accounts(Set.of(), Optional.empty());

        public Accounts {
            Objects.requireNonNull(ids, "ids");
            Objects.requireNonNull(firstOpenedAt, "firstOpenedAt");
            ids = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ids));
            if (ids.size() > BOUND) {
                throw new IllegalStateException("a party's accounts are read at most " + BOUND);
            }
            if (ids.isEmpty() != firstOpenedAt.isEmpty()) {
                throw new IllegalArgumentException("a party's first opening is known exactly when it holds an account");
            }
            ids.forEach(id -> Objects.requireNonNull(id, "an account"));
        }

        /** {@code ids} opened at {@code openings}, the earliest the anchor. */
        public static Accounts of(List<UUID> ids, List<Instant> openings) {
            Objects.requireNonNull(ids, "ids");
            Objects.requireNonNull(openings, "openings");
            return new Accounts(new LinkedHashSet<>(ids), openings.stream().min(Instant::compareTo));
        }

        @Override
        public String toString() {
            // A count only: the accounts are the party's.
            return "Accounts[" + ids.size() + "]";
        }
    }
}
