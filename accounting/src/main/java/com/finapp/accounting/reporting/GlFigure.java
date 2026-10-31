package com.finapp.accounting.reporting;

import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlMappingVersionId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One GL figure (`P14-TSK-007`; {@code INV-ACC-02}, {@code INV-ACC-06}, {@code INV-ACC-07}): the movement of one GL
 * account in one transaction currency over one accounting period - the debits and credits of every journal line whose
 * account's purpose and owner kind the version maps to it, their count, and the net on the account's normal side. It
 * <strong>names its mapping version</strong> and is the exact sum of its {@link Component}s and of its drill-down lines
 * ({@code GeneralLedger#activity}). Nothing here is stored: it is computed from the journal in one snapshot, every time.
 *
 * <p>A period's <em>movement</em>, not a cumulative balance: the opening and closing balances are the trial balance's
 * (`P14-TSK-009`, a FINAL report run), so a live read never re-aggregates the journal from inception.
 */
public record GlFigure(
        GlAccount account,
        String currency,
        BigDecimal debits,
        BigDecimal credits,
        long lines,
        List<Component> components,
        GlMappingVersionId versionId,
        int version) {

    public GlFigure {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(debits, "debits");
        Objects.requireNonNull(credits, "credits");
        Objects.requireNonNull(versionId, "versionId");
        components = List.copyOf(components);
        if (lines < 0 || debits.signum() < 0 || credits.signum() < 0) {
            throw new IllegalArgumentException("a figure's sides and count are never negative");
        }
    }

    /**
     * The net movement on the account's normal side - debits less credits for a debit-normal classification, credits
     * less debits otherwise; negative when the period moved the account against its normal side.
     */
    public BigDecimal balance() {
        return account.classification().debitNormal() ? debits.subtract(credits) : credits.subtract(debits);
    }

    /**
     * One (ledger purpose, owner kind, counterparty) folded into the figure - the counterparty a dimension under its
     * purpose's GL account, never an account of its own (owner decision C2).
     */
    public record Component(
            String purpose,
            String ownerKind,
            Optional<String> counterparty,
            BigDecimal debits,
            BigDecimal credits,
            long lines) {

        public Component {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(debits, "debits");
            Objects.requireNonNull(credits, "credits");
        }

        /** The mapping key this component folds under. */
        public String key() {
            return purpose + "/" + ownerKind;
        }
    }
}
