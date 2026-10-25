package com.finapp.risk;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * The party behind a signal's subject (`P13-TSK-007`; {@code PHASE_13_PLAN.md} sections 3.1, 12.5; ADR-0107
 * decision 3) - the port the profile's hint reads, declared here and implemented in {@code app}, because risk cannot
 * see the modules that own the subjects. The platform's events name their own aggregate - an identity, an account, a
 * loan - never the party behind it ({@link SubjectKind}), so the join is the consumer's, through this port, on the
 * caller's unit of work; it returns an identifier, never another module's row.
 *
 * <p>An implementation answers empty for a subject whose party it cannot place - the signal is recorded and pulls no
 * permit ({@code RISK_LIFECYCLES.md} section 4.9: a signal for an unknown party is recorded, no profile). `-007`'s
 * implementation places a {@link SubjectKind#PARTY} and an {@link SubjectKind#IDENTITY}; the other kinds' joins
 * arrive with the task that needs each (`DESIGN-007` D5).
 */
@FunctionalInterface
public interface RiskSubjectParties {

    /** No subject is ever placed - the suites' default. */
    RiskSubjectParties NONE = (unitOfWork, subject) -> Optional.empty();

    /** The party {@code subject} belongs to - empty when it is no party's this build can place. */
    Optional<UUID> partyOf(Connection unitOfWork, SignalSubject subject);

    /**
     * The party accountable for {@code subject} when a person's act on it is judged for self-dealing (`P13-TSK-016`,
     * {@code DESIGN-016} D6) - {@link #partyOf} by default, and in {@code app} also a {@link SubjectKind#MERCHANT}'s
     * organisation party, so a merchant's case is workable. Deliberately a separate join from {@link #partyOf}: that one
     * drives `-007`'s profile hint, and placing a merchant there would enrol every onboarded merchant's organisation in
     * the profile sweep. Empty when no party can be placed - the caller fails closed.
     */
    default Optional<UUID> accountablePartyOf(Connection unitOfWork, SignalSubject subject) {
        return partyOf(unitOfWork, subject);
    }
}
