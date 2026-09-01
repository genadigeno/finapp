package com.finapp.platform.audit;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of thing performed an audited action.
 *
 * <p><strong>Why the type is stored beside the identifier and not inferred from it.</strong>
 * An identifier alone does not say what it identifies. Once a customer identifier and an
 * employee identifier can look alike — and they will, because both end up as strings from
 * different sources — "who did this" has two possible answers and an audit query silently
 * returns the wrong rows. The distinction also matters legally rather than only technically:
 * an action taken *by* a customer and an action taken *on* a customer's behalf by an employee
 * are different events with different consequences, and `CLAUDE.md` §Domain Distinctions is
 * explicit that Customer and User are not the same concept.
 *
 * <p>The names are persisted values. They appear in a {@code CHECK} constraint on
 * {@code platform.audit_record}, and {@link #sqlValueList()} generates that constraint's
 * literal list from this enum so the two cannot drift. Renaming a constant is therefore a
 * schema change, not a refactor — and adding one is a deliberate act, which is the intent:
 * an actor type nobody declared should not appear in a financial platform's audit trail, and
 * an unconstrained column accumulates near-duplicates that fragment every audit query.
 */
public enum ActorType {

    /**
     * The platform itself: a scheduled job, a relay, a consumer.
     *
     * <p>The only type available until Phase 1 supplies real identity (ADR-0010). It is a real
     * answer rather than a placeholder — a posting made by an automated process genuinely has
     * no human actor, and recording one would be a lie that survives in the record.
     */
    SYSTEM,

    /** A human acting on their own account. */
    CUSTOMER,

    /** A human acting in an operational or administrative capacity. */
    EMPLOYEE,

    /**
     * A named external system acting through an integration credential.
     *
     * <p>Distinct from {@link #SYSTEM}: "our own scheduler did this" and "a third party's
     * server did this on their credential" are different answers to the same question, and
     * only one of them has a counterparty to ask about it.
     */
    SERVICE;

    /**
     * The types as a SQL literal list, for the {@code CHECK} constraint.
     *
     * <p>Generated rather than typed out, so adding a type without a migration cannot be done
     * quietly: the constraint and the enum are one definition.
     */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(type -> "'" + type.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
