package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * The transfer surface's meters (`P4-TSK-011`, `PHASE_4_PLAN.md` §15):
 * {@code finapp.transfers.transfer} by outcome, {@code finapp.transfers.transfer.latency},
 * {@code finapp.transfers.beneficiary} by outcome, and {@code finapp.transfers.conflict}.
 *
 * <p><strong>Every series is registered at construction</strong> (`P1-TSK-029`): a counter
 * created on its first increment is a series an alert cannot evaluate at exactly the moment it
 * is needed, and a freshly started instance must publish healthy zeros rather than absences
 * that read as a quiet system.
 *
 * <p><strong>Only the acting call counts, post-commit</strong> (`P3-TSK-020`, the
 * {@code AccountMetrics} discipline verbatim): a replayed key lands {@code replayed} with the
 * original outcome's series unchanged, a converged beneficiary retry and the losers of a
 * concurrent reversal are never throughput, and an act that rolled back is an act that did not
 * happen. The counting seam is {@code app.transfers} — the one door every transfer command
 * passes through today; a later phase adding a second door (a scheduled transfer executor)
 * must come here and wire its own counts, which is why this class is public.
 *
 * <p>{@code refused} is a transfer command the platform declined to judge with nothing
 * written — the resolution refusals ({@code transfers.UnknownSource},
 * {@code transfers.UnknownDestination}, malformed and removed folded in exactly as the
 * response folds them) and the reversal machine's {@code transfers.NotReversible}. The
 * caller's own 422s are deliberately not refusals: they never named a coherent command. A
 * metric is invisible to the caller (`P1-TSK-029`), so it may count what the byte-identical
 * responses hide — a rise in {@code refused} is somebody probing destinations.
 *
 * <p>{@code finapp.transfers.conflict} is its own series and never an {@code outcome} value,
 * deliberately: an {@code INV-IDEM-03} fingerprint conflict is a security signal (a stranger
 * replaying logged keys — ADR-0004's owning-principal fingerprint refusing them), and an
 * alert on a security signal must be one series rather than a tag filter someone forgets.
 *
 * <p>The latency timer measures the execution command ({@code POST /v1/transfers}), every
 * outcome of that path — a timer recording only successes flatters exactly the incident an
 * operator is trying to see. The reversal's duration is already visible on
 * {@code finapp.ledger.posting.latency} through the observed {@code ReversalService}. No
 * percentile histogram (`P1-TSK-029`: {@code _bucket} does not exist unless asked for); the
 * dashboard reads {@code _count}, {@code _sum} and {@code _max}.
 */
public final class TransferMetrics {

    /** {@code finapp.transfers.transfer} — transfer commands, by outcome. */
    static final String TRANSFER = "finapp.transfers.transfer";

    /** {@code finapp.transfers.transfer.latency} — the execution command's duration. */
    static final String TRANSFER_LATENCY = "finapp.transfers.transfer.latency";

    /** {@code finapp.transfers.beneficiary} — saved-destination lifecycle, by outcome. */
    static final String BENEFICIARY = "finapp.transfers.beneficiary";

    /** {@code finapp.transfers.conflict} — {@code INV-IDEM-03} fingerprint conflicts. */
    static final String CONFLICT = "finapp.transfers.conflict";

    private final Counter completed;
    private final Counter failed;
    private final Counter replayed;
    private final Counter reversed;
    private final Counter refused;
    private final Counter beneficiaryAdded;
    private final Counter beneficiaryRemoved;
    private final Counter conflict;
    private final Timer latency;

    TransferMetrics(MeterRegistry registry) {
        this.completed = transfer(registry, "completed", "judged COMPLETED and the money moved");
        this.failed =
                transfer(registry, "failed", "judged FAILED - a committed refusal, nothing posted");
        this.replayed =
                transfer(
                        registry,
                        "replayed",
                        "a retried key replayed its recorded judgement (INV-IDEM-01)");
        this.reversed = transfer(registry, "reversed", "reversed by an operator (P4-TSK-009)");
        this.refused =
                transfer(
                        registry,
                        "refused",
                        "refused with nothing judged and nothing written - resolution refusals"
                                + " and the reversal machine's 409");
        this.beneficiaryAdded = beneficiary(registry, "added", "a saved destination was created");
        this.beneficiaryRemoved =
                beneficiary(registry, "removed", "a saved destination was removed");
        this.conflict =
                Counter.builder(CONFLICT)
                        .description(
                                "INV-IDEM-03 fingerprint conflicts on the transfer command: a"
                                    + " known key presented with a materially different request."
                                    + " A security signal - a stranger replaying logged keys."
                                    + " Per instance; rate() and sum() aggregate")
                        .register(registry);
        this.latency =
                Timer.builder(TRANSFER_LATENCY)
                        .description(
                                "Duration of the transfer execution command, every outcome of"
                                    + " POST /v1/transfers. No histogram buckets: read _count,"
                                    + " _sum and _max")
                        .register(registry);
    }

    private static Counter transfer(MeterRegistry registry, String outcome, String what) {
        return Counter.builder(TRANSFER)
                .tag("outcome", outcome)
                .description(
                        "Transfer commands by outcome: " + what + ". Only the acting call"
                                + " counts, post-commit - a replay and a converged retry are"
                                + " never throughput. Per instance; rate() and sum() aggregate")
                .register(registry);
    }

    private static Counter beneficiary(MeterRegistry registry, String outcome, String what) {
        return Counter.builder(BENEFICIARY)
                .tag("outcome", outcome)
                .description(
                        "Saved-destination lifecycle: " + what + ". Only the acting call"
                                + " counts, post-commit - a converged retry is never"
                                + " throughput. Per instance; rate() and sum() aggregate")
                .register(registry);
    }

    /** A transfer judged {@code COMPLETED} by the acting call, post-commit. */
    public void completed() {
        completed.increment();
    }

    /** A transfer judged {@code FAILED} by the acting call, post-commit. */
    public void failed() {
        failed.increment();
    }

    /** A retried key that replayed its recorded judgement, post-commit. */
    public void replayed() {
        replayed.increment();
    }

    /** The acting reversal, post-commit. */
    public void reversed() {
        reversed.increment();
    }

    /** A transfer command refused with nothing written. */
    public void refused() {
        refused.increment();
    }

    /** An {@code INV-IDEM-03} fingerprint conflict on the transfer command. */
    public void conflict() {
        conflict.increment();
    }

    /** The execution command's duration, whatever its outcome — the injected clock's answer. */
    public void latency(Duration elapsed) {
        latency.record(elapsed);
    }

    /** The creating beneficiary call, post-commit. */
    public void beneficiaryAdded() {
        beneficiaryAdded.increment();
    }

    /** The winning beneficiary removal, post-commit. */
    public void beneficiaryRemoved() {
        beneficiaryRemoved.increment();
    }
}
