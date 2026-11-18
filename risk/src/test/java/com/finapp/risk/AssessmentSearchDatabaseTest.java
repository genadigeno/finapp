package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The assessment search on PostgreSQL (`P17-TSK-020`, gap G-91): the plans {@code V022}'s index and {@code V007}'s party index
 * give the window, a party's assessments and a continued page - an index range newest first, at a volume where a scan would
 * cost - recorded in {@code OPERATIONS_RUNBOOK.md} section 11.48; the keyset walk serving each assessment once; the reader's own
 * party withheld by the read and refused when named; and the page's record.
 *
 * <p>Every row here is written in one transaction that is rolled back, so the shared database keeps none: the sealed assessment
 * is append-only, and its deferred inputs check would refuse these heads at commit. The stamping trigger stamps each with the
 * database's clock, as it stamps every assessment.
 */
@Tag("database")
@DisplayName("the assessment search on PostgreSQL (P17-TSK-020)")
class AssessmentSearchDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Actor READER = new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    private static final int VOLUME = 60_000;
    private static final int PARTIES = 500;

    private final JdbcRiskCollections collections = new JdbcRiskCollections();

    @Test
    @DisplayName("a window is one index range newest first with no sort; a party's rides its own index; plans printed for the"
            + " runbook")
    void theIndexesServeTheSearch() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                statement.executeUpdate(seed("p17-020-volume", VOLUME, PARTIES));
                statement.execute("ANALYZE risk.risk_assessment");
                String party = party(7);
                String window = plan(statement, "SELECT id FROM risk.risk_assessment WHERE assessed_at >= now() - interval"
                        + " '1 day' AND assessed_at < now() + interval '1 day' AND party_id <> '" + party(1)
                        + "' ORDER BY assessed_at DESC, id DESC LIMIT 51");
                String byParty = plan(statement, "SELECT id FROM risk.risk_assessment WHERE assessed_at >= now() - interval"
                        + " '30 days' AND assessed_at < now() + interval '1 day' AND party_id <> '" + party(1)
                        + "' AND party_id = '" + party + "' ORDER BY assessed_at DESC, id DESC LIMIT 51");
                String continued = plan(statement, "SELECT id FROM risk.risk_assessment WHERE assessed_at >= now() - interval"
                        + " '1 day' AND assessed_at < now() + interval '1 day' AND party_id <> '" + party(1) + "' AND"
                        + " (assessed_at, id) < (now(), '00000000-0000-0000-0000-000000000000'::uuid)"
                        + " ORDER BY assessed_at DESC, id DESC LIMIT 51");
                System.out.println("P17-TSK-020 plan, window:\n" + window + "\nP17-TSK-020 plan, party:\n" + byParty
                        + "\nP17-TSK-020 plan, continued:\n" + continued);

                assertThat(window).contains("risk_assessment_by_time").contains("Backward").doesNotContain("Seq Scan");
                assertThat(sorts(window)).as("no sort of the window").isZero();
                assertThat(continued).contains("risk_assessment_by_time").doesNotContain("Seq Scan");
                assertThat(sorts(continued)).as("no sort of a continued page").isZero();
                assertThat(byParty).contains("risk_assessment_by_party").doesNotContain("Seq Scan");
                assertThat(sorts(byParty)).as("a party's rows are read in the stamp's order; at most the id orders a tie")
                        .isZero();
            } finally {
                root.rollback();
            }
        }
    }

    @Test
    @DisplayName("a keyset walk serves each assessment of the window once, newest first, the id breaking the tie; the reader's"
            + " own party withheld, and the filters narrow")
    void aWalkServesEachOnceAndWithholdsTheReadersParty() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                Instant before = databaseNow(statement);
                String marker = "p17-020-walk-" + UUID.randomUUID().toString().substring(0, 8);
                statement.executeUpdate(seed(marker, 30, 3));
                Instant after = databaseNow(statement).plusSeconds(1);
                UUID own = UUID.fromString(party(1));
                AssessmentSearch search = search(own);
                RiskCollections.AssessmentFilter all = filter(before, after, Optional.empty(), Optional.empty());

                List<RiskCollections.AssessmentHead> walked = walk(root, search, all, 4);

                assertThat(walked).as("parties 0 and 2 of three, ten each; party 1 is the reader's").hasSize(20);
                assertThat(walked).extracting(RiskCollections.AssessmentHead::partyId).doesNotContain(own);
                assertThat(new HashSet<>(walked.stream().map(RiskCollections.AssessmentHead::id).toList())).hasSize(20);
                // PostgreSQL orders uuid by its bytes, the lowercase hex text's order - not UUID.compareTo's signed longs.
                assertThat(walked).isSortedAccordingTo(Comparator.comparing(RiskCollections.AssessmentHead::assessedAt)
                        .thenComparing((RiskCollections.AssessmentHead head) -> head.id().value().toString()).reversed());
                assertThat(walk(root, search, filter(before, after, Optional.of(UUID.fromString(party(2))),
                        Optional.of(RiskDecision.DENY)), 50))
                        .as("party 2's DENY: n = 2, 5, ..., 29 with n % 10 == 0 - one, n = 20")
                        .singleElement().satisfies(head -> assertThat(head.decision()).isEqualTo(RiskDecision.DENY));
                assertThat(walk(root, search, new RiskCollections.AssessmentFilter(before, after, Optional.empty(),
                        Optional.of(RiskOperation.WITHDRAWAL), Optional.empty()), 50)).as("an operation none has").isEmpty();
            } finally {
                root.rollback();
            }
        }
    }

    @Test
    @DisplayName("a search naming the reader's own party, or by a reader the platform cannot place, is refused before anything"
            + " is read; a page served is recorded risk.AssessmentsSearched with its filter")
    void theRefusalsAndTheRecord() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try {
                Instant now = Instant.now();
                UUID own = UUID.randomUUID();
                AssessmentSearch search = search(own);
                RiskCollections.AssessmentFilter mine = filter(now.minusSeconds(60), now, Optional.of(own), Optional.empty());
                assertThatThrownBy(() -> search.search(root, mine, Optional.empty(), 10, READER))
                        .isInstanceOfSatisfying(RiskAssessments.SelfDealingRefused.class,
                                refused -> assertThat(refused.refusal()).isEqualTo(
                                        RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY));
                AssessmentSearch unplaced = new AssessmentSearch(collections, (uow, actor) -> Optional.empty(),
                        new JdbcAuditWriter(), IDS, Clock.systemUTC());
                RiskCollections.AssessmentFilter any = filter(now.minusSeconds(60), now, Optional.empty(), Optional.empty());
                assertThatThrownBy(() -> unplaced.search(root, any, Optional.empty(), 10, READER))
                        .isInstanceOfSatisfying(RiskAssessments.SelfDealingRefused.class,
                                refused -> assertThat(refused.refusal()).isEqualTo(
                                        RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE));

                CorrelationId flow = CorrelationId.generate(IDS);
                search.record(root, filter(now.minusSeconds(60), now, Optional.empty(), Optional.of(RiskDecision.DENY)),
                        true, 3, READER, flow);
                try (PreparedStatement read = root.prepareStatement("SELECT target_type || '|' || target_id || '|' || outcome"
                        + " || '|' || change_summary FROM platform.audit_record WHERE operation = 'risk.AssessmentsSearched'"
                        + " AND correlation_id = ?")) {
                    read.setString(1, flow.value());
                    try (ResultSet row = read.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).startsWith("risk_assessment_search|all|SUCCEEDED|from=")
                                .endsWith(", decision=DENY, continued=true, served=3");
                    }
                }
            } finally {
                root.rollback();
            }
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private AssessmentSearch search(UUID own) {
        return new AssessmentSearch(collections, (uow, actor) -> Optional.of(own), new JdbcAuditWriter(), IDS,
                Clock.systemUTC());
    }

    private static RiskCollections.AssessmentFilter filter(Instant from, Instant to, Optional<UUID> party,
            Optional<RiskDecision> decision) {
        return new RiskCollections.AssessmentFilter(from, to, party, Optional.empty(), decision);
    }

    private static List<RiskCollections.AssessmentHead> walk(Connection unitOfWork, AssessmentSearch search,
            RiskCollections.AssessmentFilter filter, int size) {
        List<RiskCollections.AssessmentHead> walked = new ArrayList<>();
        Set<RiskAssessmentId> seen = new HashSet<>();
        Optional<RiskCollections.Cursor> after = Optional.empty();
        while (true) {
            List<RiskCollections.AssessmentHead> page = search.search(unitOfWork, filter, after, size, READER);
            assertThat(page.size()).isLessThanOrEqualTo(size);
            if (page.isEmpty()) {
                return walked;
            }
            for (RiskCollections.AssessmentHead head : page) {
                assertThat(seen.add(head.id())).as("served once").isTrue();
            }
            walked.addAll(page);
            RiskCollections.AssessmentHead tail = page.getLast();
            after = Optional.of(new RiskCollections.Cursor(tail.assessedAt(), tail.id().value()));
        }
    }

    /**
     * {@code count} transfer heads tagged {@code marker}, spread over {@code parties} parties ({@link #party}), every tenth
     * denied: unpinned fail-safe heads (no policy active), which need no version row. Stamped by the table's trigger.
     */
    private static String seed(String marker, int count, int parties) {
        return "INSERT INTO risk.risk_assessment (id, operation, command_ref, party_id, amount_minor, currency, scale, engine,"
                + " score, decision, fail_safe_reason, unavailable_cause, velocity_outcome, canonical_format, inputs_sha256,"
                + " verdict_sha256, latency_micros, correlation_id, causation_id, assessed_at)"
                + " SELECT uuidv7(), 'TRANSFER', '" + marker + "-' || n,"
                + " ('00000000-0000-4000-8000-' || lpad(to_hex(n % " + parties + "), 12, '0'))::uuid, 100, 'EUR', 2,"
                + " 'RISK_ENGINE_V1', 0, CASE WHEN n % 10 = 0 THEN 'DENY' ELSE 'ALLOW' END, 'NO_POLICY_ACTIVE', 'NO_POLICY',"
                + " CASE WHEN n % 10 = 0 THEN 'NOT_PERMITTED' ELSE 'UNCOUNTED' END, 1, repeat('a', 64), repeat('b', 64), 10,"
                + " '" + marker + "', 'p17-020', now()"
                + " FROM generate_series(1, " + count + ") AS n";
    }

    private static String party(int n) {
        return "00000000-0000-4000-8000-" + String.format("%012x", n);
    }

    private static Instant databaseNow(Statement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery("SELECT clock_timestamp()")) {
            row.next();
            return row.getTimestamp(1).toInstant().minus(Duration.ofMillis(1));
        }
    }

    private static String plan(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
            StringBuilder plan = new StringBuilder();
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
            return plan.toString();
        }
    }

    /** The plan's sort nodes that sort a whole input - an incremental sort over a tie the index already ordered is not one. */
    private static long sorts(String plan) {
        return plan.lines().filter(line -> line.contains("Sort") && !line.contains("Incremental Sort")
                && !line.contains("Sort Key") && !line.contains("Presorted Key")).count();
    }
}
