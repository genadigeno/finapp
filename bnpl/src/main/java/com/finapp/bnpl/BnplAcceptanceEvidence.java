package com.finapp.bnpl;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * How a customer accepted a BNPL offer (`P12-TSK-013`, {@code bnpl V006}'s {@code bnpl_acceptance}; A18, lending's
 * {@code AcceptanceEvidence} shape) - the identity and the session that accepted, the session's assurance (a
 * {@code MULTI_FACTOR} session, always - the door refuses any other and {@code CHECK} holds it), and the client's address
 * and agent as the request carried them - the socket's, never the body's. Acceptance is not consent: no consent purpose is
 * created ({@code INV-IDN-04}). The session, address and agent are CONFIDENTIAL.
 */
public record BnplAcceptanceEvidence(
        UUID identity,
        UUID session,
        String assuranceLevel,
        Optional<String> clientIp,
        Optional<String> userAgent) {

    /** The one channel Phase 12 accepts through - the customer's API. */
    public static final String CHANNEL = "API";

    /** The one assurance an acceptance is made at (A18). */
    public static final String MULTI_FACTOR = "MULTI_FACTOR";

    /** {@code bnpl V006}'s bound on the agent. */
    public static final int MAX_USER_AGENT = 512;

    /** An IPv4 or IPv6 literal's alphabet - the database casts it to {@code inet} and refuses anything else. */
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    public BnplAcceptanceEvidence {
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
        return "BnplAcceptanceEvidence[" + assuranceLevel + ", redacted]";
    }
}
