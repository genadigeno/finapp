package com.finapp.app.ledger;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/ledger/adjustments` (`P3-TSK-017` the write, `P3-TSK-021` the four-eyes control,
 * {@code INV-REV-04}/{@code INV-AUD-04}): the manual adjustment — the highest-risk financial
 * action on the platform, and the only public write into the journal — as <strong>two
 * authenticated acts by two people</strong>. {@code POST} proposes and posts nothing;
 * {@code POST …/{id}/approval} by a <em>different</em> operator posts the entry;
 * {@code DELETE} rejects or withdraws; {@code GET} shows an approver exactly what they would
 * approve.
 *
 * <p><strong>The propose response changed shape with `P3-TSK-021` and that is a reviewed
 * BREAKING change</strong> (ADR-0015's precedent): {@code entryId} left the body, because
 * nothing posts at proposal time any more — no client exists, and keeping a parallel
 * one-person write for compatibility would keep {@code INV-AUD-04} violated by the surface
 * retained to avoid saying so.
 *
 * <p><strong>One permission for both acts, deliberately</strong> (`P2-TSK-004`'s rule: a
 * permission exists when a distinct trust decision does): the trust decision — may operate
 * the ledger's manual writes — is one, and the four-eyes control is person-distinctness,
 * enforced by the domain, `V010`'s CHECK and the deferred journal trigger, never by a
 * second permission. A maker/checker split is a recorded seam for a real population.
 *
 * <p><strong>Approval and rejection carry no idempotency key, deliberately</strong>: the
 * proposal's one-way machine is the idempotency ({@code INV-IDEM-01} through state) — the
 * same approver's retry converges on the recorded entry, a repeated rejection converges,
 * and there is no request body to fingerprint. The propose step keeps the full machinery,
 * because a duplicated <em>proposal</em> is the duplicate-effect vector.
 */
@RestController
@RequestMapping(path = "/ledger/adjustments", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdjustmentController {

    private final LedgerAdjustments adjustments;

    public AdjustmentController(LedgerAdjustments adjustments) {
        this.adjustments = Objects.requireNonNull(adjustments, "adjustments must not be null");
    }

    /**
     * Proposes the adjustment, or replays the recorded outcome for a retried key
     * ({@code INV-IDEM-01}); a reused key from a different operator or with a different
     * request — the reason included — is a {@code 409} conflict ({@code INV-IDEM-03}).
     * Nothing posts: the entry is the approval's, by a second person.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerAdjustments.ProposalView propose(
            @Valid @RequestBody AdjustmentRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return adjustments.propose(body, idempotencyKey);
    }

    /**
     * The proposal in full — dates, lines, reason, people, outcome: an approver must see
     * what they approve, and an authorised operator reads amounts (the `P2-TSK-016`
     * reviewer-sees-everything argument). Unknown and malformed are one {@code 404}.
     */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    public LedgerAdjustments.ProposalDetailView view(@PathVariable("id") String id) {
        return adjustments.view(id);
    }

    /**
     * Approves as the acting person and posts the entry in this transaction
     * ({@code INV-AUD-04}). Self-approval is {@code 409 ledger.SelfApprovalRefused} with
     * nothing written; a decided proposal is {@code 409 ledger.ProposalNotOpen}; the same
     * approver's retry converges on the recorded entry with this same {@code 201}.
     */
    @PostMapping(path = "/{id}/approval")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerAdjustments.AdjustmentView approve(@PathVariable("id") String id) {
        return adjustments.approve(id);
    }

    /**
     * Rejects the proposal — or, for the initiator, withdraws it: removing an action needs
     * no second person, because {@code INV-AUD-04}'s clause governs the approval. A repeat
     * converges on this same {@code 204}; an {@code APPROVED} proposal is a {@code 409},
     * corrected by a reversal rather than by un-deciding.
     */
    @DeleteMapping("/{id}")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable("id") String id) {
        adjustments.reject(id);
    }
}
