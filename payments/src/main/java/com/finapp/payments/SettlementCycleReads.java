package com.finapp.payments;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The scheme settlement cycles the platform's own records name (`P8-TSK-021`) — the cycle
 * report's pull worklist half, read through payments' public store by {@code app}, never by SQL
 * across schemas: a cycle a pay-in, a withdrawal or a parking announced is a report the scheme
 * owes us.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface SettlementCycleReads<T> {

    /** One cycle token, and the earliest record naming it. */
    record SeenCycle(String cycle, Instant firstSeen) {

        public SeenCycle {
            Objects.requireNonNull(cycle, "cycle must not be null");
            Objects.requireNonNull(firstSeen, "firstSeen must not be null");
        }
    }

    /**
     * One keyset page of the cycle tokens named by a record born at or after {@code since}, each
     * with its earliest such record, in token order: the tokens after {@code after} (every token
     * when empty), at most {@code limit}. A caller walks every page — a fixed first page would
     * starve every later cycle once its tokens are all received. Lock-free.
     */
    List<SeenCycle> cyclesSince(T unitOfWork, Instant since, Optional<String> after, int limit);
}
