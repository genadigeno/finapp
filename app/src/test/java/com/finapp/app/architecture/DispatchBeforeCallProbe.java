package com.finapp.app.architecture;

import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderAnswer;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0046's own shape, which {@code NoTransactionSpansAProviderCallTest} (`X-TSK-020`) must let pass - and one value it must say
 * it cannot follow.
 *
 * <p>{@link #confirm} commits the dispatch, calls the provider holding nothing, and applies the answer in a second transaction: the
 * guard must see the call (the method reaches the network) and both bodies held, and find no violation. {@link #runsAValueFromAMap}
 * runs, while handed the connection's transaction, a function taken out of a map - a value the guard cannot trace to where it was
 * made, so it must list it rather than pass it. Never wired and never run.
 */
@SuppressWarnings("unused")
final class DispatchBeforeCallProbe {

    Object confirm(TransactionTemplate transactions, PaymentProvider provider) {
        transactions.executeWithoutResult(status -> recordTheDispatch(status));
        ProviderAnswer answer = provider.authorize(null);
        return transactions.execute(status -> applyTheOutcome(status, answer));
    }

    private static void recordTheDispatch(TransactionStatus status) {}

    private static Object applyTheOutcome(TransactionStatus status, ProviderAnswer answer) {
        return answer;
    }

    Object runsAValueFromAMap(TransactionStatus status, Map<String, Supplier<Object>> work) {
        return work.get("any").get();
    }
}
