package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.JdbcMerchantPayoutStore;
import com.finapp.platform.persistence.SweepBackoff;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.JdbcMatchingStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The payout sweep's and the rematch leg's failure records on PostgreSQL (`P16-TSK-017`; written, owed to PI-023's pass under
 * C2): ten instances recording one row's failure at once count one, the grants admit the application's statement, and the
 * wait is the database's base. The records name no row of another table (no foreign key), so a random id stands in for the
 * payout and the residual; the superuser removes the records at the end.
 */
@Tag("database")
@DisplayName("ten instances recording one payout's or one residual's failure at once count one, on PostgreSQL (P16-TSK-017)")
class SweepFailureRecordsDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @FunctionalInterface
    private interface Recorder {
        boolean record(Connection app, UUID row) throws Exception;
    }

    private static long tenAtOnce(UUID row, Recorder recorder) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> recorders = new ArrayList<>();
        try {
            for (int instance = 0; instance < 10; instance++) {
                recorders.add(pool.submit(() -> {
                    try (Connection app = DatabaseRoles.application()) {
                        start.await();
                        return recorder.record(app, row);
                    }
                }));
            }
            start.countDown();
            long counted = 0;
            for (Future<Boolean> one : recorders) {
                counted += one.get(120, TimeUnit.SECONDS) ? 1 : 0;
            }
            return counted;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Long> countAndWait(String sql, UUID row) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, row);
            try (ResultSet found = read.executeQuery()) {
                assertThat(found.next()).isTrue();
                return List.of(found.getLong(1), found.getLong(2));
            }
        }
    }

    private static void remove(String sql, UUID row) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); PreparedStatement delete = root.prepareStatement(sql)) {
            delete.setObject(1, row);
            delete.executeUpdate();
        }
    }

    @Test
    @DisplayName("a payout failed by ten instances at once is counted once, its wait the base")
    void tenPayoutRecordersCountOne() throws Exception {
        UUID payout = IDS.next();
        try {
            JdbcMerchantPayoutStore payouts = new JdbcMerchantPayoutStore();
            assertThat(tenAtOnce(payout, (app, row) -> payouts.recordSweepFailure(app, row, "IllegalStateException")))
                    .isEqualTo(1);
            assertThat(countAndWait("SELECT failures, round(EXTRACT(EPOCH FROM retry_at - last_failed_at))::bigint"
                    + " FROM merchant.sweep_row_failure WHERE leg = 'PAYOUT' AND row_id = ?", payout))
                    .containsExactly(1L, SweepBackoff.BASE.toSeconds());
        } finally {
            remove("DELETE FROM merchant.sweep_row_failure WHERE row_id = ?", payout);
        }
    }

    @Test
    @DisplayName("a residual's fault recorded by ten writers at once is counted once, its wait the base")
    void tenRematchRecordersCountOne() throws Exception {
        UUID item = IDS.next();
        try {
            JdbcMatchingStore store = new JdbcMatchingStore();
            assertThat(tenAtOnce(item, (app, row) -> store.recordRematchFault(app, row, "IllegalStateException")))
                    .isEqualTo(1);
            assertThat(countAndWait("SELECT faults, round(EXTRACT(EPOCH FROM retry_at - last_faulted_at))::bigint"
                    + " FROM reconciliation.rematch_fault WHERE external_item_id = ?", item))
                    .containsExactly(1L, SweepBackoff.BASE.toSeconds());
        } finally {
            remove("DELETE FROM reconciliation.rematch_fault WHERE external_item_id = ?", item);
        }
    }
}
