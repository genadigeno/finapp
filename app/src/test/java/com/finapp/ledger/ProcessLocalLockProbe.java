package com.finapp.ledger;

import java.util.concurrent.locks.ReentrantLock;

/** A deliberate violation of {@code nothingUsesAProcessLocalLock}. */
@SuppressWarnings("unused")
public final class ProcessLocalLockProbe {

    private final ReentrantLock lock = new ReentrantLock();

    public void post() {
        lock.lock();
        try {
            // A lock the other instances cannot see.
        } finally {
            lock.unlock();
        }
    }
}
