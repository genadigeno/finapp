package com.finapp.app.fx;

import com.finapp.app.mfa.MfaKey;
import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.fx.FxEvidenceCipher;
import com.finapp.fx.FxProvider;
import com.finapp.fx.FxProviderDeclaration;
import com.finapp.fx.FxProviderEvidenceStore;
import com.finapp.fx.FxProviders;
import com.finapp.fx.JdbcFxProviderEvidenceStore;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The FX provider boundary's composition (`P9-TSK-006`, ADR-0075): the declarations this build
 * knows, the latency series (eager, whatever is configured), the evidence store under its own
 * key, and - only when {@code finapp.fx.provider.url} is set, {@code ProviderTransportGuard}
 * having admitted it - the {@code fx-sim-a} adapter under its own confined credential, timed.
 *
 * <p>The evidence store and the directory have no production consumer until the quote path
 * (`P9-TSK-008`); they are composed now because the task delivers them and because the confined
 * evidence key must be a property the application really reads ({@code
 * ConfinedCredentialVariablesTest}).
 */
@Configuration
public class FxProviderBeans {

    /**
     * Every FX provider this build can describe - the adapter's own declaration. Public since
     * `P9-TSK-011`: the settlement composition and the counterparty chart read each provider's
     * position, counterparty and settled currencies off it.
     */
    public static final Map<String, FxProviderDeclaration> DECLARED =
            Map.of(SimulatedFxProviderAdapter.CODE, SimulatedFxProviderAdapter.DECLARATION,
                    // P9-TSK-026 (M9.8): the second provider - its own counterparty, position, source and key.
                    SimulatedFxProviderAdapter.CODE_B, SimulatedFxProviderAdapter.DECLARATION_B);

    @Bean
    FxProviderMetrics fxProviderMetrics(MeterRegistry meterRegistry) {
        return new FxProviderMetrics(DECLARED.values(), meterRegistry);
    }

    @Bean
    @ConditionalOnProperty("finapp.fx.provider.url")
    FxProvider fxSimA(
            @Value("${finapp.fx.provider.url}") URI url,
            @Value("${finapp.fx.provider.timeout:PT2S}") Duration timeout,
            @Value("${finapp.fx.provider.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            FxProviderMetrics fxProviderMetrics,
            Environment environment,
            com.finapp.platform.telemetry.Spans domainSpans) {
        return new com.finapp.app.telemetry.SpannedFxProvider(fxProviderMetrics.timed(
                new SimulatedFxProviderAdapter(
                        url,
                        timeout,
                        FxProviderKey.decode(
                                configuredKey,
                                DatabaseEndpoint.isEntirelyLoopback(
                                        DatabaseEndpoint.url(environment))))), domainSpans);
    }

    /**
     * The second FX provider {@code fx-sim-b} (`P9-TSK-026`, M9.8) - wired only when {@code finapp.fx.provider.b.url}
     * is set ({@code ProviderTransportGuard} having admitted it), under its own confined credential, timed under its
     * own {@code provider} tag. A pricing policy lists it after {@code fx-sim-a} to make it the failover.
     */
    @Bean
    @ConditionalOnProperty("finapp.fx.provider.b.url")
    FxProvider fxSimB(
            @Value("${finapp.fx.provider.b.url}") URI url,
            @Value("${finapp.fx.provider.b.timeout:PT2S}") Duration timeout,
            @Value("${finapp.fx.provider.b.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            FxProviderMetrics fxProviderMetrics,
            Environment environment,
            com.finapp.platform.telemetry.Spans domainSpans) {
        return new com.finapp.app.telemetry.SpannedFxProvider(fxProviderMetrics.timed(
                new SimulatedFxProviderAdapter(
                        SimulatedFxProviderAdapter.CODE_B,
                        url,
                        timeout,
                        FxProviderBKey.decode(
                                configuredKey,
                                DatabaseEndpoint.isEntirelyLoopback(
                                        DatabaseEndpoint.url(environment))))), domainSpans);
    }

    /** The configured providers, each with the declaration this build holds for it. */
    @Bean
    FxProviders fxProviders(List<FxProvider> fxProviderAdapters) {
        return new FxProviders(
                fxProviderAdapters.stream()
                        .map(
                                adapter -> {
                                    FxProviderDeclaration declaration = DECLARED.get(adapter.code());
                                    if (declaration == null) {
                                        throw new IllegalStateException(
                                                "an FX provider adapter with no declaration: "
                                                        + adapter.code());
                                    }
                                    return new FxProviders.Composed(declaration, adapter);
                                })
                        .toList());
    }

    @Bean
    FxEvidenceCipher fxEvidenceCipher(
            @Value("${finapp.fx.evidence.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            @Value("${finapp.fx.evidence.key-version:1}") int keyVersion,
            Environment environment) {
        return new FxEvidenceCipher(
                FxEvidenceKey.decode(
                        configuredKey,
                        DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment))),
                keyVersion,
                new SecureRandom());
    }

    @Bean
    FxProviderEvidenceStore<Connection> fxProviderEvidenceStore(
            FxEvidenceCipher fxEvidenceCipher, IdGenerator idGenerator) {
        return new JdbcFxProviderEvidenceStore(fxEvidenceCipher, idGenerator);
    }
}
