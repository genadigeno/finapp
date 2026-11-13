package com.finapp.platform.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What platform {@code V015} holds, read from the migration itself (`P16-TSK-010`). {@code AuditArchivalDatabaseTest} runs it on
 * PostgreSQL at the database pass (owed); a short container check ran it once on PostgreSQL 18.6 (recorded on the task's entry).
 * Comments are stripped before anything is read, so a phrase in a comment proves nothing here.
 */
@DisplayName("the audit archive's migration: every column carried, nothing deletable, the mark after the check (P16-TSK-010)")
class AuditArchiveMigrationTest {

    private static final String DIRECTORY = "platform/src/main/resources/db/migration/platform";

    private static final String MIGRATION = DIRECTORY + "/V015__audit_archival.sql";

    private static final String AUDIT_TABLE = DIRECTORY + "/V009__create_audit_record.sql";

    private static final String RAW = read(RepositoryPaths.locate(MIGRATION));

    private static final String SQL = flat(withoutComments(RAW));

    private static final Map<String, String> TYPES = Map.of("UUID", "uuid", "TEXT", "text", "TIMESTAMPTZ", "timestamp with time zone");

    @Test
    @DisplayName("the review names every column of platform.audit_record, its type and its place, as V009 and every later migration left them")
    void theReviewIsTheTablesRealColumns() {
        Matcher review = Pattern.compile("IS DISTINCT FROM '([^']*)' '([^']*)' THEN").matcher(SQL);
        assertThat(review.find()).as("the review compares the table's columns with one literal").isTrue();
        String reviewed = review.group(1) + review.group(2);

        assertThat(reviewed).as("a column added to platform.audit_record is a line that no longer carries every column: review it and"
                + " replace format 1").isEqualTo(String.join(",", columnsOfTheAuditTable()));
    }

    @Test
    @DisplayName("format 1's line and its decoder carry every column, in the table's order")
    void theLineAndItsDecoderCarryEveryColumn() {
        List<String> columns = columnsOfTheAuditTable().stream().map(column -> column.substring(0, column.indexOf(':'))).toList();

        String line = body("audit_archive_line");
        List<String> encoded = new ArrayList<>();
        Matcher field = Pattern.compile("\\br\\.([a-z_]+)").matcher(line.substring(line.indexOf("json_build_array(")));
        while (field.find()) {
            encoded.add(field.group(1));
        }
        assertThat(encoded).as("the line's fields, in order").containsExactlyElementsOf(columns);

        String decoder = body("audit_archive_record");
        List<String> decoded = new ArrayList<>();
        Matcher assigned = Pattern.compile("\\br\\.([a-z_]+) := \\(?j ->> (\\d+)").matcher(decoder);
        while (assigned.find()) {
            assertThat(Integer.parseInt(assigned.group(2))).as("%s read from its own place", assigned.group(1)).isEqualTo(decoded.size());
            decoded.add(assigned.group(1));
        }
        assertThat(decoded).as("the decoder's assignments, in order").containsExactlyElementsOf(columns);
        assertThat(decoder).contains("json_array_length(j) <> " + columns.size());
        assertThat(line).as("occurred_at in UTC to the microsecond").contains("r.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'");
    }

    @Test
    @DisplayName("nothing here grants a write: the application role reads the three tables and executes six functions, and PUBLIC none")
    void nothingIsGrantedButReadsAndTheFunctions() {
        assertThat(SQL).doesNotContainPattern("GRANT [^;]*\\b(INSERT|UPDATE|DELETE|TRUNCATE|ALL)\\b[^;]* ON (TABLE )?platform\\.");
        assertThat(SQL).doesNotContainPattern("(?i)\\bDELETE\\s+FROM\\s+platform\\.audit_record\\b");
        assertThat(SQL).doesNotContainPattern("(?i)\\bUPDATE\\s+platform\\.audit_record\\b");
        for (String table : List.of("audit_archive_segment", "audit_archive_member", "audit_archive_pending")) {
            assertThat(SQL).contains("GRANT SELECT ON platform." + table + " TO finapp_app;");
        }
        List<String> executable = List.of("audit_archive_line(platform.audit_record)", "audit_archive_record(text)",
                "audit_archive_content(bigint)", "audit_archive_manifest(bigint)", "claim_audit_archive_segment(bigint, integer)",
                "complete_audit_archive_segment(bigint, uuid, text, text)");
        for (String function : executable) {
            assertThat(SQL).contains("REVOKE ALL ON FUNCTION platform." + function + " FROM PUBLIC;")
                    .contains("GRANT EXECUTE ON FUNCTION platform." + function + " TO finapp_app;");
        }
        for (String function : List.of("audit_archive_reviewed()", "enqueue_audit_record_for_archive()")) {
            assertThat(SQL).contains("REVOKE ALL ON FUNCTION platform." + function + " FROM PUBLIC;")
                    .doesNotContain("GRANT EXECUTE ON FUNCTION platform." + function);
        }
    }

    @Test
    @DisplayName("the claim, the mark and the queue's trigger run as the owner with the search_path pinned; every function pins it")
    void theDefinersPinTheirPath() {
        for (String function : List.of("claim_audit_archive_segment", "complete_audit_archive_segment", "enqueue_audit_record_for_archive")) {
            assertThat(header(function)).as(function).contains("SECURITY DEFINER").contains("SET search_path = pg_catalog, pg_temp");
        }
        for (String function : List.of("audit_archive_reviewed", "audit_archive_line", "audit_archive_record", "audit_archive_content",
                "audit_archive_manifest")) {
            assertThat(header(function)).as(function).doesNotContain("SECURITY DEFINER").contains("SET search_path = pg_catalog, pg_temp");
        }
    }

    @Test
    @DisplayName("every audit row is queued: a row trigger after insert, and the earlier rows queued once the trigger holds the table")
    void everyRowIsQueued() {
        String trigger = "CREATE TRIGGER audit_record_queued_for_archive AFTER INSERT ON platform.audit_record FOR EACH ROW"
                + " EXECUTE FUNCTION platform.enqueue_audit_record_for_archive();";
        assertThat(SQL).contains(trigger);
        assertThat(body("enqueue_audit_record_for_archive")).contains("INSERT INTO platform.audit_archive_pending (audit_id, enqueued_at) VALUES (NEW.audit_id, now());");
        int backfill = SQL.indexOf("INSERT INTO platform.audit_archive_pending (audit_id, enqueued_at) SELECT a.audit_id, now() FROM platform.audit_record a");
        assertThat(backfill).as("the earlier rows are queued").isPositive();
        assertThat(backfill).as("after the trigger exists, so no row commits between the two").isGreaterThan(SQL.indexOf(trigger));
        assertThat(RAW.lines().findFirst().orElseThrow())
                .as("the one contract it waives, the trigger on an old table")
                .startsWith("-- finapp-contract: first-conforming-release=ANY; waives=TRIGGER; reason=");
    }

    @Test
    @DisplayName("the claim: one claimer by the head row SKIP LOCKED, a live lease left alone, a lapsed one taken over, a missing row refused")
    void theClaimIsOneAtATime() {
        String claim = body("claim_audit_archive_segment");
        int review = claim.indexOf("PERFORM platform.audit_archive_reviewed();");
        int head = claim.indexOf("FROM platform.audit_archive_head h WHERE h.head_id = 1 FOR UPDATE SKIP LOCKED;");
        int live = claim.indexOf("IF v_lease > now() THEN RETURN; END IF;");
        int takeOver = claim.indexOf("reclaims = seg.reclaims + 1");
        int queue = claim.indexOf("FROM platform.audit_archive_pending p ORDER BY p.enqueued_at, p.audit_id LIMIT batch_size FOR UPDATE");
        int missing = claim.indexOf("USING ERRCODE = 'data_corrupted'");
        int cut = claim.indexOf("INSERT INTO platform.audit_archive_segment");
        assertThat(List.of(review, head, live, takeOver, queue, missing, cut)).allSatisfy(at -> assertThat(at).isPositive());
        assertThat(review).isLessThan(head);
        assertThat(head).isLessThan(live);
        assertThat(live).isLessThan(takeOver);
        assertThat(takeOver).isLessThan(queue);
        assertThat(queue).isLessThan(missing);
        assertThat(missing).as("nothing is cut over a gap").isLessThan(cut);
        assertThat(claim).contains("lease_ms NOT BETWEEN 1000 AND 3600000")
                .contains("batch_size NOT BETWEEN 1 AND " + AuditArchival.MAX_BATCH_SIZE)
                .contains("DELETE FROM platform.audit_archive_pending p WHERE p.audit_id = ANY (v_ids);")
                .contains("INSERT INTO platform.audit_archive_member (audit_id, segment_seq) SELECT u.id, v_seq FROM unnest(v_ids) AS u(id);");
        assertThat(SQL).contains("CREATE UNIQUE INDEX audit_archive_segment_one_claimed ON platform.audit_archive_segment (state) WHERE state = 'CLAIMED';")
                .contains("CONSTRAINT audit_archive_member_pk PRIMARY KEY (audit_id)");
    }

    @Test
    @DisplayName("the mark: the holder checked, then the digests recomputed from the rows, and only then ARCHIVED")
    void theMarkIsAfterTheCheck() {
        String complete = body("complete_audit_archive_segment");
        int locked = complete.indexOf("WHERE seg.segment_seq = p_segment_seq FOR UPDATE;");
        int held = complete.indexOf("USING ERRCODE = 'AAHLD'");
        int digests = complete.indexOf("USING ERRCODE = 'AADGS'");
        int marked = complete.indexOf("SET state = 'ARCHIVED'");
        assertThat(List.of(locked, held, digests, marked)).allSatisfy(at -> assertThat(at).isPositive());
        assertThat(locked).isLessThan(held);
        assertThat(held).isLessThan(digests);
        assertThat(digests).isLessThan(marked);
        assertThat(complete.substring(held, digests))
                .contains("encode(sha256(convert_to(platform.audit_archive_content(p_segment_seq), 'UTF8')), 'hex')")
                .contains("encode(sha256(convert_to(platform.audit_archive_manifest(p_segment_seq), 'UTF8')), 'hex')");
        assertThat(AuditArchiveDatabaseException.NOT_HELD).isEqualTo("AAHLD");
        assertThat(AuditArchiveDatabaseException.DIGEST_REFUSED).isEqualTo("AADGS");
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** {@code name:type} of every column of platform.audit_record, in order: V009's table and every column a later file adds. */
    private static List<String> columnsOfTheAuditTable() {
        String created = withoutComments(read(RepositoryPaths.locate(AUDIT_TABLE)));
        String table = created.substring(created.indexOf("CREATE TABLE platform.audit_record"), created.indexOf("CONSTRAINT audit_record_pk"));
        List<String> columns = new ArrayList<>();
        Matcher column = Pattern.compile("(?m)^\\s+([a-z_]+)\\s+(UUID|TEXT|TIMESTAMPTZ)\\b").matcher(table);
        while (column.find()) {
            columns.add(column.group(1) + ":" + TYPES.get(column.group(2)));
        }
        assertThat(columns).as("V009's columns were read").hasSizeGreaterThanOrEqualTo(11);
        Pattern added = Pattern.compile("(?i)ALTER TABLE platform\\.audit_record[^;]*ADD COLUMN\\s+([a-z_]+)\\s+([a-z]+)");
        try (Stream<Path> files = Files.list(RepositoryPaths.locate(AUDIT_TABLE).getParent())) {
            files.filter(file -> file.getFileName().toString().endsWith(".sql")).sorted().forEach(file -> {
                Matcher addition = added.matcher(flat(withoutComments(read(file))));
                while (addition.find()) {
                    columns.add(addition.group(1) + ":" + TYPES.getOrDefault(addition.group(2).toUpperCase(Locale.ROOT), addition.group(2)));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return columns;
    }

    private static String header(String function) {
        int start = SQL.indexOf("CREATE FUNCTION platform." + function + "(");
        assertThat(start).as("%s is created", function).isNotNegative();
        return SQL.substring(start, SQL.indexOf("AS $$", start));
    }

    private static String body(String function) {
        int start = SQL.indexOf("AS $$", SQL.indexOf("CREATE FUNCTION platform." + function + "("));
        return SQL.substring(start, SQL.indexOf("$$;", start + 5));
    }

    private static String flat(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String withoutComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
