package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditDataObserver;
import com.finapp.credit.CreditDecision;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditProfileId;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionRequestStatus;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.DecisionSnapshotId;
import com.finapp.credit.PinnedVersions;
import com.finapp.credit.ReasonCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * No figure leaks through credit's telemetry (`P10-TSK-020`; {@code INV-CRD-02}, {@code INV-AUD-02}): the WIRED meters
 * are driven with a decision whose every figure is distinctive - the amounts, the party, the decider, every identifier -
 * and then every {@code finapp.credit.} series in the registry is read back. Each tag key is one of credit's closed keys,
 * and each value one of the closed set that key admits - a product, an outcome, a kind of decider, a catalogued reason, a
 * source kind, a declared provider, an open state, a verdict, or a policy version NUMBER - so no amount, score,
 * attribute value or party can be carried, under any key, by any credit meter; and none of the planted figures appears.
 */
@org.junit.jupiter.api.Tag("slice")
// Pointed at a database that is not there, as PlannedMetersExistTest is: the meters are the beans' own, from startup.
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("credit's telemetry carries no figure (P10-TSK-020)")
class CreditTelemetryCarriesNoFigureTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final long REQUESTED_MINOR = 1_987_654;
    private static final long APPROVED_MINOR = 1_876_543;
    private static final UUID PARTY = UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f2001");
    private static final String DECIDER = "underwriter-4242";

    @Autowired private MeterRegistry registry;
    @Autowired private CreditDecisionMetrics decisions;
    @Autowired private CreditDataMetrics data;

    @Test
    @DisplayName("every credit series' tags are closed keys with closed values; no planted amount, party, decider or id"
            + " appears in any of them")
    void noFigureInAnyCreditSeries() {
        CreditDecision approved = decision(DecisionOutcome.APPROVED, Optional.of(Money.ofMinorUnits(APPROVED_MINOR, EUR)),
                "SYSTEM", "platform");
        CreditDecision byPerson = decision(DecisionOutcome.DECLINED, Optional.empty(), "EMPLOYEE", DECIDER);
        decisions.recorded(approved, 7, Duration.ofSeconds(42));
        decisions.recorded(byPerson, 7, Duration.ofHours(30));
        data.answered(CreditSourceKind.BUREAU, UnconfiguredBureau.CODE, CreditDataObserver.Outcome.RECEIVED);
        data.called(CreditSourceKind.BUREAU, UnconfiguredBureau.CODE, Duration.ofMillis(310));

        List<Meter> credit = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("finapp.credit."))
                .toList();
        assertThat(credit).as("the credit meters are published and read").hasSizeGreaterThan(50);
        // The planted approval was counted - under whatever tags it got - so its series is among those read below. Judged
        // without naming its tags: a check that named policy_version="7" would fail first on a figure smuggled into that
        // tag, and the scan below - the property under test - would never be reached (P10-TSK-020's first probe).
        assertThat(registry.find(CreditDecisionMetrics.DECISION).tag("decision_maker", "system").tag("outcome", "approved")
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum())
                .as("the planted decision was counted, so its tags are among those read")
                .isGreaterThanOrEqualTo(1.0);

        Map<String, Pattern> closed = closedValues();
        Set<String> planted = Set.of(Long.toString(REQUESTED_MINOR), Long.toString(APPROVED_MINOR), "19876.54",
                "18765.43", PARTY.toString(), DECIDER, approved.id().value().toString(),
                approved.decisionRequest().toString(), approved.profile().value().toString(),
                approved.snapshot().value().toString(), approved.versions().policyVersion().toString(),
                approved.versions().modelVersion().toString());
        for (Meter meter : credit) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(closed).as("%s carries tag %s, not one of credit's closed keys", meter.getId().getName(),
                        tag.getKey()).containsKey(tag.getKey());
                assertThat(tag.getValue()).as("%s's %s", meter.getId().getName(), tag.getKey())
                        .matches(closed.get(tag.getKey()));
                assertThat(planted).as("%s's %s carries a planted figure", meter.getId().getName(), tag.getKey())
                        .noneMatch(figure -> tag.getValue().contains(figure));
            }
        }
        assertThat(registry.get(CreditDecisionMetrics.DECISION).tags("policy_version", "7", "decision_maker", "person",
                "outcome", "declined").counter().count())
                .as("the version tag carries the pinned version's number").isGreaterThanOrEqualTo(1.0);
    }

    /** Each key credit's meters carry, and the closed set of values it admits. */
    private static Map<String, Pattern> closedValues() {
        return Map.of(
                "product", oneOf(Arrays.stream(CreditProduct.values()).map(Enum::name)
                        .flatMap(name -> List.of(name, lower(name)).stream()).collect(Collectors.toSet())),
                "outcome", oneOf(union(Arrays.stream(DecisionOutcome.values()).map(value -> lower(value.name())),
                        Arrays.stream(CreditDataObserver.Outcome.values()).map(value -> lower(value.name())))),
                "decision_maker", oneOf(Set.of("system", "person")),
                "reason_code", oneOf(Arrays.stream(ReasonCode.values()).map(ReasonCode::code).collect(Collectors.toSet())),
                "source_kind", oneOf(Arrays.stream(CreditSourceKind.values()).map(value -> lower(value.name()))
                        .collect(Collectors.toSet())),
                "provider", oneOf(Set.of(UnconfiguredBureau.CODE, UnconfiguredFinancialData.CODE)),
                "status", oneOf(DecisionRequestStatus.OPEN.stream().map(value -> lower(value.name()))
                        .collect(Collectors.toSet())),
                "verdict", oneOf(Arrays.stream(DecisionReplayer.Verdict.values()).map(Enum::name)
                        .collect(Collectors.toSet())),
                // A version NUMBER - a handful per product over the platform's life - never an amount's digits.
                "policy_version", Pattern.compile("[0-9]{1,3}"));
    }

    private static Set<String> union(java.util.stream.Stream<String> first, java.util.stream.Stream<String> second) {
        Set<String> all = new HashSet<>();
        first.forEach(all::add);
        second.forEach(all::add);
        return all;
    }

    private static Pattern oneOf(Set<String> values) {
        return Pattern.compile(values.stream().map(Pattern::quote).collect(Collectors.joining("|")));
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static CreditDecision decision(
            DecisionOutcome outcome, Optional<Money> approved, String decidedByType, String decidedBy) {
        return new CreditDecision(CreditDecisionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f2002")),
                UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f2003"), PARTY,
                CreditProfileId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f2004")), CreditProduct.PERSONAL_LOAN,
                DecisionSnapshotId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f2005")), new byte[32], outcome,
                Money.ofMinorUnits(REQUESTED_MINOR, EUR), approved,
                approved.isPresent() ? Optional.of(36) : Optional.empty(), List.of(ReasonCode.EXPOSURE_LIMIT),
                new PinnedVersions(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f2006"),
                        UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f2007"), 1),
                decidedBy, decidedByType, Instant.EPOCH, Instant.EPOCH.plus(Duration.ofDays(30)));
    }
}
