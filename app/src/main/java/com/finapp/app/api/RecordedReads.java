package com.finapp.app.api;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;

/**
 * The one way an operator read of a customer is recorded (`P17-TSK-025`; finapp-admin-panel ADR-C132 decision 5): each read
 * under {@link #PERMISSIONS} writes its module's {@code <module>.<Thing>Read} record - the caller, the target, the correlation, and
 * for a list its filter and how many it served, once per request, never per row - <strong>in the read's own unit of work</strong>,
 * so a read that served something cannot have gone unrecorded, and one rolled back left no record of serving anything (ADR-0010).
 *
 * <p><strong>Held at the answer, not trusted at the call site.</strong> Writing the record also marks the request; {@link
 * UnrecordedReadAdvice} refuses a successful answer from a handler under one of {@link #PERMISSIONS} whose request carries no
 * mark, {@code 500 api.InternalError} before any byte of it leaves. A forgotten call is therefore a failing request and a failing
 * test, never a silent gap in the trail: what {@code AUDITABLE_ACTIONS.md} §1 says nothing mechanical holds for other actions
 * holds for these reads.
 *
 * <p>Identifiers only (INV-AUD-02): the target is an id, and the summary names filters and counts, never a value a person typed
 * that could be their own handle - a lookup records what it resolved to, not what it was asked.
 */
@RequiredArgsConstructor
public final class RecordedReads {

    /** The permissions every one of whose answers is recorded: the operator's reads of customers and what they hold. */
    public static final Set<PermissionName> PERMISSIONS = Set.copyOf(EnumSet.of(
            PermissionName.CUSTOMER_READ, PermissionName.ACCOUNT_READ, PermissionName.TRANSFER_READ, PermissionName.PAYMENT_READ));

    /** The request attribute that says this request's read was recorded. */
    static final String RECORDED = RecordedReads.class.getName() + ".recorded";

    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Records one read in {@code unitOfWork} - the transaction that read what is served - and marks the request.
     *
     * @param action the module's {@code <module>.<Thing>Read}
     * @param targetType what was read: the module's aggregate, or the collection's name for a list
     * @param targetId the identifier read, or for a list the scope it was read within
     * @param summary the filter and the count served; identifiers and codes only
     */
    public void record(Connection unitOfWork, AuditableAction action, String targetType, String targetId, String summary) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        action,
                        targetType,
                        targetId,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        CorrelationContext.current()
                                .orElseThrow(() -> new IllegalStateException("an operator read runs inside a correlation scope"))
                                .correlationId(),
                        Optional.of(summary)));
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(RECORDED, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /**
     * Whether {@code handler}'s every answer must be recorded: it declares one of {@link #PERMISSIONS}, or it is marked
     * {@link RecordedRead} - a read of customers under a permission that also guards acts (`P17-TSK-029`).
     */
    public static boolean recordsEveryRead(HandlerMethod handler) {
        if (handler.hasMethodAnnotation(RecordedRead.class)) {
            return true;
        }
        RequiresPermission declared = handler.getMethodAnnotation(RequiresPermission.class);
        if (declared == null) {
            declared = handler.getBeanType().getAnnotation(RequiresPermission.class);
        }
        return declared != null && PERMISSIONS.contains(declared.value());
    }

    /** Whether a read was recorded in this request. */
    static boolean recorded(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(RECORDED));
    }
}
