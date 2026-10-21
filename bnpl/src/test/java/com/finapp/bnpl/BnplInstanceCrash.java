package com.finapp.bnpl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.Function;

/**
 * An instance that dies after its act wrote everything - its facts inserted, its entries posted - and before its commit
 * (the Phase 12 exit review's crash-and-re-drive finding): the act runs in one transaction on the moved database clock,
 * what it wrote is read back INSIDE that transaction (so the crash point is proven to be after the fact and its posting),
 * then the instance dies and the transaction rolls back - as a killed JVM's would. Another instance then re-drives.
 */
final class BnplInstanceCrash extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What the dying transaction had written, read on its own unit of work just before it died. */
    final transient Object seen;

    private BnplInstanceCrash(Object seen) {
        super("the instance died after its act, before its commit");
        this.seen = seen;
    }

    /**
     * {@code act} on {@code frame}'s clock in one application-role transaction, then {@code witness} on the same unit of
     * work, then the crash - rolled back. Answers what the witness saw.
     */
    static <W> W crashAfter(Duration frame, Function<Connection, ?> act, Function<Connection, W> witness) {
        try {
            BnplClockFrame.inOneTransaction(frame, uow -> {
                act.apply(uow);
                throw new BnplInstanceCrash(witness.apply(uow));
            });
        } catch (BnplInstanceCrash crashed) {
            @SuppressWarnings("unchecked")
            W seen = (W) crashed.seen;
            return seen;
        }
        throw new AssertionError("the act returned without the crash");
    }

    /** {@code sql}'s count on {@code uow} - the dying transaction's own view. */
    static long countOn(Connection uow, String sql) {
        try (Statement statement = uow.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
