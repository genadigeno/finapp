package com.finapp.accounting.reporting;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRateReads;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code TRIAL_BALANCE} report definition, version 1 (`P14-TSK-009`; ADR-0115 decision 1; {@code PHASE_14_PLAN.md}
 * section 13.6; {@code INV-ACC-01} reported, {@code INV-ACC-04}, {@code INV-ACC-06}) - the platform's first production
 * definition: a period's {@link TrialBalance}, generated inside the run's read-only snapshot through
 * {@link TrialBalanceReads}.
 *
 * <p><strong>What it reads, in one snapshot.</strong> The period's row (its bounds, status, pin and its predecessor's
 * pin); the pinned version's content; the opening's lines - every line dated before the period, from inception; the
 * movement's lines - the period's month, {@code JournalReads.Range.of}; and the ledger's own three windows, bounded by
 * the period's row as read. Then the pure fold ({@link TrialBalance#fold}) and its canonical body.
 *
 * <p><strong>The version is the run's pin.</strong> A FINAL run reads the version its period's lock pinned - and refuses
 * to answer if the period's row in the snapshot pins another ({@link PinMismatch}, the run fails
 * {@code DEFINITION_FAILED}): a FINAL trial balance is under the period's pin or it is not issued. A PROVISIONAL run reads
 * the version its claim found in force, never re-resolved at generation. A run with no version pinned (a period locked
 * before any version was activated - the production state after deployment) still answers: every line in an unmapped
 * row, so zero and equality are still judged and {@code fullyMapped} is false.
 *
 * <p><strong>The cut</strong> (`P14-TSK-008`'s review, M-8): a PROVISIONAL run is labelled with its claim's instant, and
 * every read of it counts only entries created at or before that instant - so its content is approximately the journal
 * as asked, not as it stood whenever the run was generated (a reclaimed run's generation can be minutes later). The cut is
 * fuzzy both ways (`P14-TSK-009`'s review, M-2): {@code created_at} is the posting instance's clock, minted before its
 * commit, so an entry minted before the cut but committed after the snapshot opened is absent, and an instance's clock
 * skew moves an entry across the cut - a never-issued snapshot's labelled imprecision; only FINAL runs regenerate, so no
 * false divergence follows. A FINAL run's range is immutable after its lock: it reads no cut, and its content never names
 * one.
 *
 * <p><strong>For the close</strong> (`P14-TSK-011` triggers the FINAL run at lock, not this task): it claims
 * {@link #CODE} for the locked period with no parameters ({@code RunParameters.none()}), generates the run it holds
 * ({@code ReportRunGenerator.generateAndComplete}), and reads the verdict from the stored, frozen content - {@link
 * #VERDICT_PATH}, {@code true} or {@code false} - having checked the run is FINAL and of this code and version. A
 * {@code false} fails the close's check and is never corrected. {@code passes} is the trial balance's check only:
 * mapping completeness is {@code GL_EQUALS_LEDGER}'s.
 *
 * <p><strong>Plain or translated</strong> (`P14-TSK-015`; ADR-0116 decision 3): a run naming no valuation rate set - the
 * close's, {@code RunParameters.none()}, which never needs one - reads no rate and writes {@link TrialBalance#body()},
 * byte for byte what version 1 always wrote. A run naming its period's approved set ({@link #takesValuationRateSet()}) is
 * another question and another run of the same version: the same body with the translated columns and the
 * {@code translation} member ({@link TrialBalance#body(Translation.Rates)}), its verdict the same object at the same path
 * - translation does not enter {@code passes}.
 *
 * <p>Report code: it reads through its ports on the snapshot it is handed and writes nothing
 * ({@code ReportingNeverWritesTest}). A change to its output is a new {@link #VERSION}, the old one kept for
 * regeneration. <strong>Translation did not move the version</strong>: nothing version 1 ever issued changes, and a run
 * naming a set was refused until translation existed, so no such run exists to regenerate.
 */
public final class TrialBalanceDefinition implements ReportDefinition {

    /** The definition's code - {@code ReportKind.TRIAL_BALANCE}. */
    public static final String CODE = ReportKind.TRIAL_BALANCE.name();

    /** The definition's version: the bodies {@link TrialBalance#body()} and {@link TrialBalance#body(Translation.Rates)} write. */
    public static final int VERSION = 1;

    /**
     * Where the close reads the verdict in a run's stored content - {@code content::jsonb #>> '{body,verdict,passes}'},
     * {@code true} when every currency is zero and equal to the ledger over the period's own bounds.
     */
    public static final String VERDICT_PATH = "{body,verdict,passes}";

    /** A FINAL run's version is not the one its period's row pins in the snapshot - nothing is issued. */
    public static final class PinMismatch extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PinMismatch() {
            super("a FINAL trial balance is read under its period's pinned mapping version, and the run names another");
        }
    }

    private final TrialBalanceReads reads;
    private final Optional<ValuationRateReads> rates;

    /** The trial balance with no way to the rates: a run naming a valuation rate set is refused at its claim. */
    public TrialBalanceDefinition(TrialBalanceReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.rates = Optional.empty();
    }

    /** The trial balance that may be translated - production's. */
    public TrialBalanceDefinition(TrialBalanceReads reads, ValuationRateReads rates) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.rates = Optional.of(Objects.requireNonNull(rates, "rates"));
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public boolean takesValuationRateSet() {
        return rates.isPresent();
    }

    @Override
    public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
        TrialBalance balance = trialBalance(snapshot, inputs);
        if (inputs.parameters().valuationRateSet().isEmpty()) {
            return balance.body();
        }
        return balance.body(Translation.Rates.pinnedBy(rates.orElseThrow(() -> new IllegalStateException(
                "a run named a valuation rate set this definition cannot read")), snapshot, inputs));
    }

    /**
     * The period's trial balance for {@code inputs}, read through {@code snapshot}.
     *
     * @throws IllegalStateException when the period's row is not in the snapshot
     * @throws PinMismatch when a FINAL run's version is not its period's pin
     */
    public TrialBalance trialBalance(Connection snapshot, ReportInputs inputs) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(inputs, "inputs");
        TrialBalanceReads.Period period = reads.period(snapshot, inputs.periodId())
                .orElseThrow(() -> new IllegalStateException("the run's period is not in the snapshot"));
        if (inputs.basis() == RunBasis.FINAL && !period.pin().equals(inputs.mappingVersionId())) {
            throw new PinMismatch();
        }
        Optional<GlMappingVersionId> pinned = inputs.mappingVersionId();
        Optional<GlMappingVersion> version = pinned.map(id -> reads.version(snapshot, id));
        Optional<Instant> cut = inputs.basis() == RunBasis.PROVISIONAL ? Optional.of(inputs.cutAt()) : Optional.empty();
        JournalReads.Range range = JournalReads.Range.of(inputs.period());
        return TrialBalance.fold(inputs.period(), range, period, pinned, version,
                reads.before(snapshot, inputs.start(), cut),
                reads.within(snapshot, range, cut),
                reads.ledger(snapshot, period, cut));
    }
}
