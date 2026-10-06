package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
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
import com.finapp.sharedkernel.money.Money;
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

/**
 * The one applier of a cross-border credit's return (`P9-TSK-023`, PHASE_9_PLAN.md sections 12.4(i) and 12.9.3, the
 * lifecycle document section 4): the corridor provider's inquiry answer and the corridor report's
 * {@code PAYOUT_RETURNED} line, read by the return worker, converge here on the credit the caller locked.
 *
 * <p><strong>The applicability rule</strong> ({@code INV-XB-04}): a return is applied only when the credit is
 * {@code COMPLETED}, the return is in the instructed currency for exactly the instructed amount, and the customer is
 * {@code ACTIVE}. While the credit is {@code DISPATCHED}, {@code UNKNOWN} or {@code RECEIVED} the caller defers -
 * nothing is written, and the inquiry that completes the credit applies the return after the completion. Any other
 * return writes nothing: the grace leg parks it for a person. Nothing is ever re-converted, and the spread stands.
 *
 * <p>Applied, in the caller's transaction (T-f): the entry {@code crossborder-return:<creditId>} - the clearing to
 * the customer's wallet in the returned currency, opened if absent, and the fee refunded in the source currency -
 * the return fact ({@code UNIQUE (outbound_credit_id)} the arbiter), the payment {@code RETURNED}, the operation-
 * anchored {@code CROSSBORDER_RETURN} expectation.
 */
@RequiredArgsConstructor
public final class OutboundCreditReturns {

    /** The applied return's entry key prefix (PHASE_9_PLAN.md section 12.6's catalogue). */
    public static final String POSTING_KEY_PREFIX = "crossborder-return:";

    @NonNull private final OutboundCreditReturnStore returns;
    @NonNull private final PostingService postings;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final PaymentRails rails;
    @NonNull private final SettlementExpectations expectations;
    @NonNull private final OutboundCreditComposition<Connection> composition;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What a return's evidence came to. */
    public enum Outcome {
        /** Applied by this call: the entry, the fact, the payment, the expectation. */
        APPLIED,
        /** The credit is in flight: nothing written - the inquiry completing it applies the return. */
        DEFERRED,
        /** Not the instructed credit, or its customer not active: nothing written - a person decides at grace. */
        NOT_APPLICABLE,
        /** The credit failed: a return of it contradicts the failure - the lookup parks it at once. */
        CONTRADICTED,
        /** The credit's return already exists: a converging duplicate, nothing written. */
        ALREADY_RETURNED
    }

    /** One channel's evidence of a return. */
    public record Evidence(Money amount, Optional<String> returnReference, Instant returnedAt) {
        public Evidence {
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(returnReference, "returnReference must not be null");
            Objects.requireNonNull(returnedAt, "returnedAt must not be null");
        }
    }

    /** Applies {@code evidence} to {@code locked} - the credit the caller holds {@code FOR UPDATE}. */
    public Outcome apply(
            Connection unitOfWork, OutboundCreditStore.Row locked, Evidence evidence, String channel, Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        switch (locked.status()) {
            case DISPATCHED, UNKNOWN, RECEIVED -> {
                return Outcome.DEFERRED;
            }
            case FAILED -> {
                return Outcome.CONTRADICTED;
            }
            case COMPLETED -> {
                // judged below
            }
        }
        if (returns.findByCredit(unitOfWork, locked.id()).isPresent()) {
            return Outcome.ALREADY_RETURNED;
        }
        if (!evidence.amount().equals(locked.amount())) {
            // A partial return, or one in another currency, never posts without a person (INV-XB-04).
            return Outcome.NOT_APPLICABLE;
        }
        AccountPurpose clearingPurpose = rails.capabilitiesOf(locked.rail()).clearingPurpose()
                .orElseThrow(() -> new IllegalStateException("a corridor rail declares its clearing"));
        LedgerAccount clearing = chart.resolve(unitOfWork, clearingPurpose, locked.rail().value(), locked.amount().currency());
        Optional<List<JournalLine>> lines = composition.returnLines(unitOfWork, new OutboundCreditComposition.ReturnApplication(
                locked.id(), locked.subject(), locked.customerParty(), clearing.id(), evidence.amount()));
        if (lines.isEmpty()) {
            return Outcome.NOT_APPLICABLE;
        }
        String postingKey = POSTING_KEY_PREFIX + locked.id().value();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        PostingResult posted = postings.post(unitOfWork,
                new PostingCommand(postingKey, today, today, locked.id().value().toString(), lines.get()));
        if (!returns.insert(unitOfWork, new OutboundCreditReturnStore.Return(ids.next(), locked.id(), evidence.amount(),
                evidence.returnReference(), OutboundCreditReturnStore.AppliedBy.APPLIER, Optional.empty(),
                Optional.of(posted.entryId().value()), evidence.returnedAt()))) {
            // The credit's row lock serialises every applier: a lost insert is another writer outside this order.
            throw new PaymentsStorageException("a locked credit's return was recorded by another writer");
        }
        composition.returned(unitOfWork, locked.subject(), "APPLIED", Instant.now(clock));
        expectations.open(unitOfWork, new SettlementExpectations.Opening(
                SettlementExpectations.Kind.CROSSBORDER_RETURN,
                locked.id().value().toString(),
                postingKey,
                clearingPurpose,
                clearing.id(),
                posted.entryId(),
                Optional.empty(),
                // Operation-anchored: the report's return line reaches it through the payout's own keys.
                List.of(),
                correlation,
                Optional.of(locked.rail().value())));
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), SecurityContext.require(), Instant.now(clock),
                PaymentsAuditAction.OUTBOUND_CREDIT_RETURN_APPLIED, OutboundCreditOutcomes.TARGET_TYPE,
                locked.id().value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation.correlationId(),
                Optional.of("credit=" + locked.id() + ", applied_by=APPLIER, channel=" + channel)));
        return Outcome.APPLIED;
    }
}
