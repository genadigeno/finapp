package com.finapp.platform.testing;

import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.InboxParking;
import java.sql.Connection;

/** Parking for suites that are not about it (`P15-TSK-028`). */
public final class InboxParkingDoubles {

    /** Nothing stands, and every failure is retried: what a receiver did before there was a parked state. */
    public static final InboxParking NEVER_PARKS = new InboxParking() {
        @Override
        public Standing standing(Connection unitOfWork, InboxKey key) {
            return Standing.NONE;
        }

        @Override
        public void handled(Connection unitOfWork, InboxKey key) {}

        @Override
        public Verdict recordFailure(Connection unitOfWork, Failure failure, String failureClass) {
            return Verdict.RETRY;
        }
    };

    private InboxParkingDoubles() {}
}
