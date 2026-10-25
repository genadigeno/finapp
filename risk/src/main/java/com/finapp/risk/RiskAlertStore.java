package com.finapp.risk;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for alerts (`P13-TSK-015`; {@code risk V010}; ADR-0033) - a port on the caller's unit of work. The arbiters
 * are the database's: the birth {@code ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING}, every edge one
 * conditional {@code UPDATE … WHERE status = 'OPEN' …} that the trigger judges again and stamps on the database clock,
 * the suppression's four eyes a {@code CHECK}. No delete exists, here or in the grant.
 */
public interface RiskAlertStore {

    /**
     * Inserts {@code alert} as {@code id}, born {@code OPEN} - one statement; the born row back, or empty when the
     * alert's key was already raised (nothing written). A primary-key collision is not a conflict: it throws.
     */
    Optional<RiskAlert> insert(Connection unitOfWork, RiskAlertId id, NewAlert alert);

    /** The alert standing for {@code key}, if raised - no lock (a fresh statement: under READ COMMITTED it sees a raise
     * the birth's conflict waited for). */
    Optional<RiskAlert> findByKey(Connection unitOfWork, AlertKey key);

    /** The alert {@code id}, if one exists - no lock. */
    Optional<RiskAlert> find(Connection unitOfWork, RiskAlertId id);

    /** The alert {@code id} locked {@code FOR UPDATE} - the machine's (R5) for a person's act. */
    Optional<RiskAlert> lock(Connection unitOfWork, RiskAlertId id);

    /** {@code OPEN → ATTACHED} to {@code caseId}; empty when the alert was no longer {@code OPEN}. */
    Optional<RiskAlert> attach(Connection unitOfWork, RiskAlertId id, RiskCaseId caseId);

    /**
     * Up to {@code limit} alerts still {@code OPEN} of a kind that opens a case and with no suppression proposal standing,
     * oldest first ({@code risk_alert_open_queue}) - no lock: `-016`'s drain folds each in a transaction of its own, the
     * case (R4) before the alert (R5), the attach conditional on {@code OPEN}.
     */
    List<RiskAlert> queued(Connection unitOfWork, int limit);

    /**
     * The proposal onto an {@code OPEN} alert with none: the code, the text and the proposer; empty when the alert was
     * not {@code OPEN} or a proposal already stood.
     */
    Optional<RiskAlert> propose(Connection unitOfWork, RiskAlertId id, SuppressionReason reasonCode, String reason,
            Actor proposer);

    /**
     * {@code OPEN → SUPPRESSED} over the standing proposal, by {@code approver} with {@code reason}; empty when the alert
     * was not {@code OPEN} or no proposal stood. Four eyes is the domain's and the {@code CHECK}'s - not this statement's.
     */
    Optional<RiskAlert> approve(Connection unitOfWork, RiskAlertId id, Actor approver, String reason);

    /** The standing proposal cleared, the alert {@code OPEN} again; empty when none stood or the alert was not open. */
    Optional<RiskAlert> reject(Connection unitOfWork, RiskAlertId id);

    /**
     * Up to {@code limit} alerts of {@code status} (and of {@code kind}, when named), oldest first by
     * {@code (raised_at, id)}, after the key of the alert {@code after} names (`P13-TSK-018`'s queue; {@code risk V014}'s
     * {@code risk_alert_queue}, {@code V010}'s {@code risk_alert_open_queue}) - no lock. {@code excludingParty}'s own
     * {@code PARTY}-subject alerts are left out by the statement (the reader's party; every other subject's party is the
     * domain's judgement). The cursor's row need not match the filter: its key is immutable.
     */
    List<RiskAlert> queue(Connection unitOfWork, AlertStatus status, Optional<AlertKind> kind, Optional<RiskAlertId> after,
            UUID excludingParty, int limit);

    /** The alerts attached to {@code caseId}, oldest first ({@code risk V014}'s {@code risk_alert_of_case}) - no lock. */
    List<RiskAlert> ofCase(Connection unitOfWork, RiskCaseId caseId);
}
