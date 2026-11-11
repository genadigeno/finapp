package com.finapp.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.crypto.Mac;
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
 * <h2>A version's value replaced after it was listed refuses (`P15-TSK-020`)</h2>
 *
 * <p>Nothing in a version's number says which bytes it stands for, so a slot whose stored value was overwritten while the
 * version kept its number reads as the same version, and every row written under the old value fails its authentication tag,
 * indistinguishable from tampering and unreadable for ever ({@code THREAT_MODEL.md} section 6 item 32 (a)). A <em>check
 * value</em> is published beside the version in the ring's non-secret configuration: {@link #checkValueOf} of its key, a
 * keyed digest of a fixed label. A replica whose stored value for a version does not give that version's published check
 * value refuses to start. Every check value given is checked here. <strong>Which ones must be given is the configuration's
 * rule</strong> ({@code ConfinedKeyRing}): once a ring holds more than one version, every held version needs one, because that
 * is the moment a rotation began and a version listed without one is a version nothing would notice being replaced.
 *
 * <h2>Immutable, per process</h2>
 *
 * <p>Built at startup from the environment. Activation is a rollout, not a method call: during a rolling update two rings
 * exist side by side, one per release, and each is complete on its own. Nothing is shared between replicas, and no key is
 * ever re-read. Ten replicas each compute their own check values from their own copy of the bytes; none decides for another.
 */
@Slf4j
public final class KeyRing {

    /** AES-256: exactly thirty-two bytes, because AES accepts sixteen and quietly gives AES-128. */
    public static final int KEY_BYTES = 32;

    /**
     * What a check value is the keyed digest of: fixed, so that a person computing one from a key and a replica computing
     * it from its copy arrive at the same value, and never a value anything else signs or encrypts.
     */
    public static final String CHECK_VALUE_LABEL = "finapp key ring check value v1";

    /** How much of the digest is published: sixty-four bits, so two different keys agree by chance once in 2^64. */
    public static final int CHECK_VALUE_BYTES = 8;

    private static final String CHECK_VALUE_ALGORITHM = "HmacSHA256";

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
        return of(name, versions, active, retired, Map.of());
    }

    /**
     * Builds a ring, as {@link #of(String, Map, int, Set)}, and holds each version's key to its published check value.
     *
     * @param checkValues each given version's {@link #checkValueOf check value}, {@value #CHECK_VALUE_BYTES} bytes as
     *     hexadecimal; every one given is checked
     * @throws IllegalStateException on any refusal of {@link #of(String, Map, int, Set)}; on a check value for a version not
     *     held, malformed, or not the one the stored value gives. Every message names versions only
     */
    public static KeyRing of(
            String name, Map<Integer, byte[]> versions, int active, Set<Integer> retired, Map<Integer, String> checkValues) {
        Objects.requireNonNull(checkValues, "checkValues must not be null");
        KeyRing ring = structured(name, versions, active, retired);
        ring.holdTo(checkValues);
        return ring;
    }

    /**
     * The check value of one key: HMAC-SHA256 of {@value #CHECK_VALUE_LABEL} under the key, its first
     * {@value #CHECK_VALUE_BYTES} bytes, lower-case hexadecimal. Not secret: a pseudorandom function's output under a key
     * nobody can guess says nothing about the key, and it is published so that a replaced value can be told apart.
     */
    public static String checkValueOf(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            Mac mac = Mac.getInstance(CHECK_VALUE_ALGORITHM);
            mac.init(new SecretKeySpec(key, CHECK_VALUE_ALGORITHM));
            byte[] digest = mac.doFinal(CHECK_VALUE_LABEL.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest, 0, CHECK_VALUE_BYTES);
        } catch (NoSuchAlgorithmException | InvalidKeyException unavailable) {
            // Every JRE ships HmacSHA256; reaching here is a broken runtime, never a key's fault. Never the key in the message.
            throw new IllegalStateException("HmacSHA256 is not available to compute a key ring check value", unavailable);
        }
    }

    private void holdTo(Map<Integer, String> checkValues) {
        for (int version : new TreeSet<>(checkValues.keySet())) {
            if (!held.containsKey(version)) {
                throw new IllegalStateException(
                        "The " + name + " has a check value for key version " + version + ", which this instance does not hold:"
                                + " a check value names a held version, and a retired one has no key to check");
            }
            String published = checkValues.get(version);
            if (published == null || !published.matches("[0-9a-fA-F]{" + (2 * CHECK_VALUE_BYTES) + "}")) {
                throw new IllegalStateException(
                        "The " + name + " check value of key version " + version + " must be " + (2 * CHECK_VALUE_BYTES)
                                + " hexadecimal characters");
            }
        }
        for (Map.Entry<Integer, String> published : new TreeMap<>(checkValues).entrySet()) {
            byte[] expected = HexFormat.of().parseHex(published.getValue().toLowerCase(java.util.Locale.ROOT));
            byte[] stored = HexFormat.of().parseHex(checkValueOf(held.get(published.getKey()).getEncoded()));
            if (!MessageDigest.isEqual(expected, stored)) {
                // Neither value is said: the published one is in the configuration already, and the stored one would be a fact
                // about the key this replica holds.
                throw new IllegalStateException(
                        "The " + name + " stored value of key version " + published.getKey() + " does not give its published check"
                                + " value: the value under that version was replaced after it was listed, or the check value was"
                                + " computed from another value. Refusing to start rather than read or write under a key the rows"
                                + " of that version were not written with");
            }
        }
    }

    private static KeyRing structured(String name, Map<Integer, byte[]> versions, int active, Set<Integer> retired) {
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
