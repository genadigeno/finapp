package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Applies a payout return from settlement evidence (`P8-TSK-019`, ADR-0073 §4): a merchant fact,
 * born once, beside a payout that stays {@code COMPLETED} (`INV-LIFE-04`). The platform acts —
 * every caller is an enumerated {@code enterSystem()} site — and nothing here allocates: the
 * matcher's rematch leg finds the return's expectation through the operation-anchored rule.
 *
 * <h2>In order, in the caller's one transaction</h2>
 *
 * <ol>
 *   <li>The payout row, {@code FOR UPDATE}, found by the provider's reference, then by ours —
 *       the row every applier of one payout's return serialises on.
 *   <li>The checks, each a typed outcome that writes NOTHING: the payout {@code COMPLETED}, no
 *       return standing, the amount and currency the payout's, and the merchant's payable
 *       {@code ACTIVE}, read {@code FOR SHARE} before any posting (ADR-0061 §5: share, never
 *       upgraded) — the rank that orders the return against a merchant close's {@code FOR
 *       UPDATE}.
 *   <li>The posting {@code merchant-payout-return:<payoutId>}: DR {@code PAYOUT_CLEARING} (the
 *       declared position, `INV-SET-05`) / CR the payable, dated from STORED evidence — the
 *       batch's {@code accepted_on} and the item's settlement date — so a retry on a later day
 *       converges on the key (ADR-0065 §6).
 *   <li>The return row, naming the entry (append-only, so it follows the posting).
 *   <li>The {@code PAYOUT_RETURN} expectation through the port, after the posting whose line it
 *       copies — no key of its own: the rule reaching it is operation-anchored.
 *   <li>{@code merchant.MerchantPayoutReturned} and the audit record, identifiers only.
 * </ol>
 *
 * <p>Lock order (`DISTRIBUTED_EXECUTION.md` §3): the caller's item row (shared) → the payout row
 * → the payable {@code FOR SHARE} → the projection rows, in the posting, last.
 */
@RequiredArgsConstructor
public final class PayoutReturns {

    /** The return's posting key prefix — ADR-0073 §2's own spelling. */
    public static final String POSTING_KEY_PREFIX = "merchant-payout-return:";

    static final String RETURNED_EVENT_TYPE = "merchant.MerchantPayoutReturned";

    @NonNull private final MerchantPayoutStore<Connection> payouts;
    @NonNull private final PayoutReturnStore<Connection> returns;
    @NonNull private final PostingService postings;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final PayoutSettlementExpectations expectations;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * What the evidence says, as the worker read it off the reconciliation item and its batch.
     *
     * @param externalItemRef the item whose evidence this is
     * @param providerReference the line's {@code PAYOUT_PROVIDER_REF}, when it carries one
     * @param ourReference the line's {@code OUR_REF} ({@code pyo-…}), when it carries one
     * @param returnedOn the stored {@code accepted_on} of the item's batch — the posting date
     * @param valueDate the item's settlement date
     */
    public record ReturnEvidence(
            UUID externalItemRef,
            Optional<String> providerReference,
            Optional<String> ourReference,
            Money amount,
            LocalDate returnedOn,
            LocalDate valueDate,
            Correlation correlation) {

        public ReturnEvidence {
            Objects.requireNonNull(externalItemRef, "externalItemRef must not be null");
            Objects.requireNonNull(providerReference, "providerReference must not be null");
            Objects.requireNonNull(ourReference, "ourReference must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(returnedOn, "returnedOn must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    /** What the application came to — every outcome but {@code APPLIED} wrote nothing. */
    public enum Outcome {
        /** The return was applied: posted, recorded, expected, announced, audited. */
        APPLIED,
        /** A return already stands for the payout: the converge. */
        ALREADY_RETURNED,
        /** No payout carries the evidence's references. */
        NO_PAYOUT,
        /** The payout is not {@code COMPLETED} — still in flight, or failed. */
        PAYOUT_NOT_COMPLETED,
        /** The amount or currency is not the payout's: not this fact (a person decides). */
        AMOUNT_DIFFERS,
        /** The merchant's payable is not {@code ACTIVE} — a merchant closed since, say. */
        PAYABLE_NOT_POSTABLE,
        /**
         * A person's transfer of the return's parked value stands, approved or proposed
         * (ADR-0073 §5's fallback): the return is that person's to attribute, once. *(Added
         * 2026-10-02 by the Phase 8 -> 9 transition, IDEM-1: the fallback left nothing this
         * applier checked, so a later report's repeat of the line credited the payable twice.)*
         */
        RETURNED_BY_PERSON
    }

    /**
     * Whether a person's fallback already attributes the payout's return (the Phase 8 -> 9
     * transition, IDEM-1; ADR-0073 §5) - asked under the payout's row lock, the row every
     * proposal and approval of that transfer also takes first, so the two can never both
     * credit the payable. Composed in {@code app} over reconciliation's read; merchant names no
     * sibling.
     */
    @FunctionalInterface
    public interface PersonAttribution {

        /** True when a person's transfer for the payout stands; the application then writes nothing. */
        boolean attributes(Connection unitOfWork, MerchantPayoutId payout);
    }

    /**
     * The payout the person-fallback's approval locks, whether its return stands, and its status
     * as read under that lock - a fallback transfer waits for {@code COMPLETED} (the Phase 8 -> 9
     * transition, IDEM-1's residual).
     */
    public record ReturnState(MerchantPayoutId payout, boolean returned, MerchantPayoutStatus status) {

        public ReturnState {
            Objects.requireNonNull(payout, "payout must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /** The outcome, the payout it concerned, and the posted entry when applied. */
    public record Applied(
            Outcome outcome, Optional<MerchantPayoutId> payout, Optional<JournalEntryId> entry) {

        public Applied {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(payout, "payout must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            if ((outcome == Outcome.APPLIED) != entry.isPresent()) {
                throw new IllegalArgumentException("an entry exists exactly when applied");
            }
        }

        static Applied notApplied(Outcome outcome, Optional<MerchantPayoutId> payout) {
            return new Applied(outcome, payout, Optional.empty());
        }
    }

    /**
     * Applies the evidence's return, or writes nothing and says why. {@code attribution} is
     * asked under the payout's row lock: a person's standing transfer of the return is
     * {@link Outcome#RETURNED_BY_PERSON} (the Phase 8 -> 9 transition, IDEM-1).
     */
    public Applied apply(
            Connection unitOfWork, ReturnEvidence evidence, PersonAttribution attribution) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(attribution, "attribution must not be null");

        // 1. The payout row - the serialisation point for every applier of its return, and for
        //    a person's fallback transfer of it.
        Optional<MerchantPayout> found =
                lockPayout(unitOfWork, evidence.providerReference(), evidence.ourReference());
        if (found.isEmpty()) {
            return Applied.notApplied(Outcome.NO_PAYOUT, Optional.empty());
        }
        MerchantPayout payout = found.get();
        Optional<MerchantPayoutId> named = Optional.of(payout.id());

        // 2. The checks - each writes nothing.
        if (payout.status() != MerchantPayoutStatus.COMPLETED) {
            return Applied.notApplied(Outcome.PAYOUT_NOT_COMPLETED, named);
        }
        if (returns.findByPayout(unitOfWork, payout.id()).isPresent()) {
            return Applied.notApplied(Outcome.ALREADY_RETURNED, named);
        }
        if (attribution.attributes(unitOfWork, payout.id())) {
            return Applied.notApplied(Outcome.RETURNED_BY_PERSON, named);
        }
        if (!payout.amount().equals(evidence.amount())) {
            return Applied.notApplied(Outcome.AMOUNT_DIFFERS, named);
        }
        Optional<LedgerAccount> owned =
                accounts.findOwned(
                        unitOfWork,
                        payout.merchantId().value(),
                        AccountPurpose.MERCHANT_PAYABLE,
                        payout.amount().currency());
        if (owned.isEmpty()) {
            return Applied.notApplied(Outcome.PAYABLE_NOT_POSTABLE, named);
        }
        // FOR SHARE before any posting: a close's FOR UPDATE waits for this transaction, or this
        // read waits for the close and finds the payable CLOSED (ADR-0073 section 7).
        Optional<LedgerAccount> payable = accounts.lockForShare(unitOfWork, owned.get().id());
        if (payable.isEmpty() || payable.get().status() != LedgerAccountStatus.ACTIVE) {
            return Applied.notApplied(Outcome.PAYABLE_NOT_POSTABLE, named);
        }

        // 3. The posting, dated from stored evidence - a later-day retry converges on the key.
        Instant now = Instant.now(clock);
        LedgerAccount clearing =
                chart.resolve(
                        unitOfWork,
                        PayoutSettlementDeclaration.CLEARING_PURPOSE,
                        payout.amount().currency());
        String postingKey = POSTING_KEY_PREFIX + payout.id().value();
        PostingResult posted =
                postings.post(
                        unitOfWork,
                        new PostingCommand(
                                postingKey,
                                evidence.returnedOn(),
                                evidence.valueDate(),
                                payout.id().value().toString(),
                                List.of(
                                        new JournalLine(
                                                clearing.id(), Direction.DEBIT, payout.amount()),
                                        new JournalLine(
                                                payable.get().id(),
                                                Direction.CREDIT,
                                                payout.amount()))));

        // 4. The fact, naming its entry.
        returns.insert(
                unitOfWork,
                new PayoutReturn(
                        ids.next(),
                        payout.id(),
                        payout.merchantId(),
                        payout.amount(),
                        evidence.externalItemRef(),
                        posted.entryId(),
                        evidence.returnedOn(),
                        evidence.valueDate(),
                        now));

        // 5. The expectation - no key of its own: the operation-anchored rule reaches it.
        expectations.open(
                unitOfWork,
                new PayoutSettlementExpectations.Opening(
                        PayoutSettlementExpectations.Kind.PAYOUT_RETURN,
                        payout.id().value().toString(),
                        postingKey,
                        PayoutSettlementDeclaration.CLEARING_PURPOSE,
                        clearing.id(),
                        posted.entryId(),
                        List.of(),
                        evidence.correlation()));

        // 6. The event and the audit record - identifiers only.
        announce(unitOfWork, payout, posted.entryId(), evidence.correlation(), now);
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        now,
                        MerchantAuditAction.PAYOUT_RETURN_APPLIED,
                        MerchantPayouts.TARGET_TYPE,
                        payout.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        evidence.correlation().correlationId(),
                        Optional.of(
                                "payout=" + payout.id()
                                        + ", merchant=" + payout.merchantId()
                                        + ", entry=" + posted.entryId()
                                        + ", item=" + evidence.externalItemRef())));
        return new Applied(Outcome.APPLIED, named, Optional.of(posted.entryId()));
    }

    /**
     * The payout the references name, locked {@code FOR UPDATE} - the very row
     * {@link #apply} takes first - and whether its return stands (the Phase 8 -> 9 transition,
     * IDEM-1): a person's fallback transfer of a parked return reads it at proposal and at
     * approval, so the transfer and the worker serialise on one row. Empty when no payout
     * carries the references.
     */
    public Optional<ReturnState> lockReturnState(
            Connection unitOfWork, Optional<String> providerReference, Optional<String> ourReference) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(ourReference, "ourReference must not be null");
        return lockPayout(unitOfWork, providerReference, ourReference)
                .map(payout -> new ReturnState(
                        payout.id(), returns.findByPayout(unitOfWork, payout.id()).isPresent(),
                        payout.status()));
    }

    /** The provider's reference first, then ours - one lookup order for every locker. */
    private Optional<MerchantPayout> lockPayout(
            Connection unitOfWork, Optional<String> providerReference, Optional<String> ourReference) {
        return providerReference
                .flatMap(reference -> payouts.lockByProviderReference(unitOfWork, reference))
                .or(() -> ourReference
                        .flatMap(reference -> payouts.lockByReference(unitOfWork, reference)));
    }

    private void announce(
            Connection unitOfWork,
            MerchantPayout payout,
            JournalEntryId entry,
            Correlation correlation,
            Instant now) {
        // Identifiers only - never the amount (INV-AUD-02).
        EventPayload payload =
                EventPayload.of()
                        .with("payoutId", payout.id().value().toString())
                        .with("merchantId", payout.merchantId().value().toString())
                        .with("journalEntryId", entry.value().toString());
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        RETURNED_EVENT_TYPE,
                        MerchantPayoutOutcomes.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        payout.id(),
                        MerchantPayoutOutcomes.AGGREGATE_TYPE,
                        now,
                        MerchantPayoutOutcomes.PRODUCER,
                        correlation.correlationId(),
                        MerchantPayoutOutcomes.causeOf(correlation)),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
