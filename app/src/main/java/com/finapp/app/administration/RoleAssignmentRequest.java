package com.finapp.app.administration;

import com.finapp.identity.RoleName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/identities/&#123;id&#125;/roles}.
 *
 * <h2>The role is the enum, not a string, and the boundary is where that is decided</h2>
 *
 * <p>Jackson refuses a value outside {@link RoleName} before the handler is entered, so an unknown
 * role is {@code api.MalformedRequest} rather than an exception from
 * {@code RoleName.valueOf} rendered {@code api.InternalError}. It also puts the permitted values
 * into the published contract, so a client generator produces an enum rather than a free string.
 *
 * <p><em>(This javadoc said there was exactly one role, and that inventing a second so a mutation
 * had somewhere to land would be a surface chosen to suit a test. {@code P2-TSK-004} added the
 * second — {@code KYC_REVIEWER} — because a real population arrived, which is the difference; the
 * mutation it made possible is that task's acceptance criterion.)</em>
 *
 * @param role the role to grant
 * @param reason why. This is the most consequential action the module has — see
 *     {@code IdentityAuditAction.IDENTITY_ROLE_ASSIGNED}
 */
public record RoleAssignmentRequest(
        @NotNull RoleName role,
        @NotBlank
                @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                String reason) {}
