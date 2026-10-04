package com.finapp.app.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSetProposal;
import com.finapp.reconciliation.RuleSetStatus;
import com.finapp.reconciliation.RuleSetStore;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Activates the rule-set v2 successors that make JPY and BHD reconcilable on the four seeded
 * settlement sources (`P9-TSK-003`, owner decision O6) - THROUGH THE FOUR-EYES DOOR, never by a
 * write into the rule tables: {@link RuleSetAdministration#propose} by one person, then
 * {@link RuleSetAdministration#approve} by a different one (`INV-AUD-04`), each in its own
 * transaction, exactly as the runbook orders an operator to do it. No migration seeds a rule set
 * (D26), so this is the one way a test reaches the policy production will run.
 *
 * <p><strong>A proposal restates the WHOLE version</strong> (ADR-0068 §8: a version is frozen
 * from {@code PROPOSED} and nothing is appended after review), so the fixture reads the source's
 * {@code ACTIVE} version back through the door's own reading ({@link
 * RuleSetAdministration#versions}) and restates every member - lags, rules, tolerances, fee
 * schedules and thresholds, unchanged - adding O6's rows and nothing else:
 *
 * <ul>
 *   <li>high-value thresholds JPY {@value #JPY_THRESHOLD} and BHD {@value #BHD_THRESHOLD} on
 *       EVERY source;
 *   <li>for each fee line type the version prices, a JPY and a BHD schedule - the rate the line
 *       type's EUR row carries, HALF_UP, at the currency's own minor units, and O6's fixed part:
 *       {@code PROCESSING_FEE} 40 yen / 100 fils, {@code SCHEME_FEE} 15 / 40, {@code PAYOUT_FEE}
 *       40 / 100, {@code BANK_FEE} 75 / 200;
 *   <li>for each fee bound the version carries (the PSP's alone), a JPY and a BHD bound:
 *       {@code PROCESSING_FEE_PER_LINE} 3 / 10, {@code PROCESSING_FEE_PER_BATCH} 75 / 200.
 * </ul>
 *
 * <p><strong>It converges.</strong> Suites share a database container, so a source whose
 * {@code ACTIVE} version already carries a JPY high-value threshold is left alone and reported
 * {@linkplain Activation#alreadyActive already active}: a second call writes nothing. (A
 * proposal left pending by a crashed caller is NOT withdrawn here - the door then refuses with
 * {@code RuleSetProposalPending}, loudly, rather than this fixture deciding someone's proposal.)
 *
 * <p><strong>What it changes for its container.</strong> Every later opener and run on a seeded
 * source pins the v2 it activates (ADR-0068 §8); the two-minor-unit members are restated byte for
 * byte, so a EUR, GBP or USD decision prices exactly as under v1 - but a suite asserting the v1
 * identifier itself must not share a container with a caller of this fixture.
 */
public final class JpyAndBhdRuleSets {

    public static final UUID PSP_SOURCE = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    public static final UUID SCHEME_SOURCE =
            UUID.fromString("01a0e2bc-8200-7002-8000-000000000002");
    public static final UUID PAYOUT_SOURCE =
            UUID.fromString("01a0e2bc-8200-7003-8000-000000000003");
    public static final UUID BANK_SOURCE = UUID.fromString("01a0e2bc-8200-7004-8000-000000000004");

    /** The four seeded sources, in their seed order. */
    public static final List<UUID> SEEDED_SOURCES =
            List.of(PSP_SOURCE, SCHEME_SOURCE, PAYOUT_SOURCE, BANK_SOURCE);

    /** The v1 versions reconciliation `V002` seeds, by source. */
    public static final Map<UUID, UUID> SEEDED_V1 =
            Map.of(
                    PSP_SOURCE, UUID.fromString("01a0e2bd-8300-7001-8000-000000000001"),
                    SCHEME_SOURCE, UUID.fromString("01a0e2bd-8300-7002-8000-000000000002"),
                    PAYOUT_SOURCE, UUID.fromString("01a0e2bd-8300-7003-8000-000000000003"),
                    BANK_SOURCE, UUID.fromString("01a0e2bd-8300-7004-8000-000000000004"));

    public static final CurrencyCode JPY = CurrencyCode.of("JPY");
    public static final CurrencyCode BHD = CurrencyCode.of("BHD");

    /** O6: the high-value threshold, in minor units - 150,000 yen. */
    public static final long JPY_THRESHOLD = 150_000L;

    /** O6: the high-value threshold, in minor units - 400.000 BHD. */
    public static final long BHD_THRESHOLD = 400_000L;

    /** O6's fixed parts per priced fee line type: JPY in yen, BHD in fils. */
    public static final Map<ExternalLineType, Map<CurrencyCode, Long>> FIXED_PARTS =
            Map.of(
                    ExternalLineType.PROCESSING_FEE, Map.of(JPY, 40L, BHD, 100L),
                    ExternalLineType.SCHEME_FEE, Map.of(JPY, 15L, BHD, 40L),
                    ExternalLineType.PAYOUT_FEE, Map.of(JPY, 40L, BHD, 100L),
                    ExternalLineType.BANK_FEE, Map.of(JPY, 75L, BHD, 200L));

    /** O6's PSP fee bounds, in minor units. */
    public static final Map<String, Map<CurrencyCode, Long>> FEE_BOUNDS =
            Map.of(
                    "PROCESSING_FEE_PER_LINE", Map.of(JPY, 3L, BHD, 10L),
                    "PROCESSING_FEE_PER_BATCH", Map.of(JPY, 75L, BHD, 200L));

    /** The reason both acts carry - prose with no digit run any screen could read as a number. */
    public static final String REASON =
            "P9-TSK-003: JPY and BHD become postable - owner decision O6's high-value thresholds,"
                    + " provider fee terms and PSP fee bounds; every existing member restated"
                    + " unchanged";

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String HALF_UP = "HALF_UP";
    private static final int VERSION_BOUND = 50;

    /** What one source ended with: its ACTIVE version, and whether this call activated it. */
    public record Activation(UUID sourceId, UUID ruleSetId, int version, boolean alreadyActive) {}

    private JpyAndBhdRuleSets() {}

    /**
     * Proposes (as {@code proposer}) and activates (as {@code approver}) each seeded source's
     * successor carrying O6's rows, skipping a source whose ACTIVE version already carries a JPY
     * high-value threshold.
     *
     * @throws IllegalArgumentException when the two actors are one person - the door would
     *     refuse the activation anyway; refused here first, before anything is proposed
     */
    public static List<Activation> activate(
            RuleSetAdministration administration, Actor proposer, Actor approver)
            throws SQLException {
        if (proposer.id().equals(approver.id())) {
            throw new IllegalArgumentException(
                    "a rule set version is activated by someone other than its proposer");
        }
        List<Activation> activations = new ArrayList<>();
        for (UUID source : SEEDED_SOURCES) {
            try (Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                RuleSetStore.VersionView active = activeVersion(administration, app, source);
                if (active.severityThresholds().containsKey(JPY)) {
                    app.rollback();
                    activations.add(
                            new Activation(source, active.row().id(), active.row().version(),
                                    true));
                    continue;
                }
                RuleSetAdministration.Proposed proposed;
                try {
                    proposed =
                            administration.propose(
                                    app, restatement(active), proposer, Instant.now(CLOCK),
                                    CorrelationId.generate(IDS));
                    app.commit();
                } catch (RuntimeException refused) {
                    app.rollback();
                    throw refused;
                }
                RuleSetAdministration.Decided decided;
                try {
                    decided =
                            administration.approve(
                                    app, proposed.ruleSetId(), approver, REASON,
                                    Instant.now(CLOCK), CorrelationId.generate(IDS));
                    app.commit();
                } catch (RuntimeException refused) {
                    app.rollback();
                    throw refused;
                }
                activations.add(
                        new Activation(source, decided.ruleSetId(), decided.version(), false));
            }
        }
        return List.copyOf(activations);
    }

    /** The source's ACTIVE version with its whole content, read through the door. */
    public static RuleSetStore.VersionView activeVersion(
            RuleSetAdministration administration, Connection unitOfWork, UUID source) {
        return administration.versions(unitOfWork, source, VERSION_BOUND).stream()
                .filter(view -> view.row().status() == RuleSetStatus.ACTIVE)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException(
                                "a seeded source always has an ACTIVE rule set version: "
                                        + source));
    }

    /**
     * The successor of {@code active}: every member restated unchanged, O6's JPY and BHD rows
     * added - the proposal an operator would type, member by member.
     */
    public static RuleSetProposal restatement(RuleSetStore.VersionView active) {
        List<RuleSetProposal.Tolerance> tolerances = new ArrayList<>(active.tolerances());
        Set<String> boundsCarried = new LinkedHashSet<>();
        for (RuleSetProposal.Tolerance tolerance : active.tolerances()) {
            if (FEE_BOUNDS.containsKey(tolerance.comparison())) {
                boundsCarried.add(tolerance.comparison());
            }
        }
        for (String comparison : boundsCarried) {
            for (CurrencyCode currency : List.of(JPY, BHD)) {
                tolerances.add(
                        new RuleSetProposal.Tolerance(
                                comparison, Optional.of(currency),
                                Optional.of(FEE_BOUNDS.get(comparison).get(currency)),
                                Optional.empty()));
            }
        }

        List<RuleSetProposal.FeeTerms> fees = new ArrayList<>(active.feeSchedules());
        Set<ExternalLineType> linesPriced = new LinkedHashSet<>();
        for (RuleSetProposal.FeeTerms terms : active.feeSchedules()) {
            linesPriced.add(terms.lineType());
        }
        for (ExternalLineType line : linesPriced) {
            BigDecimal rate =
                    active.feeSchedules().stream()
                            .filter(terms -> terms.lineType() == line
                                    && terms.currency().equals(EUR))
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalStateException(
                                            "O6 takes each new row's rate from the line type's"
                                                    + " EUR row, and " + line + " has none"))
                            .rate();
            Map<CurrencyCode, Long> fixed = FIXED_PARTS.get(line);
            if (fixed == null) {
                throw new IllegalStateException("O6 names no fixed part for " + line);
            }
            for (CurrencyCode currency : List.of(JPY, BHD)) {
                fees.add(
                        new RuleSetProposal.FeeTerms(
                                line, currency, rate, fixed.get(currency),
                                currency.minorUnits(), HALF_UP));
            }
        }

        Map<CurrencyCode, Long> thresholds = new LinkedHashMap<>(active.severityThresholds());
        thresholds.put(JPY, JPY_THRESHOLD);
        thresholds.put(BHD, BHD_THRESHOLD);

        RuleSetStore.VersionRow row = active.row();
        return new RuleSetProposal(
                row.sourceId(),
                row.fundingLagDays(),
                row.gainMinAgeDays(),
                active.lagDays(),
                active.rules(),
                tolerances,
                fees,
                thresholds,
                REASON);
    }
}
