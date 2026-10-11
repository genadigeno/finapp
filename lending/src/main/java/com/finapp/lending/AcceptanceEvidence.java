package com.finapp.lending;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * How a customer accepted (`P11-TSK-012`; A13, ADR-0092 point 6): who - the identity and its session - at which assurance
 * level, through which channel, from which client address and user agent. What was accepted (the offer, the agreement
 * version, the terms and document hashes, the template) is bound from the records themselves, never taken from here;
 * when is the database's clock. The session, address and agent are {@code CONFIDENTIAL} and never logged.
 *
 * <p><strong>Acceptance is not consent</strong> ({@code INV-IDN-04}): this records a contractual act, and creates no
 * consent purpose.
 *
 * @param assuranceLevel the session's assurance level, by name - {@code MULTI_FACTOR} or stronger (A19)
 * @param clientIp the connecting address as the platform saw it, when it had one
 * @param userAgent the client's own description of itself, bounded, when it sent one
 */
public record AcceptanceEvidence(
        UUID identity,
        UUID session,
        String assuranceLevel,
        Optional<String> clientIp,
        Optional<String> userAgent) {

    /** The one channel Phase 11 accepts through - the customer's API. */
    public static final String CHANNEL = "API";

    /** {@code lending V006}'s bound on the agent. */
    public static final int MAX_USER_AGENT = 512;

    /** An IPv4 or IPv6 literal's alphabet - the database casts it to {@code inet} and refuses anything else. */
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    public AcceptanceEvidence {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(assuranceLevel, "assuranceLevel must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
        Objects.requireNonNull(userAgent, "userAgent must not be null");
        clientIp = clientIp.filter(address -> ADDRESS.matcher(address).matches());
        userAgent = userAgent.map(String::strip).filter(agent -> !agent.isEmpty())
                .map(agent -> agent.length() > MAX_USER_AGENT ? agent.substring(0, MAX_USER_AGENT) : agent);
    }

    /** Never the session, address or agent ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "AcceptanceEvidence[" + assuranceLevel + ", redacted]";
    }
}
