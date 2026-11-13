package com.finapp.app.shedding;

import java.time.Duration;

/**
 * The writer pool as the shedding door reads it (`P16-TSK-016`, ADR-0137): how many threads are waiting for one of its connections
 * now, how many connections it holds, and how long a borrow waits before it fails. Read from the live pool, never copied from the
 * configuration, so the door and the pool cannot disagree about either number.
 */
public interface PoolQueue {

    /** Threads waiting for a connection at this instant; zero while the pool has not started, since nothing can be waiting on it. */
    int waiting();

    /** The pool's connections: {@code spring.datasource.hikari.maximum-pool-size}, as the pool was built. */
    int size();

    /** How long a borrow waits before it fails: {@code spring.datasource.hikari.connection-timeout}, as the pool was built. */
    Duration borrowWait();
}
