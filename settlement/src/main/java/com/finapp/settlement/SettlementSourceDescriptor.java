package com.finapp.settlement;

import com.finapp.ledger.AccountPurpose;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One settlement source, compiled (`P8-TSK-002`, ADR-0064; {@code INV-SET-05}).
 *
 * <p>A source is a counterparty's own statement stream: who reports, in which format family and
 * version, over which channels, and — for the three report kinds — which clearing position its
 * evidence discharges. The register ({@link SettlementSources}) holds one of these per source,
 * identical on every instance, and the seeded {@code settlement.source} row holds only identity
 * and operational state ({@code status}, {@code next_sequence}) — never a position — so no
 * instance and no environment can hold a different answer to "whose evidence discharges this
 * position".
 *
 * <p><strong>Coherence is refused at construction</strong>, the {@code RailCapabilities}
 * precedent: a descriptor that constructs is a descriptor whose parts agree, and every consumer
 * downstream may rely on that instead of re-checking.
 *
 * @param code the source's stable identity, `simulated-psp.settlement`-shaped: lowercase words
 *     and digits, dot- and dash-separated
 * @param kind what the counterparty reports
 * @param format the format family the source delivers — must parse exactly this {@code kind}
 * @param formatVersion the frozen version the source currently delivers (≥ 1); a change to a
 *     screen or parser is a NEW version, never an edit (ADR-0066 §8)
 * @param channels the delivery channels the source supports — {@code UPLOAD} and/or
 *     {@code PULL}, never empty, and never {@code READMISSION}, which is an operator recovery
 *     act rather than a source's channel
 * @param settledPosition the clearing position this source's evidence discharges — present for
 *     exactly the report kinds ({@code INV-SET-05}); the bank statement recognises cash, whose
 *     position binds with its first poster (`P8-TSK-016`)
 * @param remittanceReferencePattern the shape of this counterparty's remittance references on
 *     the bank statement — what lets hop 2 attribute a bank line to this source's remittance
 *     expectation (ADR-0065); a compiled fact, validated here so a malformed pattern fails the
 *     build rather than the first match. Present for exactly the report kinds: the bank
 *     statement is where remittances LAND, so it has none of its own
 */
public record SettlementSourceDescriptor(
        String code,
        SourceKind kind,
        SettlementFormatId format,
        int formatVersion,
        Set<DeliveryChannel> channels,
        Optional<AccountPurpose> settledPosition,
        Optional<String> remittanceReferencePattern) {

    /** `simulated-psp.settlement` and its siblings; also the DB `CHECK`'s shape. */
    private static final Pattern CODE_SHAPE =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)+");

    public SettlementSourceDescriptor {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(channels, "channels must not be null");
        Objects.requireNonNull(settledPosition, "settledPosition must not be null");
        Objects.requireNonNull(
                remittanceReferencePattern, "remittanceReferencePattern must not be null");
        if (!CODE_SHAPE.matcher(code).matches() || code.length() > 100) {
            throw new IllegalArgumentException(
                    "a source code is lowercase, dotted and at most 100 characters; '"
                            + code + "' is not");
        }
        if (format.kind() != kind) {
            throw new IllegalArgumentException(
                    "source '" + code + "' declares format " + format + ", which parses "
                            + format.kind() + " statements, not " + kind
                            + ": a format belongs to one kind (ADR-0066)");
        }
        if (channels.isEmpty()) {
            throw new IllegalArgumentException(
                    "source '" + code + "' declares no delivery channel: evidence that cannot"
                            + " arrive reconciles nothing");
        }
        if (channels.contains(DeliveryChannel.READMISSION)) {
            throw new IllegalArgumentException(
                    "source '" + code + "' declares READMISSION as a channel: readmission is an"
                            + " operator's recovery act, never a source's delivery channel");
        }
        if (settledPosition.isPresent() != kind.settlesAPosition()) {
            throw new IllegalArgumentException(
                    "source '" + code + "' " + (settledPosition.isPresent()
                            ? "declares a settled position, but a " + kind
                                    + " recognises cash and its position binds with its first"
                                    + " poster (P8-TSK-016)"
                            : "declares no settled position, and a " + kind
                                    + " settles exactly one (INV-SET-05)"));
        }
        if (formatVersion < 1) {
            throw new IllegalArgumentException(
                    "source '" + code + "': a format version is 1 or later");
        }
        if (remittanceReferencePattern.isPresent() != kind.settlesAPosition()) {
            throw new IllegalArgumentException(
                    "source '" + code + "': a report source declares the remittance-reference"
                            + " shape the bank statement attributes by (ADR-0065 hop 2), and"
                            + " the bank statement declares none of its own");
        }
        remittanceReferencePattern.ifPresent(
                pattern -> {
                    try {
                        Pattern.compile(pattern);
                    } catch (PatternSyntaxException malformed) {
                        throw new IllegalArgumentException(
                                "source '" + code
                                        + "' declares a malformed remittance-reference pattern",
                                malformed);
                    }
                });
        channels = Set.copyOf(channels);
    }
}
