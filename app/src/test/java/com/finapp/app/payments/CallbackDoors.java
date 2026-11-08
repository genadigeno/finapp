package com.finapp.app.payments;

import com.finapp.app.telemetry.PaymentMeters;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.CallbackAppliers;
import com.finapp.payments.CallbackHintResolution;
import com.finapp.payments.CardCallbackInquiry;
import com.finapp.payments.DisputeNotice;
import com.finapp.payments.DisputeNotifications;
import com.finapp.payments.JdbcCallbackHintStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentClearing;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.PushInquiryAnswer;
import com.finapp.payments.RailId;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.RefundStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.UnmatchedConfirmations;
import com.finapp.payments.WebhookSignature;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalOutcomes;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The card door over a suite's own components, for the suites that build it by hand (`P15-TSK-036`). */
final class CallbackDoors {

    private CallbackDoors() {}

    @SuppressWarnings("java:S107") // Every collaborator of a real composition, deliberately not a builder.
    static PaymentWebhookService card(
            WebhookSignature signature,
            ProviderEvidenceStore<Connection> evidence,
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            RefundStore<Connection> refunds,
            PaymentMeters meters,
            PaymentOutcomes outcomes,
            PaymentClearing clearing,
            InboxConsumer<Connection> inbox,
            ObjectMapper json,
            Clock clock,
            TransactionTemplate template,
            DataSource dataSource,
            DisputeNotifications disputes,
            PaymentProvider provider,
            IdGenerator ids) {
        TransactionRunner runner = new TemplateRunner(template, dataSource);
        JdbcCallbackHintStore hints = new JdbcCallbackHintStore(ids);
        CardCallbackInquiry inquiry =
                new CardCallbackInquiry(
                        attempts, intents, refunds, evidence, provider, new CardAppliers(outcomes, clearing, disputes),
                        runner, clock);
        CallbackHintResolution resolution =
                new CallbackHintResolution(
                        hints,
                        List.of(inquiry),
                        new CallbackHintResolution.Config(
                                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofDays(7), 50),
                        ids,
                        runner);
        return new PaymentWebhookService(
                signature, evidence, attempts, refunds, meters, inbox, json, clock, runner, inquiry, hints, resolution,
                SimulatedCardPspAdapter.RAIL.id());
    }

    private record TemplateRunner(TransactionTemplate template, DataSource dataSource) implements TransactionRunner {

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return template.execute(status -> {
                Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                try {
                    return work.apply(unitOfWork);
                } finally {
                    DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                }
            });
        }
    }

    /** The card rail's half of the appliers; the push-rail half is unreachable from the card door. */
    private record CardAppliers(PaymentOutcomes outcomes, PaymentClearing clearing, DisputeNotifications disputes)
            implements CallbackAppliers {

        @Override
        public PaymentOutcomes.Applied applyAuthorization(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Money promised,
                Correlation correlation) {
            return outcomes.applyAuthorization(uow, intent, attempt, from, verdict, reference, promised, correlation);
        }

        @Override
        public PaymentOutcomes.Applied applyCapture(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, LedgerAccountId wallet, Money amount,
                Correlation correlation) {
            return outcomes.applyCapture(uow, intent, attempt, from, verdict, reference, wallet, amount, correlation);
        }

        @Override
        public PaymentOutcomes.Applied applyVoid(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Correlation correlation) {
            return outcomes.applyVoid(uow, intent, attempt, from, verdict, reference, correlation);
        }

        @Override
        public PaymentOutcomes.RefundApplied applyRefund(
                Connection uow, PaymentIntentId intent, Refund refund, RefundStatus from, ProviderAnswer.Verdict verdict,
                Optional<ProviderReference> reference, LedgerAccountId wallet, Correlation correlation) {
            return outcomes.applyRefund(uow, intent, refund, from, verdict, reference, wallet, correlation);
        }

        @Override
        public PaymentClearing.Outcome recordClearing(
                Connection uow, PaymentAttempt attempt, ProviderReference acquirer, ProviderReference network,
                Correlation correlation) {
            return clearing.record(uow, attempt, acquirer, network, correlation);
        }

        @Override
        public DisputeNotifications.Outcome applyDispute(
                Connection uow, PaymentAttempt attempt, DisputeNotice notice, Correlation correlation) {
            return disputes.apply(uow, attempt, notice, correlation);
        }

        @Override
        public PaymentOutcomes.Applied applyExecution(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                PushInquiryAnswer.Verdict verdict, Optional<ProviderReference> scheme, Optional<String> cycle,
                LedgerAccountId credit, Money amount, Optional<Money> executed, Correlation correlation) {
            throw new UnsupportedOperationException("the card door never applies a push execution");
        }

        @Override
        public WithdrawalOutcomes.Applied applyWithdrawal(
                Connection uow, Withdrawal locked, PushInquiryAnswer answer, Instant neverReceivedBound,
                Correlation correlation) {
            throw new UnsupportedOperationException("the card door never concludes a withdrawal");
        }

        @Override
        public boolean canPark(Connection uow, RailId rail, CurrencyCode currency) {
            throw new UnsupportedOperationException("the card door never parks");
        }

        @Override
        public UnmatchedConfirmations.Parked park(Connection uow, UnmatchedConfirmations.Parking parking) {
            throw new UnsupportedOperationException("the card door never parks");
        }
    }
}
