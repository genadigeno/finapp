package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.FileStatus;
import com.finapp.settlement.ReceptionOutcomeObserver;
import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Readmission and the re-parse verification over real HTTP and the REAL composition
 * (`P8-TSK-022`, ADR-0066 §8–§9, `INV-SET-07`): the controller's keyed, reasoned door, the
 * investigator's audited verification, and the ordinary parse and accept legs that carry a
 * readmission onward — driven directly, since their schedules are off in tests. The database
 * rank's raw-SQL cases are the module suite's ({@code ReadmissionDatabaseTest}); this one proves
 * the wiring, the status codes, the idempotency and the authorization the routes add.
 *
 * <p><strong>How a file comes to be rejected here.</strong> A genuine validation rejection —
 * a door-clean report the parse leg refuses — is driven over HTTP wherever the case does not
 * need the readmission to parse: the same bytes under the same format version are rejected
 * again, which is exactly right. Where the case needs the readmission ACCEPTED, the original's
 * verdict is recorded through the store's own conditional edge, as the parse leg would record
 * it — how an earlier parser version's mistake looks (the module suite's fixture). A
 * {@code DECLINED} and a {@code CONFLICTING_BATCH} original are produced through the real
 * routes and legs.
 *
 * <p><strong>Shared container.</strong> Every count is scoped to the case's own rows; every
 * report is unique (random LETTERS in every reference, so no digit run of card length can meet
 * the door's screen; one capture and its fee — the shape {@code SettlementAcceptanceDatabaseTest}
 * leaves accepted). Every file this suite makes ends terminal: accepted, declined, or rejected
 * by the parse leg — nothing is left for another suite's accept leg to claim.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("readmission and the re-parse verification over HTTP (P8-TSK-022)")
class SettlementReadmissionDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String SOURCE = "simulated-psp.settlement";
    private static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-09-25");
    private static final String FILES = "/v1/operator/settlement/files/";
    private static final String READMITTED = "settlement.SettlementFileReadmitted";
    private static final String VERIFIED = "settlement.SettlementFileVerified";

    private static final ReceptionOutcomeObserver NO_RECEPTION_OUTCOMES =
            new ReceptionOutcomeObserver() {
                @Override
                public void received(
                        String sourceCode, SettlementFileStore.ReceiptOutcome outcome) {}

                @Override
                public void refused(String sourceCode, RefusalReason reason) {}
            };

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private FileParsing fileParsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private SettlementSources settlementSources;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private TransactionRunner settlementTransactionRunner;

    // -----------------------------------------------------------------
    // What authenticates a readmission: nothing inherited, inherited, a decline's nothing.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a validation-rejected, never-attested upload is readmitted over HTTP: 202, a"
            + " NEW file naming its original, ATTESTATION_REQUIRED, audited with its reason; the"
            + " accept leg leaves it until a person distinct from every submitter attests - the"
            + " uploader and the readmitting controller are refused - and then accepts it")
    void anUnattestedRejectionIsReadmittedAndWaitsForASecondPerson() throws Exception {
        Session uploader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        // The readmitter ALSO holds the ingest permission, so their attestation below meets
        // the domain's submitter rule (409), never the permission check (403).
        Session controller =
                sessionWith(RoleName.RECONCILIATION_CONTROLLER, RoleName.RECONCILIATION_OPERATOR);
        Session third = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String original = uploaded(uploader, genuineReport());
        rejectedAsAnEarlierParserDid(original, RejectionCode.MALFORMED);
        String reason = "the v1 parser misread the fee column " + letters(6);

        HttpResponse<String> readmitted = readmit(original, controller, someKey(), reason);
        assertThat(readmitted.statusCode()).as(readmitted.body()).isEqualTo(202);
        String readmission = field(readmitted.body(), "fileId");
        assertThat(readmission).isNotEqualTo(original);
        assertThat(field(readmitted.body(), "readmitsFileId")).isEqualTo(original);
        assertThat(field(readmitted.body(), "authentication"))
                .as("a never-attested upload passes nothing on (INV-SET-07)")
                .isEqualTo("ATTESTATION_REQUIRED");

        String view = get(FILES + readmission, uploader).body();
        assertThat(field(view, "receivedVia")).isEqualTo("READMISSION");
        assertThat(field(view, "status")).isEqualTo("RECEIVED");
        assertThat(field(view, "receivedBy"))
                .as("the readmitter is the readmission's submitter")
                .isEqualTo(controller.identity());
        assertThat(field(view, "contentSha256"))
                .as("the original's bytes, re-presented")
                .isEqualTo(field(get(FILES + original, uploader).body(), "contentSha256"));
        assertThat(statusOf(original)).as("the original is never touched").isEqualTo("REJECTED");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                                + " AND target_id = ? AND reason = ? AND actor_id = ?"
                                + " AND outcome = 'SUCCEEDED' AND change_summary LIKE ?"
                                + " AND change_summary LIKE ?",
                        READMITTED,
                        readmission,
                        reason,
                        controller.identity(),
                        "%original=" + original + "%",
                        "%originalVerdict=MALFORMED%"))
                .as("the readmission is audited, reasoned, by its controller (INV-AUD-03)")
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND from_status IS NULL AND to_status = 'RECEIVED'"
                                + " AND reason = ?",
                        UUID.fromString(readmission),
                        reason))
                .as("born reasoned")
                .isEqualTo(1);

        // The ordinary parse leg, under the source's current format.
        assertThat(parseSettled(readmission)).isEqualTo("PARSED");

        // The ordinary accept leg will not take it unattested.
        for (int sweep = 0; sweep < 3; sweep++) {
            acceptance.sweep();
        }
        assertThat(statusOf(readmission)).isEqualTo("PARSED");
        assertThat(eligibleForAcceptance(readmission))
                .as("the accept leg's claim reads V009's rule")
                .isFalse();

        HttpResponse<String> byUploader = attest(readmission, uploader);
        assertThat(byUploader.statusCode()).as(byUploader.body()).isEqualTo(409);
        assertThat(byUploader.body())
                .as("the original's uploader submitted these bytes - a readmission is no way"
                        + " round the second person")
                .contains("settlement.AttestationBySubmitter");
        HttpResponse<String> byReadmitter = attest(readmission, controller);
        assertThat(byReadmitter.statusCode()).as(byReadmitter.body()).isEqualTo(409);
        assertThat(byReadmitter.body()).contains("settlement.AttestationBySubmitter");
        assertThat(get(FILES + readmission, uploader).body())
                .as("both refusals wrote nothing")
                .contains("\"attestedBy\":null");

        HttpResponse<String> bySecondPerson = attest(readmission, third);
        assertThat(bySecondPerson.statusCode()).as(bySecondPerson.body()).isEqualTo(200);
        assertThat(field(bySecondPerson.body(), "attestedBy")).isEqualTo(third.identity());
        assertThat(eligibleForAcceptance(readmission)).isTrue();
        assertThat(acceptanceSettled(readmission))
                .as("attested by a person distinct from every submitter, the accept leg takes it")
                .isEqualTo("ACCEPTED");
        assertThat(statusOf(original)).isEqualTo("REJECTED");

        // An accepted file is not a rejection.
        HttpResponse<String> acceptedOne =
                readmit(readmission, controller, someKey(), "readmit an accepted file");
        assertThat(acceptedOne.statusCode()).as(acceptedOne.body()).isEqualTo(409);
        assertThat(acceptedOne.body()).contains("settlement.FileNotRejected");
    }

    @Test
    @DisplayName("an attested upload, rejected, is readmitted INHERITED: there is nothing left to"
            + " attest, and the accept leg accepts the readmission with no second attestation")
    void anAttestedRejectionPassesItsAuthenticationOn() throws Exception {
        Session uploader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session attester = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String original = uploaded(uploader, genuineReport());
        HttpResponse<String> attested = attest(original, attester);
        assertThat(attested.statusCode()).as(attested.body()).isEqualTo(200);
        rejectedAsAnEarlierParserDid(original, RejectionCode.CONTROL_TOTAL_MISMATCH);

        HttpResponse<String> readmitted =
                readmit(original, controller, someKey(), "the totals check was wrong in v1");
        assertThat(readmitted.statusCode()).as(readmitted.body()).isEqualTo(202);
        String readmission = field(readmitted.body(), "fileId");
        assertThat(field(readmitted.body(), "readmitsFileId")).isEqualTo(original);
        assertThat(field(readmitted.body(), "authentication"))
                .as("the identical checksum carries the original's second person over")
                .isEqualTo("INHERITED");

        HttpResponse<String> nothingToAttest = attest(readmission, attester);
        assertThat(nothingToAttest.statusCode()).as(nothingToAttest.body()).isEqualTo(409);
        assertThat(nothingToAttest.body()).contains("settlement.FileNotAttestable");

        assertThat(parseSettled(readmission)).isEqualTo("PARSED");
        assertThat(eligibleForAcceptance(readmission)).isTrue();
        assertThat(acceptanceSettled(readmission)).isEqualTo("ACCEPTED");
        assertThat(get(FILES + readmission, uploader).body())
                .as("accepted on the inherited authentication, never re-attested")
                .contains("\"attestedBy\":null");
        assertThat(statusOf(original)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a DECLINED file is readmissible but inherits nothing, though a second person had"
            + " attested it: ATTESTATION_REQUIRED, the accept leg will not take it, and the"
            + " original's uploader may not attest it")
    void aDeclinedFilePassesNothingOn() throws Exception {
        Session uploader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session attester = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String original = uploaded(uploader, genuineReport());
        assertThat(attest(original, attester).statusCode()).isEqualTo(200);
        HttpResponse<String> declinedOriginal =
                decline(original, attester, "declined in error " + letters(6));
        assertThat(declinedOriginal.statusCode()).as(declinedOriginal.body()).isEqualTo(200);
        assertThat(field(declinedOriginal.body(), "rejectionCode")).isEqualTo("DECLINED");

        HttpResponse<String> readmitted =
                readmit(original, controller, someKey(), "the decline was a mistake");
        assertThat(readmitted.statusCode()).as(readmitted.body()).isEqualTo(202);
        String readmission = field(readmitted.body(), "fileId");
        assertThat(field(readmitted.body(), "readmitsFileId")).isEqualTo(original);
        assertThat(field(readmitted.body(), "authentication"))
                .as("a decline is a judgement against the file: nothing passes on")
                .isEqualTo("ATTESTATION_REQUIRED");

        assertThat(parseSettled(readmission)).isEqualTo("PARSED");
        assertThat(eligibleForAcceptance(readmission))
                .as("the original's attestation does not survive its decline")
                .isFalse();
        HttpResponse<String> byUploader = attest(readmission, uploader);
        assertThat(byUploader.statusCode()).as(byUploader.body()).isEqualTo(409);
        assertThat(byUploader.body()).contains("settlement.AttestationBySubmitter");

        declined(readmission, attester); // Tidied: never left for another suite's accept leg.
    }

    // -----------------------------------------------------------------
    // Which originals are readmissible.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a CONFLICTING_BATCH file is refused 409 ConflictingBatchStands while the live"
            + " batch stands - writing nothing, its key not burned - and admitted under the same"
            + " key once the standing file is declined; the readmission parses into the freed"
            + " identity")
    void theConflictingBatchRecoveryOverHttp() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String batchRef = batchRef();
        String standing = uploaded(operator, report(batchRef));
        assertThat(parseSettled(standing)).isEqualTo("PARSED");
        String conflicting = uploaded(operator, report(batchRef));
        assertThat(parseSettled(conflicting)).isEqualTo("REJECTED");
        assertThat(scalar("SELECT rejection_code FROM settlement.file WHERE id = ?",
                        UUID.fromString(conflicting)))
                .isEqualTo("CONFLICTING_BATCH");

        String key = someKey();
        String reason = "the standing batch is a fabrication " + letters(6);
        HttpResponse<String> refused = readmit(conflicting, controller, key, reason);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body())
                .as("readmitted now, it would only be rejected again")
                .contains("settlement.ConflictingBatchStands");
        assertThat(readmissionsOf(conflicting)).isZero();

        assertThat(decline(standing, operator, "a fabricated batch " + letters(6)).statusCode())
                .isEqualTo(200);
        HttpResponse<String> admitted = readmit(conflicting, controller, key, reason);
        assertThat(admitted.statusCode())
                .as("the refusal rolled its claim back: the same key is a fresh command - %s",
                        admitted.body())
                .isEqualTo(202);
        String readmission = field(admitted.body(), "fileId");
        assertThat(field(admitted.body(), "readmitsFileId")).isEqualTo(conflicting);
        assertThat(field(admitted.body(), "authentication")).isEqualTo("ATTESTATION_REQUIRED");

        assertThat(parseSettled(readmission))
                .as("the freed identity takes the genuine file")
                .isEqualTo("PARSED");
        assertThat(scalar("SELECT external_batch_ref || ':' || status FROM settlement.batch"
                                + " WHERE file_id = ?",
                        UUID.fromString(readmission)))
                .isEqualTo(batchRef + ":PARSED");

        declined(readmission, operator); // Tidied: never left for another suite's accept leg.
    }

    @Test
    @DisplayName("a file still in its machine - RECEIVED, then PARSED - is 409 FileNotRejected,"
            + " writing nothing; an unknown and a malformed id are one 404 FileNotFound")
    void onlyARejectionIsReadmitted() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String file = uploaded(operator, genuineReport());

        HttpResponse<String> received = readmit(file, controller, someKey(), "too early");
        assertThat(received.statusCode()).as(received.body()).isEqualTo(409);
        assertThat(received.body()).contains("settlement.FileNotRejected");

        assertThat(parseSettled(file)).isEqualTo("PARSED");
        HttpResponse<String> parsed = readmit(file, controller, someKey(), "still too early");
        assertThat(parsed.statusCode()).as(parsed.body()).isEqualTo(409);
        assertThat(parsed.body()).contains("settlement.FileNotRejected");
        assertThat(readmissionsOf(file)).as("every refusal wrote nothing").isZero();

        for (String absent : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> unknown = readmit(absent, controller, someKey(), "a guess");
            assertThat(unknown.statusCode()).as("%s: %s", absent, unknown.body()).isEqualTo(404);
            assertThat(unknown.body()).contains("settlement.FileNotFound");
        }

        declined(file, operator); // Tidied: never left for another suite's accept leg.
    }

    // -----------------------------------------------------------------
    // Once: the key replays, a second readmission is refused, ten racers land one.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a file our validation rejected is readmitted once: the same key replays the"
            + " same receipt, the same key with another reason is 409 api.Conflict, any other key"
            + " - or another controller's - is 409 FileAlreadyReadmitted; and the readmission,"
            + " rejected in turn, is itself readmissible")
    void aFileIsReadmittedOnce() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session otherController = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String original =
                uploaded(operator,
                        report(batchRef(), SimulatedSettlementReports.Fault.BAD_TRAILER_NET));
        assertThat(parseSettled(original)).isEqualTo("REJECTED");
        assertThat(scalar("SELECT rejection_code FROM settlement.file WHERE id = ?",
                        UUID.fromString(original)))
                .as("our own validation's verdict, genuinely reached")
                .isEqualTo("CONTROL_TOTAL_MISMATCH");

        String key = someKey();
        String reason = "the trailer rule is under review " + letters(6);
        HttpResponse<String> first = readmit(original, controller, key, reason);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        String readmission = field(first.body(), "fileId");

        HttpResponse<String> replay = readmit(original, controller, key, reason);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.body())
                .as("the recorded receipt, replayed - never a re-read (INV-IDEM-01)")
                .isEqualTo(first.body());

        HttpResponse<String> changed = readmit(original, controller, key, reason + " amended");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(409);
        assertThat(changed.body())
                .as("the same key with a materially different request (INV-IDEM-03)")
                .contains("api.Conflict");

        HttpResponse<String> secondKey = readmit(original, controller, someKey(), reason);
        assertThat(secondKey.statusCode()).as(secondKey.body()).isEqualTo(409);
        assertThat(secondKey.body()).contains("settlement.FileAlreadyReadmitted");

        HttpResponse<String> otherPrincipal = readmit(original, otherController, key, reason);
        assertThat(otherPrincipal.statusCode())
                .as("the key is scoped per principal: a fresh claim, and the file refuses it - %s",
                        otherPrincipal.body())
                .isEqualTo(409);
        assertThat(otherPrincipal.body()).contains("settlement.FileAlreadyReadmitted");

        assertThat(readmissionsOf(original)).as("a file is readmitted once").isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                                + " AND change_summary LIKE ?",
                        READMITTED,
                        "%original=" + original + "%"))
                .as("one readmission, one record - replays and refusals wrote nothing")
                .isEqualTo(1);

        // The same bytes under the same format version: rejected in turn - and recoverable.
        assertThat(parseSettled(readmission)).isEqualTo("REJECTED");
        HttpResponse<String> chained =
                readmit(readmission, controller, someKey(), "a second look " + letters(6));
        assertThat(chained.statusCode()).as(chained.body()).isEqualTo(202);
        assertThat(field(chained.body(), "readmitsFileId")).isEqualTo(readmission);
        assertThat(field(chained.body(), "authentication")).isEqualTo("ATTESTATION_REQUIRED");
        assertThat(parseSettled(field(chained.body(), "fileId"))).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("ten concurrent readmissions of one original, each under its own key: one 202,"
            + " nine 409 FileAlreadyReadmitted - one new file, one receipt and one audit record,"
            + " counted")
    void tenRacingReadmissionsLandOne() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String original =
                uploaded(operator,
                        report(batchRef(), SimulatedSettlementReports.Fault.MALFORMED_FIELD));
        assertThat(parseSettled(original)).isEqualTo("REJECTED");

        int racers = 10;
        List<String> landed = new ArrayList<>();
        int refused = 0;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> outcomes = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String key = someKey();
                outcomes.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return readmit(original, controller, key, "raced recovery");
                                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> outcome : outcomes) {
                HttpResponse<String> response = outcome.get(60, TimeUnit.SECONDS);
                if (response.statusCode() == 202) {
                    landed.add(field(response.body(), "fileId"));
                } else {
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                    assertThat(response.body()).contains("settlement.FileAlreadyReadmitted");
                    refused++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(landed).as("exactly one racer readmits").hasSize(1);
        assertThat(refused).isEqualTo(racers - 1);
        // One effect, counted in the tables, never inferred from responses.
        assertThat(readmissionsOf(original)).isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_receipt WHERE file_id = ?"
                                + " AND channel = 'READMISSION' AND outcome = 'NEW'",
                        UUID.fromString(landed.get(0))))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                                + " AND change_summary LIKE ?",
                        READMITTED,
                        "%original=" + original + "%"))
                .isEqualTo(1);

        assertThat(parseSettled(landed.get(0))).isEqualTo("REJECTED"); // Tidied: terminal.
    }

    // -----------------------------------------------------------------
    // The door's screen, under the CURRENT format: a refusal is a recorded result.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("bytes the current screen refuses refuse the readmission as a recorded result:"
            + " 422 settlement.DeliveryRefused naming the line and field, never the value; the"
            + " same key replays the refusal without screening again; no readmission is stored")
    void theCurrentScreenRefusesTheReadmissionOverHttp() throws Exception {
        Session uploader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        byte[] content =
                report(batchRef(), SimulatedSettlementReports.Fault.PAN_IN_FREE_TEXT);
        String original = storedPastAnEarlierScreen(content, uploader);
        rejectedAsAnEarlierParserDid(original, RejectionCode.MALFORMED);
        String sha = sha256Hex(content);
        String key = someKey();
        String reason = "recover the file " + letters(6);

        HttpResponse<String> refused = readmit(original, controller, key, reason);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body())
                .contains("settlement.DeliveryRefused")
                .doesNotContain("4111 1111");
        String position =
                scalar("SELECT line_no::text || '|' || coalesce(field_name, '')"
                                + " FROM settlement.refused_delivery"
                                + " WHERE content_sha256 = decode(?, 'hex')"
                                + " AND channel = 'READMISSION' AND actor = ?"
                                + " AND reason = 'PRIMARY_ACCOUNT_NUMBER'",
                        sha,
                        controller.identity());
        String[] lineAndField = position.split("\\|", 2);
        assertThat(refused.body())
                .as("the position, never the value")
                .contains("line " + lineAndField[0])
                .contains(lineAndField[1]);

        HttpResponse<String> replay = readmit(original, controller, key, reason);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(422);
        assertThat(replay.body())
                .contains("settlement.DeliveryRefused")
                .contains("line " + lineAndField[0]);
        assertThat(count(
                        "SELECT count(*) FROM settlement.refused_delivery"
                                + " WHERE content_sha256 = decode(?, 'hex')"
                                + " AND channel = 'READMISSION'",
                        sha))
                .as("the replay re-read the recorded refusal; it never screened again")
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementDeliveryRefused'"
                                + " AND change_summary LIKE ? AND change_summary LIKE ?"
                                + " AND change_summary NOT LIKE ?",
                        "%channel=READMISSION%",
                        "%sha256=" + sha + "%",
                        "%4111 1111%"))
                .as("one refusal record, the value in no summary (INV-PAY-02)")
                .isEqualTo(1);
        assertThat(readmissionsOf(original)).as("the original stands alone").isZero();
        assertThat(statusOf(original)).isEqualTo("REJECTED");
    }

    // -----------------------------------------------------------------
    // The re-parse verification.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("verification: a parsed file re-parses to what is stored - MATCHES over every"
            + " line, audited with its reason, no line written; an unparsed file is NOT_PARSED;"
            + " an unreasoned request is 422 api.ValidationFailed and an unknown id 404, neither"
            + " recording")
    void theVerificationOverHttp() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String parsed = uploaded(operator, genuineReport());
        assertThat(parseSettled(parsed)).isEqualTo("PARSED");
        long lines =
                count("SELECT count(*) FROM settlement.line WHERE file_id = ?",
                        UUID.fromString(parsed));
        assertThat(lines).isPositive();
        String reason = "break B-" + letters(4) + " under review";

        HttpResponse<String> verified = verify(parsed, operator, reasonBody(reason));
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
        assertThat(field(verified.body(), "fileId")).isEqualTo(parsed);
        assertThat(field(verified.body(), "verdict")).isEqualTo("MATCHES");
        assertThat(number(verified.body(), "linesCompared"))
                .as("every stored line compared")
                .isEqualTo(lines);
        assertThat(verified.body())
                .contains("\"firstDifferingLine\":null")
                .as("a verdict, never a byte of the file")
                .doesNotContain("PSP-CAP-");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                                + " AND target_id = ? AND reason = ? AND actor_id = ?"
                                + " AND outcome = 'SUCCEEDED' AND change_summary LIKE ?",
                        VERIFIED,
                        parsed,
                        reason,
                        operator.identity(),
                        "%verdict=MATCHES%"))
                .as("the decryption is audited per verification, with its reason (INV-REC-10)")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlement.line WHERE file_id = ?",
                        UUID.fromString(parsed)))
                .as("a verification never writes a line")
                .isEqualTo(lines);

        String unparsed = uploaded(operator, genuineReport());
        HttpResponse<String> notParsed =
                verify(unparsed, operator, reasonBody("why is it still waiting"));
        assertThat(notParsed.statusCode()).as(notParsed.body()).isEqualTo(200);
        assertThat(field(notParsed.body(), "verdict")).isEqualTo("NOT_PARSED");
        assertThat(number(notParsed.body(), "linesCompared")).isZero();
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                                + " AND target_id = ? AND change_summary LIKE ?",
                        VERIFIED,
                        unparsed,
                        "%verdict=NOT_PARSED%"))
                .isEqualTo(1);

        long recorded = verificationsOf(parsed);
        for (String unreasoned : List.of("{}", "{\"reason\":\"   \"}")) {
            HttpResponse<String> refused = verify(parsed, operator, unreasoned);
            assertThat(refused.statusCode()).as("%s: %s", unreasoned, refused.body())
                    .isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
        assertThat(verificationsOf(parsed)).as("an unreasoned request records nothing")
                .isEqualTo(recorded);

        String guessed = UUID.randomUUID().toString();
        for (String absent : List.of(guessed, "not-a-uuid")) {
            HttpResponse<String> unknown = verify(absent, operator, reasonBody("a guess"));
            assertThat(unknown.statusCode()).as("%s: %s", absent, unknown.body()).isEqualTo(404);
            assertThat(unknown.body()).contains("settlement.FileNotFound");
        }
        assertThat(verificationsOf(guessed)).as("a guessed id records nothing").isZero();

        declined(parsed, operator); // Tidied: never left for another suite's accept leg.
        declined(unparsed, operator);
    }

    // -----------------------------------------------------------------
    // The doors' negatives.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the doors hold their permissions: readmission is the controller's (anonymous"
            + " 401, the ingesting operator and the ledger operator 403), verification the"
            + " investigator's (the controller 403), and a keyless readmission is 422"
            + " api.IdempotencyKeyRequired - nothing written")
    void theDoorsHoldTheirPermissions() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        String original =
                uploaded(operator,
                        report(batchRef(), SimulatedSettlementReports.Fault.UNKNOWN_LINE));
        assertThat(parseSettled(original)).isEqualTo("REJECTED");
        String readmission = FILES + original + "/readmission";
        String verification = FILES + original + "/verification";
        String body = reasonBody("probe " + letters(6));

        assertThat(post(readmission, body, null, someKey()).statusCode())
                .as("readmission without a session")
                .isEqualTo(401);
        assertThat(post(readmission, body, operator.token(), someKey()).statusCode())
                .as("SETTLEMENT_INGEST introduces evidence; it does not readmit it")
                .isEqualTo(403);
        assertThat(post(readmission, body, ledgerOperator.token(), someKey()).statusCode())
                .isEqualTo(403);
        HttpResponse<String> keyless = post(readmission, body, controller.token(), null);
        assertThat(keyless.statusCode()).as(keyless.body()).isEqualTo(422);
        assertThat(keyless.body()).contains("api.IdempotencyKeyRequired");
        assertThat(readmissionsOf(original)).as("no refused door wrote anything").isZero();

        assertThat(post(verification, body, null, null).statusCode())
                .as("verification without a session")
                .isEqualTo(401);
        assertThat(post(verification, body, controller.token(), null).statusCode())
                .as("the controller decides what counts; the investigator reads the content")
                .isEqualTo(403);
        assertThat(post(verification, body, ledgerOperator.token(), null).statusCode())
                .isEqualTo(403);
        assertThat(verificationsOf(original)).isZero();
    }

    // -----------------------------------------------------------------
    // Fixtures.
    // -----------------------------------------------------------------

    /** One capture and its fee, every reference unique and letters-only. */
    private static byte[] report(String batchRef, SimulatedSettlementReports.Fault... faults) {
        SimulatedSettlementReports report =
                new SimulatedSettlementReports(
                                batchRef, "EUR", BUSINESS_DATE, "PSP-REM-" + digits())
                        .with(SimulatedSettlementReports.Line.capture(
                                "PSP-CAP-" + letters(10), "", "", "100.00", "1.75"));
        for (SimulatedSettlementReports.Fault fault : faults) {
            report.faulted(fault);
        }
        return report.render();
    }

    private static byte[] genuineReport() {
        return report(batchRef());
    }

    private static String batchRef() {
        return "PSPB-RDM-" + letters(10);
    }

    /** Uploads over the real door as the given operator: 202, RECEIVED, a new file. */
    private String uploaded(Session uploader, byte[] content) throws Exception {
        HttpResponse<String> landed =
                post("/v1/operator/settlement/files", uploadBody(content), uploader.token(),
                        someKey());
        assertThat(landed.statusCode()).as(landed.body()).isEqualTo(202);
        assertThat(field(landed.body(), "status")).isEqualTo("RECEIVED");
        assertThat(landed.body()).contains("\"duplicateOf\":null");
        return field(landed.body(), "fileId");
    }

    /**
     * The verdict recorded through the store's own conditional edge, as the parse leg would -
     * how an earlier parser version's mistake looks. Called straight after the upload, before
     * any sweep could claim the file.
     */
    private void rejectedAsAnEarlierParserDid(String fileId, RejectionCode code) {
        Boolean moved;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            moved =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    settlementFileStore.markRejected(
                                            uow,
                                            UUID.fromString(fileId),
                                            FileStatus.RECEIVED,
                                            code,
                                            Optional.of(code.name()),
                                            Instant.now(CLOCK)));
        }
        assertThat(moved).as("the file was still RECEIVED").isTrue();
    }

    /**
     * Bytes stored under an earlier, laxer screen - how a file the CURRENT screen refuses comes
     * to be held (the module suite's lenient door): the app's own store, sources and audit, a
     * screen that finds nothing.
     */
    private String storedPastAnEarlierScreen(byte[] content, Session uploader) {
        DeliveryScreen findsNothing =
                bytes ->
                        new DeliveryScreen.Screening(
                                (int) new String(bytes, StandardCharsets.UTF_8).lines().count(),
                                Optional.empty());
        FileReception<Connection> earlierDoor =
                new FileReception<>(
                        settlementSources,
                        settlementFileStore,
                        Map.of(SettlementFormatId.SIM_PSP_CSV, findsNothing),
                        NO_RECEPTION_OUTCOMES,
                        auditWriter,
                        IDS,
                        CLOCK);
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    earlierDoor.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    SOURCE,
                                                    DeliveryChannel.UPLOAD,
                                                    content,
                                                    Optional.of(BUSINESS_DATE),
                                                    new Actor(
                                                            uploader.identity(),
                                                            ActorType.CUSTOMER),
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId().toString();
        }
    }

    /** Sweeps the REAL parse leg until the file leaves RECEIVED - other suites share the queue. */
    private String parseSettled(String fileId) throws SQLException {
        for (int sweep = 0; sweep < 100; sweep++) {
            fileParsing.sweep();
            String status = statusOf(fileId);
            if (!"RECEIVED".equals(status)) {
                return status;
            }
        }
        throw new AssertionError("settlement file " + fileId + " never left RECEIVED");
    }

    /** Sweeps the REAL accept leg until the file leaves PARSED. */
    private String acceptanceSettled(String fileId) throws SQLException {
        for (int sweep = 0; sweep < 100; sweep++) {
            acceptance.sweep();
            String status = statusOf(fileId);
            if (!"PARSED".equals(status)) {
                return status;
            }
        }
        throw new AssertionError("settlement file " + fileId + " never left PARSED");
    }

    /** The accept leg's own per-file claim, taken and released - V009's rule, read. */
    private boolean eligibleForAcceptance(String fileId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                return settlementFileStore
                        .lockEligibleById(app, UUID.fromString(fileId))
                        .isPresent();
            } finally {
                app.rollback();
            }
        }
    }

    private void declined(String fileId, Session decliner) throws Exception {
        HttpResponse<String> declined =
                decline(fileId, decliner, "test fixture, not for acceptance");
        assertThat(declined.statusCode()).as(declined.body()).isEqualTo(200);
    }

    // -----------------------------------------------------------------
    // The routes.
    // -----------------------------------------------------------------

    private HttpResponse<String> readmit(
            String fileId, Session controller, String key, String reason) throws Exception {
        return post(FILES + fileId + "/readmission", reasonBody(reason), controller.token(), key);
    }

    private HttpResponse<String> verify(String fileId, Session investigator, String body)
            throws Exception {
        return post(FILES + fileId + "/verification", body, investigator.token(), null);
    }

    private HttpResponse<String> attest(String fileId, Session attester) throws Exception {
        return post(FILES + fileId + "/attestation", null, attester.token(), null);
    }

    private HttpResponse<String> decline(String fileId, Session decliner, String reason)
            throws Exception {
        return post(FILES + fileId + "/decline", reasonBody(reason), decliner.token(), null);
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String uploadBody(byte[] content) {
        return "{\"sourceCode\":\"" + SOURCE + "\",\"businessDate\":\"" + BUSINESS_DATE + "\","
                + "\"content\":\"" + Base64.getEncoder().encodeToString(content) + "\"}";
    }

    // -----------------------------------------------------------------
    // Sessions.
    // -----------------------------------------------------------------

    /** A registered person's session and identity - the identity is the actor id recorded. */
    private record Session(String identity, String token) {}

    private Session sessionWith(RoleName... roles) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (RoleName role : roles) {
                authorization.assign(
                        app, IdentityId.of(identity), role, IdentityId.of(identity),
                        "test fixture");
            }
            app.commit();
        }
        return new Session(identity.toString(), field(authenticate(login).body(), "sessionToken"));
    }

    private String registered() throws Exception {
        String login = "rdmt." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registered =
                post(
                        "/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null,
                        someKey());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return login;
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.generate(IDS));
    }

    // -----------------------------------------------------------------
    // HTTP and SQL plumbing.
    // -----------------------------------------------------------------

    private HttpResponse<String> get(String path, Session reader) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + reader.token())
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String statusOf(String fileId) throws SQLException {
        return scalar("SELECT status FROM settlement.file WHERE id = ?", UUID.fromString(fileId));
    }

    private static long readmissionsOf(String originalFileId) throws SQLException {
        return count(
                "SELECT count(*) FROM settlement.file WHERE readmits_file_id = ?",
                UUID.fromString(originalFileId));
    }

    private static long verificationsOf(String fileId) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                        + " AND target_id = ?",
                VERIFIED,
                fileId);
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static String scalar(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("a row for: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static long number(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":(-?\\d+)").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    /** Letters only: no digit run the door screen could read as a card number. */
    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOM.nextInt(26)));
        }
        return letters.toString();
    }

    /** The remittance shape wants digits: eight, far short of any card length. */
    private static String digits() {
        return String.valueOf(10_000_000 + RANDOM.nextInt(90_000_000));
    }
}
