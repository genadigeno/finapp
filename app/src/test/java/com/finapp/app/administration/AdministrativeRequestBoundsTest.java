package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary's bounds are the record's bounds (`P1-TSK-028`).
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@code SuspensionRequest}'s javadoc said <em>"a test asserts they still agree"</em> and no such
 * test existed — <strong>the seventh occurrence of that pattern in this phase</strong>, after
 * {@code V005}'s enum claim, {@code AuthenticationRequest}'s bounds claim,
 * {@code RequiresSession}'s fail-closed claim, {@code secretsAreWrapped}'s exemption, {@code V010}'s
 * migration test and {@code AuditCompletenessTest}'s false exemption. Found by the completion gate,
 * as most of them were.
 *
 * <h2>The drift is not cosmetic</h2>
 *
 * <p>If the boundary admitted more than {@code AuditRecord} accepts, an over-long reason would pass
 * validation, reach the domain, and be refused by {@code AuditRecord.bounded} — as an
 * {@code IllegalArgumentException} rendered {@code api.InternalError}. Our fault reported for the
 * caller's input, which {@code ERROR_CONTRACT.md} §3 forbids, and it would fail at the
 * <strong>last write</strong>, after the status transition and the session revocations had already
 * been performed inside a transaction that then rolls back.
 *
 * <p>If it admitted <em>less</em>, nothing breaks — so the assertion is deliberately equality rather
 * than a bound, because a boundary quietly narrower than the record is a limit nobody chose.
 */
@DisplayName("administrative request bounds (P1-TSK-028)")
class AdministrativeRequestBoundsTest {

    @Test
    @DisplayName("the reason bound is the audit record's, exactly")
    void theReasonBoundMatchesTheAuditRecord() {
        assertThat(SuspensionRequest.REASON_MAX)
                .as("a boundary wider than the record fails at the last write, as a 500;"
                        + " one narrower is a limit nobody chose")
                .isEqualTo(AuditRecord.MAX_REASON_LENGTH);
    }

    @Test
    @DisplayName("every administrative body uses the same bound, so none can drift alone")
    void everyAdministrativeRequestSharesOneBound() throws Exception {
        // RoleAssignmentRequest and ReinstatementRequest reference SuspensionRequest's constants
        // rather than repeating them, which is what makes this assertion about a fact rather than
        // a coincidence. The annotation is read reflectively because that is what actually reaches
        // the validator - a constant they happen to share proves nothing if one of them stopped
        // using it.
        assertThat(sizeBoundOf(RoleAssignmentRequest.class, "reason"))
                .isEqualTo(sizeBoundOf(SuspensionRequest.class, "reason"));
        assertThat(sizeBoundOf(ReinstatementRequest.class, "reason"))
                .isEqualTo(sizeBoundOf(SuspensionRequest.class, "reason"));
    }

    @Test
    @DisplayName("the minimum is one, not zero, so a client generator is not told empty is valid")
    void theMinimumIsDeclared() {
        // @NotBlank already refuses whitespace, and it is invisible to the published contract:
        // springdoc renders an absent minimum as minLength 0, which tells a generated client an
        // empty string is acceptable when it is not. RegistrationRequest's recorded finding.
        assertThat(SuspensionRequest.REASON_MIN).isEqualTo(1);
    }

    /**
     * Read from the FIELD, not the record component.
     *
     * <p>The first version read {@code getRecordComponents()} and failed, which is the more useful
     * outcome: {@code @Size} does not declare {@code RECORD_COMPONENT} among its targets, so the
     * compiler propagates it to the field, the constructor parameter and the accessor - and
     * <strong>not</strong> to the component. A component-level lookup returns null for a constraint
     * that is present and working, which would have made this a test that fails on correct code.
     */
    private static int[] sizeBoundOf(Class<?> type, String component) throws Exception {
        assertThat(Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .as("%s has no component named %s", type.getSimpleName(), component)
                .contains(component);

        jakarta.validation.constraints.Size size =
                type.getDeclaredField(component)
                        .getAnnotation(jakarta.validation.constraints.Size.class);
        assertThat(size).as("%s.%s declares no @Size", type.getSimpleName(), component).isNotNull();
        return new int[] {size.min(), size.max()};
    }
}
