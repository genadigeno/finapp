package com.finapp.app.credit;

import com.finapp.credit.CreditBureau;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * Every bureau-collection case of `P10-TSK-006` re-run with {@code bureau-sim-b} (`P10-TSK-021`; ADR-0085 section 10):
 * the duplicate delivery, the lost response, the withdrawals in flight and after, ten sweepers on one due request, the
 * deadline, the skewed sweeper, the timeout, the crash after the opening, the starvation guard, the record, the needle,
 * the grants and the machine - each counted at the second bureau's own engine and in the rows. The collection machinery
 * is provider-neutral, so the same assertions must hold word for word; only the wire under them changes.
 */
@Tag("database")
@DisplayName("bureau data collection, re-run with bureau-sim-b (P10-TSK-006, P10-TSK-021)")
class SecondBureauCollectionDatabaseTest extends BureauCollectionDatabaseTest {

    @Override
    protected SimulatedBureauEngine startEngine() throws java.io.IOException {
        return SimulatedBureauEngine.startSecondBureau();
    }

    @Override
    protected CreditBureau adapter(SimulatedBureauEngine engine, Duration timeout, byte[] key,
            CreditDataSubjectResolver subjects) {
        return new SimulatedSecondBureauAdapter(engine.baseUrl(), timeout, key, subjects);
    }

    @Override
    protected String providerCode() {
        return SimulatedSecondBureauAdapter.CODE;
    }

    @Override
    protected int normaliserVersion() {
        return SimulatedSecondBureauAdapter.NORMALISER_VERSION;
    }

    @Override
    protected String completeAnswerWord() {
        return "FILE_FULL";
    }
}
