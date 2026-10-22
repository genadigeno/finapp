package com.finapp.app.risk;

import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.ScreeningClearances;
import java.sql.Connection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ScreeningClearances} over kyc's case checks (`P13-TSK-005`; ADR-0107 decision 3; {@code PHASE_13_PLAN.md}
 * sections 3.1, 12.10): a party's latest screening clearance is the latest <em>decided</em> sanctions or PEP check on
 * the latest KYC case of the party's live customer relationship - kyc's own rows, read through kyc's own stores on
 * the caller's unit of work, returned as three bounded facts (the check's id, whether it hit, when it was decided) and
 * never as a row ({@code INV-RSK-02}). The Screening Result stays kyc's; risk asks, kyc screened.
 *
 * <p><strong>What is not a clearance.</strong> A check still {@code REQUESTED} or {@code DISPATCHED}, or decided
 * {@code INDETERMINATE} ("we do not know", ADR-0008), is not a clearance either way: the answer is empty, as it is
 * for a party never screened, a party with no live customer relationship, or one with no case. An evaluation that
 * reads an empty answer treats it as the absence of a clearance, never as a clear - the fail-safe direction is the
 * policy's to decide (`-004`), not this read's to assume.
 *
 * <p>kyc's counterparty screenings (`P9-TSK-016`) are screenings of beneficiaries, not parties, and reach risk as
 * crossborder's own committed outcome ({@code SignalKind#BENEFICIARY_ACTIVATED}, {@code BENEFICIARY_BLOCKED}); the
 * rescreening of a party on a list update is `-022`'s, through {@code PartyRescreening}.
 */
public final class KycScreeningClearances implements ScreeningClearances {

    private static final Set<CheckType> SCREENINGS = EnumSet.of(CheckType.SANCTIONS, CheckType.PEP);
    private static final Set<CheckStatus> DECIDED = EnumSet.of(CheckStatus.CLEAR, CheckStatus.HIT);

    private final PartyStore<Connection> parties;
    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;

    public KycScreeningClearances(
            PartyStore<Connection> parties, KycCaseStore<Connection> cases, CheckStore<Connection> checks) {
        this.parties = Objects.requireNonNull(parties, "parties");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.checks = Objects.requireNonNull(checks, "checks");
    }

    @Override
    public Optional<Clearance> latestFor(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(partyId, "partyId");
        return parties.findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                .flatMap(customer -> cases.findLatestFor(unitOfWork, customer.id().value()))
                .flatMap(kycCase -> checks.forCase(unitOfWork, kycCase.id()).stream()
                        .filter(check -> SCREENINGS.contains(check.type()))
                        .filter(check -> DECIDED.contains(check.status()))
                        .max(Comparator.comparing(VerificationCheck::statusChangedAt)))
                .map(check -> new Clearance(
                        check.id().value(), check.status() == CheckStatus.HIT, check.statusChangedAt()));
    }
}
