package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of channel this is (`P1-TSK-023`).
 *
 * <h2>One value, and the enum still earns its place</h2>
 *
 * <p>`EXECUTION_PROTOCOL.md` rule 3 asks for a seam rather than the later capability, and a phone
 * channel is that later capability - it needs an SMS adapter, a different verification shape and a
 * different set of abuse cases. What the enum provides now is that the uniqueness rule is
 * <strong>per kind</strong>: "one verified channel" is the wrong rule the moment a second kind
 * exists, and a partial unique index written without it would have to be rebuilt on a live table.
 */
public enum ContactChannelKind {

    /** The only kind Phase 1 has. */
    EMAIL;

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(kind -> "'" + kind.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
