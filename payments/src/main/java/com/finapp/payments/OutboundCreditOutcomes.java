package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The one applier of a cross-border outbound credit's answers (`P9-TSK-020`, PHASE_9_PLAN.md section 12.8, the
 * lifecycle document 3.6): the synchronous send's answer, a hinted inquiry's and the sweep's all land here, on
 * the row the caller locked, and each judges the stored state - the acting conditional - so whichever resolver
 * arrives first acts and every other records nothing.
 *
 * <p>The completion is ONE transaction (T-c), in order: the acting exit; the claim on the corridor's execution
 * (V026); the hold released; the entry {@code outbound-credit:<id>} - its lines composed by the subject's owners
 * and checked against the held and instructed amounts ({@code INV-XB-03}); the trade booked, the quote
 * {@code EXECUTED} and the payment {@code IN_TRANSIT} (the composition); the {@code CROSSBORDER_PAYOUT}
 * expectation. An answer implying acceptance with a delivery applies the completion, then the delivery, in the
 * same transaction. A failure posts nothing: the hold is released and the subject fails.
 */
@Slf4j
@RequiredArgsConstructor
public final class OutboundCreditOutcomes {

    /** The completion entry's idempotency key prefix (PHASE_9_PLAN.md section 12.6's catalogue). */
    public static final String POSTING_KEY_PREFIX = "outbound-credit:";

    /** The audit target type. */
    public static final String TARGET_TYPE = "OutboundCredit";

    @NonNull private final OutboundCreditStore credits;
    @NonNull private final HoldService holds;
    @NonNull private final PostingService postings;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final PaymentRails rails;
    @NonNull private final SchemeExecutionClaimStore<Connection> claims;
    @NonNull private final SettlementExpectations expectations;
    @NonNull private final OutboundCreditComposition<Connection> composition;
    @NonNull private final OutboundCreditReturns returns;

    /** What an answer did: the credit's status after it, and whether this call acted. */
    public record Applied(OutboundCreditStore.Status status, boolean acting) {}

    /**
     * The synchronous send's answer (or a takeover's re-send). {@code NOTHING_SENT} fails only a FIRST send - the
     * row's permit never renewed - because a re-send reaching nobody says nothing about the first.
     */
    public Applied applySendAnswer(
            Connection unitOfWork, OutboundCreditStore.Row locked, CorridorRail.SendAnswer answer, Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        return switch (answer) {
            case CorridorRail.SendAnswer.Received received -> receive(unitOfWork, locked, Optional.empty(), correlation, "send");
            case CorridorRail.SendAnswer.Accepted accepted -> locked.status().resolvable()
                    ? complete(unitOfWork, locked, accepted.providerReference(), Optional.empty(), correlation, "send")
                    : contradicted(locked, "an acceptance");
            case CorridorRail.SendAnswer.Rejected rejected -> locked.status().resolvable()
                    ? fail(unitOfWork, locked, OutboundCreditStore.FailureReason.DECLINED, correlation, "send")
                    : contradicted(locked, "a rejection");
            case CorridorRail.SendAnswer.NothingSent nothing -> locked.status() == OutboundCreditStore.Status.DISPATCHED
                            && locked.lastDispatchedAt().equals(locked.createdAt())
                    ? fail(unitOfWork, locked, OutboundCreditStore.FailureReason.PROVIDER_UNAVAILABLE, correlation, "send")
                    : new Applied(locked.status(), false);
            case CorridorRail.SendAnswer.Indeterminate unknown -> unknown(unitOfWork, locked, correlation, "send");
        };
    }

    /**
     * An inquiry's answer - the sweep's, or a callback's hinted inquiry. {@code UNRECOGNISED} fails the credit
     * {@code NEVER_RECEIVED} only from {@code DISPATCHED} or {@code UNKNOWN} and only when the latest permit is at
     * or before {@code neverReceivedBound}, re-judged here on the locked row; a {@code RECEIVED} credit never.
     */
    public Applied applyInquiryAnswer(
            Connection unitOfWork,
            OutboundCreditStore.Row locked,
            CorridorRail.InquiryAnswer answer,
            Instant neverReceivedBound,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(neverReceivedBound, "neverReceivedBound must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        return switch (answer) {
            case CorridorRail.InquiryAnswer.Found found -> found(unitOfWork, locked, found, correlation);
            case CorridorRail.InquiryAnswer.Unrecognised unrecognised -> {
                boolean concludable = (locked.status() == OutboundCreditStore.Status.DISPATCHED
                                || locked.status() == OutboundCreditStore.Status.UNKNOWN)
                        && locked.permitAtOrBefore(neverReceivedBound);
                yield concludable
                        ? fail(unitOfWork, locked, OutboundCreditStore.FailureReason.NEVER_RECEIVED, correlation, "inquiry")
                        : new Applied(locked.status(), false);
            }
            // No answer is not an answer: nothing is concluded, the next inquiry asks again.
            case CorridorRail.InquiryAnswer.NothingSent nothing -> new Applied(locked.status(), false);
            case CorridorRail.InquiryAnswer.Indeterminate unknown -> new Applied(locked.status(), false);
        };
    }

    private Applied found(
            Connection unitOfWork, OutboundCreditStore.Row locked, CorridorRail.InquiryAnswer.Found found, Correlation correlation) {
        return switch (found.state()) {
            case RECEIVED -> receive(unitOfWork, locked, found.providerReference(), correlation, "inquiry");
            case ACCEPTED -> {
                ProviderReference theirs = found.providerReference().orElseThrow();
                if (locked.status().resolvable()) {
                    // An answer implying acceptance applies the completion, then the delivery, then an applicable
                    // return, in this one transaction (the lifecycle document 3.6).
                    Applied completed = complete(unitOfWork, locked, theirs, found.deliveredAt(), correlation, "inquiry");
                    found.returned().ifPresent(returned -> applyReturn(unitOfWork, locked.id(), returned, correlation));
                    yield completed;
                }
                if (locked.status() == OutboundCreditStore.Status.COMPLETED) {
                    Applied delivered = found.deliveredAt().isPresent()
                            ? deliver(unitOfWork, locked, found.deliveredAt().get(), correlation, "inquiry")
                            : new Applied(locked.status(), false);
                    if (found.returned().isPresent()) {
                        boolean applied = applyReturn(unitOfWork, locked.id(), found.returned().get(), correlation);
                        yield new Applied(locked.status(), delivered.acting() || applied);
                    }
                    yield delivered;
                }
                yield locked.status() == OutboundCreditStore.Status.FAILED
                        ? contradicted(locked, "an acceptance")
                        : new Applied(locked.status(), false);
            }
            case REJECTED -> locked.status().resolvable()
                    ? fail(unitOfWork, locked, OutboundCreditStore.FailureReason.DECLINED, correlation, "inquiry")
                    : contradicted(locked, "a rejection");
            // The provider holds the credit recalled - our recall's answer, lost on the way (P9-TSK-024): concluded
            // only beside a standing request, as the recall's own answer would have been.
            case RECALLED -> {
                if (locked.recallRequestedAt().isEmpty()) {
                    yield contradicted(locked, "a recall nobody requested");
                }
                yield locked.status().resolvable()
                        ? recalled(unitOfWork, locked, correlation, "inquiry")
                        : new Applied(locked.status(), false);
            }
        };
    }

    /**
     * Applies the provider's answer to a recall (`P9-TSK-024`, ADR-0079 point 5) to the credit the caller holds
     * {@code FOR UPDATE}: only {@code Recalled} concludes - {@code FAILED(RECALLED)} through the one failure path,
     * the hold released, the quote abandoned, a cover unwound; {@code TooLate} records the refusal and concludes
     * nothing, the credit completing as it would have; {@code Unrecognised} is the never-received rule, with no
     * re-send; no answer is not an answer, and the next pass asks again.
     */
    public Applied applyRecallAnswer(
            Connection unitOfWork,
            OutboundCreditStore.Row locked,
            CorridorRail.RecallAnswer answer,
            Instant neverReceivedBound,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(neverReceivedBound, "neverReceivedBound must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!locked.recallPending()) {
            // Answered meanwhile, or concluded by another resolver: the locked row decides, nothing more.
            return new Applied(locked.status(), false);
        }
        return switch (answer) {
            case CorridorRail.RecallAnswer.Recalled recalled -> recalled(unitOfWork, locked, correlation, "recall");
            case CorridorRail.RecallAnswer.TooLate tooLate -> {
                requireLanded(credits.recordRecallOutcome(unitOfWork, locked.id(), OutboundCreditStore.RecallOutcome.REFUSED));
                composition.recallAnswered(unitOfWork, locked.subject(), OutboundCreditStore.RecallOutcome.REFUSED);
                // "Too late" is the provider saying it holds the credit and has committed to it: a DISPATCHED or UNKNOWN
                // credit is RECEIVED from here, so no later inconsistent "unrecognised" can conclude it NEVER_RECEIVED
                // and release the hold while the provider pays (the Phase 9 -> 10 transition).
                Applied received = receive(unitOfWork, locked, Optional.empty(), correlation, "recall");
                yield new Applied(received.status(), true);
            }
            case CorridorRail.RecallAnswer.Unrecognised unrecognised -> {
                boolean concludable = (locked.status() == OutboundCreditStore.Status.DISPATCHED
                                || locked.status() == OutboundCreditStore.Status.UNKNOWN)
                        && locked.permitAtOrBefore(neverReceivedBound);
                yield concludable
                        ? fail(unitOfWork, locked, OutboundCreditStore.FailureReason.NEVER_RECEIVED, correlation, "recall")
                        : new Applied(locked.status(), false);
            }
            case CorridorRail.RecallAnswer.NothingSent nothing -> new Applied(locked.status(), false);
            case CorridorRail.RecallAnswer.Indeterminate unknown -> new Applied(locked.status(), false);
        };
    }

    private Applied recalled(Connection unitOfWork, OutboundCreditStore.Row locked, Correlation correlation, String resolver) {
        requireLanded(credits.recordRecallOutcome(unitOfWork, locked.id(), OutboundCreditStore.RecallOutcome.RECALLED));
        composition.recallAnswered(unitOfWork, locked.subject(), OutboundCreditStore.RecallOutcome.RECALLED);
        return fail(unitOfWork, locked, OutboundCreditStore.FailureReason.RECALLED, correlation, resolver);
    }

    private Applied receive(
            Connection unitOfWork,
            OutboundCreditStore.Row locked,
            Optional<ProviderReference> theirs,
            Correlation correlation,
            String resolver) {
        theirs.ifPresent(reference -> credits.recordProviderReference(unitOfWork, locked.id(), reference));
        if (locked.status() != OutboundCreditStore.Status.DISPATCHED && locked.status() != OutboundCreditStore.Status.UNKNOWN) {
            return new Applied(locked.status(), false);
        }
        requireLanded(credits.move(unitOfWork, locked.id(), locked.status(), OutboundCreditStore.Status.RECEIVED));
        // The hold STANDS: the provider has it, has not committed, and can no longer be "never received".
        record(unitOfWork, locked, OutboundCreditStore.Status.RECEIVED, Optional.empty(), correlation, resolver);
        return new Applied(OutboundCreditStore.Status.RECEIVED, true);
    }

    private Applied complete(
            Connection unitOfWork,
            OutboundCreditStore.Row locked,
            ProviderReference theirs,
            Optional<Instant> deliveredAt,
            Correlation correlation,
            String resolver) {
        Instant now = Instant.now(clock);
        // (1) The acting exit: the conditional edge on the locked row.
        requireLanded(credits.complete(unitOfWork, locked.id(), locked.status(), theirs));
        // (2) THE CLAIM (V026): the completion happened whatever it answers - the provider said so, by our reference -
        // so a claim standing elsewhere never refuses it; it is an integration break, recorded loud.
        SchemeExecutionClaim standing = claims.claim(unitOfWork, new SchemeExecutionClaim(
                locked.rail(), theirs, SchemeExecutionClaim.Subject.OUTBOUND_CREDIT, locked.id().value(), now));
        if (!standing.heldBy(SchemeExecutionClaim.Subject.OUTBOUND_CREDIT, locked.id().value())) {
            log.warn("Outbound credit {} completed under a provider reference already explained by {} {}; the credit"
                    + " stands, and the earlier record is an integration break (one execution, one money fact - V026)",
                    locked.id(), standing.subject(), standing.subjectId());
        }
        // (2b) The subject's rows - the payment, then the quote - in the global lock order, before the hold's wallet and
        // any projection row (the Phase 9 -> 10 transition: locking the quote only after posting deadlocked with the
        // cover applier, which holds the quote and then posts to the same FX position).
        composition.lockSubject(unitOfWork, locked.subject());
        // (3) The hold released - it must have been standing, or another writer moved this credit's money.
        HoldService.Release release = holds.release(unitOfWork, HoldId.of(locked.holdId()))
                .filter(HoldService.Release::released)
                .orElseThrow(() -> new PaymentsStorageException("a completing outbound credit's hold was not standing to release"));
        // (4) The entry: the clearing read off the rail's declaration, the corridor its counterparty.
        AccountPurpose clearingPurpose = rails.capabilitiesOf(locked.rail()).clearingPurpose()
                .orElseThrow(() -> new IllegalStateException("rail '" + locked.rail().value()
                        + "' carried an outbound credit but declares no clearing position"));
        LedgerAccount clearing = chart.resolve(unitOfWork, clearingPurpose, locked.rail().value(), locked.amount().currency());
        OutboundCreditComposition.Completion completion = new OutboundCreditComposition.Completion(locked.id(),
                locked.subject(), locked.rail(), release.hold().account(), clearing.id(), locked.held(), locked.amount(),
                locked.reference(), theirs, now);
        List<JournalLine> lines = composition.completionLines(unitOfWork, completion);
        requireDisclosed(lines, completion);
        String postingKey = POSTING_KEY_PREFIX + locked.id().value();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        PostingResult posted = postings.post(unitOfWork,
                new PostingCommand(postingKey, today, today, locked.id().value().toString(), lines));
        // (5)-(7) The trade booked onto the entry, the quote EXECUTED, the payment IN_TRANSIT.
        composition.completed(unitOfWork, completion, posted.entryId());
        // (8) THE EXPECTATION (ADR-0067): the clearing line's tracked counterpart, keyed by our reference and the
        // provider's, discharged by the corridor's own source.
        expectations.open(unitOfWork, new SettlementExpectations.Opening(
                SettlementExpectations.Kind.CROSSBORDER_PAYOUT,
                locked.id().value().toString(),
                postingKey,
                clearingPurpose,
                clearing.id(),
                posted.entryId(),
                Optional.empty(),
                List.of(new SettlementExpectations.Key(SettlementExpectations.ReferenceKind.END_TO_END_REF,
                                locked.reference().value()),
                        new SettlementExpectations.Key(SettlementExpectations.ReferenceKind.PAYOUT_PROVIDER_REF, theirs.value())),
                correlation,
                Optional.of(locked.rail().value())));
        record(unitOfWork, locked, OutboundCreditStore.Status.COMPLETED, Optional.empty(), correlation, resolver);
        if (deliveredAt.isPresent()) {
            deliver(unitOfWork, locked, deliveredAt.get(), correlation, resolver);
        }
        return new Applied(OutboundCreditStore.Status.COMPLETED, true);
    }

    /**
     * The inquiry channel's return (`P9-TSK-023`): applied on the credit re-read under its lock - the completion this
     * transaction may just have taken included. Whether this call applied it.
     */
    private boolean applyReturn(
            Connection unitOfWork, OutboundCreditId id, CorridorRail.ReturnFact fact, Correlation correlation) {
        OutboundCreditStore.Row current = credits.lock(unitOfWork, id)
                .orElseThrow(() -> new PaymentsStorageException("a locked outbound credit vanished"));
        OutboundCreditReturns.Outcome outcome = returns.apply(unitOfWork, current, new OutboundCreditReturns.Evidence(
                fact.amount(), Optional.of(fact.returnReference().value()), fact.returnedAt()), "inquiry", correlation);
        if (outcome == OutboundCreditReturns.Outcome.NOT_APPLICABLE) {
            log.info("Outbound credit {} was returned in a way that does not apply automatically; the report's line parks"
                    + " it for a person at grace", id);
        }
        return outcome == OutboundCreditReturns.Outcome.APPLIED;
    }

    private Applied deliver(
            Connection unitOfWork, OutboundCreditStore.Row locked, Instant deliveredAt, Correlation correlation, String resolver) {
        if (!credits.markDelivered(unitOfWork, locked.id(), deliveredAt)) {
            return new Applied(OutboundCreditStore.Status.COMPLETED, false);
        }
        composition.delivered(unitOfWork, locked.subject(), deliveredAt);
        auditDelivery(unitOfWork, locked, correlation, resolver);
        return new Applied(OutboundCreditStore.Status.COMPLETED, true);
    }

    private Applied fail(
            Connection unitOfWork,
            OutboundCreditStore.Row locked,
            OutboundCreditStore.FailureReason why,
            Correlation correlation,
            String resolver) {
        requireLanded(credits.fail(unitOfWork, locked.id(), locked.status(), why));
        holds.release(unitOfWork, HoldId.of(locked.holdId()))
                .filter(HoldService.Release::released)
                .orElseThrow(() -> new PaymentsStorageException("a failing outbound credit's hold was not standing to release"));
        composition.failed(unitOfWork, locked.subject(), why);
        record(unitOfWork, locked, OutboundCreditStore.Status.FAILED, Optional.of(why), correlation, resolver);
        return new Applied(OutboundCreditStore.Status.FAILED, true);
    }

    private Applied unknown(Connection unitOfWork, OutboundCreditStore.Row locked, Correlation correlation, String resolver) {
        if (locked.status() != OutboundCreditStore.Status.DISPATCHED) {
            // Already UNKNOWN, RECEIVED or concluded: another ambiguous answer teaches nothing new.
            return new Applied(locked.status(), false);
        }
        requireLanded(credits.move(unitOfWork, locked.id(), OutboundCreditStore.Status.DISPATCHED, OutboundCreditStore.Status.UNKNOWN));
        // The hold STANDS, and nothing is published: UNKNOWN is not terminal (INV-LIFE-03).
        record(unitOfWork, locked, OutboundCreditStore.Status.UNKNOWN, Optional.empty(), correlation, resolver);
        return new Applied(OutboundCreditStore.Status.UNKNOWN, true);
    }

    private static Applied contradicted(OutboundCreditStore.Row locked, String what) {
        if (locked.status() == OutboundCreditStore.Status.FAILED) {
            // A provider contradicting a concluded failure is caught by its settlement line (TERMINAL_STATE_CONTRADICTED,
            // parked, four-eyes): nothing is reopened here.
            log.warn("Outbound credit {} is FAILED but its provider answered {}; nothing is reopened - the settlement line"
                    + " will be parked for a person", locked.id(), what);
        }
        return new Applied(locked.status(), false);
    }

    /**
     * The disclosure-to-posting identity ({@code INV-XB-03}): the entry debits exactly the held total from the wallet
     * the hold rested on and credits exactly the instructed amount to the corridor's clearing - once each.
     */
    private static void requireDisclosed(List<JournalLine> lines, OutboundCreditComposition.Completion completion) {
        long debits = lines.stream().filter(line -> line.account().equals(completion.wallet())
                && line.direction() == Direction.DEBIT && line.amount().equals(completion.held())).count();
        long credits = lines.stream().filter(line -> line.account().equals(completion.clearing())
                && line.direction() == Direction.CREDIT && line.amount().equals(completion.instructed())).count();
        long touching = lines.stream().filter(line -> line.account().equals(completion.wallet())
                || line.account().equals(completion.clearing())).count();
        if (debits != 1 || credits != 1 || touching != 2) {
            throw new IllegalStateException("the completion entry does not post what was held and instructed: INV-XB-03"
                    + " is broken for outbound credit " + completion.credit());
        }
    }

    private static void requireLanded(boolean landed) {
        if (!landed) {
            // The caller locked the row, so a lost count is unreachable in this flow; refusing loudly beats guessing.
            throw new PaymentsStorageException("a locked outbound credit's conditional move found another writer's state");
        }
    }

    private void record(
            Connection unitOfWork,
            OutboundCreditStore.Row locked,
            OutboundCreditStore.Status after,
            Optional<OutboundCreditStore.FailureReason> failure,
            Correlation correlation,
            String resolver) {
        append(unitOfWork, locked, correlation, "credit=" + locked.id() + ", status=" + after
                + failure.map(reason -> ", failure=" + reason).orElse("") + ", resolver=" + resolver);
    }

    private void auditDelivery(Connection unitOfWork, OutboundCreditStore.Row locked, Correlation correlation, String resolver) {
        append(unitOfWork, locked, correlation, "credit=" + locked.id() + ", status=COMPLETED, delivered, resolver=" + resolver);
    }

    private void append(Connection unitOfWork, OutboundCreditStore.Row locked, Correlation correlation, String summary) {
        // The platform applies every outcome - an enumerated enterSystem() site at every caller: which resolver wins
        // the harmless race must not decide the actor.
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), SecurityContext.require(), Instant.now(clock),
                PaymentsAuditAction.OUTBOUND_CREDIT_OUTCOME_APPLIED, TARGET_TYPE, locked.id().value().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation.correlationId(), Optional.of(summary)));
    }
}
