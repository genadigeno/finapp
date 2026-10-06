package com.finapp.app.crossborder;

import com.finapp.crossborder.PaymentProgress;
import com.finapp.fx.ConversionParticipants;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.OutboundCreditOutcomes;
import com.finapp.payments.OutboundCreditReturnStore;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.PaymentsAuditAction;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.reconciliation.ResolvedCorridorReturns;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * reconciliation's {@link ResolvedCorridorReturns} over payments and crossborder (`P9-TSK-023`, the lifecycle document
 * section 4, T-g): a parked cross-border return's credit, found by the line's end-to-end reference and locked; at the
 * proposal judged, at the four-eyes approval recorded as the person's return - the return fact
 * ({@code applied_by = RESOLUTION}), the fee refunded {@code crossborder-return-fee:<creditId>} from
 * {@code FEE_REVENUE} to the customer's source wallet, the payment {@code RETURNED} with basis {@code RESOLVED} - in the
 * approval's transaction. The principal is the resolution's own transfer, the only credit in the returned currency.
 * The transfer goes to the credit's own customer's wallet in the parked currency, and the resolution never opens
 * accounts; a closed customer's value stays parked.
 */
@RequiredArgsConstructor
public final class CorridorReturnResolutions implements ResolvedCorridorReturns {

    public static final String FEE_REFUND_KEY_PREFIX = "crossborder-return-fee:";

    @NonNull private final OutboundCreditStore credits;
    @NonNull private final OutboundCreditReturnStore returns;
    @NonNull private final PaymentProgress progress;
    @NonNull private final ConversionParticipants participants;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final PostingService postings;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final io.micrometer.core.instrument.MeterRegistry meters;
    @NonNull private final com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics;

    private record Judged(Judgement judgement, Optional<OutboundCreditStore.Row> credit, Optional<UUID> customer) {}

    @Override
    public Judgement judge(Connection unitOfWork, ParkedReturn parked) {
        return judged(unitOfWork, parked).judgement();
    }

    @Override
    public Judgement record(
            Connection unitOfWork, ParkedReturn parked, UUID resolutionId, Actor approver, CorrelationId correlation) {
        Judged judged = judged(unitOfWork, parked);
        if (judged.judgement() != Judgement.RETURNABLE) {
            return judged.judgement();
        }
        OutboundCreditStore.Row credit = judged.credit().orElseThrow();
        PaymentProgress.Locked payment = progress.lock(unitOfWork, credit.subject());
        Money fee = payment.offer().fee();
        Optional<UUID> entry = Optional.empty();
        if (fee.isPositive()) {
            Optional<LedgerAccountId> sourceWallet = participants.wallet(unitOfWork, judged.customer().orElseThrow(),
                    fee.currency());
            if (sourceWallet.isEmpty()) {
                return Judgement.NOT_THE_CUSTOMERS_WALLET;
            }
            String key = FEE_REFUND_KEY_PREFIX + credit.id().value();
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            PostingResult posted = postings.post(unitOfWork, new PostingCommand(key, today, today,
                    credit.id().value().toString(), List.of(
                            new JournalLine(chart.resolve(unitOfWork, AccountPurpose.FEE_REVENUE, fee.currency()).id(),
                                    Direction.DEBIT, fee),
                            new JournalLine(sourceWallet.get(), Direction.CREDIT, fee))));
            entry = Optional.of(posted.entryId().value());
        }
        if (!returns.insert(unitOfWork, new OutboundCreditReturnStore.Return(ids.next(), credit.id(), parked.amount(),
                Optional.empty(), OutboundCreditReturnStore.AppliedBy.RESOLUTION, Optional.of(resolutionId), entry,
                Instant.now(clock)))) {
            // The credit's row lock serialises every recorder: a lost insert is a writer outside this order.
            return Judgement.ALREADY_RETURNED;
        }
        progress.returned(unitOfWork, credit.subject(), "RESOLVED", Instant.now(clock), correlation);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), approver, Instant.now(clock),
                PaymentsAuditAction.OUTBOUND_CREDIT_RETURN_APPLIED, OutboundCreditOutcomes.TARGET_TYPE,
                credit.id().value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("credit=" + credit.id() + ", applied_by=RESOLUTION, resolution=" + resolutionId)));
        crossBorderMetrics.returned(payment.payment().corridor().code(), "resolved");
        crossBorderMetrics.payment(payment.payment().corridor().code(), "returned");
        return Judgement.RECORDED;
    }

    private Judged judged(Connection unitOfWork, ParkedReturn parked) {
        Optional<OutboundCreditStore.Row> found = parked.endToEndReference().flatMap(reference -> {
            try {
                return credits.byReference(unitOfWork, new EndToEndReference(reference));
            } catch (IllegalArgumentException notOurs) {
                return Optional.empty();
            }
        });
        if (found.isEmpty()) {
            return new Judged(Judgement.NOT_A_CORRIDOR_RETURN, Optional.empty(), Optional.empty());
        }
        // The credit FOR UPDATE: the inquiry and the report worker serialise on the same row.
        OutboundCreditStore.Row credit = credits.lock(unitOfWork, found.get().id())
                .orElseThrow(() -> new IllegalStateException("a found outbound credit vanished"));
        switch (credit.status()) {
            case FAILED -> {
                return new Judged(Judgement.FAILED, Optional.of(credit), Optional.empty());
            }
            case DISPATCHED, UNKNOWN, RECEIVED -> {
                return new Judged(Judgement.NOT_COMPLETED, Optional.of(credit), Optional.empty());
            }
            case COMPLETED -> {
                // judged below
            }
        }
        if (returns.findByCredit(unitOfWork, credit.id()).isPresent()) {
            return new Judged(Judgement.ALREADY_RETURNED, Optional.of(credit), Optional.empty());
        }
        Optional<UUID> customer = participants.activeCustomer(unitOfWork, credit.customerParty());
        Optional<LedgerAccountId> wallet = customer.flatMap(id -> participants.wallet(unitOfWork, id,
                parked.amount().currency()));
        if (wallet.isEmpty() || !wallet.get().value().equals(parked.targetAccountId())) {
            return new Judged(Judgement.NOT_THE_CUSTOMERS_WALLET, Optional.of(credit), customer);
        }
        return new Judged(Judgement.RETURNABLE, Optional.of(credit), customer);
    }
}
