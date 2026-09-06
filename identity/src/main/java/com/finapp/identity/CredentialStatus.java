package com.finapp.identity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A credential's lifecycle: {@code ACTIVE → SUPERSEDED}, and nothing else.
 *
 * <p><strong>There is no edit and no delete.</strong> Changing a password creates a new credential
 * and supersedes the old one, which is {@code INV-HIST-01}'s reasoning applied to authentication:
 * the superseded rows are the evidence of <em>when protection changed</em>, and that evidence is
 * exactly what a compromise investigation needs. A row that was overwritten in place can answer
 * "what is the password now" and nothing else.
 *
 * <p><strong>{@code SUPERSEDED} is terminal.</strong> Reinstating an old credential is not a
 * transition and never will be: the whole reason it was superseded is that it should no longer
 * authenticate anybody, and a state machine that allows the way back makes that a matter of who
 * calls what.
 */
public enum CredentialStatus {

    /** Usable for authentication. At most one per identity and type - a partial unique index. */
    ACTIVE,

    /** Replaced. Retained as evidence, never usable again. */
    SUPERSEDED;

    public Set<CredentialStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(SUPERSEDED);
            case SUPERSEDED -> EnumSet.noneOf(CredentialStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(CredentialStatus target) {
        return permittedTransitions().contains(target);
    }

    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
