package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.FinappApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;

/**
 * Every connection the platform makes reaches the primary, whatever the driver could choose (`P16-TSK-012`, ADR-0142;
 * {@code PHASE_GATES.md} §5 Phase 16 criterion 6). Hermetic: the guard reads configuration and connects to nothing.
 */
@DisplayName("no connection may be routed by the driver to a server that is not the primary (P16-TSK-012)")
class PrimaryOnlyGuardTest {

    private static final String ONE_HOST = "jdbc:postgresql://db.internal:5432/finapp";
    private static final String TWO_HOSTS = "jdbc:postgresql://primary.internal:5432,standby.internal:5432/finapp";

    @Test
    @DisplayName("one host and no setting, the platform's shape, starts; so do the driver's two spellings of a primary")
    void oneHostStarts() {
        assertThatCode(() -> PrimaryOnlyGuard.verify("jdbc:postgresql://127.0.0.1:5432/finapp", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> PrimaryOnlyGuard.verify(ONE_HOST, Map.of("connectTimeout", "5", "ApplicationName", "finapp")))
                .doesNotThrowAnyException();
        for (String primary : List.of("primary", "master")) {
            assertThatCode(() -> PrimaryOnlyGuard.verify(ONE_HOST + "?targetServerType=" + primary, Map.of())).doesNotThrowAnyException();
            assertThatCode(() -> PrimaryOnlyGuard.verify(ONE_HOST, Map.of("targetServerType", primary))).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a targetServerType that admits a standby is refused, in the URL or as a driver property, whatever the key's case")
    void aSettingThatAdmitsAStandbyIsRefused() {
        for (String value : List.of("any", "secondary", "preferSecondary", "preferPrimary", "slave", "preferSlave", "Primary", "")) {
            assertThatThrownBy(() -> PrimaryOnlyGuard.verify(ONE_HOST + "?sslmode=verify-full&targetServerType=" + value, Map.of()))
                    .as("?targetServerType=%s", value).isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start");
            assertThatThrownBy(() -> PrimaryOnlyGuard.verify(ONE_HOST, Map.of("targetServerType", value)))
                    .as("the property %s", value).isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start");
        }
        assertThatThrownBy(() -> PrimaryOnlyGuard.verify(ONE_HOST + "?TargetServerType=any", Map.of()))
                .as("a key in another case").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PrimaryOnlyGuard.verify(ONE_HOST + "?targetServerType=primary", Map.of("targetservertype", "secondary")))
                .as("both sources must say primary: one that does not is refused, whichever the driver reads first")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("several hosts, or a host the guard cannot read, start only with targetServerType=primary under the driver's own key")
    void severalHostsNeedThePrimaryOnly() {
        assertThatThrownBy(() -> PrimaryOnlyGuard.verify(TWO_HOSTS, Map.of()))
                .as("the driver's default, any, would take the first host that answers").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PrimaryOnlyGuard.SETTING);
        assertThatThrownBy(() -> PrimaryOnlyGuard.verify(ONE_HOST + "?host=standby.internal", Map.of()))
                .as("a host parameter the URL's authority does not show").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PrimaryOnlyGuard.verify("jdbc:postgresql:///finapp?service=reporting", Map.of()))
                .as("a service file's host").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PrimaryOnlyGuard.verify(TWO_HOSTS, Map.of("targetservertype", "primary")))
                .as("a spelling the driver ignores, so its default applies").isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> PrimaryOnlyGuard.verify(TWO_HOSTS + "?targetServerType=primary", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> PrimaryOnlyGuard.verify(TWO_HOSTS, Map.of("targetServerType", "primary"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the guard is a component the application scans, and judges the address the pool really connects with")
    void theGuardIsWiredAndReadsTheEffectiveConfiguration() {
        assertThat(PrimaryOnlyGuard.class.isAnnotationPresent(Component.class)).as("a component, constructed at startup").isTrue();
        assertThat(PrimaryOnlyGuard.class.getPackageName()).as("under the application's component scan")
                .startsWith(FinappApplication.class.getPackageName());

        // Hikari's own URL wins over the generic one, so the generic one left on one host proves nothing.
        MockEnvironment overridden = new MockEnvironment()
                .withProperty("spring.datasource.url", ONE_HOST)
                .withProperty("spring.datasource.hikari.jdbc-url", TWO_HOSTS);
        assertThatThrownBy(() -> new PrimaryOnlyGuard(overridden)).isInstanceOf(IllegalStateException.class);

        MockEnvironment property = new MockEnvironment()
                .withProperty("spring.datasource.url", ONE_HOST)
                .withProperty("spring.datasource.hikari.data-source-properties.targetServerType", "preferSecondary");
        assertThatThrownBy(() -> new PrimaryOnlyGuard(property)).isInstanceOf(IllegalStateException.class);

        MockEnvironment primaryOnly = new MockEnvironment()
                .withProperty("spring.datasource.url", TWO_HOSTS)
                .withProperty("spring.datasource.hikari.data-source-properties.targetServerType", "primary");
        assertThatCode(() -> new PrimaryOnlyGuard(primaryOnly)).as("the property's key reaches the guard as the driver spells it")
                .doesNotThrowAnyException();

        assertThatCode(() -> new PrimaryOnlyGuard(new MockEnvironment().withProperty("spring.datasource.url", ONE_HOST)))
                .doesNotThrowAnyException();
    }
}
