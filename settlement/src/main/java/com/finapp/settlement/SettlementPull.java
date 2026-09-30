package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * One pull of one source's report (`P8-TSK-021`, ADR-0066 §1): the permit, the fetch, the door.
 *
 * <ol>
 *   <li><strong>The permit</strong>, in its own short transaction: the schedule's herd takes it
 *       only past the window (pacing, never correctness), an operator's explicit fetch renews it
 *       unconditionally — both strictly advancing it.
 *   <li><strong>The fetch</strong>, holding NO connection (ADR-0046): an external read with an
 *       ambiguous outcome, at-least-once — a lost answer is simply pulled again.
 *   <li><strong>The door</strong>, in the reception's one transaction, exactly as an upload:
 *       screened, content-addressed, born {@code RECEIVED} with {@code received_via = PULL} and no
 *       deliverer, audited {@code SettlementFileReceivedByPull} acting-only. The content unique
 *       makes ten pullers — or a pull racing an upload of the same bytes — one file with a
 *       {@code DUPLICATE} receipt per delivery; the accept leg accepts it once parsed, without
 *       attestation, its credential being its authentication ({@code INV-SET-07}).
 * </ol>
 */
@RequiredArgsConstructor
public final class SettlementPull {

    /** {@code settlement.pull_permit.business_key}'s shape, restated for a refusal before SQL. */
    private static final Pattern BUSINESS_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<java.sql.Connection> files;
    @NonNull private final PullPermitStore<java.sql.Connection> permits;
    @NonNull private final FileReception<java.sql.Connection> reception;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final PullOutcomeObserver observer;
    @NonNull private final Clock clock;

    /** The configured collectors, by source code — a source without one is not pulled. */
    @NonNull private final Map<String, SettlementReportCollector> collectors;

    /** What one pull came to. */
    public sealed interface Outcome {

        /** Another attempt stands inside the window: nothing fetched (the herd's loser). */
        record Paced() implements Outcome {}

        /** The source declares no pull, or no collector is configured for it. */
        record NotPullable() implements Outcome {}

        /** The counterparty has not published the report yet. */
        record NotYet() implements Outcome {}

        /** Nothing usable came back. */
        record Failed(SettlementReportCollector.FailureOutcome outcome) implements Outcome {
            public Failed {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }

        /** Bytes reached the door, and this is what the door committed. */
        record Received(FileReception.Result result) implements Outcome {
            public Received {
                Objects.requireNonNull(result, "result must not be null");
            }
        }
    }

    /** Whether {@code sourceCode} can be pulled at all: declared for PULL, a collector wired. */
    public boolean pullable(String sourceCode) {
        return sources.byCode(sourceCode)
                        .map(declared -> declared.channels().contains(DeliveryChannel.PULL))
                        .orElse(false)
                && collectors.containsKey(sourceCode);
    }

    /**
     * Pulls {@code businessKey}'s report for {@code sourceCode}. {@code window} empty is an
     * operator's explicit fetch — the permit renewed, never refused.
     *
     * @throws FileReception.SettlementSourceUnknown for a code no declaration names
     * @throws FileReception.SettlementSourceRetired for a retired source
     * @throws IllegalArgumentException for a business key of the wrong shape
     */
    public Outcome pull(
            String sourceCode,
            String businessKey,
            Optional<Duration> window,
            Actor actor,
            Correlation correlation) {
        Objects.requireNonNull(sourceCode, "sourceCode must not be null");
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!BUSINESS_KEY.matcher(businessKey).matches()) {
            throw new IllegalArgumentException(
                    "a business key is an ISO date or a cycle token of at most 64 characters");
        }
        SettlementSourceDescriptor declared =
                sources.byCode(sourceCode)
                        .orElseThrow(() -> new FileReception.SettlementSourceUnknown(sourceCode));
        SettlementReportCollector collector = collectors.get(sourceCode);
        if (!declared.channels().contains(DeliveryChannel.PULL) || collector == null) {
            return new Outcome.NotPullable();
        }

        // 1. The permit - its own short transaction, before any bytes move.
        Instant now = clock.instant();
        boolean mine =
                transactions.inTransaction(
                        unitOfWork -> {
                            SettlementFileStore.SourceRow source =
                                    files.sourceByCode(unitOfWork, sourceCode)
                                            .orElseThrow(
                                                    () ->
                                                            new SettlementStorageException(
                                                                    "source '" + sourceCode
                                                                            + "' is declared but"
                                                                            + " not seeded"));
                            if (!source.active()) {
                                throw new FileReception.SettlementSourceRetired(sourceCode);
                            }
                            if (window.isPresent()) {
                                return permits.claim(
                                        unitOfWork, source.id(), businessKey, now, window.get());
                            }
                            permits.renew(unitOfWork, source.id(), businessKey, now);
                            return true;
                        });
        if (!mine) {
            return new Outcome.Paced();
        }

        // 2. The fetch - no connection held across it (ADR-0046).
        SettlementReportCollector.Collected collected = collector.collect(businessKey);

        // 3. The door - exactly as an upload's, authenticated by the channel.
        return switch (collected) {
            case SettlementReportCollector.Collected.Report report ->
                    new Outcome.Received(
                            transactions.inTransaction(
                                    unitOfWork ->
                                            reception.receive(
                                                    unitOfWork,
                                                    new FileReception.Delivery(
                                                            sourceCode,
                                                            DeliveryChannel.PULL,
                                                            report.content(),
                                                            businessDateOf(businessKey),
                                                            actor,
                                                            SettlementAuditAction
                                                                    .SETTLEMENT_FILE_RECEIVED_BY_PULL,
                                                            correlation))));
            case SettlementReportCollector.Collected.NotYet notYet -> {
                observer.notReceived(sourceCode, "not_yet");
                yield new Outcome.NotYet();
            }
            case SettlementReportCollector.Collected.Failed failed -> {
                observer.notReceived(
                        sourceCode, failed.outcome().name().toLowerCase(Locale.ROOT));
                yield new Outcome.Failed(failed.outcome());
            }
        };
    }

    /** A daily key is its business date; a cycle token declares none. */
    private static Optional<LocalDate> businessDateOf(String businessKey) {
        try {
            return Optional.of(LocalDate.parse(businessKey));
        } catch (DateTimeParseException notADate) {
            return Optional.empty();
        }
    }
}
