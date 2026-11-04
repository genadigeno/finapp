package com.finapp.accounting;

import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatoryReportingProvider.CallKind;
import com.finapp.accounting.RegulatoryReportingProvider.Outcome;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a provider's answer is taken to say, and what of it is kept (`P14-TSK-018`; {@code INV-HIST-02}; {@code accounting V010}'s
 * {@code regulatory_provider_answer}). The answer is <strong>untrusted</strong>: only what has the shape of a reference or a code
 * is read off it, and an answer that cannot be believed <strong>decides nothing</strong> - it reads {@link Outcome#UNKNOWN}, the
 * row keeps its permit, the anomaly is named and the bytes are kept whatever they say.
 *
 * <ul>
 *   <li><strong>{@link Anomaly#OVERSIZED}</strong>: the bound is {@value #MAX_RAW_BYTES} bytes. Past it the first
 *       {@value #MAX_RAW_BYTES} bytes, the whole length and the whole body's SHA-256 are kept, and nothing is read off it. Verbatim
 *       retention is a promise about what fits in a row: the rule is stated here, in the table's {@code CHECK}s and in
 *       {@code DATA_CLASSIFICATION.md}, and a truncated answer says so on every read.</li>
 *   <li><strong>{@link Anomaly#MALFORMED}</strong>: a reference or a code of no allowed shape, or holding an instrument's shape, an acknowledgement with no reference, a
 *       rejection with no code, a "not received" naming a reference.</li>
 *   <li><strong>{@link Anomaly#CONTRADICTORY}</strong>: a send answered "not received", a provider contradicting the very call it is
 *       answering. The safe direction costs one inquiry; reading it any other way would record an answer nobody gave.</li>
 * </ul>
 *
 * @param stated what the adapter read the answer to say
 * @param read what the record takes it to say - {@link Outcome#UNKNOWN} when there is an anomaly
 * @param retained the bytes kept
 * @param rawSha256 the SHA-256 of everything received
 * @param receivedBytes how many bytes were received
 * @param truncated whether {@code retained} is shorter than what was received
 */
public record RegulatoryAnswerReading(CallKind call, Outcome stated, Outcome read, Optional<String> providerReference, Optional<String> rejectionCode, Optional<Anomaly> anomaly,
        byte[] retained, String rawSha256, long receivedBytes, boolean truncated) {

    /** The most of an answer's bytes kept - {@code accounting V010}'s {@code regulatory_provider_answer_raw_bounded}. */
    public static final int MAX_RAW_BYTES = 64 * 1024;

    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** Why an answer decided nothing. */
    public enum Anomaly { OVERSIZED, MALFORMED, CONTRADICTORY }

    public RegulatoryAnswerReading {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(stated, "stated");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(providerReference, "providerReference");
        Objects.requireNonNull(rejectionCode, "rejectionCode");
        Objects.requireNonNull(anomaly, "anomaly");
        Objects.requireNonNull(rawSha256, "rawSha256");
        retained = Objects.requireNonNull(retained, "retained").clone();
    }

    @Override
    public byte[] retained() {
        return retained.clone();
    }

    /** The provider's text is never logged: a generated {@code toString} would carry what it said of a reference. */
    @Override
    public String toString() {
        return "RegulatoryAnswerReading[" + call + ", stated " + stated + ", read " + read + ", " + receivedBytes + " bytes]";
    }

    /**
     * {@code answer} to {@code call}, read.
     */
    public static RegulatoryAnswerReading of(CallKind call, Answer answer) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(answer, "answer");
        byte[] raw = answer.raw();
        String sha256 = sha256(raw);
        if (raw.length > MAX_RAW_BYTES) {
            return new RegulatoryAnswerReading(call, answer.outcome(), Outcome.UNKNOWN, Optional.empty(), Optional.empty(), Optional.of(Anomaly.OVERSIZED),
                    Arrays.copyOf(raw, MAX_RAW_BYTES), sha256, raw.length, true);
        }
        Optional<String> reference = answer.providerReference();
        Optional<String> code = answer.rejectionCode();
        // What is read off an answer reaches audit summaries and events, where a provider's text never does: instrument-shaped, it is no reference or code of ours. The answer then decides nothing; its bytes stay.
        boolean referenceMalformed = reference.filter(found -> !REFERENCE.matcher(found).matches() || instrumentShaped(found)).isPresent();
        boolean codeMalformed = code.filter(found -> !CODE.matcher(found).matches() || instrumentShaped(found)).isPresent();
        boolean malformed = referenceMalformed || codeMalformed
                || (answer.outcome() == Outcome.ACKNOWLEDGED && reference.isEmpty())
                || (answer.outcome() == Outcome.REJECTED && code.isEmpty())
                || (answer.outcome() != Outcome.REJECTED && code.isPresent())
                || (answer.outcome() == Outcome.NOT_RECEIVED && reference.isPresent());
        if (malformed) {
            return new RegulatoryAnswerReading(call, answer.outcome(), Outcome.UNKNOWN, Optional.empty(), Optional.empty(), Optional.of(Anomaly.MALFORMED), raw, sha256, raw.length, false);
        }
        if (answer.outcome() == Outcome.NOT_RECEIVED && call != CallKind.INQUIRE) {
            return new RegulatoryAnswerReading(call, answer.outcome(), Outcome.UNKNOWN, Optional.empty(), Optional.empty(), Optional.of(Anomaly.CONTRADICTORY), raw, sha256, raw.length, false);
        }
        return new RegulatoryAnswerReading(call, answer.outcome(), answer.outcome(), reference, code, Optional.empty(), raw, sha256, raw.length, false);
    }

    /** The screen's separators are narrower than this class's grammars: {@code 4111.1111.1111.1111} and {@code GB82_WEST_12345698765432} are instruments all the same, so every separator the grammars admit is made the one the screen reads. */
    private static boolean instrumentShaped(String found) {
        return InstrumentShapes.holdsAny(found.replaceAll("[._:]", "-"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", impossible);
        }
    }
}
