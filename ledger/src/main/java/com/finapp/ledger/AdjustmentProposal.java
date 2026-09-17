package com.finapp.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A proposed manual adjustment awaiting a second person (`P3-TSK-021`, {@code INV-AUD-04}).
 *
 * <p><strong>Four-eyes is two authenticated acts, never one request with two names.</strong>
 * The initiator proposes — nothing posts — and a <em>different</em> {@code LEDGER_ADJUST}
 * holder approves, whereupon the journal entry posts in the approval's own transaction. An
 * {@code approverId} field on a request would be a name anyone can type, not an authorised
 * act, which is why the proposal is an aggregate with a lifecycle rather than a parameter.
 *
 * <p><strong>The payload is fixed at proposal time</strong>: dates, reference, reason and
 * lines are what the approver reads and exactly what will post — immutable here by having no
 * mutator, and at the schema by trigger for every writer (`V010`), so the approve-what-you-
 * read property is structural rather than procedural.
 *
 * <p><strong>Self-approval is refused by the aggregate</strong> ({@code INV-LIFE-02}: the
 * rule lives on the domain object, not merely behind the API), with `V010`'s
 * {@code approver <> initiator} CHECK as the DB-CONSTRAINT-rank layer beneath — the
 * invariant's own Enforce clause. Both terminal states are terminal ({@code INV-LIFE-04});
 * the one-way machine is also the approval's idempotency, because
 * {@code PROPOSED → APPROVED} happens at most once for any set of writers.
 */
public record AdjustmentProposal(
        AdjustmentProposalId id,
        AdjustmentProposalStatus status,
        LocalDate postingDate,
        LocalDate valueDate,
        String reference,
        String reason,
        String proposedBy,
        Instant proposedAt,
        List<JournalLine> lines,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<JournalEntryId> entry) {

    public AdjustmentProposal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(postingDate, "postingDate is a domain input and must be given");
        Objects.requireNonNull(valueDate, "valueDate is a domain input and must be given");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (reason == null || reason.isBlank()) {
            // INV-REV-04: the justification is what makes an adjustment defensible, and it
            // enters the trail at proposal time - a reason-less proposal must not exist.
            throw new IllegalArgumentException(
                    "an adjustment proposal requires a reason (INV-REV-04)");
        }
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            // The INV-LED-02 shape at the proposal: a single-sided set could never post, so
            // asking a second person to read it would be asking them to approve nothing.
            throw new IllegalArgumentException(
                    "an adjustment proposal has at least two lines (INV-LED-02): a"
                            + " single-sided set could never post");
        }
        boolean decided = status.isTerminal();
        if (decided != decidedBy.isPresent() || decided != decidedAt.isPresent()) {
            throw new IllegalArgumentException(
                    "a decided proposal records who decided and when; a standing one has"
                            + " neither");
        }
        if ((status == AdjustmentProposalStatus.APPROVED) != entry.isPresent()) {
            throw new IllegalArgumentException(
                    "exactly an approved proposal names the entry its approval posted");
        }
        if (status == AdjustmentProposalStatus.APPROVED
                && proposedBy.equals(decidedBy.orElseThrow())) {
            // INV-AUD-04's Enforce clause at the domain, beside V010's CHECK: an approved
            // proposal always names two distinct people.
            throw new IllegalArgumentException(
                    "an approved proposal's approver is never its initiator (INV-AUD-04)");
        }
    }

    /** A newly proposed adjustment: {@code PROPOSED} from birth, awaiting a second person. */
    public static AdjustmentProposal propose(
            AdjustmentProposalId id,
            LocalDate postingDate,
            LocalDate valueDate,
            String reference,
            String reason,
            String proposedBy,
            List<JournalLine> lines,
            Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new AdjustmentProposal(
                id,
                AdjustmentProposalStatus.PROPOSED,
                postingDate,
                valueDate,
                reference,
                reason,
                proposedBy,
                Instant.now(clock),
                lines,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * Refuses everything that must stop an approval by {@code actorId} — the aggregate's
     * half of the protocol; the store's conditional {@code UPDATE} and `V010`'s CHECKs are
     * the layers beneath.
     *
     * @throws SelfApprovalRefusedException the actor is the initiator ({@code INV-AUD-04}) —
     *     the invariant's named negative, and nothing is written on this path
     * @throws AdjustmentProposalNotOpenException the proposal is already decided
     *     ({@code INV-LIFE-04}); a same-approver retry converges in the service instead of
     *     reaching this check
     */
    public void requireApprovableBy(String actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (status.isTerminal()) {
            throw new AdjustmentProposalNotOpenException(id, status);
        }
        if (proposedBy.equals(actorId)) {
            throw new SelfApprovalRefusedException(id);
        }
    }

    /**
     * Refuses a rejection of a decided proposal. The initiator <em>may</em> reject their
     * own — withdrawal removes an action rather than performing one, and {@code INV-AUD-04}
     * governs the approval — so there is no self check here, deliberately.
     */
    public void requireRejectable() {
        if (status.isTerminal()) {
            throw new AdjustmentProposalNotOpenException(id, status);
        }
    }

    /**
     * Names the proposal, its state and its people — never the reason and never an amount
     * ({@code INV-AUD-02}): a record's generated {@code toString} prints every component,
     * and the reason is a person's free text.
     */
    @Override
    public String toString() {
        return "AdjustmentProposal[id=" + id + ", status=" + status + ", proposedBy="
                + proposedBy + decidedBy.map(by -> ", decidedBy=" + by).orElse("") + "]";
    }
}
