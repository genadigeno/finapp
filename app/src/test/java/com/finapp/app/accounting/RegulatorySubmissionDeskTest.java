package com.finapp.app.accounting;

import static com.finapp.accounting.testing.RegulatoryWorld.APPROVER;
import static com.finapp.accounting.testing.RegulatoryWorld.JANUARY;
import static com.finapp.accounting.testing.RegulatoryWorld.PREPARER;
import static com.finapp.accounting.testing.RegulatoryWorld.REASON;
import static com.finapp.accounting.testing.RegulatoryWorld.SENDER;
import static com.finapp.accounting.testing.RegulatoryWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The regulatory submission's doors (`P14-TSK-018`; {@code PHASE_14_PLAN.md} sections 10, 12, 13.7; {@code INV-ACC-13}, {@code INV-AUD-01}, {@code INV-AUD-04}): every door's happy path, its refusal on the contract, and its permission and assurance declared; the same key replaying the first answer, another body refused;
 * a refused self-approval and self-sending recorded {@code FAILED} in their own transaction; every read audited before it is served; what each role can really open said so, and held against the routes that really exist. The permissions are held by {@code RoutePermissionRegisterTest} too; the interceptor's refusal of a lesser holder, by {@code
 * RegulatorySubmissionDatabaseTest} (owed, C5).
 */
@DisplayName("the regulatory submission's doors (P14-TSK-018)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RegulatorySubmissionDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2096-03-10T09:00:00Z"), ZoneOffset.UTC);

    private final RegulatoryWorld world = new RegulatoryWorld(CLOCK);
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RegulatorySubmissionMetrics metrics = new RegulatorySubmissionMetrics(registry);
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = world.audit.size();
            int events = world.events.size();
            Runnable rollback = world.store.savepoint();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                rollback.run();
                claims.clear();
                claims.putAll(held);
                world.audit.subList(records, world.audit.size()).clear();
                world.events.subList(events, world.events.size()).clear();
                throw failed;
            }
        }
    };
    private final RegulatorySubmissionDriver driver = new RegulatorySubmissionDriver(world.submissions, transactions, metrics, new CloseRunMetrics(registry), AccountingFlowScope.NONE, Duration.ofMinutes(2), Duration.ofMinutes(15), 10);
    private final RegulatorySubmissionDesk desk = new RegulatorySubmissionDesk(world.submissions, driver, new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions, AccountingFlowScope.NONE, metrics);
    private RegulatoryObligationId obligation;
    private String january;

    @BeforeEach
    void januaryClosedAndIssued() {
        january = world.closed(JANUARY).value().toString();
        world.issued(JANUARY, RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00));
        obligation = world.active();
        world.audit.clear();
    }

    private <R> R as(Actor actor, Supplier<R> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor); CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(world.ids)))) {
            return work.get();
        }
    }

    private RegulatorySubmissionDesk.RegulatorySubmissionReceipt draft(String key) {
        return as(PREPARER, () -> desk.draft(key, obligation.value().toString(), january, null, REASON));
    }

    private RegulatorySubmissionDesk.RegulatorySubmissionReceipt validated() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt drafted = draft("d-1");
        return as(PREPARER, () -> desk.validate("v-1", drafted.submissionId()));
    }

    private RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt validated = validated();
        return as(APPROVER, () -> desk.approve("a-1", validated.submissionId(), validated.contentSha256(), validated.periodRevision(), REASON));
    }

    private double count(String outcome) {
        return registry.get(RegulatorySubmissionMetrics.SUBMISSION).tag("outcome", outcome).counter().count();
    }

    private static void refused(Runnable door, AccountingErrorCode code) {
        assertThatThrownBy(door::run).isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ the routes

    @Test
    @DisplayName("the routes are the partition: each role's read its own door; MULTI_FACTOR on the attestation, the sending and the non-delivery, and nowhere else; every POST keyed; no route asks ACCOUNTING_READ")
    void theRoutesAreThePartition() {
        Map<String, PermissionName> expected = Map.ofEntries(
                Map.entry("draftRegulatorySubmission", PermissionName.REGULATORY_REPORT_PREPARE),
                Map.entry("listStandingRegulatorySubmissions", PermissionName.REGULATORY_REPORT_PREPARE),
                Map.entry("readRegulatorySubmission", PermissionName.REGULATORY_REPORT_PREPARE),
                Map.entry("readRegulatoryReferenceExport", PermissionName.REGULATORY_REPORT_PREPARE),
                Map.entry("validateRegulatorySubmission", PermissionName.REGULATORY_REPORT_PREPARE),
                Map.entry("withdrawRegulatorySubmission", PermissionName.REGULATORY_REPORT_PREPARE),
                Map.entry("listRegulatorySubmissionsAwaitingApproval", PermissionName.REGULATORY_REPORT_APPROVE),
                Map.entry("readRegulatorySubmissionAttestation", PermissionName.REGULATORY_REPORT_APPROVE),
                Map.entry("approveRegulatorySubmission", PermissionName.REGULATORY_REPORT_APPROVE),
                Map.entry("refuseRegulatorySubmission", PermissionName.REGULATORY_REPORT_APPROVE),
                Map.entry("sendRegulatorySubmission", PermissionName.REGULATORY_REPORT_SUBMIT),
                Map.entry("declareRegulatorySubmissionUndelivered", PermissionName.REGULATORY_REPORT_SUBMIT));
        List<String> stepped = List.of("approveRegulatorySubmission", "sendRegulatorySubmission", "declareRegulatorySubmissionUndelivered");
        int seen = 0;
        for (Method method : RegulatorySubmissionController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                seen++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName()).isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName()).isEqualTo(!(method.getName().startsWith("read") || method.getName().startsWith("list")));
                assertThat(method.isAnnotationPresent(RequiresAssurance.class)).as(method.getName()).isEqualTo(stepped.contains(method.getName()));
                if (stepped.contains(method.getName())) {
                    assertThat(method.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                }
            }
        }
        assertThat(seen).isEqualTo(expected.size());
    }

    @Test
    @DisplayName("the bodies are closed, and a list's null cannot reach a lambda; a toString carries no reason")
    void theBodiesAreClosed() {
        assertThat(RegulatorySubmissionController.RegulatorySubmissionDraftRequest.class.isAnnotationPresent(ClosedBody.class)).isTrue();
        assertThat(RegulatorySubmissionController.RegulatorySubmissionAttestationRequest.class.isAnnotationPresent(ClosedBody.class)).isTrue();
        assertThat(RegulatorySubmissionController.RegulatorySubmissionDecisionRequest.class.isAnnotationPresent(ClosedBody.class)).isTrue();
        assertThat(new RegulatorySubmissionController.RegulatorySubmissionDraftRequest("o", "p", null, REASON).toString()).doesNotContain(REASON);
        assertThat(new RegulatorySubmissionController.RegulatorySubmissionAttestationRequest("h", 1, REASON).toString()).doesNotContain(REASON);
        assertThat(new RegulatorySubmissionController.RegulatorySubmissionDecisionRequest(REASON).toString()).doesNotContain(REASON);
        assertThat(Arrays.stream(RegulatorySubmissionController.RegulatorySubmissionAttestationRequest.class.getRecordComponents()).map(RecordComponent::getName)).containsExactly("contentSha256", "periodRevision", "reason");
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new RegulatorySubmissionController.RegulatorySubmissionDecisionRequest("x".repeat(1001)))).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ the draft door

    @Test
    @DisplayName("the draft door: a receipt with no figure; the same key replays, another body conflicts; the month not closed, a live return and a malformed id each refused on the contract")
    void theDraftDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt first = draft("d-1");
        assertThat(first.status()).isEqualTo("DRAFT");
        assertThat(first.standing()).isEqualTo("REFERENCE");
        assertThat(first.toString()).doesNotContain("10000");
        assertThat(count("drafted")).isEqualTo(1);

        assertThat(draft("d-1").submissionId()).isEqualTo(first.submissionId());
        assertThat(count("drafted")).as("a replay does nothing").isEqualTo(1);
        assertThatThrownBy(() -> as(PREPARER, () -> desk.draft("d-1", obligation.value().toString(), january, null, "another reason"))).isInstanceOf(IdempotencyConflictException.class);

        refused(() -> draft("d-2"), AccountingErrorCode.REGULATORY_SUBMISSION_LIVE);
        refused(() -> as(PREPARER, () -> desk.draft("d-3", "not-a-uuid", january, null, REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_INVALID);
        refused(() -> as(PREPARER, () -> desk.draft("d-4", obligation.value().toString(), "not-a-uuid", null, REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_INVALID);
        refused(() -> as(PREPARER, () -> desk.draft("d-5", obligation.value().toString(), january, "not-a-uuid", REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_INVALID);
        refused(() -> as(PREPARER, () -> desk.draft("d-6", obligation.value().toString(), january, null, " ")), AccountingErrorCode.REASON_REQUIRED);

        world.closed(RegulatoryWorld.FEBRUARY);
        String february = world.row(RegulatoryWorld.FEBRUARY).id().value().toString();
        refused(() -> as(PREPARER, () -> desk.draft("d-7", obligation.value().toString(), february, null, REASON)), AccountingErrorCode.REGULATORY_SOURCE_NOT_FINAL);
        assertThat(count("source_not_final")).isEqualTo(1);
    }

    @Test
    @DisplayName("a figure not found and bytes that do not prove are refused on the contract, naming no figure")
    void aFigureAndBytesOnTheContract() {
        RegulatoryObligationId broken = world.active(2, new com.finapp.accounting.RegulatoryObligationConfiguration(List.of(new com.finapp.accounting.RegulatoryObligationConfiguration.Source(RegulatoryWorld.SOURCE, 1)),
                List.of(new com.finapp.accounting.RegulatoryObligationConfiguration.Line("ONLY", "The only line", RegulatoryWorld.SOURCE, "body.equation[currency=CHF].assets")),
                List.of(new com.finapp.accounting.RegulatoryObligationConfiguration.Rule("R", com.finapp.accounting.RegulatoryRuleType.NON_NEGATIVE, List.of("ONLY"), List.of()))));
        assertThatThrownBy(() -> as(PREPARER, () -> desk.draft("f-1", broken.value().toString(), january, null, REASON))).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.REGULATORY_FIGURE_UNRESOLVED);
            assertThat(refused.clientDetail().orElse("")).contains("ONLY").doesNotContain("10000");
        });
        assertThat(count("figure_unresolved")).isEqualTo(1);

        RegulatoryObligationId current = world.active(3, RegulatoryWorld.configuration());
        world.runs.corruptContentForTest(world.runs.all().get(0).id(), "{\"body\":{}}");
        refused(() -> as(PREPARER, () -> desk.draft("f-2", current.value().toString(), january, null, REASON)), AccountingErrorCode.REGULATORY_RUN_BYTES_DIFFER);
        assertThat(count("run_bytes_differ")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the acts

    @Test
    @DisplayName("the validation door: passing moves it on; failing stays DRAFT, the receipt says so, and is counted; an unknown id is not found")
    void theValidationDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt validated = validated();
        assertThat(validated.status()).isEqualTo("VALIDATED");
        assertThat(validated.validationPassed()).isTrue();
        assertThat(count("validated")).isEqualTo(1);
        refused(() -> as(PREPARER, () -> desk.validate("v-2", "not-a-uuid")), AccountingErrorCode.NOT_FOUND);
        refused(() -> as(PREPARER, () -> desk.validate("v-3", RegulatorySubmissionId.next(world.ids).value().toString())), AccountingErrorCode.NOT_FOUND);

        as(APPROVER, () -> desk.refuse("r-1", validated.submissionId(), REASON));
        world.reopened(JANUARY);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(100_00, 99_00, 50_00, 50_00));
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt unbalanced = draft("d-9");
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt failed = as(PREPARER, () -> desk.validate("v-4", unbalanced.submissionId()));
        assertThat(failed.status()).isEqualTo("DRAFT");
        assertThat(failed.validationPassed()).isFalse();
        assertThat(count("validation_failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("the approval door: a second person naming what they read; the proposer refused 403, recorded FAILED in its own transaction, the row untouched; a differing hash or revision Stale")
    void theApprovalDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt validated = validated();

        refused(() -> as(PREPARER, () -> desk.approve("a-1", validated.submissionId(), validated.contentSha256(), validated.periodRevision(), REASON)), AccountingErrorCode.SELF_APPROVAL_REFUSED);
        AuditRecord refusal = world.audit.get(world.audit.size() - 1);
        assertThat(refusal.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
        assertThat(refusal.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(count("self_approval_refused")).isEqualTo(1);

        refused(() -> as(APPROVER, () -> desk.approve("a-2", validated.submissionId(), "0".repeat(64), validated.periodRevision(), REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_STALE);
        refused(() -> as(APPROVER, () -> desk.approve("a-3", validated.submissionId(), validated.contentSha256(), 9, REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_STALE);
        assertThat(count("stale")).isEqualTo(2);

        RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved = as(APPROVER, () -> desk.approve("a-4", validated.submissionId(), validated.contentSha256(), validated.periodRevision(), REASON));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(count("approved")).isEqualTo(1);
    }

    @Test
    @DisplayName("the refusal door: a controller's, on a validated or approved return; the receipt says who it was, never the provider; a DRAFT refused Stale")
    void theRefusalDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt drafted = draft("d-1");
        refused(() -> as(APPROVER, () -> desk.refuse("r-0", drafted.submissionId(), REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_STALE);
        as(PREPARER, () -> desk.validate("v-1", drafted.submissionId()));

        RegulatorySubmissionDesk.RegulatorySubmissionReceipt done = as(APPROVER, () -> desk.refuse("r-1", drafted.submissionId(), REASON));
        assertThat(done.status()).isEqualTo("REFUSED");
        assertThat(done.statusMeaning()).contains("controller").doesNotContain("provider");
        assertThat(count("refused")).isEqualTo(1);
        refused(() -> as(APPROVER, () -> desk.refuse("r-2", drafted.submissionId(), " ")), AccountingErrorCode.REASON_REQUIRED);
    }

    @Test
    @DisplayName("the withdrawal door: a preparer's, until it is sent; frees the month; what was sent refuses")
    void theWithdrawalDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt drafted = draft("d-1");
        assertThat(as(PREPARER, () -> desk.withdraw("w-1", drafted.submissionId(), REASON)).status()).isEqualTo("WITHDRAWN");
        assertThat(count("withdrawn")).isEqualTo(1);
        assertThat(as(PREPARER, () -> desk.withdraw("w-2", drafted.submissionId(), REASON)).status()).as("the same person again converges beneath a new key").isEqualTo("WITHDRAWN");
        assertThat(count("withdrawn")).as("and is not counted again").isEqualTo(1);
        assertThat(world.store.history().stream().filter(step -> step.to() == com.finapp.accounting.SubmissionStatus.WITHDRAWN)).as("nor moved twice").hasSize(1);

        assertThat(draft("d-2").status()).as("the month is free").isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("the withdrawal door: what was sent is not a preparer's to take back")
    void aSentReturnCannotBeWithdrawn() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved = approved();
        as(SENDER, () -> desk.submit("s-1", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON));
        refused(() -> as(PREPARER, () -> desk.withdraw("w-1", approved.submissionId(), REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_STALE);
    }

    @Test
    @DisplayName("the sending door: SUBMITTED committed, then driven on no transaction; a replay claims nothing new and sends nothing twice; the approver refused; the month moved Stale")
    void theSendingDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved = approved();

        refused(() -> as(APPROVER, () -> desk.submit("s-0", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON)), AccountingErrorCode.SELF_APPROVAL_REFUSED);

        RegulatorySubmissionDesk.RegulatorySubmissionReceipt sent = as(SENDER, () -> desk.submit("s-1", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON));
        assertThat(sent.status()).as("the door's own drive answered").isEqualTo("ACKNOWLEDGED");
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(count("submitted")).isEqualTo(1);
        assertThat(count("acknowledged")).isEqualTo(1);

        as(SENDER, () -> desk.submit("s-1", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON));
        assertThat(world.provider.submits()).as("the key replayed, the drive found nothing due").isEqualTo(1);
        assertThat(count("submitted")).isEqualTo(1);
    }

    @Test
    @DisplayName("a reopen between the attestation and the sending: the door refuses, Stale, nothing sent")
    void aReopenBeforeTheSendingRefuses() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved = approved();
        world.reopened(JANUARY);
        refused(() -> as(SENDER, () -> desk.submit("s-1", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_STALE);
        assertThat(world.provider.submits()).isZero();
    }

    @Test
    @DisplayName("the non-delivery door: refused until the provider definitely holds nothing and the resends are spent; then a person concludes it")
    void theNonDeliveryDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved = approved();
        world.provider.nextSubmit(() -> Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8)));
        as(SENDER, () -> desk.submit("s-1", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON));
        refused(() -> as(SENDER, () -> desk.declareUndelivered("n-1", approved.submissionId(), REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_STALE);

        RegulatorySubmissionId id = RegulatorySubmissionId.of(java.util.UUID.fromString(approved.submissionId()));
        world.store.await(UOW, id, 1, Duration.ofMinutes(15), "NOT_RECEIVED", Optional.empty());
        world.store.horizonPassedForTest(id);
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt done = as(SENDER, () -> desk.declareUndelivered("n-2", approved.submissionId(), REASON));
        assertThat(done.status()).isEqualTo("UNDELIVERED");
        assertThat(count("undelivered")).isEqualTo(1);
        assertThat(done.statusMeaning()).contains("A person concluded");
    }

    // ------------------------------------------------------------------ the reads

    @Test
    @DisplayName("a reporter's read: the draft and its figures under their own permission, audited before it is served; each line traced to the run's figure; no preparer's reason")
    void aReportersRead() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt drafted = draft("d-1");
        world.audit.clear();
        RegulatorySubmissionDesk.RegulatorySubmissionView view = as(PREPARER, () -> desk.read(drafted.submissionId()));

        assertThat(view.standing()).isEqualTo("REFERENCE");
        assertThat(view.notice()).contains("reference").doesNotContainIgnoringCase("complian");
        assertThat(view.lines()).hasSize(4).allSatisfy(line -> {
            assertThat(line.lineage().agreesWithTheRun()).isTrue();
            assertThat(line.lineage().runStatus()).isEqualTo("FINAL");
            assertThat(line.lineage().runFigureMinorUnits()).isEqualTo(line.minorUnits());
            assertThat(line.lineage().figurePath()).startsWith("body.equation[currency=" + line.currency() + "]");
        });
        assertThat(view.lines().get(0).minorUnits()).isEqualTo("10000");
        assertThat(view.preparationReason()).isNull();
        assertThat(view.monthMoved()).isFalse();
        assertThat(view.stillStands()).isTrue();
        assertThat(view.toString()).doesNotContain("10000").doesNotContain(REASON);
        assertThat(view.lines().get(0).toString()).doesNotContain("10000");
        assertThat(view.lines().get(0).lineage().toString()).doesNotContain("10000");
        AuditRecord read = world.audit.get(0);
        assertThat(read.operation().code()).isEqualTo("regulatory.SubmissionRead");
        assertThat(read.changeSummary().orElseThrow()).contains("door=preparer").doesNotContain("10000");
        assertThat(read.actor()).isEqualTo(PREPARER);
        refused(() -> as(PREPARER, () -> desk.read("not-a-uuid")), AccountingErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("an approver's read: what they attest under their own door - lines, lineage, validation, the obligation version and the preparer's reason - audited")
    void anApproversRead() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt validated = validated();
        world.audit.clear();
        RegulatorySubmissionDesk.RegulatorySubmissionView view = as(APPROVER, () -> desk.attestation(validated.submissionId()));

        assertThat(view.preparationReason()).isEqualTo(REASON);
        assertThat(view.validation().passed()).isTrue();
        assertThat(view.validation().findings()).isNotEmpty();
        assertThat(view.obligationId()).isEqualTo(obligation.value().toString());
        assertThat(view.obligationStatus()).isEqualTo("ACTIVE");
        assertThat(view.formVersion()).isEqualTo(1);
        assertThat(view.contentSha256()).isEqualTo(validated.contentSha256());
        assertThat(world.audit.get(0).changeSummary().orElseThrow()).contains("door=approver").doesNotContain(REASON);
    }

    @Test
    @DisplayName("what each role can open is said truly: the drill-down names a route that exists, its real permission and the roles that really hold it; neither reader's role is among them")
    void whatARoleCanOpenIsSaidTruly() throws Exception {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt drafted = draft("d-1");
        RegulatorySubmissionDesk.RegulatoryDrillDownView drill = as(PREPARER, () -> desk.read(drafted.submissionId())).lines().get(0).lineage().drillDown();

        assertThat(drill.requiresPermission()).isEqualTo("ACCOUNTING_READ");
        assertThat(drill.heldByRoles()).contains("ACCOUNTANT").doesNotContain(RoleName.REGULATORY_REPORTER.name(), RoleName.FINANCIAL_CONTROLLER.name());
        assertThat(RoleName.REGULATORY_REPORTER.permissions()).doesNotContain(PermissionName.ACCOUNTING_READ);
        assertThat(RoleName.FINANCIAL_CONTROLLER.permissions()).doesNotContain(PermissionName.ACCOUNTING_READ);
        assertThat(drill.route()).startsWith("GET /v1/operator/accounting/report-runs/").doesNotContain("/lines");
        assertThat(drill.note()).contains("ACCOUNTING_READ");

        // The two routes named are the routes that exist, under the permission named: a note that lies is the review's finding.
        Method run = ReportRunController.class.getMethod("readReportRun", String.class);
        assertThat(run.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ACCOUNTING_READ);
        assertThat(ReportRunController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).path()).containsExactly("/operator/accounting/report-runs");
        assertThat(run.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).path()).containsExactly("/{runId}");
        Method lines = ReportRunLinesController.class.getMethod("readReportRunLines", String.class, String.class, String.class, String.class, String.class, Integer.class);
        assertThat(lines.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ACCOUNTING_READ);
        assertThat(lines.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value()).containsExactly("/{runId}/lines");

        com.finapp.accounting.RegulatoryFormLine byAccount = new com.finapp.accounting.RegulatoryFormLine(1, "CASH", "Cash", "EUR", java.math.BigInteger.ONE, 2, com.finapp.accounting.ReportRunId.next(world.ids), "h", "BALANCE_SHEET",
                "body.sections[section=CASH_AND_BANK].rows[glAccount=1000,currency=EUR].closing");
        RegulatorySubmissionStore.Row row = world.store.find(UOW, RegulatorySubmissionId.of(java.util.UUID.fromString(drafted.submissionId()))).orElseThrow();
        assertThat(RegulatorySubmissionDesk.drillDown(byAccount, row).route()).endsWith("/lines?account=1000&currency=EUR&month=" + JANUARY);
    }

    @Test
    @DisplayName("a month reopened after a return was sent: every read says so, with both revisions, and the preparer's list carries it until a correction stands")
    void aReopenedMonthIsToldOnEveryRead() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved = approved();
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt sent = as(SENDER, () -> desk.submit("s-1", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON));
        assertThat(sent.monthMoved()).isFalse();
        world.reopened(JANUARY);

        RegulatorySubmissionDesk.RegulatorySubmissionView view = as(PREPARER, () -> desk.read(sent.submissionId()));
        assertThat(view.monthMoved()).isTrue();
        assertThat(view.periodRevision()).isEqualTo(1);
        assertThat(view.periodRevisionNow()).isEqualTo(2);
        assertThat(view.periodStatusNow()).isEqualTo("OPEN");
        assertThat(view.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(view.lines()).allSatisfy(line -> {
            assertThat(line.lineage().runStatus()).isEqualTo("SUPERSEDED");
            assertThat(line.lineage().agreesWithTheRun()).as("the run's bytes are retained: the return still traces to what it read").isTrue();
        });
        assertThat(as(PREPARER, desk::standing)).singleElement().satisfies(summary -> {
            assertThat(summary.monthMoved()).isTrue();
            assertThat(summary.submissionId()).isEqualTo(sent.submissionId());
        });
        assertThat(as(APPROVER, () -> desk.attestation(sent.submissionId())).monthMoved()).isTrue();
    }

    @Test
    @DisplayName("the provider's answer is served as data: base64, its hash, what was read off it; a read of the provider's text never logs it, and an untrusted body stays bytes")
    void theProvidersAnswerIsServedAsData() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt approved = approved();
        world.provider.nextSubmit(() -> ScriptedRegulatoryProvider.answering(com.finapp.accounting.RegulatoryReportingProvider.Outcome.UNKNOWN, Optional.empty(), Optional.empty(), "<script>alert('x')</script>"));
        as(SENDER, () -> desk.submit("s-1", approved.submissionId(), approved.contentSha256(), approved.periodRevision(), REASON));

        RegulatorySubmissionDesk.RegulatorySubmissionView view = as(PREPARER, () -> desk.read(approved.submissionId()));
        RegulatorySubmissionDesk.RegulatoryProviderAnswerView answer = view.answers().get(0);
        assertThat(answer.rawBase64()).isEqualTo(Base64.getEncoder().encodeToString("<script>alert('x')</script>".getBytes(StandardCharsets.UTF_8)));
        assertThat(answer.toString()).doesNotContain("script");
        assertThat(answer.readOutcome()).isEqualTo("UNKNOWN");
        assertThat(answer.truncated()).isFalse();
        assertThat(world.audit.get(world.audit.size() - 1).changeSummary().orElseThrow()).doesNotContain("script");
    }

    @Test
    @DisplayName("a controller's pending list holds what they can decide and never their own, and is audited; the preparer's list is theirs; every list carries no figure")
    void theLists() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt validated = validated();

        assertThat(as(APPROVER, desk::awaitingApproval)).extracting(RegulatorySubmissionDesk.RegulatorySubmissionSummaryView::submissionId).containsExactly(validated.submissionId());
        assertThat(as(PREPARER, desk::awaitingApproval)).isEmpty();
        assertThat(as(PREPARER, desk::standing)).extracting(RegulatorySubmissionDesk.RegulatorySubmissionSummaryView::status).containsExactly("VALIDATED");
        assertThat(world.audit.stream().map(record -> record.changeSummary().orElse(""))).anyMatch(summary -> summary.contains("door=awaiting-approval")).anyMatch(summary -> summary.contains("door=standing"));
        assertThat(as(APPROVER, desk::awaitingApproval).get(0).toString()).doesNotContain("10000");
    }

    @Test
    @DisplayName("the reference export door: what it says of itself first, audited, under the preparer's permission; an unknown id not found")
    void theReferenceExportDoor() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt drafted = draft("d-1");
        world.audit.clear();
        RegulatorySubmissionDesk.RegulatoryReferenceExportView export = as(PREPARER, () -> desk.referenceExport(drafted.submissionId()));
        assertThat(export.standing()).isEqualTo("REFERENCE");
        assertThat(export.channel()).isEqualTo("SIMULATED");
        assertThat(export.toString()).doesNotContain("10000");
        assertThat(world.audit.get(0).changeSummary().orElseThrow()).contains("door=reference-export");
        refused(() -> as(PREPARER, () -> desk.referenceExport(RegulatorySubmissionId.next(world.ids).value().toString())), AccountingErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a refused act stores nothing: the key is free, and nothing of it is left")
    void aRefusedActStoresNothing() {
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt validated = validated();
        refused(() -> as(APPROVER, () -> desk.approve("k", validated.submissionId(), "0".repeat(64), 1, REASON)), AccountingErrorCode.REGULATORY_SUBMISSION_STALE);
        RegulatorySubmissionDesk.RegulatorySubmissionReceipt ok = as(APPROVER, () -> desk.approve("k2", validated.submissionId(), validated.contentSha256(), 1, REASON));
        assertThat(ok.status()).isEqualTo(SubmissionStatus.APPROVED.name());
    }

    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint, CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState, StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response, held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
