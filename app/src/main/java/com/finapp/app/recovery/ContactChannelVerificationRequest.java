package com.finapp.app.recovery;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/me/channels/verification}.
 *
 * @param token proof that somebody read what was sent to the address
 */
public record ContactChannelVerificationRequest(@NotNull Sensitive<String> token) {}
