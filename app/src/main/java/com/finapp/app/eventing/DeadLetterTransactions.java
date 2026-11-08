package com.finapp.app.eventing;

import java.sql.Connection;
import java.util.function.Function;

/** The dead-letter doors' unit of work (`P15-TSK-028`): a port, so a hermetic suite runs the desk over a fake. */
public interface DeadLetterTransactions {

    /** {@code work} in one transaction on one connection; a thrown refusal rolls it back whole. */
    <R> R inTransaction(Function<Connection, R> work);
}
