package com.finapp.platform.sourcejoin;

import java.util.Objects;

/**
 * One posting namespace as the register keys it: the door and the key's namespace ({@code transfer:}, or for an adjustment its
 * proposal's origin, {@code RECONCILIATION}). A namespace written through two doors is two joins: a reversal mirrors, a posting
 * states.
 */
public record SourceNamespace(SourceDoor door, String namespace) {

    public SourceNamespace {
        Objects.requireNonNull(door, "door");
        Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("a namespace names something");
        }
    }
}
