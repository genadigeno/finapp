package com.finapp.platform.outbox;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Records {@code outbox.EventAbandoned} when the relay gives up (`P15-TSK-028`), as the platform and in the relay's own transaction: a
 * log line is not an audit trail (ADR-0010). The row keeps the failure; the record says it happened and to which event.
 */
public final class AuditedAbandonments implements OutboxRelay.Abandonments {

    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public AuditedAbandonments(AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @SuppressWarnings("try") // The system scope is used for its close side effect.
    public void recordAbandoned(Connection connection, PendingEvent event, int attempts) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            audit.append(connection, new AuditRecord(
                    AuditId.next(ids),
                    SecurityContext.require(),
                    clock.instant(),
                    PlatformAuditAction.OUTBOX_EVENT_ABANDONED,
                    "outbox.event",
                    event.eventId().value().toString(),
                    Optional.empty(),
                    AuditOutcome.SUCCEEDED,
                    event.correlationId(),
                    Optional.of("type=" + event.eventType() + ", aggregate=" + event.aggregateId() + ", attempts=" + attempts)));
        }
    }
}
