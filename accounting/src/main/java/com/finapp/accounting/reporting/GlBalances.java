package com.finapp.accounting.reporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A period's GL (`P14-TSK-007`; {@code INV-ACC-06}): every GL figure with movement, the journal lines no rule of the
 * version maps - <strong>never dropped</strong>, an explicit bucket - and, per currency, the GL against the ledger's own
 * totals over the same posting-date range: the GL side of GL = ledger, which a planted double count or a dropped line
 * turns false.
 *
 * @param mapping the version the period is read under - every figure names it
 * @param currency the currency the read was narrowed to, if any
 */
public record GlBalances(
        PinnedMapping mapping,
        Optional<String> currency,
        List<GlFigure> figures,
        List<Unmapped> unmapped,
        List<Agreement> agreement) {

    public GlBalances {
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(currency, "currency");
        figures = List.copyOf(figures);
        unmapped = List.copyOf(unmapped);
        agreement = List.copyOf(agreement);
    }

    /** The figure of {@code code} in {@code currency}, when it moved. */
    public Optional<GlFigure> figure(String code, String currency) {
        return figures.stream()
                .filter(figure -> figure.account().code().equals(code) && figure.currency().equals(currency))
                .findFirst();
    }

    /** True when every currency's GL equals its ledger - every line mapped exactly once, nothing doubled or dropped. */
    public boolean agrees() {
        return agreement.stream().allMatch(Agreement::agrees);
    }

    /** The lines of one (purpose, owner kind) in one currency that the version does not map. */
    public record Unmapped(
            String purpose, String ownerKind, String currency, BigDecimal debits, BigDecimal credits, long lines) {

        public Unmapped {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(debits, "debits");
            Objects.requireNonNull(credits, "credits");
        }

        public String key() {
            return purpose + "/" + ownerKind;
        }
    }

    /**
     * One currency: the GL's mapped debits, credits and lines; the unmapped ones; and the ledger's own, read by a
     * mapping-independent statement in the same snapshot. {@link #agrees()} when the GL equals the ledger on all three -
     * which, every line being counted once in either bucket, holds exactly when nothing is unmapped or doubled.
     */
    public record Agreement(
            String currency,
            BigDecimal glDebits,
            BigDecimal glCredits,
            long glLines,
            BigDecimal unmappedDebits,
            BigDecimal unmappedCredits,
            long unmappedLines,
            BigDecimal ledgerDebits,
            BigDecimal ledgerCredits,
            long ledgerLines) {

        public Agreement {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(glDebits, "glDebits");
            Objects.requireNonNull(glCredits, "glCredits");
            Objects.requireNonNull(unmappedDebits, "unmappedDebits");
            Objects.requireNonNull(unmappedCredits, "unmappedCredits");
            Objects.requireNonNull(ledgerDebits, "ledgerDebits");
            Objects.requireNonNull(ledgerCredits, "ledgerCredits");
        }

        /** GL = ledger in this currency: debits, credits and the line count. */
        public boolean agrees() {
            return glDebits.compareTo(ledgerDebits) == 0 && glCredits.compareTo(ledgerCredits) == 0
                    && glLines == ledgerLines;
        }
    }
}
