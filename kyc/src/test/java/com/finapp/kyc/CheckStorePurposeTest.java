package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which checks the store's statements reach (`P13-TSK-022`, {@code kyc V011}), hermetic over a recording connection:
 * every onboarding read - {@code forCase}, the run's convergence, the budget's count - names
 * {@code purpose = 'ONBOARDING'}, so a list update's rescreen is never the evidence a case's assessment, review or
 * decision reads; {@code allForCase} reads both; the rescreen is one keyed insert on the database's clock. The live
 * behaviour is {@code RescreeningDatabaseTest}'s (written, owed - C5).
 */
@DisplayName("the check store's statements: onboarding reads see onboarding checks alone (P13-TSK-022)")
class CheckStorePurposeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final List<String> prepared = new ArrayList<>();
    private final JdbcCheckStore store =
            new JdbcCheckStore(new DocumentCipher(new byte[32], 1, new SecureRandom()));

    @Test
    @DisplayName("forCase - the assessment's, the review door's, the decision's read - names ONBOARDING; allForCase - risk's"
            + " screening reading - names no purpose")
    void theOnboardingReadIsScoped() {
        KycCaseId caseId = KycCaseId.next(IDS);
        store.forCase(recording(), caseId);
        assertThat(prepared).singleElement().asString().contains("WHERE case_id = ?").contains("purpose = 'ONBOARDING'");
        prepared.clear();
        store.allForCase(recording(), caseId);
        assertThat(prepared).singleElement().asString().contains("WHERE case_id = ?").doesNotContain("purpose =");
    }

    @Test
    @DisplayName("the run's convergence reads the newest ONBOARDING check of the type and inserts an onboarding check - a"
            + " rescreen is never converged on")
    void theRunConvergesOnOnboardingAlone() {
        store.requestOrConverge(recording(), VerificationCheck.request(IDS, CLOCK, KycCaseId.next(IDS), CheckType.PEP));
        assertThat(prepared).first().asString().contains("ORDER BY requested_at DESC").contains("purpose = 'ONBOARDING'");
        assertThat(prepared).anySatisfy(sql -> assertThat(sql).startsWith("INSERT INTO kyc.verification_check")
                .contains("'ONBOARDING'"));
    }

    @Test
    @DisplayName("a rescreen is one keyed insert - ON CONFLICT (rescreen_key) DO NOTHING, both stamps the database's"
            + " clock_timestamp() - then the row its key names; a type that is not a screening, or a key out of bounds,"
            + " is refused before any statement")
    void aRescreenIsOneKeyedInsert() {
        assertThatThrownBy(() -> store.requestRescreen(recording(), KycCaseId.next(IDS), CheckType.SANCTIONS,
                        CheckId.next(IDS), "risk.rescreen:k#0"))
                .as("the recording connection answers no row: the standing check is looked for")
                .isInstanceOf(KycStorageException.class);
        assertThat(prepared).hasSize(2);
        assertThat(prepared.get(0)).contains("clock_timestamp()").contains("'RESCREEN'")
                .contains("ON CONFLICT (rescreen_key) DO NOTHING");
        assertThat(prepared.get(1)).contains("WHERE rescreen_key = ?");
        prepared.clear();
        assertThatThrownBy(() -> store.requestRescreen(recording(), KycCaseId.next(IDS), CheckType.IDENTITY,
                CheckId.next(IDS), "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.requestRescreen(recording(), KycCaseId.next(IDS), CheckType.PEP,
                CheckId.next(IDS), "k".repeat(JdbcCheckStore.RESCREEN_KEY_MAX + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(prepared).as("refused before any statement").isEmpty();
    }

    /** A connection that records every statement prepared and answers every query with no row, every update with 1. */
    private Connection recording() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        prepared.add((String) args[0]);
                        yield statement();
                    }
                    case "setSavepoint" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Savepoint.class}, (p, m, a) -> null);
                    case "releaseSavepoint", "rollback" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement() {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> emptyResult();
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> {
                        if (method.getName().startsWith("set")) {
                            yield null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private ResultSet emptyResult() {
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
