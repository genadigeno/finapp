package com.finapp.fx;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * The expectation-opening seam on the cover's acting branch (`P9-TSK-011`, ADR-0067 §1,
 * PHASE_9_PLAN.md section 12.9.1) - {@code payments.SettlementExpectations}' and
 * {@code merchant.PayoutSettlementExpectations}' twin, declared here because {@code fx} may see
 * neither {@code settlement} nor {@code reconciliation}: what an executed cover means for the
 * position proof is the composition root's to record, and {@code app}'s one recorder implements
 * all three ports.
 *
 * <h2>Where it is called, and what crosses</h2>
 *
 * <p>In the cover outcome's transaction, after the cover entry whose provider lines the
 * expectations copy (`P9-TSK-012` posts the first): one {@link Kind#FX_SELL_LEG} on the sold
 * currency's {@code FX_PROVIDER_CLEARING(provider)} account and one {@link Kind#FX_BUY_LEG} on the
 * bought currency's - each leg in its own currency, because reconciliation never converts
 * ({@code INV-REC-08}). Identifiers, the declared position AND its counterparty, the posted clearing
 * account and entry, and the typed references cross - <strong>never an amount, a direction or a
 * date</strong>: the implementation reads all three off the posted entry (ADR-0067 §3, §4).
 */
public interface FxSettlementExpectations {

    /** Opens one leg's expectation with its keys. */
    void open(Connection unitOfWork, Opening opening);

    /** A cover's two legs (PHASE_9_PLAN.md section 12.9.1). */
    enum Kind {
        /** The currency the platform delivered to the provider - OUTBOUND on its position. */
        FX_SELL_LEG,
        /** The currency the provider delivers to the platform - INBOUND on its position. */
        FX_BUY_LEG
    }

    /** The typed references the FX provider's trade report will quote. */
    enum ReferenceKind {
        /** The attempt's minted {@code client_reference} {@code T-...}: the leg's key. */
        COVER_REF,
        /** The provider's trade reference, once it executed: an alias, for the trace. */
        FX_TRADE_REF
    }

    /** One typed reference. */
    record Key(ReferenceKind kind, String value) {
        public Key {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(value, "value must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException("a key value must not be blank");
            }
        }
    }

    /**
     * One leg's opening: copies of facts the cover outcome already holds.
     *
     * @param operationRef the identifier the posting key names
     * @param position the provider's declared clearing purpose ({@link FxProviderDeclaration#clearingPurpose()})
     * @param counterparty the provider's code - whose position it is ({@code INV-RAIL-04})
     * @param clearingAccount the provider's own account the posting hit, in this leg's currency
     */
    record Opening(
            Kind kind,
            String operationRef,
            String postingKey,
            AccountPurpose position,
            String counterparty,
            LedgerAccountId clearingAccount,
            JournalEntryId journalEntryId,
            List<Key> keys,
            Correlation correlation) {

        public Opening {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(operationRef, "operationRef must not be null");
            Objects.requireNonNull(postingKey, "postingKey must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(counterparty, "counterparty must not be null");
            Objects.requireNonNull(clearingAccount, "clearingAccount must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            keys = List.copyOf(keys);
        }
    }
}
