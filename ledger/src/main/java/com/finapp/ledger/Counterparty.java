package com.finapp.ledger;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A declared external party owning clearing positions (`P9-TSK-010`, ADR-0078 section 2;
 * {@code GLOSSARY.md} "Counterparty"): an FX provider, a corridor provider - distinct from a
 * customer, a merchant or a beneficiary. Registered by migration only, never minted at runtime,
 * never renamed: a new code is a new counterparty with new accounts.
 *
 * @param id the registry row's identity - what a {@link OwnerKind#COUNTERPARTY} account's
 *     {@code owner_ref} names
 * @param code the stable code its declaration carries ({@code fx-sim-a}); also the database
 *     {@code CHECK}'s shape
 * @param kind what the counterparty is
 */
public record Counterparty(UUID id, String code, CounterpartyKind kind) {

    /** The code's shape - lowercase, at most 32 characters; the {@code FxProviderDeclaration} shape. */
    public static final Pattern CODE_SHAPE = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public Counterparty {
        Objects.requireNonNull(id, "id must not be null");
        requireCode(code);
        Objects.requireNonNull(kind, "kind must not be null");
    }

    /** Refuses a code that could not name a registry row. */
    public static String requireCode(String code) {
        Objects.requireNonNull(code, "code must not be null");
        if (!CODE_SHAPE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "a counterparty code is lowercase, starts with a letter and is at most 32"
                            + " characters; '" + code + "' is not");
        }
        return code;
    }
}
