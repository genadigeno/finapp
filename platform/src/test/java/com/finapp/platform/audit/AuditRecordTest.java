package com.finapp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What may be recorded as an audited action. */
class AuditRecordTest {

    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new SecureRandom());

    @Test
    @DisplayName("every question an audit record must answer is mandatory at construction")
    void theSevenQuestionsAreMandatory() {
        // The same technique EventEnvelope uses for INV-EVT-03: a record that could not be
        // answered cannot be built. Enforcing it at construction rather than at the database
        // means the failure names the missing field instead of a constraint.
        assertThatNullPointerException().isThrownBy(() -> record(null, Actor.SYSTEM));
        assertThatNullPointerException().isThrownBy(() -> record(AuditId.next(IDS), null));
    }

    @Test
    @DisplayName("optional fields must be Optional, never null")
    void optionalsAreNotNullable() {
        // A null Optional is the one way to get a NullPointerException out of a field designed
        // to be absent, and it would surface inside the writer rather than at the caller.
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new AuditRecord(
                                        AuditId.next(IDS),
                                        Actor.SYSTEM,
                                        OCCURRED,
                                        "probe.Op",
                                        "Probe",
                                        "t1",
                                        null,
                                        AuditOutcome.SUCCEEDED,
                                        CorrelationId.of("flow"),
                                        Optional.empty()));
    }

    @Test
    @DisplayName("a blank operation, target type or target id is refused")
    void blankIdentifiersAreRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withOperation("  "));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withTarget("  ", "t1"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withTarget("Probe", "  "));
    }

    @Test
    @DisplayName("a present but blank reason is refused, so absence stays distinguishable")
    void aBlankReasonIsRefused() {
        // Optional.of("") would record "a reason was required and here it is: nothing". The
        // distinction between that and Optional.empty() is the whole value of the column.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withReason(Optional.of("   ")));

        assertThat(withReason(Optional.empty()).reason()).isEmpty();
        assertThat(withReason(Optional.of("threshold override")).reason())
                .contains("threshold override");
    }

    @Test
    @DisplayName("values at exactly the maximum length are accepted, and one beyond is not")
    void boundsAreInclusive() {
        // Asserted from both sides: an off-by-one to `>=` would reject a legal record, and
        // under ADR-0010 a rejected audit write rolls back the action it was recording - so the
        // off-by-one would not lose a log line, it would refuse a legitimate operation.
        assertThat(withOperation("o".repeat(AuditRecord.MAX_NAME_LENGTH)).operation())
                .hasSize(AuditRecord.MAX_NAME_LENGTH);
        assertThat(withReason(Optional.of("r".repeat(AuditRecord.MAX_REASON_LENGTH))).reason())
                .isPresent();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withOperation("o".repeat(AuditRecord.MAX_NAME_LENGTH + 1)));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () -> withReason(Optional.of("r".repeat(AuditRecord.MAX_REASON_LENGTH + 1))));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                withChangeSummary(
                                        Optional.of("c".repeat(AuditRecord.MAX_CHANGE_SUMMARY_LENGTH + 1))));
    }

    @Test
    @DisplayName("an actor is never defaulted when its identifier is missing")
    void anActorIsNeverInvented() {
        // Recording Actor.SYSTEM for an action whose actor was not established would make the
        // record wrong rather than absent, and a wrong attribution is worse than a missing one:
        // it is believed.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new Actor("  ", ActorType.EMPLOYEE));
        assertThatNullPointerException().isThrownBy(() -> new Actor(null, ActorType.EMPLOYEE));
        assertThatNullPointerException().isThrownBy(() -> new Actor("employee-1", null));
    }

    @Test
    @DisplayName("the system actor is a real answer, not a placeholder")
    void theSystemActorIsTyped() {
        assertThat(Actor.SYSTEM.type()).isEqualTo(ActorType.SYSTEM);
        assertThat(Actor.SYSTEM.id()).isNotBlank();
        assertThat(Actor.SYSTEM.toString()).isEqualTo("SYSTEM:system");
    }

    // -----------------------------------------------------------------

    private static AuditRecord record(AuditId id, Actor actor) {
        return new AuditRecord(
                id,
                actor,
                OCCURRED,
                "probe.Op",
                "Probe",
                "t1",
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                CorrelationId.of("flow"),
                Optional.empty());
    }

    private static AuditRecord withOperation(String operation) {
        return new AuditRecord(
                AuditId.next(IDS),
                Actor.SYSTEM,
                OCCURRED,
                operation,
                "Probe",
                "t1",
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                CorrelationId.of("flow"),
                Optional.empty());
    }

    private static AuditRecord withTarget(String targetType, String targetId) {
        return new AuditRecord(
                AuditId.next(IDS),
                Actor.SYSTEM,
                OCCURRED,
                "probe.Op",
                targetType,
                targetId,
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                CorrelationId.of("flow"),
                Optional.empty());
    }

    private static AuditRecord withReason(Optional<String> reason) {
        return new AuditRecord(
                AuditId.next(IDS),
                Actor.SYSTEM,
                OCCURRED,
                "probe.Op",
                "Probe",
                "t1",
                reason,
                AuditOutcome.SUCCEEDED,
                CorrelationId.of("flow"),
                Optional.empty());
    }

    private static AuditRecord withChangeSummary(Optional<String> changeSummary) {
        return new AuditRecord(
                AuditId.next(IDS),
                Actor.SYSTEM,
                OCCURRED,
                "probe.Op",
                "Probe",
                "t1",
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                CorrelationId.of("flow"),
                changeSummary);
    }
}
