package com.finapp.accounting;

import java.util.Objects;
import java.util.Optional;

/**
 * The adapter a regulatory submission is sent through (`P14-TSK-001` declared it; `P14-TSK-018` gave it its methods;
 * {@code PHASE_14_PLAN.md} section 13.7; ADR-0118; owner decision C4) - simulated in Phase 14, its export a reference
 * document. No legal obligation is asserted.
 *
 * <p><strong>A writer port</strong> (it sends to an external party), so {@code ReportingNeverWritesTest} refuses it to every
 * reporting class: it is called only from {@link RegulatorySubmissions}, and never inside a transaction.
 *
 * <h2>The contract every implementation keeps</h2>
 *
 * <ul>
 *   <li><strong>{@link #submit}</strong> sends under the submission's id, known before the call, and the provider
 *       <strong>deduplicates on it</strong>: a second send under one id is the first, never a second return. It may throw
 *       (unavailable, timed out, the answer lost): the caller records nothing and inquires later. It must time out below the
 *       driver's lease.</li>
 *   <li><strong>{@link #inquire}</strong> asks the standing of what was sent under the id. {@link Outcome#NOT_RECEIVED} is the
 *       one definite "nothing arrived"; only that permits a send again, under the same id. An {@link Outcome#UNKNOWN} is an
 *       inquiry later, never a re-submit.</li>
 *   <li><strong>{@link #name}, {@link #declares} and {@link #export}</strong> are local and constant: never a call.</li>
 * </ul>
 *
 * <p><strong>The answer is untrusted.</strong> Whatever an implementation hands back is retained byte for byte and read through
 * {@link RegulatoryAnswerReading}, which decides nothing on a malformed, oversized or self-contradicting answer. It is never
 * logged and never in a metric's tag.
 */
public interface RegulatoryReportingProvider {

    /** The adapter's name, as an obligation names it. */
    String name();

    /** Whether it files with {@code jurisdiction}. Never I/O: a proposal and a draft ask it. */
    boolean declares(String jurisdiction);

    /** The document it would send for {@code request}, as a person may read it. Never I/O. */
    ReferenceExport export(Request request);

    /** Sends {@code request} under its submission's id; the answer is the provider's first. */
    Answer submit(Request request);

    /** The standing of what was sent under {@code submission}'s id. */
    Answer inquire(RegulatorySubmissionId submission);

    /** What is sent: the submission's id, what it was drawn for, and the content as attested. */
    record Request(RegulatorySubmissionId submission, String jurisdiction, String form, int formVersion, String period, String content, String contentSha256) {

        public Request {
            Objects.requireNonNull(submission, "submission");
            Objects.requireNonNull(jurisdiction, "jurisdiction");
            Objects.requireNonNull(form, "form");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(contentSha256, "contentSha256");
        }

        /** Figures stay out of a log line. */
        @Override
        public String toString() {
            return "Request[" + submission.value() + ", " + form + "/" + formVersion + ", " + period + "]";
        }
    }

    /**
     * The document an adapter renders. {@code standing} and {@code channel} say plainly what it is: in Phase 14, {@code REFERENCE}
     * and {@code SIMULATED}.
     */
    record ReferenceExport(String standing, String channel, String notice, String document, String sha256) {

        public ReferenceExport {
            Objects.requireNonNull(standing, "standing");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(notice, "notice");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(sha256, "sha256");
        }

        /** Figures stay out of a log line. */
        @Override
        public String toString() {
            return "ReferenceExport[" + standing + ", " + channel + ", " + sha256 + "]";
        }
    }

    /**
     * A provider's answer: the outcome an adapter read from it, what it says of the reference and the code, and the bytes it
     * arrived as. Nothing here is checked: {@link RegulatoryAnswerReading} does that, keeping the bytes whatever it decides.
     */
    record Answer(Outcome outcome, Optional<String> providerReference, Optional<String> rejectionCode, byte[] raw) {

        public Answer {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(providerReference, "providerReference");
            Objects.requireNonNull(rejectionCode, "rejectionCode");
            raw = Objects.requireNonNull(raw, "raw").clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        /** The provider's text is never logged: a generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "Answer[" + outcome + ", " + raw.length + " bytes]";
        }

        public static Answer acknowledged(String providerReference, byte[] raw) {
            return new Answer(Outcome.ACKNOWLEDGED, Optional.of(providerReference), Optional.empty(), raw);
        }

        public static Answer rejected(Optional<String> providerReference, String code, byte[] raw) {
            return new Answer(Outcome.REJECTED, providerReference, Optional.of(code), raw);
        }

        public static Answer unknown(Optional<String> providerReference, byte[] raw) {
            return new Answer(Outcome.UNKNOWN, providerReference, Optional.empty(), raw);
        }

        public static Answer notReceived(byte[] raw) {
            return new Answer(Outcome.NOT_RECEIVED, Optional.empty(), Optional.empty(), raw);
        }
    }

    /** A submission's standing as the provider reports it. */
    enum Outcome {
        /** Received and acknowledged. */
        ACKNOWLEDGED,
        /** Refused by the provider; a correction may follow. */
        REJECTED,
        /** Its standing not known - inquire again, never re-submit. */
        UNKNOWN,
        /** Nothing arrived under the id: only an inquiry can say so, and it is what permits a send again. */
        NOT_RECEIVED
    }

    /** Which call an answer came from. */
    enum CallKind {
        /** The first send: the first attempt, which no earlier call can have preceded. */
        SUBMIT,
        /** An inquiry. */
        INQUIRE,
        /** A send again under the same id, after a definite "not received". */
        RESEND
    }
}
