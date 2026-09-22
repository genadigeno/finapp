package com.finapp.merchant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The MerchantApiKey machine (`P6-TSK-002`, {@code INV-LIFE-01}):
 *
 * <pre>ACTIVE -> REVOKED</pre>
 *
 * <p>Two states and one edge — the smallest machine on the platform, and it is still a
 * machine: {@code REVOKED} is terminal ({@code INV-LIFE-04}), so a revoked key can never be
 * reinstated. That is not a limitation to work around; reinstating a credential whose secret
 * may have been disclosed is precisely the act a security model must make impossible. A
 * merchant that needs access again is issued a NEW key with a new secret.
 *
 * <p>The schema {@code CHECK} is generated from {@link #sqlValueList()} and the live-key index
 * predicate from {@link #sqlTerminalValueList()}; {@code MerchantApiKeyMigrationTest} fails the
 * build if this enum and `V003` disagree.
 */
public enum MerchantApiKeyStatus {

    /** The key authenticates — subject to the merchant's own standing, checked per request. */
    ACTIVE,

    /** Terminal. Refuses immediately on every instance, and is never reinstated. */
    REVOKED;

    /** The states reachable from this one. */
    public Set<MerchantApiKeyStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(REVOKED);
            case REVOKED -> EnumSet.noneOf(MerchantApiKeyStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list, for the live-key index predicate — generated
     * so "terminal" and "no longer authenticates" are one definition.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(MerchantApiKeyStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
