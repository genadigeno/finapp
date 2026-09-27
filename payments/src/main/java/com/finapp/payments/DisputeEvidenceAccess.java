package com.finapp.payments;

import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * The dispute evidence paths (`P7-TSK-014`, ADR-0061 §7, {@code INV-DSP-03}): attach a document,
 * read one back — and nothing else touches evidence content but the response dispatch.
 *
 * <h2>The trail of who looked is the control</h2>
 *
 * <p>{@code DocumentAccess}'s rule, restated for disputes: every content read writes
 * {@code payments.DisputeEvidenceRead} in the read's own transaction, so a read whose record
 * could fail to commit afterwards cannot happen — the transaction holds both or neither. The
 * actor is whoever is looking ({@code SecurityContext.require()}), never the platform.
 *
 * <h2>An upload is an act on the dispute, judged like a response</h2>
 *
 * <p>It locks the attempt and then the dispute ({@link DisputeActs}), and a NEW document is
 * admitted only while the dispute takes an answer: {@code CHARGED_BACK}, no live response (the
 * evidence set froze with it), the deadline not passed, and fewer than
 * {@value DisputeEvidenceContent#MAX_PER_DISPUTE} documents held. A document already stored —
 * the same bytes — is answered first, before any of those guards: a retry after a lost response
 * learns its document is stored, which is the truth, rather than a refusal it cannot interpret.
 */
public final class DisputeEvidenceAccess {

    static final String EVIDENCE_TARGET_TYPE = "dispute-evidence";

    private final TransactionRunner transactions;
    private final DisputeActs acts;
    private final DisputeEvidenceStore<Connection> evidence;
    private final DisputeResponseStore<Connection> responses;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    /** Manual: the act rule is derived from the stores it reads, not injected. */
    public DisputeEvidenceAccess(
            TransactionRunner transactions,
            DisputeStore<Connection> disputes,
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            LedgerAccountStore<Connection> ledgerAccounts,
            DisputeEvidenceStore<Connection> evidence,
            DisputeResponseStore<Connection> responses,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.acts = new DisputeActs(disputes, attempts, intents, ledgerAccounts);
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** What an upload did: stored a new document, or found the identical one already stored. */
    public record Uploaded(DisputeEvidence evidence, boolean created) {}

    /**
     * Attaches a document, or answers the identical one already attached.
     *
     * @throws UnknownDisputeException no dispute visible to the actor has the id
     * @throws DisputeResponseRefusedException the dispute takes no evidence now — its stage, a
     *     live response, the deadline, the document bound, or an operator outside its policy
     */
    public Uploaded upload(
            DisputeActor actor,
            DisputeId dispute,
            DisputeEvidenceKind kind,
            DisputeEvidenceContentType contentType,
            DisputeEvidenceContent content) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Optional<String> reason = DisputeActs.reasonOf(actor);
        Actor uploader = SecurityContext.require();
        return transactions.inTransaction(
                uow -> {
                    DisputeStore.Found found = acts.lockForAct(uow, actor, dispute);
                    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                    Optional<DisputeEvidence> same = evidence.findByContent(uow, dispute, content);
                    if (same.isPresent()) {
                        audited(uow, uploader, found.dispute(), same.get(), false, reason, now);
                        return new Uploaded(same.get(), false);
                    }
                    DisputeActs.requireRespondable(
                            found.dispute(), responses.findLive(uow, dispute).isPresent(), now);
                    if (evidence.countFor(uow, dispute) >= DisputeEvidenceContent.MAX_PER_DISPUTE) {
                        throw new DisputeResponseRefusedException(
                                DisputeResponseRefusedException.Refusal.EVIDENCE_LIMIT_REACHED);
                    }
                    DisputeEvidenceStore.Stored stored =
                            evidence.appendOrConverge(
                                    uow,
                                    new DisputeEvidence(
                                            DisputeEvidenceId.next(ids),
                                            dispute,
                                            kind,
                                            contentType,
                                            content.length(),
                                            uploader.id(),
                                            uploader.type().name(),
                                            now),
                                    content);
                    audited(uow, uploader, found.dispute(), stored.evidence(), stored.created(),
                            reason, now);
                    return new Uploaded(stored.evidence(), stored.created());
                });
    }

    /**
     * Reads one document's content — the counterparty within its tenancy, the operator across
     * tenants — writing {@code payments.DisputeEvidenceRead} in the same transaction. Empty for a
     * document the actor cannot see, which writes no record: a trail entry for a guessed
     * identifier would put identifiers that were never real into the permanent record.
     */
    public Optional<DisputeEvidenceStore.Content> read(
            DisputeActor actor, DisputeId dispute, DisputeEvidenceId id) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor reader = SecurityContext.require();
        return transactions.inTransaction(
                uow -> {
                    Optional<DisputeEvidenceStore.Content> content =
                            switch (actor) {
                                case DisputeActor.Counterparty counterparty ->
                                        counterparty.accounts().isEmpty()
                                                ? Optional.empty()
                                                : evidence.readContentForCounterparties(
                                                        uow, dispute, id, counterparty.accounts());
                                case DisputeActor.Operator operator ->
                                        evidence.readContent(uow, dispute, id);
                            };
                    content.ifPresent(
                            read ->
                                    audit.append(
                                            uow,
                                            new AuditRecord(
                                                    AuditId.next(ids),
                                                    reader,
                                                    Instant.now(clock),
                                                    PaymentsAuditAction.DISPUTE_EVIDENCE_READ,
                                                    EVIDENCE_TARGET_TYPE,
                                                    id.value().toString(),
                                                    Optional.empty(),
                                                    AuditOutcome.SUCCEEDED,
                                                    PaymentCreation.resolvedCorrelation()
                                                            .correlationId(),
                                                    Optional.of("dispute=" + dispute))));
                    return content;
                });
    }

    private void audited(
            Connection unitOfWork,
            Actor uploader,
            Dispute dispute,
            DisputeEvidence document,
            boolean created,
            Optional<String> reason,
            Instant now) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        uploader,
                        now,
                        PaymentsAuditAction.DISPUTE_EVIDENCE_UPLOADED,
                        DisputeNotifications.TARGET_TYPE,
                        dispute.id().value().toString(),
                        // The operator's own words when an operator acts (INV-AUD-03).
                        reason,
                        AuditOutcome.SUCCEEDED,
                        PaymentCreation.resolvedCorrelation().correlationId(),
                        // Identifiers and shape only - never the content (INV-AUD-02).
                        Optional.of(
                                "evidence=" + document.id()
                                        + ", kind=" + document.kind()
                                        + ", contentType=" + document.contentType()
                                        + ", bytes=" + document.contentLength()
                                        + ", created=" + created)));
    }
}
