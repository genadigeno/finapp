package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link BeneficialOwner} aggregate's own rules (`P2-TSK-015`). Few and crucial: the
 * qualification invariant, the stake bounds, the self-reference refusal, and the
 * no-personal-data rendering — everything else about the graph is the schema's and the
 * store's, proven against the real database in {@code KybCaseDatabaseTest}.
 */
@DisplayName("a beneficial owner qualifies, is bounded, and renders no personal data (P2-TSK-015)")
class BeneficialOwnerTest {

    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC),
                    new SecureRandom());
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("an owner with neither a stake nor a control role is unrepresentable")
    void anOwnerMustQualify() {
        assertThatThrownBy(
                        () ->
                                BeneficialOwner.declare(
                                        IDS,
                                        CLOCK,
                                        KycCaseId.next(IDS),
                                        IDS.next(),
                                        KycCaseId.next(IDS),
                                        OptionalInt.empty(),
                                        Optional.empty()))
                .as("a person on the graph for no stated reason is what a reviewer cannot defend")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a stake is 1..10000 basis points; zero and beyond-whole are refused")
    void theStakeIsBounded() {
        for (int outOfRange : new int[] {0, -1, 10_001}) {
            assertThatThrownBy(
                            () ->
                                    BeneficialOwner.declare(
                                            IDS,
                                            CLOCK,
                                            KycCaseId.next(IDS),
                                            IDS.next(),
                                            KycCaseId.next(IDS),
                                            OptionalInt.of(outOfRange),
                                            Optional.empty()))
                    .as("%s basis points is not a stake", outOfRange)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // The bounds themselves are legal - an owner may hold the whole organisation.
        assertThat(
                        BeneficialOwner.declare(
                                        IDS,
                                        CLOCK,
                                        KycCaseId.next(IDS),
                                        IDS.next(),
                                        KycCaseId.next(IDS),
                                        OptionalInt.of(10_000),
                                        Optional.empty())
                                .stakeBasisPoints())
                .hasValue(10_000);
    }

    @Test
    @DisplayName("a role alone qualifies - the FATF fallback owner has no equity")
    void aRoleAloneQualifies() {
        BeneficialOwner owner =
                BeneficialOwner.declare(
                        IDS,
                        CLOCK,
                        KycCaseId.next(IDS),
                        IDS.next(),
                        KycCaseId.next(IDS),
                        OptionalInt.empty(),
                        Optional.of(ControlRole.SENIOR_MANAGING_OFFICIAL));
        assertThat(owner.controlRole()).contains(ControlRole.SENIOR_MANAGING_OFFICIAL);
        assertThat(owner.stakeBasisPoints()).isEmpty();
    }

    @Test
    @DisplayName("a case cannot be its own owner's verification")
    void selfReferenceIsRefused() {
        KycCaseId caseId = KycCaseId.next(IDS);
        assertThatThrownBy(
                        () ->
                                BeneficialOwner.declare(
                                        IDS,
                                        CLOCK,
                                        caseId,
                                        IDS.next(),
                                        caseId,
                                        OptionalInt.of(5_000),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString carries identifiers and enumerated names only (INV-AUD-02)")
    void renderingCarriesNoAttributes() {
        BeneficialOwner owner =
                BeneficialOwner.declare(
                        IDS,
                        CLOCK,
                        KycCaseId.next(IDS),
                        IDS.next(),
                        KycCaseId.next(IDS),
                        OptionalInt.of(2_500),
                        Optional.of(ControlRole.DIRECTOR));
        // The stake and role are CONFIDENTIAL facts about a person (DATA_CLASSIFICATION §4);
        // a log line renders the row's identity, never its content.
        assertThat(owner.toString()).doesNotContain("2500").doesNotContain("DIRECTOR");
    }
}
