package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.FinancialDataProvider;
import com.finapp.sharedkernel.money.CountryCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The financial-data provider contract battery for {@code findata-sim-a} (`P10-TSK-007`): the adapter against the
 * simulated provider's summary wire, every {@link CreditDataSourceContract} case, and its own wire facts.
 */
@DisplayName("the financial-data provider contract battery - findata-sim-a (P10-TSK-007)")
class SimulatedFinancialDataContractTest extends FinancialDataProviderContract {

    private static final byte[] KEY = "a-findata-test-key-of-32-bytes-ok".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofMillis(800);

    private static final Map<String, CreditDataSubject> PEOPLE = Map.of(
            "S-1", new CreditDataSubject("Ada Example", LocalDate.of(1985, 3, 14), CountryCode.of("DE")),
            "S-2", new CreditDataSubject("Grace Sample", LocalDate.of(1972, 11, 2), CountryCode.of("FR")));

    private SimulatedBureauEngine engine;
    private FinancialDataProvider provider;

    @BeforeEach
    void start() throws Exception {
        engine = SimulatedBureauEngine.startFinancialData();
        engine.slowness(Duration.ofMillis(2_500));
        provider = new SimulatedFinancialDataAdapter(engine.baseUrl(), TIMEOUT, KEY,
                reference -> Optional.ofNullable(PEOPLE.get(reference)));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Override
    protected FinancialDataProvider provider() {
        return provider;
    }

    @Override
    protected String subject(int person) {
        return "S-" + person;
    }

    @Override
    protected void arm(Misbehaviour misbehaviour) {
        engine.arm(switch (misbehaviour) {
            case PARTIAL -> SimulatedBureauEngine.Fault.PARTIAL;
            case FOREIGN_CURRENCY -> SimulatedBureauEngine.Fault.FOREIGN_CURRENCY;
            case MALFORMED -> SimulatedBureauEngine.Fault.MALFORMED;
            case UNKNOWN_STATUS -> SimulatedBureauEngine.Fault.UNKNOWN_STATUS;
            case PROVIDER_ERROR -> SimulatedBureauEngine.Fault.UNAVAILABLE;
            case TIMEOUT -> SimulatedBureauEngine.Fault.SILENT;
            case SLOW_BEYOND_TIMEOUT -> SimulatedBureauEngine.Fault.SLOW;
            case RESPONSE_LOST -> SimulatedBureauEngine.Fault.LOSE_RESPONSE;
        });
    }

    @Override
    protected int countedPulls() {
        return engine.pulls();
    }

    @Test
    @DisplayName("our reference travels as the idempotency key, the bearer key beside it, the subject resolved here")
    void theWireCarriesOurReferenceTheKeyAndTheSubject() {
        provider.pull(request("R-20", 1));
        assertThat(engine.idempotencyKeys()).containsExactly("R-20");
        assertThat(engine.authorizations()).containsExactly("Bearer " + Base64.getEncoder().encodeToString(KEY));
        assertThat(engine.requestBodies()).singleElement().asString()
                .contains("Ada Example").contains("\"currency\":\"EUR\"").doesNotContain("S-1");
    }

    @Test
    @DisplayName("the adapter renders neither key nor identity")
    void nothingSensitiveRenders() {
        assertThat(provider.toString()).isEqualTo("SimulatedFinancialDataAdapter[findata-sim-a]");
    }
}
