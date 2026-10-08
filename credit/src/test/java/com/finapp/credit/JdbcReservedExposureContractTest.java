package com.finapp.credit;

import static com.finapp.credit.ScorecardFixtures.IDS;
import static com.finapp.credit.ScorecardFixtures.inOneTransaction;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * {@code P10-TSK-010}'s reserved-exposure contract over the real {@code credit_decision} (`P10-TSK-016`;
 * {@code INV-CRD-09}): every case the in-memory fake answers, answered by {@link JdbcReservedExposure} from rows the
 * deciding transaction's own store writes - a decision per request, its validity on the database's clock, a consumption
 * row for a consumed approval.
 */
@Tag("database")
@DisplayName("the reserved-exposure contract, over credit_decision (P10-TSK-016)")
class JdbcReservedExposureContractTest extends ReservedExposureContract<Connection> {

    private static final UUID MODEL = UUID.fromString("0190a1b2-5c0e-7000-8000-00000000c001");
    private static final JdbcDecisionRequestStore REQUESTS = new JdbcDecisionRequestStore();

    private final JdbcReservedExposure implementation = new JdbcReservedExposure();
    private Connection reader;

    @BeforeEach
    void open() throws SQLException {
        reader = DatabaseRoles.application();
    }

    @AfterEach
    void close() throws SQLException {
        reader.close();
    }

    @Override
    protected ReservedExposure<Connection> implementation() {
        return implementation;
    }

    @Override
    protected Connection unitOfWork() {
        return reader;
    }

    /** A decision as the deciding transaction's store records it, on a request of its own, then that request closed. */
    @Override
    protected void decided(UUID party, DecisionOutcome outcome, Money approved, Duration validityFromNow, boolean consumed) {
        inOneTransaction(uow -> {
            DecisionRequestId requestId = DecisionRequestId.of(DecisionRequestRows.submitted(uow, party, CreditProduct.PERSONAL_LOAN));
            DecisionRequest request = REQUESTS.ownedBy(uow, requestId, party).orElseThrow();
            SnapshotContent content = new SnapshotContent(requestId.value(), party, CreditProduct.PERSONAL_LOAN,
                    request.application().requested(), Optional.of(36),
                    new PinnedVersions(CreditPolicyV1.PERSONAL_LOAN_ID.value(), MODEL, 1), CanonicalSnapshotTest.attributes());
            String canonical = CanonicalSnapshot.render(content);
            JdbcDecisionSnapshotStore snapshots = new JdbcDecisionSnapshotStore();
            snapshots.insertSnapshot(uow, DecisionSnapshotId.next(IDS), requestId.value(), 1, CanonicalSnapshot.FORMAT,
                    canonical, CanonicalSnapshot.sha256(canonical), content.versions());
            DecisionSnapshotStore.StoredSnapshot stored = snapshots.snapshotOf(uow, requestId.value(), 1).orElseThrow();
            DecisionSnapshot snapshot = new DecisionSnapshot(stored.id(), 1, stored.format(), stored.canonical(),
                    stored.sha256(), stored.frozenAt(), CanonicalSnapshot.parse(stored.canonical()));
            CreditDecisionId decision = CreditDecisionId.next(IDS);
            boolean approval = outcome == DecisionOutcome.APPROVED;
            new JdbcCreditDecisions().insert(uow, new JdbcCreditDecisions.NewDecision(decision, request, snapshot, outcome,
                    approval ? Optional.of(approved) : Optional.empty(),
                    List.of(approval ? ReasonCode.AUTO_APPROVAL_CEILING : ReasonCode.SCORE_INSUFFICIENT), validityFromNow,
                    "system", "SYSTEM"));
            if (consumed) {
                try (PreparedStatement consume = uow.prepareStatement("INSERT INTO credit.credit_decision_consumption"
                        + " (id, decision_id, consumed_at) VALUES (?, ?, statement_timestamp())")) {
                    consume.setObject(1, IDS.next());
                    consume.setObject(2, decision.value());
                    consume.executeUpdate();
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            REQUESTS.transition(uow, requestId, DecisionRequestStatus.CANCELLABLE, DecisionRequestStatus.CANCELLED,
                    Optional.empty(), new Actor(party.toString(), ActorType.CUSTOMER), Optional.empty());
            return null;
        });
    }
}
