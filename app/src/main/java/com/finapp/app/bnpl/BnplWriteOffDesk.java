package com.finapp.app.bnpl;

import com.finapp.app.api.DecimalText;
import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplReasons;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplWriteOff;
import com.finapp.bnpl.BnplWriteOffPosition;
import com.finapp.bnpl.BnplWriteOffs;
import com.finapp.bnpl.BnplWrittenOffRefused;
import com.finapp.bnpl.BnplRepaymentReversals;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryOverflowException;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of a BNPL agreement's write-off (`P14-TSK-021`; owner decision C6 (A)): the proposal, the approval, the rejection
 * and the approver's reads. Each act keyed per principal ({@code bnpl.servicing:<type>:<id>}, the servicing doors' one scope), one
 * transaction under the {@link IdempotentExecutor} claim, so a retried key replays the recorded receipt - a response lost after the
 * commit included - the same key with another body conflicts, and a refusal, which writes nothing, rolls the claim back with it. The
 * judgements, the lock order and the arbiters are the domain's and {@code bnpl V020}'s; nothing here decides them.
 *
 * <p>A self-dealing or self-approval attempt is recorded {@code FAILED} in a transaction of its own after the act rolled back.
 * {@code ledger.PeriodClosed} - today's month locked - passes through {@code ApiErrorHandler}: nothing posted, the proposal
 * standing, never moved to another date. Every view names figures and ids: only the approver's read names a reason.
 */
@RequiredArgsConstructor
public final class BnplWriteOffDesk {

    @NonNull private final BnplWriteOffs writeOffs;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplWriteOffMetrics metrics;

    /**
     * A write-off as answered, and replayed: the figures it takes by component, the principal part and the total; the revision read;
     * the default it stands on; once approved its entry and date. Never a reason or a person.
     */
    public record WriteOffReceipt(
            String writeOffId,
            String agreementId,
            String status,
            String currency,
            String principal,
            String principalDue,
            String interestAccrued,
            String interestDue,
            String feesDue,
            String principalTotal,
            String total,
            long revision,
            String defaultEventId,
            int agreementVersion,
            String proposedAt,
            String decidedAt,
            String journalEntryId,
            String valueDate) {}

    /** What the account holds now: the figures and the revision the approval re-judges under the lock. */
    public record WriteOffLiveView(
            String principal, String principalDue, String interestAccrued, String interestDue, String feesDue, long revision) {}

    /**
     * What an approver attests, under their own permission: the proposal, its proposer and reason, the account, the date its default
     * stands on, the date it would post on (posting and value), what the account holds now, and why it could not post now - empty
     * when it stands.
     */
    public record WriteOffAttestation(
            WriteOffReceipt writeOff,
            String product,
            String accountStatus,
            String proposedBy,
            String proposalReason,
            String defaultedOn,
            String postsOn,
            WriteOffLiveView live,
            boolean stands,
            List<String> blockers) {

        /** The reason and the figures stay out of a log line. */
        @Override
        public String toString() {
            return "WriteOffAttestation[" + writeOff.writeOffId() + ", stands=" + stands + "]";
        }
    }

    // ------------------------------------------------------------------ the acts

    /** Proposes it - a holder of {@code BNPL_WRITE_OFF_PROPOSE}; nothing is posted. */
    public WriteOffReceipt propose(String rawAgreementId, String idempotencyKey, String reason) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, BnplWriteOffMetrics.Outcome.PROPOSED,
                String.join("|", "propose", agreement.value().toString(), String.valueOf(reason)),
                uow -> {
                    BnplWriteOffs.Proposed proposed = writeOffs.propose(uow, agreement, reason, actor, correlation);
                    return new Acted(proposed.writeOff(), proposed.product(), false);
                });
    }

    /** Approves it - a holder of {@code BNPL_WRITE_OFF_APPROVE} other than its proposer, on a MULTI_FACTOR session; posts F11. */
    public WriteOffReceipt approve(String rawAgreementId, String rawWriteOffId, String idempotencyKey,
            BnplWriteOffController.BnplWriteOffApprovalRequest body) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID writeOff = id(rawWriteOffId);
        BnplWriteOffPosition attested = attested(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, BnplWriteOffMetrics.Outcome.APPROVED,
                approvalFingerprint(agreement.value(), writeOff, attested, body.reason()),
                uow -> decided(writeOffs.approve(uow, agreement, writeOff, attested, body.reason(), actor, correlation)));
    }

    /** Rejects it - a holder of {@code BNPL_WRITE_OFF_APPROVE}, on any account status. */
    public WriteOffReceipt reject(String rawAgreementId, String rawWriteOffId, String idempotencyKey, String reason) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID writeOff = id(rawWriteOffId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, BnplWriteOffMetrics.Outcome.REJECTED,
                String.join("|", "reject", agreement.value().toString(), writeOff.toString(), String.valueOf(reason)),
                uow -> decided(writeOffs.reject(uow, agreement, writeOff, reason, actor, correlation)));
    }

    // ------------------------------------------------------------------ the approver's reads

    /** One write-off as its approver reads it, under the obligation's lock - one state - its audit record committing with the read. */
    public WriteOffAttestation read(String rawAgreementId, String rawWriteOffId) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID writeOff = id(rawWriteOffId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        try {
            return transactions.inTransaction(unitOfWork -> attestation(
                    writeOffs.read(unitOfWork, agreement, writeOff, actor, correlation)));
        } catch (BnplRepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> writeOffs.recordRefusal(uow, selfDealing, actor, correlation))
                    .ifPresent(product -> metrics.record(product, BnplWriteOffMetrics.Outcome.SELF_DEALING_REFUSED));
            throw refused(BnplErrorCode.SELF_DEALING_REFUSED, selfDealing);
        } catch (BnplRepaymentReversals.NotFound unknown) {
            throw notFound();
        } catch (MonetaryOverflowException overflow) {
            throw unpostable();
        }
    }

    /** The oldest write-offs awaiting a decision, audited. */
    public List<WriteOffReceipt> pending() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        try {
            return transactions.inTransaction(unitOfWork -> writeOffs.pending(unitOfWork, actor, correlation).stream()
                    .map(BnplWriteOffDesk::receipt).toList());
        } catch (MonetaryOverflowException overflow) {
            throw unpostable();
        }
    }

    // ------------------------------------------------------------------ the views

    static WriteOffReceipt receipt(BnplWriteOff writeOff) {
        BnplWriteOffPosition position = writeOff.position();
        return new WriteOffReceipt(
                writeOff.id().toString(),
                writeOff.agreement().value().toString(),
                writeOff.status().name(),
                position.principal().currency().code(),
                decimal(position.principal()),
                decimal(position.principalDue()),
                decimal(position.interestAccrued()),
                decimal(position.interestDue()),
                decimal(position.feesDue()),
                decimal(position.principalTotal()),
                decimal(position.total()),
                position.revision(),
                writeOff.defaultEvent().toString(),
                writeOff.agreementVersion(),
                writeOff.proposedAt().toString(),
                writeOff.decidedAt().map(Instant::toString).orElse(null),
                writeOff.journalEntry().map(entry -> entry.value().toString()).orElse(null),
                writeOff.valueDate().map(LocalDate::toString).orElse(null));
    }

    static WriteOffAttestation attestation(BnplWriteOffs.Attestation read) {
        BnplWriteOffPosition live = read.live();
        return new WriteOffAttestation(
                receipt(read.writeOff()),
                read.product().name(),
                read.accountStatus().name(),
                read.writeOff().proposedBy(),
                read.writeOff().proposalReason(),
                read.defaultedOn().map(LocalDate::toString).orElse(null),
                read.postsOn().toString(),
                new WriteOffLiveView(decimal(live.principal()), decimal(live.principalDue()), decimal(live.interestAccrued()),
                        decimal(live.interestDue()), decimal(live.feesDue()), live.revision()),
                read.blockers().isEmpty(),
                read.blockers().stream().map(Enum::name).toList());
    }

    private static String decimal(Money money) {
        return money.toBigDecimal().toPlainString();
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction. The act's meter is told after the commit, once (a replay or a converged retry counts
     * nothing); a refusal is counted by reason and product.
     */
    private WriteOffReceipt keyed(Actor actor, CorrelationId correlation, String idempotencyKey, BnplWriteOffMetrics.Outcome success,
            String fingerprinted, Function<Connection, Acted> act) {
        IdempotencyKey key = new IdempotencyKey(BnplCorrectionDesk.SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<Acted> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                Acted done = act.apply(uow);
                acted.set(done);
                return CommandResult.succeeded(stored(receipt(done.writeOff())));
            }));
        } catch (BnplRepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> writeOffs.recordRefusal(uow, selfDealing, actor, correlation))
                    .ifPresent(product -> metrics.record(product, BnplWriteOffMetrics.Outcome.SELF_DEALING_REFUSED));
            throw refused(BnplErrorCode.SELF_DEALING_REFUSED, selfDealing);
        } catch (BnplRepaymentReversals.NotFound unknown) {
            throw notFound();
        } catch (BnplReasons.ReasonRequired reason) {
            throw refused(BnplErrorCode.REASON_REQUIRED, reason);
        } catch (BnplWriteOffs.SelfApprovalRefused self) {
            transactions.inTransaction(uow -> {
                writeOffs.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            metrics.record(self.product(), BnplWriteOffMetrics.Outcome.SELF_APPROVAL_REFUSED);
            throw refused(BnplErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (BnplWriteOffs.NotInServicing closed) {
            metrics.record(closed.product(), BnplWriteOffMetrics.Outcome.NOT_IN_SERVICING);
            throw refused(BnplErrorCode.OBLIGATION_CLOSED, closed);
        } catch (BnplWriteOffs.NotDefaulted notDefaulted) {
            metrics.record(notDefaulted.product(), BnplWriteOffMetrics.Outcome.NOT_DEFAULTED);
            throw refused(BnplErrorCode.OBLIGATION_NOT_DEFAULTED, notDefaulted);
        } catch (BnplWriteOffs.Stale stale) {
            metrics.record(stale.product(), BnplWriteOffMetrics.Outcome.STALE);
            throw refused(BnplErrorCode.WRITE_OFF_STALE, stale);
        } catch (BnplWriteOffs.DisputeOpen open) {
            metrics.record(open.product(), BnplWriteOffMetrics.Outcome.DISPUTE_OPEN);
            throw refused(BnplErrorCode.WRITE_OFF_DISPUTE_OPEN, open);
        } catch (BnplWriteOffs.CreditHeld held) {
            metrics.record(held.product(), BnplWriteOffMetrics.Outcome.CREDIT_HELD);
            throw refused(BnplErrorCode.WRITE_OFF_CREDIT_HELD, held);
        } catch (BnplWriteOffs.BehindAccrual behind) {
            metrics.record(behind.product(), BnplWriteOffMetrics.Outcome.BEHIND_ACCRUAL);
            throw refused(BnplErrorCode.WRITE_OFF_BEHIND_ACCRUAL, behind);
        } catch (BnplWriteOffs.ProposalPending pending) {
            metrics.record(pending.product(), BnplWriteOffMetrics.Outcome.PROPOSAL_PENDING);
            throw refused(BnplErrorCode.PROPOSAL_PENDING, pending);
        } catch (BnplWriteOffs.ProposalNotPending decided) {
            metrics.record(decided.product(), BnplWriteOffMetrics.Outcome.PROPOSAL_NOT_PENDING);
            throw refused(BnplErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (MonetaryOverflowException overflow) {
            throw unpostable();
        } catch (BnplWrittenOffRefused | AllocationAnomaly passesThrough) {
            throw passesThrough;
        }
        if (outcome.executed() && acted.get() != null && !acted.get().replayed()) {
            metrics.record(acted.get().product(), success);
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a write-off act stores its receipt")));
    }

    /** What an act did: the write-off it left, its product, and whether the same person's retry converged on their own decision. */
    private record Acted(BnplWriteOff writeOff, BnplProduct product, boolean replayed) {}

    private static Acted decided(BnplWriteOffs.Decided decided) {
        return new Acted(decided.writeOff(), decided.product(), decided.replayed());
    }

    /** What the key is held to: every figure named, the revision, the scale, the currency, the reason - any other body is a conflict. */
    static String approvalFingerprint(UUID account, UUID writeOff, BnplWriteOffPosition attested, String reason) {
        return String.join("|", "approve", account.toString(), writeOff.toString(),
                Long.toString(attested.principal().minorUnits()), Long.toString(attested.principalDue().minorUnits()),
                Long.toString(attested.interestAccrued().minorUnits()), Long.toString(attested.interestDue().minorUnits()),
                Long.toString(attested.feesDue().minorUnits()), Integer.toString(attested.principal().scale()),
                attested.principal().currency().code(), Long.toString(attested.revision()),
                String.valueOf(reason));
    }

    /** The receipt: the view's fields, '|'-separated, an absent one empty. */
    static StoredResponse stored(WriteOffReceipt view) {
        String body = String.join("|", view.writeOffId(), view.agreementId(), view.status(), view.currency(), view.principal(),
                view.principalDue(), view.interestAccrued(), view.interestDue(), view.feesDue(), view.principalTotal(), view.total(),
                Long.toString(view.revision()), view.defaultEventId(), Integer.toString(view.agreementVersion()), view.proposedAt(),
                nullable(view.decidedAt()), nullable(view.journalEntryId()), nullable(view.valueDate()));
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static WriteOffReceipt replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new WriteOffReceipt(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8],
                fields[9], fields[10], Long.parseLong(fields[11]), fields[12], Integer.parseInt(fields[13]), fields[14],
                fields[15].isEmpty() ? null : fields[15], fields[16].isEmpty() ? null : fields[16],
                fields[17].isEmpty() ? null : fields[17]);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    /** What the approver read, exact at the currency's minor units; the agreement's currency and scale are the domain's to compare. */
    static BnplWriteOffPosition attested(BnplWriteOffController.BnplWriteOffApprovalRequest body) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(body.currency());
        } catch (RuntimeException unknown) {
            throw invalid("the currency is not one this platform supports.");
        }
        try {
            // The shape first: an exponent never reaches Money.of's rescale; an amount past what the platform can hold is malformed.
            return new BnplWriteOffPosition(money(body.principal(), currency), money(body.principalDue(), currency),
                    money(body.interestAccrued(), currency), money(body.interestDue(), currency), money(body.feesDue(), currency),
                    body.revision());
        } catch (RuntimeException malformed) {
            throw invalid("each figure must be an exact decimal at the currency's minor units, never negative, and the revision a count.");
        }
    }

    private static Money money(String raw, CurrencyCode currency) {
        return Money.of(DecimalText.parse(raw), currency);
    }

    private static ApiException refused(BnplErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A write-off was refused", cause.getMessage() + ".");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The write-off was not valid", detail);
    }

    private static ApiException unpostable() {
        return new ApiException(BnplErrorCode.WRITE_OFF_UNPOSTABLE, "A write-off was refused",
                "the account's figures cannot be totalled; nothing was done.");
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL agreement or write-off matches the request", "no such record.");
    }

    /** An agreement id is a UUIDv7 (ADR-0013): any other identifier names no agreement - the uniform 404, never a 500. */
    private static BnplAgreementId agreementId(String raw) {
        try {
            return BnplAgreementId.of(id(raw));
        } catch (IllegalArgumentException notABnplAgreementId) {
            throw notFound();
        }
    }

    private static UUID id(String raw) {
        if (raw == null) {
            throw notFound();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a write-off command runs inside a correlation scope"))
                .correlationId();
    }
}
