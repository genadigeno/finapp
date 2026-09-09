package com.finapp.app.credential;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/me/credential} (`P1-TSK-033`).
 *
 * <h2>Two secrets, both wrapped, bounds not declared here</h2>
 *
 * <p>Both fields are {@code Sensitive<String>} for {@code RegistrationRequest}'s reason: a record's
 * generated {@code toString} prints every component, so an unwrapped password field would put a
 * plaintext one careless {@code log.info("{}", request)} away from disclosure ({@code
 * secretsAreWrapped} enforces the wrapping). Length bounds are <strong>not</strong> declared as
 * annotations, because Bean Validation cannot see inside {@code Sensitive} and a constraint that
 * unwrapped it would put a plaintext in {@code app} — which {@code SecretsAreUnwrappedInOnePlaceTest}
 * fails the build over. {@code RawPassword} enforces the length in {@code identity}, and the
 * application service maps its refusal to a {@code 422} naming the field.
 *
 * @param currentPassword the password being replaced, re-proven before anything changes
 * @param newPassword the replacement
 */
public record ChangePasswordRequest(
        @NotNull Sensitive<String> currentPassword, @NotNull Sensitive<String> newPassword) {}
