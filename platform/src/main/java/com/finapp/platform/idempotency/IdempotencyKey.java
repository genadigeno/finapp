package com.finapp.platform.idempotency;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * What a claim is claimed on: a command scope plus the caller's key.
 *
 * <p><strong>Why the scope is part of the key and not an afterthought.</strong> A client
 * generating one key per business action would otherwise find its second command rejected
 * because an unrelated command already used that key. Worse, two different clients could
 * collide, and one would silently receive the other's response. The scope carries the command
 * type and the owning principal, so neither can happen (ADR-0004).
 *
 * <p>Bounds mirror the {@code CHECK} constraints on {@code platform.idempotency_record}. They
 * are asserted here so a caller gets a domain error rather than a constraint violation from
 * three layers down, and asserted there so the guarantee does not depend on this class being
 * the only writer.
 */
public record IdempotencyKey(String scope, String key) implements Serializable {

    @Serial private static final long serialVersionUID = 1L;


    /** Matches {@code idempotency_record_scope_bounded} and {@code _key_bounded}. */
    public static final int MAX_LENGTH = 200;

    public IdempotencyKey {
        scope = required(scope, "scope");
        key = required(key, "idempotency key");
    }

    /**
     * This key's value under the scope it moved from (`X-TSK-003`): the claim a command made before its scope named its
     * principal, which {@link IdempotentExecutor}'s transition consults first, so a retry spanning the move finds it. Every
     * call is the transition's and goes when it does (`X-TSK-028`).
     *
     * @param scope the old scope, which must differ from this key's
     */
    public IdempotencyKey movedFrom(String scope) {
        IdempotencyKey before = new IdempotencyKey(scope, key);
        if (before.scope.equals(this.scope)) {
            throw new IllegalArgumentException("a moved scope differs from the one it moved from: " + scope);
        }
        return before;
    }

    private static String required(String value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isBlank()) {
            // An empty key is a client defect. Generating one instead would hide it and produce
            // a claim the client can never match on retry.
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    what + " must be at most " + MAX_LENGTH + " characters but was " + value.length());
        }
        return value;
    }

    @Override
    public String toString() {
        return scope + "/" + key;
    }
}
