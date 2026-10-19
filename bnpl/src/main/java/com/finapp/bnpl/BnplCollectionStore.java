package com.finapp.bnpl;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for the automatic collection attempts (`P12-TSK-019`, {@code bnpl V011}; lending's
 * {@code RepaymentStore.attemptedOn}/{@code insertAttempt}): every read is of an obligation the caller holds under B4 - the
 * servicing sweep's claim - never a request value; every instant the database's.
 */
public interface BnplCollectionStore {

    /**
     * The latest attempt date of each instalment of {@code obligation} a collection was ever attempted on - read under the
     * obligation's row lock (B4), so today's attempts and the retry cadence are judged on what every earlier step committed.
     */
    Map<UUID, LocalDate> latestAttempts(Connection unitOfWork, BnplObligationId obligation);

    /** Births one collection attempt ({@code bnpl V011}); the database stamps its instant and holds its ranks. */
    void insertAttempt(Connection unitOfWork, BnplCollectionAttempt attempt);
}
