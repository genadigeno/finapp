package com.finapp.app.settlement;

import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.app.telemetry.CommittedReceptionOutcomes;
import com.finapp.app.telemetry.SettlementMeters;
import com.finapp.merchant.PayoutSettlementDeclaration;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.RailId;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.JdbcSettlementFileStore;
import com.finapp.settlement.ReceptionOutcomeObserver;
import com.finapp.settlement.SettlementFileCipher;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The settlement module's composition (`P8-TSK-002`, ADR-0064, ADR-0066) — and the ONE place
 * the source register is bound, from each counterparty's own declaration ({@code INV-SET-05}):
 * the rails' positions read off {@code RailCapabilities.clearingPurpose()}, the payout's off
 * {@code PayoutSettlementDeclaration.CLEARING_PURPOSE}. This file sits in
 * {@code RailVocabularyIsConfinedTest.CONFIGURATION_FILES}: the composition root may bind
 * declarations; {@code settlement} and {@code reconciliation} may not even name a clearing
 * purpose.
 *
 * <p>No door exists yet (`P8-TSK-003` opens the first), so nothing here maps routes; the
 * beans exist so the register's coverage is verified at every startup and so the door task
 * only adds routes.
 */
@Configuration
public class SettlementBeans {

    /**
     * The register, composed and verified: every declared rail that settles externally has
     * exactly one source discharging its position, and so does the payout — an uncovered
     * settling position refuses composition, so it fails the build and every startup, never a
     * payment (`EverySettlingPositionHasASourceTest` drives both ways).
     */
    static SettlementSources composedSettlementSources(PaymentRails rails) {
        SettlementSources sources =
                SettlementSources.of(
                        List.of(
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        rails.capabilitiesOf(
                                                        com.finapp.payments.SimulatedCardPspAdapter
                                                                .RAIL.id())
                                                .clearingPurpose(),
                                        Optional.of("PSP-REM-[0-9]{4,12}")),
                                new SettlementSourceDescriptor(
                                        "simulated-scheme.cycle-report",
                                        SourceKind.SCHEME_CYCLE_REPORT,
                                        SettlementFormatId.SIM_SCHEME_JSON,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        rails.capabilitiesOf(
                                                        com.finapp.payments
                                                                .SimulatedInstantSchemeAdapter
                                                                .RAIL.id())
                                                .clearingPurpose(),
                                        Optional.of("SCH-REM-[0-9]{4,12}")),
                                new SettlementSourceDescriptor(
                                        "simulated-payout.settlement",
                                        SourceKind.PAYOUT_PROVIDER_REPORT,
                                        SettlementFormatId.SIM_PAYOUT_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.of(PayoutSettlementDeclaration.CLEARING_PURPOSE),
                                        Optional.of("PAY-REM-[0-9]{4,12}")),
                                new SettlementSourceDescriptor(
                                        "simulated-bank.statement",
                                        SourceKind.BANK_STATEMENT,
                                        SettlementFormatId.SIM_STATEMENT_TAGGED,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.empty(),
                                        Optional.empty())));
        for (RailId rail : rails.declaredIds()) {
            rails.capabilitiesOf(rail)
                    .clearingPurpose()
                    .ifPresent(
                            position -> {
                                if (sources.dischargedBy(position).isEmpty()) {
                                    throw new IllegalStateException(
                                            "rail '" + rail.value() + "' settles externally on "
                                                    + position + " and no settlement source"
                                                    + " discharges it: every externally settling"
                                                    + " position has exactly one declared source"
                                                    + " (INV-SET-05)");
                                }
                            });
        }
        if (sources.dischargedBy(PayoutSettlementDeclaration.CLEARING_PURPOSE).isEmpty()) {
            throw new IllegalStateException(
                    "the payout settles externally on "
                            + PayoutSettlementDeclaration.CLEARING_PURPOSE
                            + " and no settlement source discharges it (INV-SET-05)");
        }
        return sources;
    }

    @Bean
    SettlementSources settlementSources(PaymentRails paymentRails) {
        return composedSettlementSources(paymentRails);
    }

    /** Nonces for the file cipher — its own instance, the payments randomness precedent. */
    @Bean
    SecureRandom settlementRandomness() {
        return new SecureRandom();
    }

    @Bean
    SettlementFileStore<Connection> settlementFileStore(
            @Value("${finapp.settlement.file.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT
                            + "}")
                    String configuredKey,
            @Value("${finapp.settlement.file.key-version:1}") int keyVersion,
            SecureRandom settlementRandomness,
            Environment environment) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new JdbcSettlementFileStore(
                new SettlementFileCipher(
                        SettlementFileKey.decode(configuredKey, loopback),
                        keyVersion,
                        settlementRandomness));
    }

    @Bean
    SettlementMeters settlementMeters(MeterRegistry meterRegistry, SettlementSources settlementSources) {
        return new SettlementMeters(meterRegistry, settlementSources);
    }

    @Bean
    ReceptionOutcomeObserver receptionOutcomeObserver(SettlementMeters settlementMeters) {
        return new CommittedReceptionOutcomes(settlementMeters);
    }

    /**
     * The door itself — unconditional: it calls nothing outside the database, and the format
     * screens map is `P8-TSK-008`'s seam, empty until a format version exists (the
     * conservative whole-stream screen stands in, ADR-0066 §3).
     */
    @Bean
    FileReception<Connection> fileReception(
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            ReceptionOutcomeObserver receptionOutcomeObserver,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new FileReception<>(
                settlementSources,
                settlementFileStore,
                Map.of(),
                receptionOutcomeObserver,
                auditWriter,
                idGenerator,
                clock);
    }
}
