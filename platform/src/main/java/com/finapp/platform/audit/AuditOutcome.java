package com.finapp.platform.audit;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What came of an audited action.
 *
 * <p><strong>A refused action is more interesting than a successful one.</strong> An audit
 * trail containing only successes describes a system nobody ever attacked and nobody ever made
 * a mistake in; it cannot answer "did anyone try", which is the question asked after an
 * incident. So the outcome is recorded rather than implied by the record's existence.
 *
 * <p>The names are persisted values, and {@link #sqlValueList()} generates the {@code CHECK}
 * constraint's literal list so the enum and the schema are one definition.
 */
public enum AuditOutcome {

    /** The action was permitted and completed. */
    SUCCEEDED,

    /**
     * The action was permitted, attempted, and did not complete.
     *
     * <p>Deliberately distinct from {@link #DENIED}: a failure is a system or domain problem
     * to investigate, a denial is a control working. Collapsing them makes an authorization
     * refusal look like an outage and an outage look like a policy decision.
     */
    FAILED,

    /**
     * The action was refused before it was attempted — authorization, policy or limit.
     *
     * <p>This is the outcome {@code INV-AUD-03} depends on being recorded: a control with no
     * evidence that it ever refused anything is a control nobody can show is working.
     */
    DENIED;

    /** The outcomes as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(outcome -> "'" + outcome.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
