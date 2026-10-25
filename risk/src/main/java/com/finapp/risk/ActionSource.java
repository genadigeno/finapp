package com.finapp.risk;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Where a risk action comes from (`P13-TSK-017`; ADR-0112 decision 3): a case's investigator proposed it, or an approved
 * override issued it (`P13-TSK-012`'s, folded into this machine - its id is the override's, so its key is
 * {@code risk.action:<override id>}).
 */
public enum ActionSource {

    /** Proposed on a case by one {@code RISK_INVESTIGATE} holder, approved by another. */
    CASE,

    /** Issued by an approved decision override - born {@code APPROVED} by the override's own approval (C7). */
    OVERRIDE;

    /** The vocabulary as a SQL list - {@code V013}'s CHECK is held to it. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
