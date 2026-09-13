package com.finapp.app.consent;

import com.finapp.consent.ConsentPurpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/me/consents}.
 *
 * <h2>The client names the version, deliberately</h2>
 *
 * <p>The obvious alternative — the server grants against whatever version is current — records a
 * consent to words the platform merely <em>hopes</em> the person saw: a client that cached the
 * text yesterday would silently consent its user to today's revision. Carrying the version the
 * client actually presented makes the grant a statement about specific words
 * ({@code INV-CNS-04}), and makes the stale-version refusal possible at all — the server cannot
 * refuse a mismatch it was never told about.
 *
 * <p>{@code purpose} is the {@code ConsentPurpose} enum, so the published contract enumerates
 * the closed vocabulary (the {@code RoleAssignmentRequest.role} precedent) and an unknown string
 * is refused by deserialisation as the caller's 4xx, never a 500. {@code textVersion} mirrors
 * the domain's own floor ({@code ConsentRecord} refuses anything below 1), so a nonsense version
 * is told at the boundary naming the field rather than thrown from the factory as our fault.
 *
 * @param purpose the processing the grant is for
 * @param textVersion the version of the consent text the person was shown
 */
public record ConsentGrantRequest(
        @NotNull ConsentPurpose purpose, @NotNull @Min(1) Integer textVersion) {}
