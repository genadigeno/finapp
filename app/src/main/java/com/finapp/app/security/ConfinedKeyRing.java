package com.finapp.app.security;

import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.platform.security.KeyRing;
import com.finapp.platform.security.PreviousKeyWindow;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

/**
 * Reads a key's ring, and a callback door's previous-key window, from the environment (`P15-TSK-019`, ADR-0123 points 4 and
 * 5), through the same confinement as the key they extend: every version's value goes through
 * {@link KeySpec#decode}, so the marked local default is refused off loopback on every version of a ring.
 *
 * <h2>Known, then active: why they are two inputs</h2>
 *
 * <p>{@code ring.versions} lists what this replica <em>holds</em>; a listed version with no value refuses this replica at
 * startup, so when a rollout has finished, every running replica holds what the configuration listed. Only then does
 * {@code ring.active-version} move, in a later change. {@code ring.retired-versions} is the third input: a version
 * whose key is gone, checked against the real tables by {@code KeyRingRetirementGuard}. All three are non-secret, so a
 * change to any of them is a line in a reviewed diff, and absent all three the ring is the base key alone.
 *
 * <h2>What cannot be seen from here</h2>
 *
 * <p>A version dropped off the top of the ring leaves no hole: nothing in a replica's configuration says how high the ring
 * once went. {@code infra/scripts/key-ring-activation-check.sh} catches that in CI, by comparing a change with its base
 * (a {@code git revert} of a rotation, say). A {@code kubectl rollout undo} never reaches CI, so the runbook forbids it for a
 * ring change (section 11.29).
 *
 * <p>Refusals name properties and versions, never a value: a key pasted into the wrong property would otherwise reach a
 * log line (INV-AUD-02).
 */
@Slf4j
public final class ConfinedKeyRing {

    private ConfinedKeyRing() {}

    /**
     * One key's ring configuration.
     *
     * @param versions the property listing the held versions, comma-separated, default the base version alone
     * @param activeVersion the property holding the active pointer, default the base version
     * @param retiredVersions the property listing retired versions, default none
     * @param slots the property of each further version's key; a version beyond these has no way in
     */
    public record RingProperties(String versions, String activeVersion, String retiredVersions, Map<Integer, String> slots) {

        public RingProperties {
            Objects.requireNonNull(versions, "versions");
            Objects.requireNonNull(activeVersion, "activeVersion");
            Objects.requireNonNull(retiredVersions, "retiredVersions");
            slots = Map.copyOf(slots);
        }
    }

    /**
     * Builds a ring. Absent ring configuration, one version, the base key: byte for byte today's behaviour.
     *
     * @throws IllegalStateException on any configuration this class or {@link KeyRing} refuses
     */
    public static KeyRing read(
            KeySpec spec, RingProperties properties, String configuredBase, int baseVersion, Environment environment,
            boolean localDefaultPermitted) {
        Objects.requireNonNull(configuredBase, "The " + spec.name() + " must be configured");
        if (baseVersion < 1) {
            throw new IllegalStateException("The " + spec.name() + " key-version must be positive");
        }
        Set<Integer> versions = versionsOf(environment, properties.versions(), Set.of(baseVersion));
        Set<Integer> retired = versionsOf(environment, properties.retiredVersions(), Set.of());
        int active = activeOf(environment, properties.activeVersion(), baseVersion);

        // Version numbers start at 1. A base key-version above 1 implies rows under the lower ones unless they are declared
        // retired, which sends the question to the guard that reads the tables.
        for (int lower = 1; lower < baseVersion; lower++) {
            if (!versions.contains(lower) && !retired.contains(lower)) {
                throw new IllegalStateException(
                        "The " + spec.name() + " base key-version is " + baseVersion + " and version " + lower + " is neither held nor"
                                + " retired: rows stamped with it would be refused with nothing having said so");
            }
        }
        if (!versions.contains(baseVersion) && !retired.contains(baseVersion)) {
            throw new IllegalStateException(
                    "The " + spec.name() + " base version " + baseVersion + " is neither held nor retired: every version"
                            + " from the first must stay held until it is declared retired");
        }
        Map<Integer, byte[]> keys = new TreeMap<>();
        for (int version : versions) {
            keys.put(version, keyOf(spec, properties, version, baseVersion, configuredBase, environment, localDefaultPermitted));
        }
        KeyRing ring = KeyRing.of(spec.name(), keys, active, retired);
        log.info("Key ring: {}", ring);
        return ring;
    }

    private static byte[] keyOf(
            KeySpec spec, RingProperties properties, int version, int baseVersion, String configuredBase,
            Environment environment, boolean localDefaultPermitted) {
        String slot = properties.slots().get(version);
        if (version == baseVersion) {
            if (slot != null && isSet(environment.getProperty(slot))) {
                throw new IllegalStateException(
                        "The " + spec.name() + " version " + version + " is named twice: by the base key and by " + slot);
            }
            return spec.decode(configuredBase, localDefaultPermitted);
        }
        if (slot == null) {
            throw new IllegalStateException(
                    "The " + spec.name() + " has no configured place for version " + version + ": add one (a reviewed change, an entry of the"
                            + " inventory and of the ExternalSecret) before listing it");
        }
        String configured = environment.getProperty(slot);
        KeySpec versioned = spec.forVersion(version, environmentForm(slot));
        // An unset version falls to the marked default, confined to loopback by decode: refused off it, naming the variable.
        return versioned.decode(isSet(configured) ? configured : ConfinedCredential.MARKED_LOCAL_DEFAULT, localDefaultPermitted);
    }

    /**
     * The previous signing key's window for one door (`P15-TSK-019`). Needs both the key and the instant: a key with no
     * instant opens nothing (a value waiting in the store is not a window), and an instant with no key is a mistake,
     * refused. Past its instant, the window is closed and the replica starts anyway.
     *
     * @throws IllegalStateException if the instant does not parse, is further ahead than the window allows, or has no key
     */
    public static Optional<PreviousKeyWindow> previousWindow(
            KeySpec spec, String previousKeyProperty, String validUntilProperty, Environment environment,
            boolean localDefaultPermitted, Instant now) {
        String configuredUntil = environment.getProperty(validUntilProperty);
        if (!isSet(configuredUntil)) {
            return Optional.empty();
        }
        String configuredKey = environment.getProperty(previousKeyProperty);
        if (!isSet(configuredKey)) {
            throw new IllegalStateException(
                    "The " + spec.name() + " has a previous-key window (" + validUntilProperty + ") and no previous key: refusing to start");
        }
        Instant validUntil;
        try {
            validUntil = Instant.parse(configuredUntil.trim());
        } catch (DateTimeParseException notAnInstant) {
            throw new IllegalStateException("The property " + validUntilProperty + " must be an ISO-8601 instant");
        }
        byte[] previous = spec.forPrevious(environmentForm(previousKeyProperty)).decode(configuredKey, localDefaultPermitted);
        Optional<PreviousKeyWindow> window = PreviousKeyWindow.of(previous, validUntil, now);
        window.ifPresentOrElse(
                open -> log.info("The {}'s previous key is accepted until {}", spec.name(), open.validUntil()),
                () -> log.info("The {}'s previous-key window has closed; remove the previous key", spec.name()));
        return window;
    }

    private static Set<Integer> versionsOf(Environment environment, String property, Set<Integer> fallback) {
        String configured = environment.getProperty(property);
        if (!isSet(configured)) {
            return fallback;
        }
        Set<Integer> versions = new LinkedHashSet<>();
        for (String element : configured.split(",", -1)) {
            if (!versions.add(parseVersion(property, element))) {
                throw new IllegalStateException("The property " + property + " names one version twice");
            }
        }
        return versions;
    }

    private static int activeOf(Environment environment, String property, int fallback) {
        String configured = environment.getProperty(property);
        return isSet(configured) ? parseVersion(property, configured) : fallback;
    }

    private static int parseVersion(String property, String element) {
        String trimmed = element.trim();
        if (!trimmed.matches("[0-9]{1,9}")) {
            // Never the element: a key pasted into this property would otherwise reach a log line.
            throw new IllegalStateException("The property " + property + " must hold positive version numbers");
        }
        int version = Integer.parseInt(trimmed);
        if (version < 1) {
            throw new IllegalStateException("The property " + property + " must hold positive version numbers");
        }
        return version;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Spring Boot's canonical mapping: dots to underscores, dashes dropped, upper case. */
    private static String environmentForm(String property) {
        return property.replace("-", "").replace('.', '_').toUpperCase(Locale.ROOT);
    }
}
