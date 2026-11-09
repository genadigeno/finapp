package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The fingerprint is stable for equal configuration, moved by a {@code FINANCIAL} value and by nothing else, and carries no value
 * (`P15-TSK-031`). It is computed from the register's own rows, so this reads the real table and not a fixture of it where that
 * matters; the promtool cases are {@code infra/prometheus/tests/config-fingerprint.test.yml}.
 */
@org.junit.jupiter.api.Tag("slice") // ApplicationContextRunner: the taxonomy reads it as a slice
@DisplayName("the configuration fingerprint: stable for equal configuration, moved by a FINANCIAL value, carrying none (P15-TSK-031)")
class ConfigurationFingerprintTest {

    private static final String HEADER = "| Property | Variable | Class | Default | local | test | ci | staging | production | A mixed fleet |\n|---|---|---|---|---|---|---|---|---|---|\n";

    private static List<ConfigurationRegister.Row> two() {
        return ConfigurationRegister.rows(HEADER
                + "| `finapp.a` | `FINAPP_A` | FINANCIAL | `PT1H` | default | default | default | default | default | why it is safe to mix these values at all |\n"
                + "| `finapp.b` | `FINAPP_B` | FINANCIAL | (none) | default | default | default | default | default | why it is safe to mix these values at all |\n"
                + "| `finapp.c` | `FINAPP_C` | OPERATIONAL | `5` | default | default | default | default | default | - |\n");
    }

    private static String of(Map<String, String> environment) {
        return ConfigurationFingerprint.of(two(), environment::get);
    }

    @Test
    @DisplayName("equal configuration hashes equal, however the rows are ordered")
    void stableForEqualConfiguration() {
        assertThat(of(Map.of("finapp.a", "PT2H"))).isEqualTo(of(Map.of("finapp.a", "PT2H")));
        List<ConfigurationRegister.Row> reversed = new java.util.ArrayList<>(two());
        java.util.Collections.reverse(reversed);
        assertThat(ConfigurationFingerprint.of(reversed, Map.of("finapp.a", "PT2H")::get)).isEqualTo(of(Map.of("finapp.a", "PT2H")));
    }

    @Test
    @DisplayName("a FINANCIAL value moves it, and an unset property is its default")
    void aFinancialValueMovesIt() {
        assertThat(of(Map.of("finapp.a", "PT2H"))).isNotEqualTo(of(Map.of("finapp.a", "PT3H")));
        assertThat(of(Map.of("finapp.a", "PT1H"))).as("the default written out is the default").isEqualTo(of(Map.of()));
        assertThat(of(Map.of("finapp.b", ""))).as("blank and absent are different to the code that reads them").isNotEqualTo(of(Map.of()));
    }

    @Test
    @DisplayName("an OPERATIONAL value moves nothing")
    void anOperationalValueDoesNotMoveIt() {
        assertThat(of(Map.of("finapp.c", "500"))).isEqualTo(of(Map.of()));
    }

    @Test
    @DisplayName("a name and a value cannot be re-split to hash the same")
    void theEncodingIsUnambiguous() {
        assertThat(of(Map.of("finapp.a", "XY", "finapp.b", "Z"))).isNotEqualTo(of(Map.of("finapp.a", "X", "finapp.b", "YZ")));
    }

    @Test
    @DisplayName("the output is a whole digest in lower hex, and no value is readable in it")
    void theOutputCarriesNoValue() {
        String value = "SIMBANK-EUR-01";
        String fingerprint = ConfigurationFingerprint.of(ConfigurationRegister.rows(HEADER
                + "| `finapp.settlement.bank.account-reference.EUR` | `X` | FINANCIAL | `SIMBANK-EUR-01` | default | default | default | default | default | why it is safe to mix these values at all |\n"), Map.<String, String>of()::get);
        assertThat(fingerprint).matches("[0-9a-f]{64}").doesNotContain(value);
    }

    @Test
    @DisplayName("the variable a pod is given reaches the digest through Spring's own binding, the way @Value reads it")
    void anEnvironmentVariableReachesTheDigest() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(Map.of("FINAPP_A", "PT9H"))));
        ConfigurationPropertySources.attach(environment);
        assertThat(ConfigurationFingerprint.of(two(), environment::getProperty)).isEqualTo(of(Map.of("finapp.a", "PT9H")));
    }

    @Test
    @DisplayName("the running bean reads the real register: one gauge of 1, tagged with the digest of this configuration, and it moves with a FINANCIAL value")
    void theBeanPublishesTheDigest() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(ConfigurationFingerprintBeans.class);
        String[] tags = new String[2];
        runner.run(context -> {
            Gauge gauge = context.getBean(MeterRegistry.class).get(ConfigurationFingerprintBeans.METER).gauge();
            assertThat(gauge.value()).isEqualTo(1d);
            assertThat(gauge.getId().getTags()).hasSize(1);
            tags[0] = gauge.getId().getTag(ConfigurationFingerprintBeans.TAG);
        });
        runner.withPropertyValues("finapp.accounting.period.lock-grace-business-days=5").run(context -> {
            tags[1] = context.getBean(MeterRegistry.class).get(ConfigurationFingerprintBeans.METER).gauge().getId().getTag(ConfigurationFingerprintBeans.TAG);
        });
        assertThat(tags[0]).matches("[0-9a-f]{64}");
        assertThat(tags[1]).matches("[0-9a-f]{64}").isNotEqualTo(tags[0]);
    }

    @Test
    @DisplayName("the register's bytes in this build are the document's")
    void theEmbeddedRegisterIsTheDocument() throws IOException {
        try (InputStream embedded = ConfigurationRegister.class.getResourceAsStream(ConfigurationRegister.RESOURCE)) {
            assertThat(embedded).as("processResources wrote it").isNotNull();
            assertThat(new String(embedded.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo(RepositoryPaths.read("docs/architecture/CONFIGURATION_REGISTER.md"));
        }
    }

    @Test
    @DisplayName("a register with no table, or a row short of a column, is refused rather than read as empty")
    void anUnreadableRegisterIsRefused() {
        assertThatThrownBy(() -> ConfigurationRegister.rows("no table here")).isInstanceOf(IllegalStateException.class).hasMessageContaining("no table");
        assertThatThrownBy(() -> ConfigurationRegister.rows(HEADER + "| `finapp.a` | `X` | FINANCIAL |\n"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("columns");
        assertThatThrownBy(() -> ConfigurationRegister.rows(HEADER)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no rows");
    }
}
