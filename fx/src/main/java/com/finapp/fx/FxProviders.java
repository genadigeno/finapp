package com.finapp.fx;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The FX providers the running build composes (`P9-TSK-006`): code to declaration and adapter.
 *
 * <p>Built once at the composition root and immutable after: a duplicate code is refused (two
 * adapters answering for one counterparty would make "which provider executed" ambiguous), and so
 * is an adapter with no declaration - the pricing policy may name only declared providers, and a
 * provider the build cannot describe cannot be sourced from.
 */
public final class FxProviders {

    /** One composed provider: what the build declares about it, and the adapter that speaks to it. */
    public record Composed(FxProviderDeclaration declaration, FxProvider adapter) {
        public Composed {
            Objects.requireNonNull(declaration, "declaration must not be null");
            Objects.requireNonNull(adapter, "adapter must not be null");
            if (!declaration.code().equals(adapter.code())) {
                throw new IllegalArgumentException(
                        "an adapter answers for its own declaration: " + adapter.code()
                                + " is not " + declaration.code());
            }
        }
    }

    private final Map<String, Composed> byCode;

    public FxProviders(Collection<Composed> composed) {
        Objects.requireNonNull(composed, "composed must not be null");
        Map<String, Composed> map = new LinkedHashMap<>();
        for (Composed provider : composed) {
            if (map.putIfAbsent(provider.declaration().code(), provider) != null) {
                throw new IllegalArgumentException(
                        "two providers declare the code " + provider.declaration().code());
            }
        }
        this.byCode = Map.copyOf(map);
    }

    /** The composed provider {@code code}, when the build declares it. */
    public Optional<Composed> find(String code) {
        return Optional.ofNullable(byCode.get(Objects.requireNonNull(code, "code must not be null")));
    }

    /** Every composed provider's declaration. */
    public List<FxProviderDeclaration> declarations() {
        return byCode.values().stream().map(Composed::declaration).toList();
    }
}
