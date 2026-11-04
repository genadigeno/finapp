package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.time.YearMonth;

/**
 * A return's content, drawn from issued runs (`P14-TSK-018`; ADR-0118 decision 2; {@code INV-ACC-13}): {@link #draw} takes a
 * configuration and each source's proved run to the form's lines, and {@link #content} writes the canonical text the approver
 * attests, whose hash is what they name. A function of its inputs and nothing else: no clock, no iteration order the code does
 * not fix.
 *
 * <p><strong>Standing.</strong> Every content says {@code "standing":"REFERENCE"}: there is no real jurisdiction, channel or
 * statutory form here (owner decision C4), and {@code accounting V010} refuses any other.
 */
public final class RegulatoryForm {

    /** Every submission's and obligation's standing in this phase - {@code accounting V010}'s only admitted value. */
    public static final String REFERENCE = "REFERENCE";

    /** The most lines a return reaches once a per-currency line is expanded. */
    public static final int MAX_LINES = 2000;

    /** The content's bound - {@code accounting V010}'s {@code regulatory_submission_permits_only_machine_edges}. */
    public static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private RegulatoryForm() {}

    /** A line's figure cannot be found: nothing, more than one, not an amount. Names the line and the path, never a figure. */
    public static final class FigureUnresolved extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final FigurePath.Why why;

        FigureUnresolved(String line, FigurePath.Unresolved unresolved) {
            super("form line " + line + ": " + unresolved.getMessage());
            this.why = unresolved.why();
        }

        /** Why. */
        public FigurePath.Why why() {
            return why;
        }
    }

    /**
     * One source's proved run: its bytes already checked against its stored hash.
     *
     * @param content the run's content, parsed
     */
    public record SourceRun(String definition, int definitionVersion, ReportRunId run, String runSha256, CanonicalJson.Obj content) {

        public SourceRun {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(runSha256, "runSha256");
            Objects.requireNonNull(content, "content");
        }
    }

    /** What the content's header pins. */
    public record Header(RegulatoryObligationId obligation, String jurisdiction, String form, int formVersion, String configurationSha256, YearMonth period, int periodRevision,
            Optional<RegulatorySubmissionId> corrects) {

        public Header {
            Objects.requireNonNull(obligation, "obligation");
            Objects.requireNonNull(jurisdiction, "jurisdiction");
            Objects.requireNonNull(form, "form");
            Objects.requireNonNull(configurationSha256, "configurationSha256");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(corrects, "corrects");
        }
    }

    /**
     * The form's lines, from {@code runs} by definition: one per line, or one per currency for {@code currency=*}.
     *
     * @throws FigureUnresolved when a figure resolves to nothing, more than one, or no amount - never a zero
     */
    public static List<RegulatoryFormLine> draw(RegulatoryObligationConfiguration configuration, Map<String, SourceRun> runs) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(runs, "runs");
        List<RegulatoryFormLine> lines = new ArrayList<>();
        Set<String> held = new HashSet<>();
        for (RegulatoryObligationConfiguration.Line line : configuration.lines()) {
            SourceRun run = runs.get(line.source());
            if (run == null) {
                throw new IllegalStateException("a form line's source has its run: the draft read each");
            }
            List<FigurePath.Figure> figures;
            try {
                figures = new FigurePath(line.figure()).resolve(run.content());
            } catch (FigurePath.Unresolved unresolved) {
                throw new FigureUnresolved(line.code(), unresolved);
            }
            for (FigurePath.Figure figure : figures) {
                if (!held.add(line.code() + "|" + figure.currency())) {
                    throw new IllegalStateException("a figure path's currencies are distinct");
                }
                if (lines.size() == MAX_LINES) {
                    throw new FigureUnresolved(line.code(), new FigurePath.Unresolved(FigurePath.Why.MORE_THAN_ONE, "(more than " + MAX_LINES + " lines)"));
                }
                lines.add(new RegulatoryFormLine(lines.size() + 1, line.code(), line.label(), figure.currency(), figure.minor(), figure.scale(), run.run(), run.runSha256(), run.definition(), figure.path()));
            }
        }
        return List.copyOf(lines);
    }

    /** The canonical text the approver attests. */
    public static String content(Header header, List<SourceRun> runs, List<RegulatoryFormLine> lines) {
        Objects.requireNonNull(header, "header");
        CanonicalJson.Arr sources = CanonicalJson.array();
        for (SourceRun run : runs) {
            sources.add(CanonicalJson.object()
                    .put("definition", run.definition())
                    .put("definitionVersion", run.definitionVersion())
                    .put("runId", run.run().value().toString())
                    .put("runSha256", run.runSha256()));
        }
        CanonicalJson.Arr written = CanonicalJson.array();
        for (RegulatoryFormLine line : lines) {
            written.add(CanonicalJson.object()
                    .put("code", line.code())
                    .put("currency", line.currency())
                    .put("figure", line.figure())
                    .put("label", line.label())
                    .put("minor", line.minor().toString())
                    .put("runId", line.run().value().toString())
                    .put("runSha256", line.runSha256())
                    .put("scale", line.scale())
                    .put("source", line.source()));
        }
        CanonicalJson.Obj content = CanonicalJson.object()
                .put("standing", REFERENCE)
                .put("jurisdiction", header.jurisdiction())
                .put("form", header.form())
                .put("formVersion", header.formVersion())
                .put("obligationId", header.obligation().value().toString())
                .put("configurationSha256", header.configurationSha256())
                .put("period", header.period().toString())
                .put("periodRevision", header.periodRevision())
                .put("sources", sources)
                .put("lines", written);
        if (header.corrects().isPresent()) {
            content.put("correctsSubmissionId", header.corrects().get().value().toString());
        } else {
            content.putNull("correctsSubmissionId");
        }
        return CanonicalJson.text(content);
    }
}
