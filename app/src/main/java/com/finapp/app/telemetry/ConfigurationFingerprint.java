package com.finapp.app.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A digest of what this replica would decide money with (`P15-TSK-031`, ADR-0125 decision 4): the effective value of every
 * {@code FINANCIAL} row of the register, so two replicas configured alike publish one fingerprint and two configured differently
 * publish two.
 *
 * <p><strong>Effective</strong> means what the code would read: the environment's value when one is set, or else the register's
 * default, which {@code ConfigurationRegisterIsCompleteTest} holds equal to the code's. A property absent and without a default is
 * hashed as absent, so unset and blank stay different, as they are to the code that reads them.
 *
 * <p>It is a whole SHA-256, and the alert reads it as a label: a gauge's value is a double, whose 53 bits could only carry a folded
 * hash with a collision argument behind it. No value appears anywhere in the digest's output, only a value's effect on it.
 */
final class ConfigurationFingerprint {

    private static final String ALGORITHM = "SHA-256";

    private ConfigurationFingerprint() {}

    /**
     * @param rows the register's rows; only the {@code FINANCIAL} ones are read
     * @param environment the value a property currently holds, or {@code null} when it holds none
     * @return the lowercase hexadecimal digest
     */
    static String of(List<ConfigurationRegister.Row> rows, Function<String, String> environment) {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        MessageDigest digest = sha256();
        rows.stream()
                .filter(row -> row.klass().equals(ConfigurationRegister.FINANCIAL))
                .sorted(Comparator.comparing(ConfigurationRegister.Row::property))
                .forEach(row -> {
                    Optional<String> effective = Optional.ofNullable(environment.apply(row.property())).or(row::defaultValue);
                    update(digest, row.property());
                    digest.update(effective.isPresent() ? (byte) 1 : (byte) 0);
                    update(digest, effective.orElse(""));
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Length-prefixed, so that no pair of name and value can be split differently and hash the same. */
    private static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required of every Java platform", e);
        }
    }
}
