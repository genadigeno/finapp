package com.finapp.app.risk;

import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.party.Customer;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.ScreeningList;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PartyRescreening} over kyc's case-bound screening, as {@code app} reaches it today (`P13-TSK-022`; ADR-0107
 * decision 3; {@code PHASE_13_PLAN.md} section 12.10; {@code DESIGN-022} D1; {@code INV-RSK-02}) - kyc's own rows, read
 * through kyc's and party's own stores on a read-only snapshot of its own (never the caller's transaction: the leg asks
 * holding no risk row), returned as kyc's answer and never as a row:
 *
 * <ul>
 *   <li>the party's live customer relationship not {@code ACTIVE} (none, {@code SUSPENDED}, {@code CLOSED}) -
 *       {@link Inactive};</li>
 *   <li>on the latest KYC case of that customer, the newest check of the list's type ({@code SANCTIONS} for the sanctions
 *       list, {@code PEP} for the PEP list) still {@code REQUESTED} or {@code DISPATCHED} - {@link Pending}
 *       {@code IN_FLIGHT};</li>
 *   <li>else the latest <em>decided</em> check of the type ({@code CLEAR} or {@code HIT}) - {@link Screened}: its id,
 *       whether it hit, when kyc decided it;</li>
 *   <li>else - no case, no check of the type, or only "we do not know" ({@code INDETERMINATE}) - {@link Pending}
 *       {@code NOT_SCREENED}.</li>
 * </ul>
 *
 * <p><strong>kyc screens; this port asks and reads.</strong> kyc today has no door that screens a decided party again: a
 * case's answered check is converged on, never re-asked ({@code CheckStore#requestOrConverge}), and a list update's
 * re-verification is a <em>new</em> case ({@code INV-LIFE-04}) - consent-gated, re-running identity and documents,
 * customer-visible, and undecidable for an {@code ACTIVE} customer today. So the request key binds nothing yet: what kyc
 * holds is its answer, and risk's leg judges whether it is current under the list version (decided at or after the
 * activation) - a party kyc screened before the activation waits, and fails at the leg's bound, counted, until kyc
 * re-screens it (a successor case's checks are read here the moment they are decided). kyc's re-screening door is the
 * owner's item (O1); it slots in here and nowhere else. kyc's {@code CounterpartyScreenings} screens a payee abroad by name,
 * country and payee check - facts a party does not have - and is not used.
 */
public final class KycPartyRescreening implements PartyRescreening {

    private final PartyStore<Connection> parties;
    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;
    private final RiskTransactions transactions;

    public KycPartyRescreening(PartyStore<Connection> parties, KycCaseStore<Connection> cases,
            CheckStore<Connection> checks, RiskTransactions transactions) {
        this.parties = Objects.requireNonNull(parties, "parties");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.checks = Objects.requireNonNull(checks, "checks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Answer ask(UUID partyId, ScreeningList list, String requestKey) {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(requestKey, "requestKey");
        return transactions.inSnapshot(unitOfWork -> answer(unitOfWork, partyId, list));
    }

    /** kyc's answer on {@code unitOfWork} - one snapshot, so the customer, its case and its checks are one instant. */
    Answer answer(Connection unitOfWork, UUID partyId, ScreeningList list) {
        Optional<Customer> customer = parties.findLiveCustomerFor(unitOfWork, PartyId.of(partyId));
        if (customer.isEmpty() || customer.get().status() != CustomerStatus.ACTIVE) {
            return new Inactive();
        }
        Optional<KycCase> latest = cases.findLatestFor(unitOfWork, customer.get().id().value());
        if (latest.isEmpty()) {
            return new Pending(Wait.NOT_SCREENED);
        }
        CheckType type = typeOf(list);
        List<VerificationCheck> ofType = checks.forCase(unitOfWork, latest.get().id()).stream()
                .filter(check -> check.type() == type).toList();
        Optional<VerificationCheck> newest = ofType.stream()
                .max(Comparator.comparing(VerificationCheck::requestedAt).thenComparing(VerificationCheck::statusChangedAt));
        if (newest.isPresent() && !newest.get().status().isTerminal()) {
            return new Pending(Wait.IN_FLIGHT);
        }
        return ofType.stream()
                .filter(check -> check.status() == CheckStatus.CLEAR || check.status() == CheckStatus.HIT)
                .max(Comparator.comparing(VerificationCheck::statusChangedAt))
                .<Answer>map(check -> new Screened(check.id().value(), check.status() == CheckStatus.HIT,
                        check.statusChangedAt()))
                .orElseGet(() -> new Pending(Wait.NOT_SCREENED));
    }

    /** The kyc screening a list is: a sanctions list is kyc's {@code SANCTIONS} check, a PEP list its {@code PEP}. */
    static CheckType typeOf(ScreeningList list) {
        return switch (Objects.requireNonNull(list, "list")) {
            case SANCTIONS -> CheckType.SANCTIONS;
            case PEP -> CheckType.PEP;
        };
    }
}
