package com.finapp.risk;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The judged command's identity within its operation (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 4, 7.1;
 * {@code INV-RSK-01}) - what {@code UNIQUE (operation, command_ref)} holds once: the seam adapter names it from its own
 * aggregate (a transfer's id, a withdrawal's id, a payment intent's, a cross-border payment's), so a retry of the
 * command - under ten instances, after a timeout, from a second seam in one transaction - finds the sealed assessment
 * and is answered with it.
 *
 * <p>A token, never free text: {@code [A-Za-z0-9][A-Za-z0-9_-]{0,127}} - a canonical UUID fits, as does a
 * {@code transfer-<uuid>} shape - the grammar the event payload carries ({@code risk.RiskDecisionMade}) and
 * {@code risk V007}'s {@code CHECK} holds for every writer.
 */
public record CommandRef(String value) {

    public static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    public CommandRef {
        Objects.requireNonNull(value, "value");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("a command ref is 1 to 128 characters of [A-Za-z0-9_-], starting with a"
                    + " letter or digit");
        }
    }

    /** The command's identity as its canonical UUID. */
    public static CommandRef of(UUID command) {
        return new CommandRef(Objects.requireNonNull(command, "command").toString());
    }

    public static CommandRef of(String value) {
        return new CommandRef(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
