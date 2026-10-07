package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditBureau;
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
 * The credit bureau contract battery for {@code bureau-sim-a} (`P10-TSK-005`): the adapter against
 * the honest, stateful {@link SimulatedBureauEngine}, every {@link CreditBureauContract} case, and the
 * wire facts only this adapter has - our reference on the wire, the bearer key, the subject sent and
 * resolved nowhere else.
 */
@DisplayName("the credit bureau contract battery - bureau-sim-a (P10-TSK-005)")
class SimulatedBureauContractTest extends CreditBureauContract {

    private static final byte[] KEY = "a-bureau-test-key-of-32-bytes-ok!".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofMillis(800);

    private static final Map<String, BureauSubject> PEOPLE = Map.of(
            "S-1", new BureauSubject("Ada Example", LocalDate.of(1985, 3, 14), CountryCode.of("DE")),
            "S-2", new BureauSubject("Grace Sample", LocalDate.of(1972, 11, 2), CountryCode.of("FR")));

    private SimulatedBureauEngine engine;
    private CreditBureau bureau;

    @BeforeEach
    void start() throws Exception {
        engine = SimulatedBureauEngine.start();
        engine.slowness(Duration.ofMillis(2_500));
        bureau = new SimulatedBureauAdapter(engine.baseUrl(), TIMEOUT, KEY,
                reference -> Optional.ofNullable(PEOPLE.get(reference)));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Override
    protected CreditBureau bureau() {
        return bureau;
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
    @DisplayName("our reference travels as the idempotency key, the bearer key beside it, and the subject's facts in"
            + " the body - resolved here, from the opaque reference")
    void theWireCarriesOurReferenceTheKeyAndTheSubject() {
        bureau.pull(request("R-20", 1));
        assertThat(engine.idempotencyKeys()).containsExactly("R-20");
        assertThat(engine.authorizations()).containsExactly("Bearer " + Base64.getEncoder().encodeToString(KEY));
        assertThat(engine.requestBodies()).singleElement().asString()
                .contains("Ada Example").contains("1985-03-14").contains("\"currency\":\"EUR\"")
                .doesNotContain("S-1");
    }

    @Test
    @DisplayName("a subject the resolver cannot name is refused before anything is sent")
    void anUnresolvableSubjectIsNeverSent() {
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> bureau.pull(request("R-21", 9)));
        assertThat(engine.idempotencyKeys()).isEmpty();
    }

    @Test
    @DisplayName("the adapter and the subject render neither key nor identity")
    void nothingSensitiveRenders() {
        assertThat(bureau.toString()).isEqualTo("SimulatedBureauAdapter[bureau-sim-a]");
        assertThat(PEOPLE.get("S-1").toString()).doesNotContain("Ada").doesNotContain("1985");
    }
}
