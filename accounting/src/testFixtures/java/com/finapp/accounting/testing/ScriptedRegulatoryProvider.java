package com.finapp.accounting.testing;

import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.accounting.RegulatorySubmissionId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A provider that <strong>remembers</strong> (`P14-TSK-018`): what {@code SimulatedRegulatoryReportingProvider} deliberately cannot do, so a suite can prove the driver against the truth - a send deduplicated on its id; an inquiry answering what really arrived, and a definite "not
 * received" when nothing did; a call that throws before or after delivering; a scripted answer for the case at hand. Test code: it ships in no jar.
 */
public final class ScriptedRegulatoryProvider implements RegulatoryReportingProvider {

    public static final String NAME = "scripted-reference";
    public static final String JURISDICTION = "REF";

    private final ConcurrentHashMap<RegulatorySubmissionId, AtomicInteger> arrived = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Supplier<Answer>> onSubmit = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Supplier<Answer>> onInquire = new ConcurrentLinkedQueue<>();
    private final AtomicInteger submits = new AtomicInteger();
    private final AtomicInteger inquiries = new AtomicInteger();
    private final Queue<String> contents = new ConcurrentLinkedQueue<>();

    /** How many times {@link #submit} was called. */
    public int submits() {
        return submits.get();
    }

    /** How many times {@link #inquire} was called. */
    public int inquiries() {
        return inquiries.get();
    }

    /** How many returns really arrived under {@code id}: deduplicated, so at most one. */
    public int returnsHeld(RegulatorySubmissionId id) {
        return arrived.containsKey(id) ? 1 : 0;
    }

    /** How many sends reached the provider under {@code id}, whether or not they were the first. */
    public int sendsReceived(RegulatorySubmissionId id) {
        AtomicInteger count = arrived.get(id);
        return count == null ? 0 : count.get();
    }

    /** The next {@link #submit} answers this instead of the truth. */
    public ScriptedRegulatoryProvider nextSubmit(Supplier<Answer> answer) {
        onSubmit.add(answer);
        return this;
    }

    /** The next {@link #inquire} answers this instead of the truth. */
    public ScriptedRegulatoryProvider nextInquiry(Supplier<Answer> answer) {
        onInquire.add(answer);
        return this;
    }

    /** A call that throws, the send never having arrived. */
    public static Supplier<Answer> unreachable() {
        return () -> {
            throw new IllegalStateException("the scripted provider is unreachable");
        };
    }

    /** An answer of no shape: what an untrusted provider might say. */
    public static Answer answering(Outcome outcome, Optional<String> reference, Optional<String> code, String raw) {
        return new Answer(outcome, reference, code, raw.getBytes(StandardCharsets.UTF_8));
    }

    /** What every send carried, in order. */
    public List<String> contents() {
        return List.copyOf(contents);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean declares(String jurisdiction) {
        return JURISDICTION.equals(jurisdiction);
    }

    @Override
    public ReferenceExport export(Request request) {
        return new ReferenceExport("REFERENCE", "SIMULATED", "a suite's reference export", request.content(), request.contentSha256());
    }

    @Override
    public Answer submit(Request request) {
        submits.incrementAndGet();
        Supplier<Answer> scripted = onSubmit.poll();
        if (scripted != null) {
            return scripted.get();
        }
        return delivered(request);
    }

    /** The send arrives, deduplicated on its id, and the provider acknowledges it. */
    public Answer delivered(Request request) {
        arrived.computeIfAbsent(request.submission(), ignored -> new AtomicInteger()).incrementAndGet();
        contents.add(request.content());
        return Answer.acknowledged(reference(request.submission()), raw(request.submission(), "ACKNOWLEDGED"));
    }

    @Override
    public Answer inquire(RegulatorySubmissionId submission) {
        inquiries.incrementAndGet();
        Supplier<Answer> scripted = onInquire.poll();
        if (scripted != null) {
            return scripted.get();
        }
        return arrived.containsKey(submission) ? Answer.acknowledged(reference(submission), raw(submission, "ACKNOWLEDGED")) : Answer.notReceived(raw(submission, "NOT_RECEIVED"));
    }

    /** The reference the truth answers under. */
    public static String reference(RegulatorySubmissionId submission) {
        return "SCRIPTED-" + submission.value();
    }

    private static byte[] raw(RegulatorySubmissionId submission, String outcome) {
        return ("{\"outcome\":\"" + outcome + "\",\"submission\":\"" + submission.value() + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** The provider's map of what arrived: for a suite's assertion. */
    public Map<RegulatorySubmissionId, AtomicInteger> arrived() {
        return Map.copyOf(arrived);
    }
}
