package com.finapp.accounting;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The content of one GL mapping version - its GL chart and its rules - immutable from birth (`P14-TSK-003`; ADR-0113
 * decision 3; {@code INV-ACC-07}): the calendar month it takes effect from (owner decision C9), its GL accounts, and one
 * rule per (ledger purpose, owner kind) naming one of those accounts. Judged here for its own coherence - codes unique,
 * every rule's account present, one rule per key, every section admitting its account's classification - before
 * anything is locked or written; judged at activation for totality and classification-compatibility against the
 * ledger's chart as it then stands ({@link MappingJudgement}), because the chart may grow between the two.
 */
public record GlMappingVersion(YearMonth effectiveFrom, List<GlAccount> accounts, List<MappingRule> rules) {

    /** The most GL accounts and rules a version may carry - a bound on the proposal, not a business limit. */
    public static final int MAX_ENTRIES = 400;

    /** The version is not coherent - nothing was proposed. */
    public static final class MappingInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public MappingInvalid(String detail) {
            super(detail);
        }
    }

    public GlMappingVersion {
        if (effectiveFrom == null) {
            throw new MappingInvalid("a version names the calendar month it takes effect from");
        }
        if (accounts == null || accounts.isEmpty() || accounts.size() > MAX_ENTRIES) {
            throw new MappingInvalid("a version carries 1 to " + MAX_ENTRIES + " GL accounts");
        }
        if (rules == null || rules.isEmpty() || rules.size() > MAX_ENTRIES) {
            throw new MappingInvalid("a version carries 1 to " + MAX_ENTRIES + " rules");
        }
        accounts = List.copyOf(accounts);
        rules = List.copyOf(rules);
        Set<String> codes = new HashSet<>();
        for (GlAccount account : accounts) {
            if (!codes.add(account.code())) {
                throw new MappingInvalid("GL account " + account.code() + " appears twice");
            }
        }
        Set<String> keys = new HashSet<>();
        for (MappingRule rule : rules) {
            if (!keys.add(rule.key())) {
                throw new MappingInvalid("two rules map " + rule.key() + ": a journal line maps to exactly one GL account");
            }
            if (!codes.contains(rule.glAccountCode())) {
                throw new MappingInvalid("the rule for " + rule.key() + " names GL account " + rule.glAccountCode()
                        + ", which this version does not hold");
            }
        }
    }

    /** The first day of the effective month - {@code accounting V002}'s {@code effective_from_period}. */
    public java.time.LocalDate effectiveFromPeriod() {
        return effectiveFrom.atDay(1);
    }

    /** The GL account by code. */
    public Optional<GlAccount> account(String code) {
        return accounts.stream().filter(account -> account.code().equals(code)).findFirst();
    }

    /** The rules by key ({@link MappingRule#key()}). */
    public Map<String, MappingRule> rulesByKey() {
        Map<String, MappingRule> byKey = new HashMap<>();
        rules.forEach(rule -> byKey.put(rule.key(), rule));
        return byKey;
    }
}
