package com.finapp.app.risk;

import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.Reading;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskInputs;
import com.finapp.risk.RuleKind;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.SessionAssurance;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link RiskInputs} over the owning modules' reads (`P13-TSK-004`; ADR-0107 decision 3; {@code PHASE_13_PLAN.md}
 * sections 7.1, 12.2) - each read on the caller's unit of work, inside T-eval's timed savepoint, only for a kind the
 * pinned version judges, returning bounded facts and never a row ({@code INV-RSK-02}). The sources, as of this task:
 *
 * <ul>
 *   <li><strong>the session's assurance</strong> - {@link SessionAssurance} over the request's actor: identity's
 *       session, re-read live ({@code IdentitySessionAssurance}, wired by `P13-TSK-008`); {@code ABSENT} when the actor
 *       acts under no session (a system flow, a call outside a request), which leaves a {@code CHALLENGE}
 *       unsatisfied;</li>
 *   <li><strong>the screening hit</strong> - {@link ScreeningClearances} (kyc's latest decided sanctions or PEP check);
 *       a party never screened is {@code ABSENT}, never "no hit";</li>
 *   <li><strong>KYC approved</strong> - the latest KYC case of the party's live customer relationship, {@code APPROVED}
 *       or not; no relationship or no case is {@code ABSENT};</li>
 *   <li><strong>the merchant suspended</strong> - merchant's own status: {@code SUSPENDED} or {@code CLOSED} is
 *       suspended for judging (a closed merchant takes no payment either); an unknown merchant is {@code ABSENT};</li>
 *   <li><strong>the authentication failures</strong> - {@code ABSENT}: identity's throttle counts consecutive failures
 *       toward a lockout, not the rule's {@code H1}/{@code D1} windows, and its failure event names no identity
 *       ({@code INV-IDN-07}).</li>
 * </ul>
 *
 * <p>An account payee's first sighting left this port with `P13-TSK-013`: risk answers it from its own velocity windows
 * (the destination's earliest counted window), as it does a device's.
 */
public final class PlatformRiskInputs implements RiskInputs {

    private final Optional<SessionAssurance> sessions;
    private final ScreeningClearances clearances;
    private final PartyStore<Connection> parties;
    private final KycCaseStore<Connection> cases;
    private final MerchantStore<Connection> merchants;

    public PlatformRiskInputs(Optional<SessionAssurance> sessions, ScreeningClearances clearances,
            PartyStore<Connection> parties, KycCaseStore<Connection> cases, MerchantStore<Connection> merchants) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clearances = Objects.requireNonNull(clearances, "clearances");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.merchants = Objects.requireNonNull(merchants, "merchants");
    }

    @Override
    public Readings read(Connection unitOfWork, RiskEvaluationRequest request, Set<RuleKind> judged, Instant now) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(judged, "judged");
        Reading<RiskAssurance> assurance = reading(sessions.flatMap(port -> request.actor()
                .flatMap(actor -> port.of(unitOfWork, actor))));
        Reading<Boolean> screeningHit = judged.contains(RuleKind.SCREENING_HIT)
                ? reading(clearances.latestFor(unitOfWork, request.party()).map(ScreeningClearances.Clearance::hit))
                : Reading.absent();
        Reading<Boolean> kycApproved = judged.contains(RuleKind.KYC_NOT_APPROVED)
                ? reading(parties.findLiveCustomerFor(unitOfWork, PartyId.of(request.party()))
                        .flatMap(customer -> cases.findLatestFor(unitOfWork, customer.id().value()))
                        .map(kycCase -> kycCase.status() == KycCaseStatus.APPROVED))
                : Reading.absent();
        Reading<Boolean> merchantSuspended = judged.contains(RuleKind.MERCHANT_SUSPENDED)
                ? reading(request.merchant().flatMap(merchant -> merchants.findById(unitOfWork, MerchantId.of(merchant)))
                        .map(merchant -> merchant.status() != MerchantStatus.ACTIVE))
                : Reading.absent();
        return new Readings(assurance, screeningHit, kycApproved, merchantSuspended, Map.of());
    }

    private static <T> Reading<T> reading(Optional<T> answered) {
        return answered.<Reading<T>>map(Reading::of).orElseGet(Reading::absent);
    }
}
