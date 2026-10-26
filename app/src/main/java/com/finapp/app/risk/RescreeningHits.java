package com.finapp.app.risk;

import com.finapp.app.kyc.RescreenHits;
import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.RescreeningPermitId;
import com.finapp.risk.RescreeningRun;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * A rescreen's hit told back to the permit that asked it (`P13-TSK-022`'s last review, item 3: "a HIT on any of the
 * permit's ordinals wins - and a person is told"). kyc calls it after it records a {@code RESCREEN} check's {@code HIT}
 * - on its door's path and on the provider's late callback alike ({@link RescreenHits}); this reads the check and the
 * key it was asked under on a snapshot of its own (kyc's rows, kyc's stores - risk asks, kyc screened) and, when the
 * key is a permit's, hands the hit to {@link RescreeningRun#hit} in a risk transaction of its own, as the platform:
 * a pending permit is concluded {@code HIT} at once (a slower conclusion computed from an earlier read then records
 * nothing), a permit concluded otherwise is told late - its own signal and alert - and one already concluded
 * {@code HIT} is left as it is. Idempotent: every source is born once.
 */
public final class RescreeningHits implements RescreenHits {

    private final CheckStore<Connection> checks;
    private final RescreeningRun run;
    private final RiskTransactions transactions;

    public RescreeningHits(CheckStore<Connection> checks, RescreeningRun run, RiskTransactions transactions) {
        this.checks = Objects.requireNonNull(checks, "checks");
        this.run = Objects.requireNonNull(run, "run");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void hit(CheckId checkId) {
        Objects.requireNonNull(checkId, "checkId");
        Optional<Told> told = transactions.inSnapshot(unitOfWork -> told(unitOfWork, checkId));
        told.ifPresent(hit -> RescreeningDriver.asThePlatform(
                () -> transactions.inTransaction(unitOfWork -> run.hit(unitOfWork, hit.permit(), hit.screened()))));
    }

    /** The permit and kyc's hit, when {@code checkId} is a permit's question answered {@code HIT}. */
    private Optional<Told> told(Connection unitOfWork, CheckId checkId) {
        Optional<String> key = checks.rescreenKeyOf(unitOfWork, checkId);
        Optional<RescreeningPermitId> permit = key.flatMap(PartyRescreening::permitOf);
        if (permit.isEmpty()) {
            return Optional.empty();
        }
        return checks.findRescreen(unitOfWork, key.get())
                .filter(check -> check.status() == CheckStatus.HIT)
                .map(check -> new Told(permit.get(), new PartyRescreening.Screened(check.id().value(), true,
                        check.requestedAt(), check.statusChangedAt())));
    }

    private record Told(RescreeningPermitId permit, PartyRescreening.Screened screened) {}
}
