package com.finapp.accounting;

import java.util.regex.Pattern;

/**
 * A GL account of one mapping version (`P14-TSK-003`; ADR-0113 decision 3; {@code PHASE_14_PLAN.md} section 13.1): its
 * code, name, classification and the statement section it is presented in; its normal side is its classification's.
 * <strong>Reference values for qualified review</strong> (owner decision C2): what is not reference is the rule that a
 * section admits only its statement's classifications ({@link StatementSection#admits}) - refused here, and by
 * {@code accounting V002}'s {@code gl_account_section_admits_classification} for every writer.
 */
public record GlAccount(String code, String name, GlClassification classification, StatementSection section) {

    /** {@code accounting V002}'s {@code gl_account_code_shape}. */
    public static final Pattern CODE = Pattern.compile("[0-9]{4,8}");

    /** {@code accounting V002}'s {@code gl_account_name_bounded}. */
    public static final int NAME_BOUND = 120;

    public GlAccount {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new GlMappingVersion.MappingInvalid("a GL account code is 4 to 8 digits");
        }
        if (name == null || name.isBlank() || name.length() > NAME_BOUND || !name.equals(name.strip())) {
            throw new GlMappingVersion.MappingInvalid(
                    "GL account " + code + " needs a name of 1 to " + NAME_BOUND + " characters, unpadded");
        }
        if (classification == null || section == null) {
            throw new GlMappingVersion.MappingInvalid("GL account " + code + " names a classification and a section");
        }
        if (!section.admits(classification)) {
            throw new GlMappingVersion.MappingInvalid("GL account " + code + ": section " + section.name()
                    + " does not admit a " + classification.name() + " (" + section.statement().name() + ")");
        }
    }

    /** {@code DEBIT} or {@code CREDIT} - the classification's normal side, as the ledger's account type's. */
    public String normalBalance() {
        return classification.debitNormal() ? "DEBIT" : "CREDIT";
    }
}
