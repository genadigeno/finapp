package com.finapp.app.risk;

import com.finapp.risk.ReportContent;
import com.finapp.risk.RegulatoryFilingProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The simulated FIU endpoint behind {@link RegulatoryFilingProvider} (`P13-TSK-021`; {@code PHASE_13_PLAN.md} section
 * 12.9; ADR-0111 decisions 6-7; owner decision C4; the {@code ScreeningAdapter} precedent; {@code DESIGN-021} D13) - the
 * platform's only filing channel in Phase 13. No real authority exists in any phase ({@code DECISIONS.md} §Deferred), so
 * this adapter is the EU reference jurisdiction's shape, kept as adapter data: jurisdiction {@code EU-REF}, format
 * version 1, the reporting entity's reference from configuration, and the fields a real jurisdiction would add (the
 * report type, the legal basis - <em>to be verified against the official text</em> when a jurisdiction is chosen) in its
 * rendering, never in the platform's {@link ReportContent}.
 *
 * <h2>Deterministic by a knob, an {@code UNKNOWN} path, and no state</h2>
 *
 * <p>The authority's answer is the configured {@link Behaviour} ({@code finapp.risk.filing.simulation}): acknowledge,
 * reject, answer unknown and acknowledge on inquiry, stay unknown, or be unavailable (every call throws). Its references
 * are <strong>derived from the filing reference</strong> (SHA-256), so the answer to a submission and to every later
 * inquiry is the same on every instance - the simulator holds nothing in memory, which is what makes it correct with N
 * instances: an in-process FIU that remembered its filings would answer a second instance "nothing received" and invite
 * the very second filing the port forbids. For the same reason it never answers {@code NOT_RECEIVED}; that path - the
 * drive's owed submission - is the domain's, proven over a stateful fake ({@code RegulatoryReportTest}).
 *
 * <p>It refuses, as an authority would, a filing in a format it does not speak or past its size bound, and a reference
 * that is not the report's own. It logs nothing and keeps nothing: the narrative is the artefact's, rendered into the
 * document and gone with the call. It receives no personal data beyond the artefact - references, codes and the approved
 * narrative; no name, address or document is resolved for it.
 */
public final class SimulatedFilingProvider implements RegulatoryFilingProvider {

    /** The reference jurisdiction this adapter files with. */
    public static final String JURISDICTION = "EU-REF";

    /** The format version it renders. */
    public static final int FORMAT_VERSION = 1;

    /** The EU reference's own additions - adapter data, never the platform's content. */
    public static final String REPORT_TYPE = "SUSPICIOUS_TRANSACTION_REPORT";

    /** The legal basis the EU reference names - AMLR Article 69, to be verified against the official text (C4). */
    public static final String LEGAL_BASIS = "EU-2024-1624-ART-69-TO-BE-VERIFIED";

    /** The largest rendered document the authority accepts. */
    public static final int MAX_RENDERED_BYTES = 64 * 1024;

    /** The rejection codes this authority answers - closed. */
    public static final String REJECTED_BY_AUTHORITY = "REJECTED_BY_AUTHORITY";
    public static final String FORMAT_UNSUPPORTED = "FORMAT_UNSUPPORTED";
    public static final String DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** What the simulated authority answers - {@code finapp.risk.filing.simulation}. */
    public enum Behaviour {
        /** Every filing acknowledged at once (the default). */
        ACKNOWLEDGE,
        /** Every filing refused - {@link #REJECTED_BY_AUTHORITY}. */
        REJECT,
        /** A submission answered unknown; every inquiry acknowledges it - the inquiry leg's path. */
        UNKNOWN_THEN_ACKNOWLEDGE,
        /** Every answer unknown - the alert's path: the report stays {@code FILED}, inquired at every permit. */
        UNKNOWN,
        /** Every call throws - the authority unreachable. */
        UNAVAILABLE
    }

    /** The authority could not be reached - nothing is known of the call. */
    public static final class FilingUnavailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        FilingUnavailable() {
            super("the simulated FIU is unavailable");
        }
    }

    private final Behaviour behaviour;
    private final Declaration declaration;

    /**
     * @param behaviour what the simulated authority answers
     * @param reportingEntityReference the platform's reference with the authority - configuration
     */
    public SimulatedFilingProvider(Behaviour behaviour, String reportingEntityReference) {
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
        this.declaration = new Declaration(JURISDICTION, FORMAT_VERSION,
                Objects.requireNonNull(reportingEntityReference, "reportingEntityReference"));
    }

    @Override
    public Optional<Declaration> declared(String jurisdiction) {
        return JURISDICTION.equals(jurisdiction) ? Optional.of(declaration) : Optional.empty();
    }

    @Override
    public Receipt file(Request request) {
        Objects.requireNonNull(request, "request");
        String reference = reference(request.filingReference());
        if (!reference.equals(request.report().value().toString())) {
            throw new IllegalArgumentException("a filing is made under its own report's reference");
        }
        unavailable();
        ReportContent content = request.content();
        String authority = authorityReference(reference);
        if (!JURISDICTION.equals(content.jurisdiction()) || content.formatVersion() != FORMAT_VERSION
                || !declaration.reportingEntityReference().equals(content.reportingEntityReference())) {
            return Receipt.rejected(Optional.of(authority), FORMAT_UNSUPPORTED);
        }
        if (render(reference, content).getBytes(StandardCharsets.UTF_8).length > MAX_RENDERED_BYTES) {
            return Receipt.rejected(Optional.of(authority), DOCUMENT_TOO_LARGE);
        }
        return switch (behaviour) {
            case ACKNOWLEDGE -> Receipt.acknowledged(authority);
            case REJECT -> Receipt.rejected(Optional.of(authority), REJECTED_BY_AUTHORITY);
            case UNKNOWN_THEN_ACKNOWLEDGE, UNKNOWN -> Receipt.unknown(Optional.of(authority));
            case UNAVAILABLE -> throw new FilingUnavailable();
        };
    }

    @Override
    public Receipt inquire(String filingReference) {
        String reference = reference(filingReference);
        unavailable();
        String authority = authorityReference(reference);
        return switch (behaviour) {
            case ACKNOWLEDGE, UNKNOWN_THEN_ACKNOWLEDGE -> Receipt.acknowledged(authority);
            case REJECT -> Receipt.rejected(Optional.of(authority), REJECTED_BY_AUTHORITY);
            case UNKNOWN -> Receipt.unknown(Optional.of(authority));
            case UNAVAILABLE -> throw new FilingUnavailable();
        };
    }

    /**
     * The EU reference's document, version 1 - the platform's categories and the adapter's additions, one
     * {@code key: value} per line, lists comma-joined, the narrative's line breaks escaped. Deterministic: the same report
     * renders the same bytes on every instance.
     */
    public static String render(String filingReference, ReportContent content) {
        Objects.requireNonNull(filingReference, "filingReference");
        Objects.requireNonNull(content, "content");
        return String.join("\n", List.of(
                "format: " + JURISDICTION + "/" + FORMAT_VERSION,
                "reportType: " + REPORT_TYPE,
                "legalBasis: " + LEGAL_BASIS,
                "filingReference: " + filingReference,
                "reportingEntity: " + content.reportingEntityReference(),
                "subject: PARTY/" + content.subject(),
                "accounts: " + joined(content.accounts()),
                "transactions: " + joined(content.transactions()),
                "grounds: " + String.join(",", content.grounds()),
                "narrative: " + content.narrative().replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n"),
                "attachments: " + joined(content.attachments())));
    }

    /** The authority's own reference for a filing - derived from the filing reference, so every instance agrees. */
    static String authorityReference(String filingReference) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(filingReference.getBytes(StandardCharsets.UTF_8));
            return "SIM-FIU-" + HexFormat.of().formatHex(digest, 0, 8).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", absent);
        }
    }

    private void unavailable() {
        if (behaviour == Behaviour.UNAVAILABLE) {
            throw new FilingUnavailable();
        }
    }

    private static String reference(String filingReference) {
        Objects.requireNonNull(filingReference, "filingReference");
        if (!UUID_TEXT.matcher(filingReference).matches()) {
            throw new IllegalArgumentException("a filing reference is a report's identifier");
        }
        return filingReference;
    }

    private static String joined(List<UUID> references) {
        return references.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
