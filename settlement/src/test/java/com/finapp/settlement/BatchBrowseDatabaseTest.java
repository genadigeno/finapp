package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The batch browse's read (`P17-TSK-107`): the batches of a window of business dates, of one source when named, newest business date
 * first and the id breaking the tie, walked page by page from a keyset cursor, each batch once. On PostgreSQL, so V021's indexes and
 * the row comparison are the ones the door reads with. Every seed is raw, in a 1987 window no other suite writes, and rolled back.
 */
@Tag("database")
@DisplayName("the settlement batch browse (P17-TSK-107)")
class BatchBrowseDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PSP = "simulated-psp.settlement";
    private static final String PAYOUT = "simulated-payout.settlement";
    private static final LocalDate FIRST = LocalDate.parse("1987-03-01");

    private final JdbcSettlementBatchStore batches = new JdbcSettlementBatchStore(IDS);
    private Connection application;

    @BeforeEach
    void open() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
    }

    @AfterEach
    void rollBack() throws SQLException {
        application.rollback();
        application.close();
    }

    @Test
    @DisplayName("newest business date first, the id breaking the tie; a keyset walk serves each batch of the window once")
    void theWindowIsWalkedInItsDeclaredOrder() throws SQLException {
        List<UUID> seeded = new ArrayList<>();
        for (int day = 0; day < 4; day++) {
            for (int perDay = 0; perDay < 2; perDay++) {
                seeded.add(batch(PSP, "SIM_PSP_CSV", FIRST.plusDays(day)));
            }
        }
        seeded.add(batch(PAYOUT, "SIM_PAYOUT_CSV", FIRST.plusDays(1)));
        UUID outside = batch(PSP, "SIM_PSP_CSV", FIRST.plusDays(10));

        List<SettlementBatchStore.BatchRow> walked = walk(Optional.empty(), FIRST, FIRST.plusDays(3), 3);

        assertThat(walked).extracting(SettlementBatchStore.BatchRow::id).containsExactlyInAnyOrderElementsOf(seeded)
                .doesNotContain(outside);
        assertThat(walked).as("the declared total order across every page")
                .isSortedAccordingTo(Comparator.comparing(SettlementBatchStore.BatchRow::businessDate).reversed()
                        .thenComparing(Comparator.comparing(SettlementBatchStore.BatchRow::id).reversed()));
        assertThat(walked.getFirst().businessDate()).isEqualTo(FIRST.plusDays(3));
        assertThat(walked.getLast().businessDate()).as("the window's bounds are inclusive").isEqualTo(FIRST);
    }

    @Test
    @DisplayName("a named source is browsed alone")
    void aSourceIsBrowsedAlone() throws SQLException {
        UUID psp = batch(PSP, "SIM_PSP_CSV", FIRST);
        UUID payout = batch(PAYOUT, "SIM_PAYOUT_CSV", FIRST);
        UUID payoutSource = batches.browse(application, Optional.empty(), FIRST, FIRST, Optional.empty(), 10).stream()
                .filter(row -> row.id().equals(payout)).findFirst().orElseThrow().sourceId();

        List<SettlementBatchStore.BatchRow> one = walk(Optional.of(payoutSource), FIRST, FIRST, 10);

        assertThat(one).extracting(SettlementBatchStore.BatchRow::id).containsExactly(payout).doesNotContain(psp);
        assertThat(one.getFirst().sourceCode()).isEqualTo(PAYOUT);
    }

    /** The whole window, page by page, each page resuming after the last row of the one before. */
    private List<SettlementBatchStore.BatchRow> walk(Optional<UUID> source, LocalDate from, LocalDate to, int pageSize) {
        List<SettlementBatchStore.BatchRow> walked = new ArrayList<>();
        Optional<SettlementBatchStore.BrowseCursor> after = Optional.empty();
        HashSet<UUID> seen = new HashSet<>();
        while (true) {
            List<SettlementBatchStore.BatchRow> page = batches.browse(application, source, from, to, after, pageSize);
            assertThat(page.size()).isLessThanOrEqualTo(pageSize);
            if (page.isEmpty()) {
                return walked;
            }
            for (SettlementBatchStore.BatchRow row : page) {
                assertThat(seen.add(row.id())).as("served once: %s", row.id()).isTrue();
            }
            walked.addAll(page);
            SettlementBatchStore.BatchRow tail = page.getLast();
            after = Optional.of(new SettlementBatchStore.BrowseCursor(tail.businessDate(), tail.id()));
        }
    }

    /** A RECEIVED file and its PARSED batch, raw as the application role (SettlementV010MigrationTest's shape). */
    private UUID batch(String source, String format, LocalDate businessDate) throws SQLException {
        UUID fileId = IDS.next();
        byte[] sha = new byte[32];
        ThreadLocalRandom.current().nextBytes(sha);
        try (PreparedStatement insert = application.prepareStatement(
                "INSERT INTO settlement.file (id, source_id, received_via, status, format_id, format_version, content_sha256,"
                        + " content_length, line_count, key_version, received_by, received_at, status_changed_at, correlation_id)"
                        + " SELECT ?, id, 'UPLOAD', 'RECEIVED', ?, 1, ?, 1, 1, 1, 'op-p17-107', now(), now(), 'p17-tsk-107'"
                        + " FROM settlement.source WHERE code = ?")) {
            insert.setObject(1, fileId);
            insert.setString(2, format);
            insert.setBytes(3, sha);
            insert.setString(4, source);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        UUID batchId = IDS.next();
        try (PreparedStatement insert = application.prepareStatement(
                "INSERT INTO settlement.batch (id, file_id, source_id, external_batch_ref, currency, status, business_date,"
                        + " format_id, format_version, line_count, declared_line_count, net_minor, net_scale, remittance_reference,"
                        + " created_at, status_changed_at, correlation_id)"
                        + " SELECT ?, ?, id, ?, 'EUR', 'PARSED', ?, ?, 1, 0, 0, 0, 2, 'PSP-REM-0001', now(), now(), 'p17-tsk-107'"
                        + " FROM settlement.source WHERE code = ?")) {
            insert.setObject(1, batchId);
            insert.setObject(2, fileId);
            insert.setString(3, "PSPB-P17-" + UUID.randomUUID().toString().substring(0, 18));
            insert.setObject(4, businessDate);
            insert.setString(5, format);
            insert.setString(6, source);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return batchId;
    }
}
