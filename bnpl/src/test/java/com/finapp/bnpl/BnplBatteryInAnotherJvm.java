package com.finapp.bnpl;

import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The BNPL battery's second JVM (`P12-TST-002`; the backlog's "every figure replayed {@code IDENTICAL} in two JVMs"): the
 * same seeded population generated and replayed by a process with another default charset, locale and time zone and other
 * identity hash codes ({@link SecondJvm#CHILD_DEFAULTS}, the harness `P12-TSK-008` and `-009`'s two-JVM digests run in), one
 * line per purchase - {@code index product verdict replayed checks recordDigest} - and a totals line.
 * {@code BnplBatteryTest} computes the same lines in its own JVM and requires them equal, every line: both JVMs write the
 * same stored bytes (the digests equal) and replay them to the same verdict.
 *
 * <p>The digest walks the record's components by reflection - every record, list, map, optional, figure, id, date, instant
 * and byte, in declaration order, a map's entries in its keys' text order - so no component can be left out of it by
 * omission. Locale-free throughout: concatenation, {@link Long#toString}, {@link BigDecimal#toPlainString} and ISO text
 * only, every digest over explicit UTF-8.
 */
final class BnplBatteryInAnotherJvm {

    private BnplBatteryInAnotherJvm() {}

    /** {@code arguments}: the output file, the purchases. */
    public static void main(String[] arguments) throws IOException {
        int purchases = Integer.parseInt(arguments[1]);
        BnplReplayProof replay = new BnplReplayProof();
        List<Summary> summaries = IntStream.range(0, purchases).parallel().mapToObj(index -> {
            BnplRecord record = BnplBatteryWorld.generate(index, BnplBatteryWorld.Arithmetic.STANDARD).record();
            return summary(index, record, replay.replay(record));
        }).toList();
        SecondJvm.write(Path.of(arguments[0]), lines(summaries, purchases));
    }

    /** One purchase's line and the counts the totals line sums. */
    record Summary(String line, String digest, boolean identical, long accruals, long billings, long repayments,
            long refunds) {}

    /** The purchase's line: {@code index product verdict replayed checks recordDigest}. */
    static Summary summary(int index, BnplRecord record, BnplReplayProof.Replay replay) {
        String digest = digest(record);
        String checks = replay.checks().isEmpty() ? "-"
                : replay.checks().stream().map(Enum::name).collect(Collectors.joining(","));
        String line = index + " " + record.obligation().product().name() + " " + replay.verdict().name() + " "
                + replay.replayed() + " " + checks + " " + digest;
        return new Summary(line, digest, replay.verdict() == BnplReplayProof.Verdict.IDENTICAL, record.accruals().size(),
                record.billings().size(), record.repayments().size(), record.refunds().size());
    }

    /** Every purchase's line in index order, then the totals: counts and the SHA-256 of every record digest in order. */
    static List<String> lines(List<Summary> summaries, int purchases) {
        List<String> out = new ArrayList<>(summaries.size() + 1);
        MessageDigest all = sha256();
        long identical = 0;
        long accruals = 0;
        long billings = 0;
        long repayments = 0;
        long refunds = 0;
        for (Summary summary : summaries) {
            out.add(summary.line());
            all.update(summary.digest().getBytes(StandardCharsets.UTF_8));
            identical += summary.identical() ? 1 : 0;
            accruals += summary.accruals();
            billings += summary.billings();
            repayments += summary.repayments();
            refunds += summary.refunds();
        }
        out.add("purchases=" + purchases + " identical=" + identical + " accruals=" + accruals + " billings=" + billings
                + " repayments=" + repayments + " refunds=" + refunds + " all=" + HexFormat.of().formatHex(all.digest()));
        return out;
    }

    // ------------------------------------------------------------------ the record's digest

    /** The SHA-256 of every figure, id, date, number and byte of {@code record}, in one canonical text. */
    static String digest(BnplRecord record) {
        Text text = new Text();
        text.walk(record);
        return text.digest();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java platform provides SHA-256", absent);
        }
    }

    /** A canonical text fed to SHA-256 in UTF-8 chunks - '|' between tokens, '{' and '}' around each record. */
    private static final class Text {

        private final MessageDigest digest = sha256();
        private final StringBuilder buffer = new StringBuilder(1 << 16);

        void walk(Object value) {
            switch (value) {
                case null -> token("NULL");
                case Money money -> token(money.currency().code() + ":" + money.scale() + ":" + money.minorUnits());
                case EntityId id -> token(id.value().toString());
                case CorrelationId id -> token(id.value());
                case CurrencyCode code -> token(code.code());
                case UUID id -> token(id.toString());
                case LocalDate date -> token(date.toString());
                case Instant instant -> token(instant.toString());
                case BigDecimal number -> token(number.toPlainString());
                case Integer number -> token(Integer.toString(number));
                case Long number -> token(Long.toString(number));
                case Boolean flag -> token(flag ? "T" : "F");
                case String string -> token(string.length() + ":" + string);
                case Enum<?> member -> token(member.name());
                case byte[] bytes -> token(HexFormat.of().formatHex(bytes));
                case Optional<?> optional -> {
                    token(optional.isPresent() ? "SOME" : "NONE");
                    optional.ifPresent(this::walk);
                }
                case Map<?, ?> map -> {
                    TreeMap<String, Object> ordered = new TreeMap<>();
                    map.forEach((key, entry) -> ordered.put(keyOf(key), entry));
                    token("MAP" + ordered.size());
                    ordered.forEach((key, entry) -> {
                        token(key);
                        walk(entry);
                    });
                }
                case Collection<?> list -> {
                    token("LIST" + list.size());
                    list.forEach(this::walk);
                }
                case Record row -> {
                    buffer.append('{').append(row.getClass().getSimpleName()).append('|');
                    for (RecordComponent component : row.getClass().getRecordComponents()) {
                        try {
                            component.getAccessor().setAccessible(true);
                            walk(component.getAccessor().invoke(row));
                        } catch (ReflectiveOperationException unreadable) {
                            throw new IllegalStateException(unreadable);
                        }
                    }
                    buffer.append('}');
                    if (buffer.length() > 1 << 15) {
                        flush();
                    }
                }
                default -> throw new IllegalStateException("the digest names no canonical text for "
                        + value.getClass().getName());
            }
        }

        private static String keyOf(Object key) {
            return key instanceof EntityId id ? id.value().toString() : key.toString();
        }

        private void token(String value) {
            buffer.append(value).append('|');
        }

        private void flush() {
            digest.update(buffer.toString().getBytes(StandardCharsets.UTF_8));
            buffer.setLength(0);
        }

        String digest() {
            flush();
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
