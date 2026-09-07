package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Registering a channel and proving control of it (`P1-TSK-023`, {@code INV-IDN-06}).
 *
 * <h2>This exists because the invariant had no subject</h2>
 *
 * <p>{@code INV-IDN-06} forbids recovery <em>"without proving control of a previously registered and
 * verified channel"</em>, and until this task the platform had no channel at all — no type, no
 * table, no verification, and <strong>no backlog task that owned one</strong>. That is the seventh
 * backlog defect of this class in Phase 1 and the most consequential: the others were missing
 * endpoints, and this was a missing <em>precondition of the invariant</em>. Without it recovery
 * could only appear to satisfy `INV-IDN-06`.
 *
 * <h2>Adding is authenticated; proving control is not the same act</h2>
 *
 * <p>A channel is added by somebody holding a session, so an attacker cannot register their own
 * mailbox against a stranger's account. Verification is separate and asynchronous, because control
 * is proven by reading what was sent — not by asserting it in the request that added it.
 *
 * <h2>The challenge is delivered nowhere, and that is a seam</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §8: the channel adapter is Phase 15's. So {@link #add} returns the
 * plaintext to its caller, where a notifier will attach in this same transaction, and the HTTP layer
 * discards it. Returning it to whoever asked would make channel control prove nothing at all.
 */
public final class ContactChannelService {

    /** Long enough to find the message; short enough that a leaked mailbox is not a standing key. */
    public static final Duration CHALLENGE_LIFETIME = Duration.ofHours(24);

    private static final String AUDIT_TARGET_TYPE = "ContactChannel";

    private final ContactChannelStore<Connection> channels;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecureRandom randomness;
    private final AuditWriter<Connection> auditWriter;

    public ContactChannelService(
            ContactChannelStore<Connection> channels,
            IdGenerator ids,
            Clock clock,
            SecureRandom randomness,
            AuditWriter<Connection> auditWriter) {
        this.channels = Objects.requireNonNull(channels, "channels must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
    }

    /**
     * Registers an unverified channel and issues a challenge to it.
     *
     * @return the challenge, for a notifier that does not exist yet to deliver
     */
    public Added add(Connection unitOfWork, IdentityId identityId, EmailAddress address) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(address, "address must not be null");

        Instant at = Instant.now(clock);
        SingleUseToken challenge = SingleUseToken.issue(randomness);
        ContactChannel channel =
                new ContactChannel(
                        ContactChannelId.next(ids),
                        identityId,
                        ContactChannelKind.EMAIL,
                        address,
                        Optional.empty(),
                        at);

        channels.add(unitOfWork, channel, challenge, at.plus(CHALLENGE_LIFETIME));
        audit(unitOfWork, at, IdentityAuditAction.CONTACT_CHANNEL_ADDED, channel, "kind=EMAIL");

        // No event. An unverified channel is not a fact about the account - announcing one would
        // tell every consumer a person can be reached at an address nobody has proven.
        return new Added(channel, challenge);
    }

    /**
     * Spends a challenge, proving control.
     *
     * @return the now-verified channel, or empty for <strong>every</strong> reason — unknown token,
     *     already spent, expired, a lost race. One answer, because a caller able to tell them apart
     *     learns whether a verification is pending on an account
     */
    public Optional<ContactChannel> verify(
            Connection unitOfWork, com.finapp.sharedkernel.security.Sensitive<String> presented) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(presented, "presented must not be null");

        Instant at = Instant.now(clock);
        Optional<ContactChannel> verified =
                channels.verify(unitOfWork, SingleUseToken.of(presented), at);

        verified.ifPresent(
                channel ->
                        audit(
                                unitOfWork,
                                at,
                                IdentityAuditAction.CONTACT_CHANNEL_VERIFIED,
                                channel,
                                "identity=" + channel.identityId()));
        return verified;
    }

    // -----------------------------------------------------------------

    /**
     * A channel and the challenge that will prove it.
     *
     * <p>The challenge is a per-call return value, never retained — the {@code Rotated} shape.
     */
    public record Added(ContactChannel channel, SingleUseToken challenge) {
        public Added {
            Objects.requireNonNull(channel, "channel must not be null");
            Objects.requireNonNull(challenge, "challenge must not be null");
        }
    }

    private void audit(
            Connection unitOfWork,
            Instant at,
            IdentityAuditAction action,
            ContactChannel channel,
            String changeSummary) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A channel operation must run inside a correlation"
                                                        + " scope"));
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        action,
                        AUDIT_TARGET_TYPE,
                        channel.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Never the address. It is RESTRICTED-PII, and an audit record is the one
                        // artefact nobody can delete afterwards (INV-HIST-03).
                        Optional.of(changeSummary)));
    }
}
