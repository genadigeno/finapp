package com.finapp.accounting;

import java.util.regex.Pattern;

/**
 * One rule of a GL mapping version (`P14-TSK-003`; ADR-0113 decision 3; owner decision C2): the ledger purpose and its
 * owner kind - the ledger's names, as strings, {@code accounting} having no ledger edge - map to one GL account of the
 * same version. A counterparty is a dimension under the purpose's GL account, never a key: there is no counterparty
 * here. Whether the purpose exists, carries this owner kind and has a type the GL account's classification matches is
 * judged at activation against the ledger's chart ({@link MappingJudgement}).
 */
public record MappingRule(String purpose, String ownerKind, String glAccountCode) {

    /** {@code accounting V002}'s {@code gl_mapping_rule_purpose_shape}. */
    public static final Pattern PURPOSE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** {@code accounting V002}'s {@code gl_mapping_rule_owner_kind_shape}. */
    public static final Pattern OWNER_KIND = Pattern.compile("[A-Z][A-Z_]{0,31}");

    public MappingRule {
        if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
            throw new GlMappingVersion.MappingInvalid("a rule names a ledger purpose (upper-case, at most 64 characters)");
        }
        if (ownerKind == null || !OWNER_KIND.matcher(ownerKind).matches()) {
            throw new GlMappingVersion.MappingInvalid("the rule for " + purpose + " names an owner kind");
        }
        if (glAccountCode == null || !GlAccount.CODE.matcher(glAccountCode).matches()) {
            throw new GlMappingVersion.MappingInvalid("the rule for " + purpose + " names a GL account code");
        }
    }

    /** The rule's key - the ledger purpose and owner kind it maps. */
    public String key() {
        return purpose + "/" + ownerKind;
    }
}
