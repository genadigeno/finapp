package com.finapp.consent;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which of the two immutable facts a consent record is (`P2-TSK-017`, ADR-0037).
 *
 * <p>There are exactly two, and neither edits the other: a withdrawal is a <strong>new
 * record</strong>, never a change to the grant it follows ({@code INV-CNS-02}). There is no
 * third value for "expired" or "superseded" — those are questions the <em>derivation</em>
 * answers by reading the history and the text versions, never states written into it
 * (the {@code P1-TSK-013} no-EXPIRED-status reasoning: a stored answer needs a sweep to keep
 * true, and until the sweep runs it is wrong).
 *
 * <p>Persisted values, in a generated {@code CHECK} constraint ({@code P0-TSK-022};
 * {@code ConsentMigrationTest} reconciles).
 */
public enum ConsentAction {

    /** The party granted consent for the purpose, against a named text version. */
    GRANT,

    /**
     * The party withdrew consent for the purpose. The pinned text version is the one current
     * when they withdrew — a withdrawal is also a dated fact against an artefact
     * ({@code INV-CNS-04}'s reference is unconditional).
     */
    WITHDRAWAL;

    /** The actions as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(action -> "'" + action.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
