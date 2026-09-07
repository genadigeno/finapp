package com.finapp.app.recovery;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/me/channels}.
 *
 * <p>The address is <strong>wrapped</strong>, and that is not over-caution: it is
 * {@code RESTRICTED-PII}, and a record generated {@code toString} prints every component, so one
 * careless log call puts a customer email into an aggregator with different access control and
 * months of retention ({@code INV-AUD-02}).
 *
 * @param address where the platform may reach this person, once they prove they control it
 */
public record ContactChannelRequest(@NotNull Sensitive<String> address) {}
