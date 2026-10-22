package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A monitored transaction's identity - one per ledger journal entry, born once (`P13-TSK-001`; the table is `P13-TSK-019`'s (`risk V004`)).
 */
public final class MonitoredTransactionId extends EntityId {

    private MonitoredTransactionId(UUID value) {
        super(value);
    }

    public static MonitoredTransactionId next(IdGenerator ids) {
        return new MonitoredTransactionId(ids.next());
    }

    public static MonitoredTransactionId of(UUID value) {
        return new MonitoredTransactionId(value);
    }
}
