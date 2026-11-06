package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A retired terms version ends after every offer made under it, whatever the database clock did (`P14-DOC-001`, lending
 * {@code V033}; {@code INV-LND-05}). The phase's database pass caught {@code LoanTermsDatabaseTest
 * #anOfferMadeDuringAnActivationStandsInsideItsVersion} red once: lending {@code V030} stamped a retirement's end from the
 * database's clock alone, so a clock stepped back between an offer's commit and the successor's activation ended the version
 * before the offer pinned to it. The container's clock was measured stepping back 0.57 s and 2.15 s about every 25 s. Here the
 * step is made, not waited for: {@link ClockStep}'s shadow of the clock functions steps the activation's session back 300 ms
 * after the offer commits and before the activation retires the version the offer pins.
 */
@Tag("database")
@DisplayName("a retired terms version ends after every offer made under it, whatever the database clock did (P14-DOC-001)")
class TermsRetirementClockDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final LoanTermsAdministration ADMINISTRATION = new LoanTermsAdministration(new JdbcLoanTermsStore(),
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, LoanTermsFixtures.BOUNDS);

    @Test
    @DisplayName("the clock stepped back 300 ms between an offer's commit and the successor's activation: the retired version"
            + " still ends after the offer, and the successor starts where it ends")
    void aBackwardStepNeverEndsAVersionBeforeItsOffer() throws Exception {
        ClockStep.install();
        try {
            DisbursementWorld.ensureActive();
            DisbursementWorld.Approved approved = DisbursementWorld.approved(LoanProduct.PERSONAL_LOAN, 4_100_00, Optional.of(12),
                    PayoutPreference.WALLET, 10);
            DisbursementWorld.clearPendingTerms(LoanProduct.PERSONAL_LOAN);
            LoanTermsAdministration.Proposed successor = DisbursementWorld.inOneTransaction(uow -> ADMINISTRATION.propose(uow,
                    LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0713").build(), "P14-DOC-001", employee(), correlation()));
            ClockStep.inOneTransaction(Duration.ZERO, activation -> {
                try (Statement begin = activation.createStatement()) {
                    begin.execute("SELECT transaction_timestamp()");
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
                DisbursementWorld.inOneTransaction(uow -> DisbursementWorld.ORIGINATION.originate(uow, approved.application(),
                        DisbursementWorld.SYSTEM));
                ClockStep.stepBack(activation, Duration.ofMillis(300));
                ADMINISTRATION.approve(activation, successor.id(), employee(), "P14-DOC-001", correlation());
                return null;
            });
            assertThat(DisbursementWorld.scalar("SELECT (o.offered_at >= v.effective_from AND o.offered_at < v.effective_to)::text"
                    + " FROM lending.loan_offer o JOIN lending.loan_terms_version v ON v.id = o.terms_version_id"
                    + " WHERE o.application_id = ?", approved.application().value()))
                    .as("the offer inside its pinned version's period (INV-LND-05) - lending V030 ended the version before it")
                    .isEqualTo("true");
            assertThat(DisbursementWorld.scalar("SELECT (s.effective_from = v.effective_to)::text FROM lending.loan_offer o"
                    + " JOIN lending.loan_terms_version v ON v.id = o.terms_version_id"
                    + " JOIN lending.loan_terms_version s ON s.product = v.product AND s.status = 'ACTIVE'"
                    + " WHERE o.application_id = ?", approved.application().value()))
                    .as("the successor starts exactly where the retired version ends").isEqualTo("true");
        } finally {
            ClockStep.uninstall();
        }
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
