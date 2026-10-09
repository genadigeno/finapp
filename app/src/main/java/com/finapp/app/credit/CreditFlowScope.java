package com.finapp.app.credit;

import com.finapp.credit.CreditDataRequestId;
import com.finapp.credit.CreditDataRequestStore;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.DecisionRequestStore;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Links a credit request's later legs to its submission (`P10-TSK-020`; PHASE_10_PLAN.md section 15: "spans for
 * submission, collection, freeze, evaluation and decision, linked by the request's correlation").
 *
 * <p>A sweeper on any instance takes a request's next step under a correlation of its own - the one its audit rows and
 * events carry. Around that step this enters the scope the platform's span processor reads: the correlation the REQUEST
 * stored at submission (frozen by V010's trigger), with the step's own correlation as the cause. So every span the step
 * records - the freeze, the evaluation, the decision, a collection's pull - carries the submission's correlation, which
 * finds the whole flow from the one value a customer holds, and the step's, which finds the step's own records. Nothing
 * the step writes changes: the scope is read by spans and logs only.
 *
 * <p>The lookup is one read without a lock; a lookup that fails leaves the step to run unlinked, never to fail - a span is
 * telemetry, never the work's precondition.
 */
@Slf4j
public final class CreditFlowScope {

    /** No lookup: every step runs unlinked - the suites' scope. */
    public static final CreditFlowScope NONE = new CreditFlowScope(null, null, null);

    private final TransactionRunner transactions;
    private final DecisionRequestStore requests;
    private final CreditDataRequestStore dataRequests;

    public CreditFlowScope(
            TransactionRunner transactions, DecisionRequestStore requests, CreditDataRequestStore dataRequests) {
        this.transactions = transactions;
        this.requests = requests;
        this.dataRequests = dataRequests;
    }

    /** {@code work} inside the scope of decision request {@code id}'s stored correlation, caused by {@code step}. */
    public <T> T forRequest(DecisionRequestId id, CorrelationId step, Supplier<T> work) {
        return within(lookup(() -> transactions.inTransaction(uow -> requests.correlationOf(uow, id))), step, work);
    }

    /** {@code work} inside the scope of the decision request data request {@code id} serves, caused by {@code step}. */
    public <T> T forDataRequest(CreditDataRequestId id, CorrelationId step, Supplier<T> work) {
        return within(lookup(() -> transactions.inTransaction(uow -> dataRequests.find(uow, id)
                .flatMap(row -> requests.correlationOf(uow, DecisionRequestId.of(row.decisionRequestId()))))),
                step, work);
    }

    /** {@code work} inside {@code request}'s correlation with {@code step} as its cause; unscoped when there is none. */
    @SuppressWarnings("try") // The scope is used for its close side effect.
    static <T> T within(Optional<String> request, CorrelationId step, Supplier<T> work) {
        Objects.requireNonNull(step, "step");
        Optional<Correlation> linked;
        try {
            linked = request.map(stored -> new Correlation(CorrelationId.of(stored), CausationId.of(step.value())));
        } catch (IllegalArgumentException unusable) {
            linked = Optional.empty();
        }
        if (linked.isEmpty()) {
            return work.get();
        }
        try (CorrelationContext.Scope scope = CorrelationContext.enter(linked.get())) {
            return work.get();
        }
    }

    private Optional<String> lookup(Supplier<Optional<String>> read) {
        if (transactions == null) {
            return Optional.empty();
        }
        try {
            return read.get();
        } catch (RuntimeException unreadable) {
            log.warn("A credit request's correlation could not be read; the step runs unlinked: {}",
                    unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
