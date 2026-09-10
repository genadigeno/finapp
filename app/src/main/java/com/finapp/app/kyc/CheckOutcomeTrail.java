package com.finapp.app.kyc;

import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable trail of one check outcome: the audit record and the counter (`P2-TSK-009`,
 * extracted by `P2-TSK-011` the moment a second door arrived — the provider callback).
 *
 * <p>Extraction rather than a second copy for the same reason as {@link CaseAssessment}: the
 * audit write is the one place the platform claims the system actor for a check outcome, and
 * two copies of it would be two {@code enterSystem()} sites saying the same sentence and
 * drifting apart. The enumerated site in {@code SystemActorCallSitesAreEnumeratedTest}
 * <strong>moved here</strong> from {@code VerificationRunService.audit} — one site, whichever
 * door the outcome arrived through.
 *
 * <p>Called only by the writer that <em>won</em> the conditional completion: one outcome, one
 * record, one increment — a late or losing answer becomes evidence and nothing else, because a
 * {@code kyc.CheckCompleted} record for a completion that did not happen would be a lie in a
 * permanent trail.
 */
public class CheckOutcomeTrail {

    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final Map<CheckOutcome, Counter> outcomes;

    public CheckOutcomeTrail(
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        // Eager, one per outcome (P1-TSK-029): a counter that starts existing when the thing it
        // counts happens is a delayed notification, not monitoring - and a rising
        // `indeterminate` is a provider degrading, which is exactly the moment the series must
        // already exist. Registration is idempotent, so both doors sharing this class share
        // the same series.
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.outcomes = new EnumMap<>(CheckOutcome.class);
        for (CheckOutcome outcome : CheckOutcome.values()) {
            outcomes.put(
                    outcome,
                    Counter.builder("finapp.kyc.check")
                            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                            .description("Verification check outcomes, as normalised by us")
                            .register(meterRegistry));
        }
    }

    /**
     * Records a won completion: the audit record and the counter, in the caller's transaction.
     *
     * <p>The record ties a provider's answer into the decision trail ({@code INV-KYC-02} will
     * reference these checks). The actor is the platform — {@code enterSystem()}, the
     * enumerated site: nobody is present when a machine records what a machine answered, and
     * the correlation is what ties the record to the flow that carried the answer — the run's,
     * or the callback request's.
     */
    @SuppressWarnings("try") // the actor scope is used for its close side effect
    public void record(Connection unitOfWork, VerificationCheck check, CheckOutcome outcome) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            auditWriter.append(
                    unitOfWork,
                    new AuditRecord(
                            AuditId.next(ids),
                            SecurityContext.require(),
                            Instant.now(clock),
                            KycAuditAction.KYC_CHECK_COMPLETED,
                            "VerificationCheck",
                            check.id().value().toString(),
                            Optional.empty(),
                            AuditOutcome.SUCCEEDED,
                            CorrelationContext.current()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "a check outcome must be recorded"
                                                                + " inside a correlation scope:"
                                                                + " the record carries the flow's"
                                                                + " identifier"))
                                    .correlationId(),
                            // Identifiers and enum names only (INV-AUD-02) - which case, which
                            // question, what it normalised to.
                            Optional.of(
                                    "case=" + check.caseId()
                                            + " type=" + check.type()
                                            + " outcome=" + outcome)));
        }
        outcomes.get(outcome).increment();
    }
}
