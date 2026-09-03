package com.finapp.ledger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * A deliberate violation of {@code noStaticMutableState}.
 *
 * <p>The most convincing shape: a `ConcurrentHashMap`, which announces thread safety and says
 * nothing at all about instance safety. Every replica gets its own, so the balance it caches is
 * whatever that replica last saw (CLAUDE.md rule 12).
 */
@SuppressWarnings("unused")
public final class StaticMutableStateProbe {

    private static final Map<String, Long> BALANCE_CACHE = new ConcurrentHashMap<>();

    public static void remember(String account, long balance) {
        BALANCE_CACHE.put(account, balance);
    }
}
