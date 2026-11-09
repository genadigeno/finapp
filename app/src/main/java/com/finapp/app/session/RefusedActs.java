package com.finapp.app.session;

import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records every refused or failed act at a door that declares {@link RecordsRefusals} (`P15-TSK-039`,
 * {@code door.ActRefused}; {@code THREAT_MODEL.md} T38; the debt row "refused and failed Phase 7 acts leave no audit
 * outcome").
 *
 * <h2>In a transaction of its own, because the act's rolled back</h2>
 *
 * <p>An accepted act is audited inside the act. A refused one has none left to be audited in: the handler's transaction
 * rolled back with the refusal. So the record is written here, in the session's {@code REQUIRES_NEW} template, committed
 * before the refusal is answered - the shape a permission denial and a refused step-up already have. The error contract
 * ({@code RefusedActAdvice}) calls this for every problem a declared door renders, so one choke point sees every refusal
 * path: a domain refusal, a stale echo, a bean-validation failure, a missing key, an accident.
 *
 * <h2>Once per refused request; a retry is a second attempt</h2>
 *
 * <p>A request is recorded once. A client's retry is a new attempt and records again, as a denial and a refused step-up
 * do. What bounds the rows: this is reached only past authentication and past the door's permission or an active
 * merchant key, every row names who made it, and a row's size does not depend on anything the caller sent
 * ({@code AUDITABLE_ACTIONS.md}).
 *
 * <h2>The summary holds no caller's text</h2>
 *
 * <p>The route pattern, the error code from the contract's own list, the status, and the path's identifiers - and of those
 * only a value that is a UUID, for an unknown record's id is what an investigator wants of a not-found and any other text
 * in a path is the caller's. Never a body, a reason, a client detail, an exception's message, a secret or a PAN. For a
 * merchant it names the API key by its public id ({@code AuthenticatedMerchant#keyId}), never the secret.
 *
 * <p><strong>Only a proven actor.</strong> With no actor nothing is recorded: a {@code 401} has nobody to name, and a
 * refusal the session interceptor answers has closed its scope because it has written its own record.
 */
@RequiredArgsConstructor
public final class RefusedActs {

    /** What a {@code door.ActRefused} record points at: the door, by its route pattern. */
    public static final String AUDIT_TARGET_TYPE = "api.Route";

    static final String RECORDED = RefusedActs.class.getName() + ".recorded";

    private static final Pattern A_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String NOT_AN_ID = "(not an id)";

    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Records the refused or failed act, unless it is recorded already or nobody proven is acting.
     *
     * @param code the code the door answers
     * @param status the status the door answers
     * @return whether this call wrote the record
     */
    public boolean record(HttpServletRequest request, String code, int status) {
        if (request.getAttribute(RECORDED) != null) {
            return false;
        }
        Optional<Actor> actor = SecurityContext.current();
        if (actor.isEmpty()) {
            return false;
        }
        Correlation correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a refused act's record needs the request's correlation"));
        AuditRecord record = new AuditRecord(
                AuditId.next(ids),
                actor.get(),
                clock.instant(),
                PlatformAuditAction.DOOR_ACT_REFUSED,
                AUDIT_TARGET_TYPE,
                StepUpRefusals.operationOf(request),
                Optional.empty(),
                outcomeOf(status),
                correlation.correlationId(),
                Optional.of(summaryOf(request, code, status)));
        transactions.executeWithoutResult(committing -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                auditWriter.append(unitOfWork, record);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
        request.setAttribute(RECORDED, Boolean.TRUE);
        return true;
    }

    /** {@code FAILED} for what was attempted and did not complete, a lookup that found nothing among them; {@code DENIED} for a control refusing it. */
    static AuditOutcome outcomeOf(int status) {
        return status >= 500 || status == 404 ? AuditOutcome.FAILED : AuditOutcome.DENIED;
    }

    private static String summaryOf(HttpServletRequest request, String code, int status) {
        StringBuilder summary = new StringBuilder("code=").append(code).append(", status=").append(status);
        String identifiers = identifiersOf(request);
        if (!identifiers.isEmpty()) {
            summary.append(", ").append(identifiers);
        }
        if (request.getAttribute(MerchantKeyAuthenticationInterceptor.CURRENT_MERCHANT)
                instanceof AuthenticatedMerchant merchant) {
            summary.append(", apiKey=").append(merchant.keyId().value());
        }
        return summary.toString();
    }

    /** The path's values by the name the route gives them, in a stable order; a value that is not a UUID is not written. */
    private static String identifiersOf(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables)) {
            return "";
        }
        return new TreeMap<>(variables).entrySet().stream()
                .map(variable -> variable.getKey() + "=" + identifierOf(String.valueOf(variable.getValue())))
                .collect(Collectors.joining(", "));
    }

    private static String identifierOf(String raw) {
        return A_UUID.matcher(raw).matches() ? raw : NOT_AN_ID;
    }
}
