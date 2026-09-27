package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The read side of disputes (`P7-TSK-012`): the counterparty's reads, tenant-scoped in every
 * statement ({@code INV-MER-01}), and the operator's, across tenants by permission and audited
 * per dispute shown ({@code payments.DisputeRead}, the routing explanation's reasoning: a
 * privileged read of somebody else's facts is itself a fact).
 *
 * <p>A counterparty is named by its ledger accounts, never by who it is — the caller resolves
 * the merchant's own payables through the ledger's owner-scoped read and hands them in, so
 * {@code payments} still does not know what a merchant is (ADR-0061 §4's composition rule,
 * applied to reading).
 */
@RequiredArgsConstructor
public final class DisputeReads {

    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The dispute's documents, metadata only (`P7-TSK-014`) — last, so no positional argument
     * moved. */
    @NonNull private final DisputeEvidenceStore<Connection> evidence;

    /** The dispute's answers to the network (`P7-TSK-014`). */
    @NonNull private final DisputeResponseStore<Connection> responses;

    /**
     * One dispute as a surface shows it: the row, its payment, its trail — and, since
     * `P7-TSK-014`, its evidence (metadata only: content is reachable solely through the audited
     * read) and its responses.
     */
    public record Read(
            DisputeStore.Found found,
            List<DisputeStore.StageChange> history,
            List<DisputeEvidence> evidence,
            List<DisputeResponse> responses) {

        public Read {
            Objects.requireNonNull(found, "found must not be null");
            history = List.copyOf(history);
            evidence = List.copyOf(evidence);
            responses = List.copyOf(responses);
        }
    }

    /** The operator's read of one dispute, audited; empty when no dispute has the id. */
    public Optional<Read> readForOperator(Connection unitOfWork, DisputeId id) {
        Objects.requireNonNull(id, "id must not be null");
        return disputes.findById(unitOfWork, id)
                .map(
                        found -> {
                            audited(unitOfWork, found.dispute());
                            return read(unitOfWork, found);
                        });
    }

    /**
     * The operator's read of one payment's disputes, each dispute shown audited; empty when
     * the payment itself is unknown, so a mistyped identifier is told apart from a payment
     * nobody disputed.
     */
    public Optional<List<Read>> listForPaymentForOperator(
            Connection unitOfWork, PaymentIntentId intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        if (intents.findById(unitOfWork, intent).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                disputes.listForIntent(unitOfWork, intent).stream()
                        .map(
                                found -> {
                                    audited(unitOfWork, found.dispute());
                                    return read(unitOfWork, found);
                                })
                        .toList());
    }

    /**
     * A counterparty's read of one dispute: present only when the disputed payment credited
     * one of {@code counterparties} — another tenant's dispute and an unknown one are one
     * absence.
     */
    public Optional<Read> readForCounterparties(
            Connection unitOfWork, DisputeId id, Set<LedgerAccountId> counterparties) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(counterparties, "counterparties must not be null");
        if (counterparties.isEmpty()) {
            return Optional.empty();
        }
        return disputes.findForCounterparties(unitOfWork, id, counterparties)
                .map(found -> read(unitOfWork, found));
    }

    /** A counterparty's disputes, newest first, at most {@code limit}. */
    public List<DisputeStore.Found> listForCounterparties(
            Connection unitOfWork, Set<LedgerAccountId> counterparties, int limit) {
        Objects.requireNonNull(counterparties, "counterparties must not be null");
        if (counterparties.isEmpty()) {
            return List.of();
        }
        return disputes.listForCounterparties(unitOfWork, counterparties, limit);
    }

    /** The chargeback-ratio report's audit target (`P7-TSK-015`): the report, named by its period. */
    public static final String RATIO_REPORT_TARGET_TYPE = "chargeback-ratio-report";

    /**
     * The chargeback-ratio report's counts, per credited account, over {@code [from, to)} on
     * {@code rails} (`P7-TSK-015`; {@link DisputeStore#chargebackCountsByCreditAccount}) — the
     * operator's read across every tenant, so it is ON THE RECORD in the caller's transaction
     * ({@code payments.ChargebackRatioRead}, the report named by {@code period}): a privileged read
     * of every merchant's dispute experience is itself a fact. The caller attributes the accounts;
     * {@code payments} never learns what a merchant is.
     */
    public List<DisputeStore.CreditedCounts> chargebackCountsForOperator(
            Connection unitOfWork, Set<RailId> rails, Instant from, Instant to, String period) {
        Objects.requireNonNull(period, "period must not be null");
        List<DisputeStore.CreditedCounts> counts =
                disputes.chargebackCountsByCreditAccount(unitOfWork, rails, from, to);
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        PaymentsAuditAction.CHARGEBACK_RATIO_READ,
                        RATIO_REPORT_TARGET_TYPE,
                        period,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        PaymentCreation.resolvedCorrelation().correlationId(),
                        // Counts of the window's shape only - never an account, never an amount.
                        Optional.of("period=" + period + ", accounts=" + counts.size())));
        return counts;
    }

    private Read read(Connection unitOfWork, DisputeStore.Found found) {
        DisputeId id = found.dispute().id();
        return new Read(
                found,
                disputes.historyOf(unitOfWork, id),
                evidence.listFor(unitOfWork, id),
                responses.listFor(unitOfWork, id));
    }

    private void audited(Connection unitOfWork, Dispute dispute) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        PaymentsAuditAction.DISPUTE_READ,
                        DisputeNotifications.TARGET_TYPE,
                        dispute.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        PaymentCreation.resolvedCorrelation().correlationId(),
                        Optional.of(
                                "dispute=" + dispute.id() + ", attempt=" + dispute.attemptId())));
    }
}
