package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tipping-off control at the event sink (`P13-TSK-018`; {@code INV-RSK-08}, {@code INV-AUD-02};
 * {@code PHASE_13_PLAN.md} sections 10, 11): <strong>every {@code risk} event carries ids, codes, statuses, kinds and
 * counts - no free text, no amount, no score, no name</strong>. A schema per event type names each field it may carry
 * and the shape of its value: an identifier (a canonical UUID; a subject's reference is a UUID or a device's 64-hex
 * hash), a code of one named closed vocabulary, a count. A field outside its type's schema - a reason text, a note, an
 * amount, a rule's parameter - or a value outside its shape fails here.
 *
 * <p><strong>Not vacuous, two ways.</strong> The event types are read from {@code risk}'s own classes (every
 * {@code static final String} event-type constant naming a {@code risk.} event), so a new event without a schema
 * fails; and every declared type is <em>emitted</em> here by its real producer over the hermetic worlds - T-sig,
 * T-eval, the policy's activation, the alert's raise, the case's openings, escalation and disposal, the profile's
 * prohibition, the action's request and conclusion - and its payloads judged, so a schema that described nothing would
 * fail the coverage assertion.
 */
@DisplayName("every risk event carries only ids, codes, statuses, kinds and counts (P13-TSK-018, INV-RSK-08)")
class RiskEventsCarryOnlyCodesTest {

    /** The shape of one field's value. */
    enum Shape {
        /** A canonical UUID. */
        ID(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
        /** A subject's reference: a canonical UUID, or a device's SHA-256 (64 hex, OQ-4). */
        SUBJECT_REF(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64}")),
        /** A judged command's token - {@link CommandRef#SHAPE}. */
        COMMAND_REF(CommandRef.SHAPE),
        /** A count or an ordinal: a small non-negative whole number. */
        COUNT(Pattern.compile("[0-9]{1,6}")),
        /** A code of a closed vocabulary - which one is the field's. */
        CODE(Pattern.compile("[A-Z][A-Z0-9_]{0,63}")),
        /** A regulatory report's jurisdiction code - {@link ReportContent#JURISDICTION} (`P13-TSK-021`). */
        JURISDICTION(ReportContent.JURISDICTION);

        final Pattern pattern;

        Shape(Pattern pattern) {
            this.pattern = pattern;
        }
    }

    /** One field: its shape and, for a code, the vocabulary its value is one of. */
    record FieldShape(Shape shape, Class<? extends Enum<?>> vocabulary) {
        static FieldShape id() {
            return new FieldShape(Shape.ID, null);
        }

        static FieldShape count() {
            return new FieldShape(Shape.COUNT, null);
        }

        static FieldShape code(Class<? extends Enum<?>> vocabulary) {
            return new FieldShape(Shape.CODE, vocabulary);
        }

        boolean admits(String value) {
            if (!shape.pattern.matcher(value).matches()) {
                return false;
            }
            if (vocabulary == null) {
                return true;
            }
            for (Enum<?> constant : vocabulary.getEnumConstants()) {
                if (constant.name().equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code risk.RiskDecisionMade}'s ordered reason codes: {@code reason1} … */
    private static final Pattern REASON_FIELD = Pattern.compile("reason[1-9][0-9]?");

    /** Each event type's schema - every field it may carry and its shape. A new field is a deliberate edit here. */
    static final Map<String, Map<String, FieldShape>> SCHEMAS = schemas();

    private static Map<String, Map<String, FieldShape>> schemas() {
        Map<String, Map<String, FieldShape>> schemas = new LinkedHashMap<>();
        schemas.put(RiskSignals.EVENT_TYPE, Map.of("signalId", FieldShape.id(), "kind", FieldShape.code(SignalKind.class),
                "subjectKind", FieldShape.code(SubjectKind.class), "subjectRef", new FieldShape(Shape.SUBJECT_REF, null)));
        schemas.put(RiskEvaluation.DECISION_MADE_EVENT, Map.of("assessmentId", FieldShape.id(), "operation",
                FieldShape.code(RiskOperation.class), "commandRef", new FieldShape(Shape.COMMAND_REF, null), "decision",
                FieldShape.code(RiskDecision.class), "reasonCount", FieldShape.count()));
        schemas.put(RiskEvaluation.LIMIT_BREACHED_EVENT, Map.of("assessmentId", FieldShape.id(), "operation",
                FieldShape.code(RiskOperation.class), "ruleCode", FieldShape.code(ReasonCode.class), "ruleOrdinal",
                FieldShape.count()));
        schemas.put(RiskPolicyAdministration.ACTIVATED_EVENT, Map.of("operation", FieldShape.code(RiskOperation.class),
                "version", FieldShape.count()));
        schemas.put(ProfileRecompute.EVENT_TYPE, Map.of("party", FieldShape.id(), "rating",
                FieldShape.code(RiskRating.class), "version", FieldShape.count()));
        schemas.put(Alerts.EVENT_TYPE, Map.of("alertId", FieldShape.id(), "kind", FieldShape.code(AlertKind.class),
                "severity", FieldShape.code(AlertSeverity.class), "subjectKind", FieldShape.code(SubjectKind.class),
                "subjectRef", new FieldShape(Shape.SUBJECT_REF, null)));
        Map<String, FieldShape> theCase = Map.of("caseId", FieldShape.id(), "kind", FieldShape.code(CaseKind.class),
                "status", FieldShape.code(CaseStatus.class), "disposition", FieldShape.code(Disposition.class));
        schemas.put(Cases.OPENED_EVENT, theCase);
        schemas.put(Cases.ESCALATED_EVENT, theCase);
        schemas.put(Cases.DISPOSED_EVENT, theCase);
        Map<String, FieldShape> theAction = Map.of("actionId", FieldShape.id(), "source",
                FieldShape.code(ActionSource.class), "caseId", FieldShape.id(), "overrideId", FieldShape.id(), "kind",
                FieldShape.code(ActionKind.class), "targetKind", FieldShape.code(ActionTarget.class), "targetId",
                FieldShape.id(), "status", FieldShape.code(ActionStatus.class), "outcome",
                FieldShape.code(ActionOutcome.class));
        schemas.put(RiskActions.REQUESTED_EVENT, theAction);
        schemas.put(RiskActions.COMPLETED_EVENT, theAction);
        // P13-TSK-021: the filing names the report and its jurisdiction - never the subject, an account, a transaction,
        // a ground or the narrative (PHASE_13_PLAN.md section 10).
        schemas.put(RegulatoryReports.FILED_EVENT, Map.of("reportId", FieldShape.id(), "jurisdiction",
                new FieldShape(Shape.JURISDICTION, null)));
        return schemas;
    }

    /** What every producer emitted here, in order: the type and the payload's fields. */
    private final List<Map.Entry<String, Map<String, String>>> emitted = new ArrayList<>();

    @Test
    @DisplayName("every event type risk declares has a schema, and every schema is a type risk declares")
    void everyDeclaredEventTypeHasASchema() {
        assertThat(declaredEventTypes()).as("the guard is not vacuous").hasSizeGreaterThanOrEqualTo(11)
                .containsExactlyInAnyOrderElementsOf(SCHEMAS.keySet());
    }

    @Test
    @DisplayName("every risk event, emitted by its real producer, carries only its schema's fields, each of its shape -"
            + " and every declared type is among them")
    void everyEventCarriesOnlyCodes() {
        emitEveryEvent();
        Set<String> seen = new TreeSet<>();
        for (Map.Entry<String, Map<String, String>> event : emitted) {
            seen.add(event.getKey());
            assertThat(violations(event.getKey(), event.getValue())).as("%s %s", event.getKey(), event.getValue())
                    .isEmpty();
        }
        assertThat(seen).as("every declared event type was emitted and judged").containsAll(SCHEMAS.keySet());
    }

    @Test
    @DisplayName("the judgement has teeth: a reason text, an amount, a score, a name and an unknown code are each"
            + " refused")
    void theJudgementHasTeeth() {
        Map<String, String> disposed = Map.of("caseId", UUID.randomUUID().toString(), "kind", "FRAUD", "status",
                "DISPOSED", "disposition", "CONFIRMED_FRAUD");
        assertThat(violations(Cases.DISPOSED_EVENT, disposed)).isEmpty();
        assertThat(violations(Cases.DISPOSED_EVENT, with(disposed, "reason", "mule-account-confirmed")))
                .as("a reason text").isNotEmpty();
        assertThat(violations(Cases.DISPOSED_EVENT, with(disposed, "amountMinor", "1000000"))).as("an amount")
                .isNotEmpty();
        assertThat(violations(Cases.DISPOSED_EVENT, with(disposed, "score", "870"))).as("a score").isNotEmpty();
        assertThat(violations(Cases.DISPOSED_EVENT, with(disposed, "kind", "Ada_Lovelace"))).as("a name in a code")
                .isNotEmpty();
        assertThat(violations(Cases.DISPOSED_EVENT, with(disposed, "disposition", "CLOSED_SOMEHOW")))
                .as("a code outside the vocabulary").isNotEmpty();
        assertThat(violations("risk.SomethingNew", disposed)).as("a type without a schema").isNotEmpty();
        Map<String, String> filed = Map.of("reportId", UUID.randomUUID().toString(), "jurisdiction", "EU-REF");
        assertThat(violations(RegulatoryReports.FILED_EVENT, filed)).isEmpty();
        assertThat(violations(RegulatoryReports.FILED_EVENT, with(filed, "subjectRef", UUID.randomUUID().toString())))
                .as("the report's subject (P13-TSK-021)").isNotEmpty();
        assertThat(violations(RegulatoryReports.FILED_EVENT, with(filed, "jurisdiction", "the EU"))).isNotEmpty();
    }

    // ------------------------------------------------------------------ the judgement

    static List<String> violations(String type, Map<String, String> payload) {
        Map<String, FieldShape> schema = SCHEMAS.get(type);
        if (schema == null) {
            return List.of(type + " has no schema");
        }
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> field : payload.entrySet()) {
            FieldShape shape = type.equals(RiskEvaluation.DECISION_MADE_EVENT) && REASON_FIELD.matcher(field.getKey())
                    .matches() ? FieldShape.code(ReasonCode.class) : schema.get(field.getKey());
            if (shape == null) {
                violations.add(field.getKey() + " is not a field of " + type);
            } else if (!shape.admits(field.getValue())) {
                violations.add(field.getKey() + "=" + field.getValue() + " is not a " + shape);
            }
        }
        return violations;
    }

    /**
     * Every {@code risk.} event type a {@code static final String} constant of risk's production classes declares - the
     * classes read from the directory the production {@code com.finapp.risk} package was compiled into.
     */
    static Set<String> declaredEventTypes() {
        Set<String> types = new TreeSet<>();
        for (Class<?> type : productionClasses()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.getType() != String.class
                        || !(field.getName().equals("EVENT_TYPE") || field.getName().endsWith("_EVENT"))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    if (value.matches("risk\\.[A-Z][A-Za-z]+")) {
                        types.add(value);
                    }
                } catch (IllegalAccessException unreadable) {
                    throw new IllegalStateException(unreadable);
                }
            }
        }
        return types;
    }

    /**
     * The production classes of {@code com.finapp.risk}, from where {@link RiskAuditAction} was loaded - the module's jar
     * on the test classpath, or its classes directory.
     */
    private static List<Class<?>> productionClasses() {
        try {
            Path location = Path.of(RiskAuditAction.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(location)) {
                return classesUnder(location);
            }
            try (FileSystem jar = FileSystems.newFileSystem(location)) {
                return classesUnder(jar.getPath("/"));
            }
        } catch (URISyntaxException | IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static List<Class<?>> classesUnder(Path root) throws IOException {
        Path pkg = root.resolve("com").resolve("finapp").resolve("risk");
        assertThat(Files.isDirectory(pkg)).as("risk's production package at %s", pkg).isTrue();
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(pkg)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".class")
                    && !path.getFileName().toString().startsWith("package-info")).toList()) {
                String simple = file.getFileName().toString().replace(".class", "");
                try {
                    classes.add(Class.forName("com.finapp.risk." + simple));
                } catch (ClassNotFoundException absent) {
                    throw new IllegalStateException(absent);
                }
            }
        }
        assertThat(classes).as("the guard reads risk's classes").hasSizeGreaterThan(100);
        return classes;
    }

    private static Map<String, String> with(Map<String, String> payload, String field, String value) {
        Map<String, String> changed = new LinkedHashMap<>(payload);
        changed.put(field, value);
        return changed;
    }

    // ------------------------------------------------------------------ the producers, over the hermetic worlds

    private void emitEveryEvent() {
        OutboxWriter<Connection> recording = (unitOfWork, envelope, payload, mediaType) ->
                emitted.add(Map.entry(envelope.eventType(), EventPayload.fields(payload)));

        // T-sig: a signal born.
        new RiskSignals(new BornSignals(), recording, EvaluationWorld.IDS, EvaluationWorld.CLOCK).record(null,
                new RiskSignal(RiskSignalId.next(EvaluationWorld.IDS), SignalKind.ACCOUNT_OPENED,
                        SignalSubject.of(SubjectKind.ACCOUNT, UUID.randomUUID()),
                        new RiskSignal.SourceEvent(EventId.next(EvaluationWorld.IDS), "accounts.AccountOpened", 1,
                                "accounts", CorrelationId.of("events-suite"), CausationId.of("events-suite")),
                        Instant.parse("2026-10-25T11:59:00Z"),
                        SignalAttributes.of(SignalKind.ACCOUNT_OPENED, Map.of("product", "WALLET"))));

        // T-eval: a DENY with its LimitBreached.
        EvaluationWorld evaluation = new EvaluationWorld();
        evaluation.activate(RiskOperation.TRANSFER);
        evaluation.evaluation.evaluate(EvaluationWorld.UOW, EvaluationWorld.transfer(EvaluationWorld.eur(10_000_00)));
        for (int i = 0; i < evaluation.envelopes.size(); i++) {
            emitted.add(Map.entry(evaluation.envelopes.get(i).eventType(), evaluation.payloads.get(i)));
        }

        // The policy's activation.
        RiskPolicyAdministration administration = new RiskPolicyAdministration(
                new RiskPolicyAdministrationTest.FakeRiskPolicyStore(), (AuditWriter<Connection>) (uow, record) -> { },
                recording, EvaluationWorld.IDS, EvaluationWorld.CLOCK);
        RiskPolicyAdministration.Proposed proposed = administration.propose(EvaluationWorld.UOW,
                RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL), "the events suite's version",
                new Actor("alice", ActorType.EMPLOYEE), CorrelationId.of("events-suite"));
        administration.activate(EvaluationWorld.UOW, proposed.id(), new Actor("bob", ActorType.EMPLOYEE), "activate",
                CorrelationId.of("events-suite"));

        // The alert raised and folded (AlertRaised, CaseOpened), the case escalated, disposed prohibiting, and the
        // profile's prohibition applied (CustomerRiskRatingChanged).
        CaseWorld cases = new CaseWorld();
        UUID party = EvaluationWorld.IDS.next();
        Alerts.Raised raised = cases.raiseAto(CaseWorld.party(party), RuleKind.RECENT_CREDENTIAL_CHANGE);
        RiskCaseId caseId = raised.folded().caseId().orElseThrow();
        Actor alice = cases.analyst();
        Actor bob = cases.analyst();
        RiskCase taken = cases.taken(cases.caseStore.find(CaseWorld.UOW, caseId).orElseThrow(), alice);
        RiskCase escalated = cases.cases.escalate(CaseWorld.UOW, caseId, taken.revision(), "SECOND_LINE",
                "a second line's look", alice, CaseWorld.correlation()).riskCase();
        Cases.Decided disposition = cases.cases.proposeDisposition(CaseWorld.UOW, caseId, escalated.revision(),
                "CONFIRMED_FRAUD", true, "the takeover is confirmed", alice, CaseWorld.correlation());
        cases.cases.approveDisposition(CaseWorld.UOW, caseId, disposition.dispositionId(),
                disposition.riskCase().revision(), "agreed", bob, CaseWorld.correlation());
        cases.recompute.applyDisposition(CaseWorld.UOW, party, disposition.dispositionId(), CaseWorld.correlation());
        for (int i = 0; i < cases.events.size(); i++) {
            emitted.add(Map.entry(cases.events.get(i).eventType(), cases.payloads.get(i)));
        }

        // The action requested on its approval and concluded by T-act.
        OverrideWorld actions = new OverrideWorld();
        OverrideWorld.Taken partyCase = actions.partyCase(CaseKind.FRAUD);
        RiskAction action = actions.propose(partyCase, ActionKind.REVOKE_SESSIONS, Optional.empty()).action();
        actions.approve(action, actions.analyst());
        actions.drive(action.id());
        for (int i = 0; i < actions.events.size(); i++) {
            emitted.add(Map.entry(actions.events.get(i).eventType(), actions.payloads.get(i)));
        }

        // The regulatory report filed (P13-TSK-021) - and driven to its acknowledgement, which announces nothing.
        ReportWorld reports = new ReportWorld();
        RegulatoryReport filed = reports.filed(reports.confirmedSuspicious());
        reports.drive(filed.id());
        for (int i = 0; i < reports.events.size(); i++) {
            emitted.add(Map.entry(reports.events.get(i).eventType(), reports.payloads.get(i)));
        }
    }

    /** A signal store whose every insert is a birth. */
    private static final class BornSignals implements RiskSignalStore {
        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            return true;
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            throw new UnsupportedOperationException();
        }
    }
}
