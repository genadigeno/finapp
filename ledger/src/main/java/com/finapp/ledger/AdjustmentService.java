package com.finapp.ledger;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The adjustment under four-eyes (`P3-TSK-017` the write, `P3-TSK-021` the control):
 * {@code INV-AUD-04} as <strong>two authenticated acts</strong> — an initiator proposes and
 * nothing posts; a <em>different</em> {@code LEDGER_ADJUST} holder approves, and the journal
 * entry posts in the approval's own transaction through {@link PostingEffect}.
 *
 * <p><strong>The threshold is every adjustment, and that is defining it rather than dodging
 * it.</strong> {@code INV-REV-04} permits "above defined thresholds", but a threshold is a
 * per-currency amount policy — a versioned artefact ({@code INV-HIST-04}) with nothing to
 * calibrate it yet, and a cross-currency threshold is {@code INV-MON-04}'s trap wearing
 * policy clothes. Unconditional is a strengthening; a de-minimis threshold arrives later as
 * its own pinned policy artefact, with the proposal row as its seam.
 *
 * <p><strong>One permission, two acts, two audit records.</strong> Approval requires
 * {@code LEDGER_ADJUST} like proposing (`P2-TSK-004`'s rule: a permission exists when a
 * distinct trust decision does, and the four-eyes control is person-distinctness, not
 * privilege-distinctness). The trail carries {@code ledger.AdjustmentProposed} naming the
 * initiator with the justification, and {@code ledger.AdjustmentPosted} naming the approver
 * — ADR-0010's "second actor column" debt dissolved rather than paid: no record needs two
 * actors, because both people acted, each on their own record.
 *
 * <p><strong>Idempotency lives where the duplicate hurts.</strong> Propose keeps the full
 * machinery — key, scope {@code ledger.adjust}, fingerprint binding actor, reason and lines
 * ({@code INV-IDEM-03}) — because a duplicated <em>proposal</em> is the duplicate-effect
 * vector. Approval carries <strong>no key, deliberately</strong>: the proposal's one-way
 * machine is the idempotency ({@code INV-IDEM-01} through state, the `P2-TSK-008`
 * natural-key argument) — {@code PROPOSED → APPROVED} happens at most once ever under the
 * row lock, the same approver's retry converges on the recorded entry, and there is no
 * request body to fingerprint.
 *
 * <p><strong>Distinctness holds at three ranks</strong>: {@link AdjustmentProposal} refuses
 * self-approval at the domain; `V010`'s {@code approver <> initiator} CHECK is the
 * invariant's own Enforce clause at {@code DB-CONSTRAINT}; and `V010`'s deferred trigger
 * refuses any {@code ADJUSTMENT} entry COMMIT without an approved proposal — binding raw
 * SQL and every future writer.
 */
public final class AdjustmentService {

    /** The propose command's idempotency scope (ADR-0004): one command type, one scope. */
    public static final String IDEMPOTENCY_SCOPE = "ledger.adjust";

    /**
     * The approval-posted entry's {@code idempotency_scope} prefix: the entry's durable tie
     * to the proposal whose approval posted it — the investigator's join, beside the
     * proposal row's own {@code journal_entry_id}.
     */
    static final String APPROVAL_SCOPE_PREFIX = "ledger.adjust.approve:";

    static final String PROPOSAL_TARGET_TYPE = "adjustment_proposal";

    private final IdempotentExecutor executor;
    private final PostingEffect effect;
    private final AdjustmentProposalStore<Connection> proposals;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final PostingObserver observer;

    public AdjustmentService(
            IdempotentExecutor executor,
            JournalEntryStore<Connection> journal,
            AdjustmentProposalStore<Connection> proposals,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            BalanceProjection<Connection> projection,
            IdGenerator ids,
            Clock clock,
            PostingObserver observer) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.effect = new PostingEffect(journal, audit, outbox, projection, ids, clock);
        this.proposals = Objects.requireNonNull(proposals, "proposals must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    /** What a proposal came to: the proposal that stands, and whether this call created it. */
    public record ProposalResult(AdjustmentProposalId proposalId, boolean replayed) {
        public ProposalResult {
            Objects.requireNonNull(proposalId, "proposalId must not be null");
        }
    }

    /**
     * Records the adjustment proposal at most once for its key, or replays the outcome.
     * <strong>Nothing posts here</strong>: the journal entry is the approval's
     * ({@code INV-AUD-04}), which is why this path is not observed by the posting meter —
     * the meter counts journal-write commands, and this writes no journal.
     *
     * <p>Validate, then claim: the lines are proven balanced ({@code INV-LED-01/02}) by
     * building — and discarding — the entry they would become, so an unbalanced request
     * never consumes its key and the minted-and-thrown-away id costs nothing (ADR-0013).
     *
     * @throws UnbalancedJournalEntryException before any claim ({@code INV-LED-01})
     * @throws UnknownPostingAccountException a line names an unknown account or a foreign
     *     currency — `V010`'s FKs refusing at proposal time
     */
    public ProposalResult propose(Connection unitOfWork, AdjustmentCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // Validation first: an unbalanced request never consumes its key.
        JournalEntry.balanced(
                ids, clock, command.postingDate(), command.valueDate(), command.lines());

        // An unestablished actor is an error, never a default (ADR-0021) - and for a
        // proposal the actor IS the initiator INV-AUD-04 distinguishes the approver from.
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, command.idempotencyKey());
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(canonicalForm(command, actor));

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> recordProposal(uow, command, actor, correlation));

        AdjustmentProposalId proposalId =
                AdjustmentProposalId.of(
                        UUID.fromString(
                                new String(
                                        outcome.body()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "a recorded proposal"
                                                                            + " outcome always"
                                                                            + " carries the"
                                                                            + " proposal id")),
                                        StandardCharsets.UTF_8)));
        return new ProposalResult(proposalId, outcome.replayed());
    }

    private CommandResult recordProposal(
            Connection unitOfWork,
            AdjustmentCommand command,
            Actor actor,
            Correlation correlation) {
        AdjustmentProposal proposal =
                AdjustmentProposal.propose(
                        AdjustmentProposalId.next(ids),
                        command.postingDate(),
                        command.valueDate(),
                        command.reference(),
                        command.reason(),
                        actor.id(),
                        command.lines(),
                        clock);
        proposals.insert(unitOfWork, proposal);
        // The first of the four-eyes trail's two records: the initiator, with the
        // justification, at the moment they wrote it (INV-REV-04's reason regime entering
        // the trail at proposal time; ledger.AdjustmentPosted names the approver later).
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        proposal.proposedAt(),
                        LedgerAuditAction.ADJUSTMENT_PROPOSED,
                        PROPOSAL_TARGET_TYPE,
                        proposal.id().value().toString(),
                        Optional.of(command.reason()),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and counts - never an amount (INV-AUD-02).
                        Optional.of(
                                "proposal=" + proposal.id() + ", lines="
                                        + proposal.lines().size())));
        // No outbox event, deliberately: the proposal lifecycle has no consumer, and the
        // posted entry's ledger.JournalEntryPosted at approval remains the announcement.
        return CommandResult.succeeded(
                StoredResponse.of(
                        proposal.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    /** A proposal in full — the approver must be able to read what they would approve. */
    public Optional<AdjustmentProposal> find(
            Connection unitOfWork, AdjustmentProposalId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return proposals.findById(unitOfWork, id);
    }

    /**
     * Approves the proposal as the acting person and posts the entry — the journal-write
     * command, observed by the posting meter (`P3-TSK-020`): posted, replayed (the same
     * approver's converged retry), or refused.
     *
     * <p><strong>Lock-then-look</strong> (`P2-TSK-015`): {@code FOR UPDATE} on the proposal
     * row, then judge status and person, then {@link PostingEffect} and the conditional
     * decision — so N racing approvals produce one entry, and the losers resume onto the
     * winner's committed decision and converge or conflict.
     *
     * @throws AdjustmentProposalNotFoundException nothing has that identifier
     * @throws SelfApprovalRefusedException the actor is the initiator ({@code INV-AUD-04});
     *     nothing is written — the proposal stays standing for a second person
     * @throws AdjustmentProposalNotOpenException a different decision already stands
     * @throws LedgerAccountNotPostableException an account stopped accepting postings since
     *     the proposal — `V007`'s trigger at the approval's insert, the authoritative check
     */
    public PostingResult approve(Connection unitOfWork, AdjustmentProposalId id) {
        Instant started = clock.instant();
        try {
            PostingResult result = doApprove(unitOfWork, id);
            observer.observe(
                    result.replayed()
                            ? PostingObserver.Outcome.REPLAYED
                            : PostingObserver.Outcome.POSTED,
                    Duration.between(started, clock.instant()));
            return result;
        } catch (RuntimeException refusal) {
            observer.observe(
                    PostingObserver.Outcome.REFUSED,
                    Duration.between(started, clock.instant()));
            throw refusal;
        }
    }

    private PostingResult doApprove(Connection unitOfWork, AdjustmentProposalId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        AdjustmentProposal proposal =
                proposals
                        .lockById(unitOfWork, id)
                        .orElseThrow(() -> new AdjustmentProposalNotFoundException(id));

        // The same approver's retry converges on the recorded entry (INV-IDEM-01 through
        // state): the machine, not a stored response, is what answers a lost response.
        if (proposal.status() == AdjustmentProposalStatus.APPROVED
                && proposal.decidedBy().map(actor.id()::equals).orElse(false)) {
            return new PostingResult(proposal.entry().orElseThrow(), true);
        }
        proposal.requireApprovableBy(actor.id());

        // The entry is built from the STORED rows - what the approver read is what posts,
        // with the payload frozen by V010's trigger beneath (TOCTOU closed at the schema).
        JournalEntry entry =
                JournalEntry.balanced(
                        ids,
                        clock,
                        proposal.postingDate(),
                        proposal.valueDate(),
                        proposal.lines());
        PostingAttribution attribution =
                new PostingAttribution(
                        JournalEntryType.ADJUSTMENT,
                        proposal.reference(),
                        Optional.of(proposal.reason()),
                        Optional.empty(),
                        // The approver: the posting is THEIR act, and ledger.AdjustmentPosted
                        // names them (ADR-0021's honesty rule). The initiator is one join
                        // away - the proposal row, reachable from the entry's scope below.
                        actor.id(),
                        correlation,
                        APPROVAL_SCOPE_PREFIX + proposal.id().value());

        effect.record(unitOfWork, entry, attribution, actor, correlation);

        // Belt under the lock: we read PROPOSED on the locked row, so this must win - a
        // zero row count here means the lock protocol was broken, which must be loud.
        boolean decided =
                proposals.decide(
                        unitOfWork,
                        id,
                        AdjustmentProposalStatus.APPROVED,
                        actor.id(),
                        Instant.now(clock),
                        Optional.of(entry.id()));
        if (!decided) {
            throw new IllegalStateException(
                    "the locked proposal " + id + " was decided by another writer: the"
                            + " FOR UPDATE protocol was bypassed");
        }
        return new PostingResult(entry.id(), false);
    }

    /**
     * Rejects — or, for the initiator, withdraws — a standing proposal; converges on one
     * already rejected. <strong>The initiator may reject their own, deliberately</strong>:
     * withdrawal removes an action rather than performing one, and {@code INV-AUD-04}'s
     * clause governs the approval. Not observed: no journal is written on any path here.
     *
     * @return {@code true} when this call recorded the rejection, {@code false} when it
     *     converged on one already standing
     * @throws AdjustmentProposalNotFoundException nothing has that identifier
     * @throws AdjustmentProposalNotOpenException the proposal is {@code APPROVED} — an
     *     approved adjustment is corrected by a reversal, never by un-deciding the proposal
     */
    public boolean reject(Connection unitOfWork, AdjustmentProposalId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        AdjustmentProposal proposal =
                proposals
                        .lockById(unitOfWork, id)
                        .orElseThrow(() -> new AdjustmentProposalNotFoundException(id));
        if (proposal.status() == AdjustmentProposalStatus.REJECTED) {
            // Ensure-rejected converges whoever rejected it: the resource is gone-equivalent,
            // and a second record would name an act that did not happen.
            return false;
        }
        proposal.requireRejectable();

        Instant now = Instant.now(clock);
        boolean decided =
                proposals.decide(
                        unitOfWork,
                        id,
                        AdjustmentProposalStatus.REJECTED,
                        actor.id(),
                        now,
                        Optional.empty());
        if (!decided) {
            throw new IllegalStateException(
                    "the locked proposal " + id + " was decided by another writer: the"
                            + " FOR UPDATE protocol was bypassed");
        }
        // The rejection is an act and is audited (INV-AUD-01); no reason is demanded -
        // declining to move value needs no justification, and the record names who.
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        LedgerAuditAction.ADJUSTMENT_REJECTED,
                        PROPOSAL_TARGET_TYPE,
                        proposal.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "proposal=" + proposal.id() + ", proposedBy="
                                        + proposal.proposedBy())));
        return true;
    }

    /** The flow's correlation with the cause resolved — the {@code PostingService} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an adjustment must run inside a correlation"
                                                        + " scope: the journal's causation"
                                                        + " column is NOT NULL"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }

    /**
     * The canonical form the fingerprint hashes. <strong>Includes the actor</strong> (a key
     * is not a secret, and a second operator replaying a logged key must conflict — never
     * inherit somebody else's proposal) and the reason, because a key reused with a
     * different justification is a materially different request ({@code INV-IDEM-03}).
     */
    private static byte[] canonicalForm(AdjustmentCommand command, Actor actor) {
        StringBuilder canonical =
                new StringBuilder("ledger.adjust|ADJUSTMENT|")
                        .append(actor.id())
                        .append('|')
                        .append(command.postingDate())
                        .append('|')
                        .append(command.valueDate())
                        .append('|')
                        .append(command.reference())
                        .append('|')
                        .append(command.reason())
                        .append('|');
        for (JournalLine line : command.lines()) {
            canonical
                    .append(line.account().value())
                    .append('>')
                    .append(line.direction())
                    .append('>')
                    .append(line.amount().minorUnits())
                    .append('>')
                    .append(line.amount().currency().code())
                    .append('>')
                    .append(line.amount().scale())
                    .append(';');
        }
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }
}
