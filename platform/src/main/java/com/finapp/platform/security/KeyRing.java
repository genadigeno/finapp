package com.finapp.platform.security;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

/**
 * Every version of one field-level encryption key (`P15-TSK-019`, ADR-0123 point 4).
 *
 * <p>A ring belongs to a <em>key</em>, not to a cipher class: {@code finapp.doc.key}'s ring is read by both
 * {@code DocumentCipher} and {@code CounterpartySubjectCipher}, the object shared. One version is {@link #activeVersion()
 * active} and writes every new ciphertext. The others are retained, and decrypt only the rows stamped with them.
 *
 * <h2>Two rules this class exists to hold</h2>
 *
 * <p><strong>The stamped version decides the key, never the active one.</strong> A row carries the version that wrote it;
 * {@link #keyFor} is the only way to the key for a read, and a version this ring does not hold is refused rather than
 * tried under another key: decrypting a v1 row with v2's key would fail only at the authentication tag, a failure that
 * cannot be told from tampering. <strong>The active version is always held.</strong> A replica cannot be built around a
 * version it could not itself read back, which is how "a replica holding an active version it does not know" is refused
 * at startup instead of at the first write.
 *
 * <h2>Holes refuse</h2>
 *
 * <p>Every integer between the lowest and the highest version named here is held or retired. A version dropped by a
 * configuration mistake would otherwise read as "no such version" only when somebody tried to read a row under it.
 *
 * <h2>Immutable, per process</h2>
 *
 * <p>Built at startup from the environment. Activation is a rollout, not a method call: during a rolling update two rings
 * exist side by side, one per release, and each is complete on its own. Nothing is shared between replicas, and no key is
 * ever re-read.
 */
@Slf4j
public final class KeyRing {

    /** AES-256: exactly thirty-two bytes, because AES accepts sixteen and quietly gives AES-128. */
    public static final int KEY_BYTES = 32;

    private final String name;
    private final NavigableMap<Integer, SecretKeySpec> held;
    private final NavigableSet<Integer> retired;
    private final int active;

    private KeyRing(String name, NavigableMap<Integer, SecretKeySpec> held, NavigableSet<Integer> retired, int active) {
        this.name = name;
        this.held = held;
        this.retired = retired;
        this.active = active;
    }

    /** A ring of one version, active: today's single key, unrotated. */
    public static KeyRing single(String name, byte[] key, int version) {
        return of(name, Map.of(version, key), version, Set.of());
    }

    /**
     * Builds a ring, refusing anything that could lose a row or write one nobody can read.
     *
     * @param name names this ring in a refusal; never key material
     * @param versions every held version's key bytes, exactly {@value #KEY_BYTES} each
     * @param active the version new ciphertexts are written under; must be held
     * @param retired versions no row stands under any more; their keys are not held
     * @throws IllegalStateException on any of the above; every message names versions only, never a key or its length
     */
    public static KeyRing of(String name, Map<Integer, byte[]> versions, int active, Set<Integer> retired) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(versions, "versions must not be null");
        Objects.requireNonNull(retired, "retired must not be null");
        if (versions.isEmpty()) {
            throw new IllegalStateException("The " + name + " holds no key version");
        }
        NavigableMap<Integer, SecretKeySpec> held = new TreeMap<>();
        for (Map.Entry<Integer, byte[]> version : versions.entrySet()) {
            int number = version.getKey();
            if (number < 1) {
                throw new IllegalStateException("The " + name + " names a key version that is not positive");
            }
            if (version.getValue() == null || version.getValue().length != KEY_BYTES) {
                // Never the length seen: it is a fact about the key material.
                throw new IllegalStateException(
                        "The " + name + " key version " + number + " must be exactly " + KEY_BYTES + " bytes");
            }
            held.put(number, new SecretKeySpec(version.getValue().clone(), "AES"));
        }
        NavigableSet<Integer> retiredVersions = new TreeSet<>(retired);
        for (int version : retiredVersions) {
            if (version < 1) {
                throw new IllegalStateException("The " + name + " names a retired key version that is not positive");
            }
            if (held.containsKey(version)) {
                throw new IllegalStateException(
                        "The " + name + " version " + version + " is both held and retired: retiring a version"
                                + " removes its key from the ring");
            }
        }
        if (retiredVersions.contains(active)) {
            throw new IllegalStateException(
                    "The " + name + " active version " + active + " is retired: a retired version is refused for encryption");
        }
        if (!held.containsKey(active)) {
            throw new IllegalStateException(
                    "The " + name + " active version " + active + " is not held by this instance: refusing to"
                            + " start rather than write a version it could not read back");
        }
        int lowest = retiredVersions.isEmpty() ? held.firstKey() : Math.min(held.firstKey(), retiredVersions.first());
        int highest = retiredVersions.isEmpty() ? held.lastKey() : Math.max(held.lastKey(), retiredVersions.last());
        for (int version = lowest; version <= highest; version++) {
            if (!held.containsKey(version) && !retiredVersions.contains(version)) {
                throw new IllegalStateException(
                        "The " + name + " has a hole at key version " + version
                                + ": it is neither held nor retired, so rows under it would be unreadable with"
                                + " nothing having said so");
            }
        }
        return new KeyRing(
                name,
                Collections.unmodifiableNavigableMap(held),
                Collections.unmodifiableNavigableSet(retiredVersions),
                active);
    }

    public String name() {
        return name;
    }

    /** The version every new ciphertext is written under. */
    public int activeVersion() {
        return active;
    }

    /** The active version's key: a ciphertext it writes must be stamped {@link #activeVersion()}. */
    public SecretKey activeKey() {
        return held.get(active);
    }

    /**
     * The key of the version stamped on a row.
     *
     * @throws UnknownKeyVersionException if this ring does not hold that version, retired or unknown alike: never answered
     *     with another version's key
     */
    public SecretKey keyFor(int stampedVersion) {
        return keyFor(stampedVersion, "The " + name + " holds no key version " + stampedVersion);
    }

    /**
     * The key of the version stamped on a row, for a cipher whose refusals must not distinguish a wrong version from
     * tampering: its callers cannot be told which version a row was written under. The miss itself is logged here, by the
     * ring's name and the version's number, which an operator needs and a caller never sees.
     *
     * @param refusal the message of the refusal: the cipher's own, generic one
     * @throws UnknownKeyVersionException if this ring does not hold that version
     */
    public SecretKey keyFor(int stampedVersion, String refusal) {
        SecretKey key = held.get(stampedVersion);
        if (key == null) {
            log.warn("The {} holds no key version {}: a row stamped with it cannot be read here", name, stampedVersion);
            throw new UnknownKeyVersionException(refusal, stampedVersion);
        }
        return key;
    }

    public boolean holds(int version) {
        return held.containsKey(version);
    }

    /** Every version with a key here, ascending. */
    public NavigableSet<Integer> heldVersions() {
        return held.navigableKeySet();
    }

    /** Versions whose key is gone, ascending. */
    public NavigableSet<Integer> retiredVersions() {
        return retired;
    }

    /** Versions and their states only, ever ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "KeyRing[" + name + ", active=" + active + ", held=" + held.navigableKeySet() + ", retired=" + retired + "]";
    }
}
