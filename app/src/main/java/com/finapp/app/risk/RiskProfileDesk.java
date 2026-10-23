package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.CustomerRiskProfileVersion;
import com.finapp.risk.CustomerRiskProfiles;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The analyst's customer risk profile door (`P13-TSK-007`; {@code PHASE_13_PLAN.md} sections 9, 11; behind
 * {@code RISK_INVESTIGATE}; bnpl's {@code BnplInvestigationDesk} shape): one transaction - the self-dealing judgement,
 * the head and every version, and {@code risk.ProfileRead} committed with them before the answer, so nothing is served
 * unrecorded. A read, so no idempotency key: a repeated read is a second serving, recorded again.
 *
 * <p>An analyst of the party's own party - or one the platform cannot place in a party - is {@code 403
 * risk.SelfDealingRefused}, the attempt recorded {@code FAILED} in a transaction of its own; a party with no profile, or
 * a malformed id, is the uniform {@code 404 risk.NotFound}. The answer carries the ratings, scores and reasons - an
 * operator's surface only, never a customer's ({@code INV-RSK-08}); none of it is logged. The sealed inputs are named by
 * their digest, not served.
 */
public final class RiskProfileDesk {

    private final CustomerRiskProfiles profiles;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskProfileDesk(CustomerRiskProfiles profiles, RiskTransactions transactions, RiskFlowScope flow) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /** A party's profile as the analyst reads it: the current rating, if assessed, and every version, oldest first. */
    public record RiskProfileView(String partyId, RiskProfileCurrentView current, List<RiskProfileVersionView> versions) {}

    /** The current rating and the profile version it is. */
    public record RiskProfileCurrentView(int version, String rating) {}

    /**
     * One version and what set it: a recompute's pinned {@code MONITORING} policy version, engine, signal score, reason
     * codes and sealed inputs' digest - or a disposition's id. Instants are ISO-8601 UTC.
     */
    public record RiskProfileVersionView(int version, String rating, String cause, Integer signalScore,
            List<String> reasonCodes, String policyVersionId, Integer policyVersion, String engine, String inputsSha256,
            String dispositionId, String computedAt) {}

    /** {@code rawPartyId}'s profile, read and recorded. */
    public RiskProfileView read(String rawPartyId) {
        UUID party = partyId(rawPartyId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CustomerRiskProfiles.History history;
        try {
            history = flow.door(RiskSpans.PROFILE_READ, () -> transactions.inTransaction(
                    unitOfWork -> profiles.history(unitOfWork, party, actor, correlation)));
        } catch (CustomerRiskProfiles.SelfDealingRefused refused) {
            // The read rolled back; the attempt is recorded FAILED in a transaction of its own, so it survives.
            transactions.inTransaction(unitOfWork -> {
                profiles.recordRefusal(unitOfWork, party, refused, actor, correlation);
                return null;
            });
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk profile read was refused",
                    "a holder never reads the risk profile of their own party; nothing was read.");
        } catch (CustomerRiskProfiles.ProfileNotFound none) {
            throw notFound();
        }
        return view(history);
    }

    private static RiskProfileView view(CustomerRiskProfiles.History history) {
        return new RiskProfileView(history.partyId().toString(),
                history.current().map(current -> new RiskProfileCurrentView(current.version(), current.rating().name()))
                        .orElse(null),
                history.versions().stream().map(RiskProfileDesk::version).toList());
    }

    private static RiskProfileVersionView version(CustomerRiskProfileVersion version) {
        return new RiskProfileVersionView(
                version.version(),
                version.rating().name(),
                version.cause().name(),
                version.evaluation().map(CustomerRiskProfileVersion.Evaluation::signalScore).orElse(null),
                version.reasons().stream().map(ReasonCode::code).toList(),
                version.evaluation().map(e -> e.policyVersionId().value().toString()).orElse(null),
                version.evaluation().map(CustomerRiskProfileVersion.Evaluation::policyVersion).orElse(null),
                version.evaluation().map(e -> e.engine().name()).orElse(null),
                version.evaluation().map(CustomerRiskProfileVersion.Evaluation::inputsSha256).orElse(null),
                version.dispositionId().map(UUID::toString).orElse(null),
                version.computedAt().map(Object::toString).orElse(null));
    }

    private static UUID partyId(String raw) {
        try {
            return UUID.fromString(Objects.requireNonNull(raw, "raw"));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk profile answers the request", "no such record.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk profile read runs inside a correlation scope"))
                .correlationId();
    }
}
