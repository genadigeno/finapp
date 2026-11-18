package com.finapp.identity;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The segregation-of-duties table (`P17-TSK-016`, gap G-14; the owner's decision of 2026-11-18): pairs of roles one person never
 * holds together, because together they would let one person complete what is meant to take two. The per-act rule - the proposer
 * cannot approve - stops one person doing both halves of one act; this stops one person being both populations.
 *
 * <p>Seven pairs, the owner's:
 * <ul>
 *   <li><strong>the propose/approve splits</strong> - one role proposes, the other approves the same act: {@code ACCOUNTANT} and
 *       {@code FINANCIAL_CONTROLLER}; {@code REGULATORY_REPORTER} and {@code FINANCIAL_CONTROLLER}; {@code LOAN_SERVICING_AGENT}
 *       and {@code LENDING_OFFICER}; {@code BNPL_SERVICING_AGENT} and {@code BNPL_OFFICER};
 *   <li><strong>B3.3's examples</strong>: {@code RECONCILIATION_OPERATOR} and {@code RECONCILIATION_CONTROLLER} (resolving breaks,
 *       and shaping the register they are judged by); {@code RISK_ANALYST} and {@code RISK_OFFICER} (a case's approval, and the
 *       policy and its overrides);
 *   <li><strong>the checker and the checked</strong>: {@code LEDGER_OPERATOR} and {@code RECONCILIATION_OPERATOR} - the
 *       reconciliation desk checks the money desk's work, which {@link RoleName#RECONCILIATION_OPERATOR} has always said must stay
 *       separate populations.
 * </ul>
 * Not {@code CREDIT_POLICY_OFFICER} and {@code UNDERWRITER}: no four-eyes act spans the two today. {@code RoleConflictsTest} pins
 * the table; {@code PRIVILEGED_ACCESS_REVIEW.md} lists it. Changing it is a reviewed code change and the owner's decision.
 */
public final class RoleConflicts {

    /** One conflicting pair, unordered. */
    public record Pair(RoleName first, RoleName second) {

        public Pair {
            if (first == second) {
                throw new IllegalArgumentException("a role does not conflict with itself: " + first);
            }
        }

        boolean involves(RoleName role) {
            return first == role || second == role;
        }

        RoleName other(RoleName role) {
            return first == role ? second : first;
        }
    }

    /** The owner's seven pairs (2026-11-18). */
    public static final List<Pair> PAIRS = List.of(
            new Pair(RoleName.ACCOUNTANT, RoleName.FINANCIAL_CONTROLLER),
            new Pair(RoleName.REGULATORY_REPORTER, RoleName.FINANCIAL_CONTROLLER),
            new Pair(RoleName.LOAN_SERVICING_AGENT, RoleName.LENDING_OFFICER),
            new Pair(RoleName.BNPL_SERVICING_AGENT, RoleName.BNPL_OFFICER),
            new Pair(RoleName.RECONCILIATION_OPERATOR, RoleName.RECONCILIATION_CONTROLLER),
            new Pair(RoleName.RISK_ANALYST, RoleName.RISK_OFFICER),
            new Pair(RoleName.LEDGER_OPERATOR, RoleName.RECONCILIATION_OPERATOR));

    private RoleConflicts() {}

    /** Every role {@code role} conflicts with. */
    public static Set<RoleName> conflictsOf(RoleName role) {
        Set<RoleName> conflicts = EnumSet.noneOf(RoleName.class);
        for (Pair pair : PAIRS) {
            if (pair.involves(role)) {
                conflicts.add(pair.other(role));
            }
        }
        return conflicts;
    }

    /** A role of {@code held} that {@code role} conflicts with, the first in {@link RoleName}'s order, or empty. */
    public static Optional<RoleName> conflictWith(RoleName role, Collection<RoleName> held) {
        Set<RoleName> conflicts = conflictsOf(role);
        return held.stream().filter(conflicts::contains).sorted().findFirst();
    }
}
