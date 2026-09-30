package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * The expectation-opening seam on the payout applier's acting branch (`P8-TSK-005`,
 * ADR-0067 §1) — {@code payments.SettlementExpectations}' twin, declared here because
 * {@code merchant} may see neither {@code payments} nor {@code reconciliation} (ADR-0064):
 * what a completed payout means for the position proof is the composition root's to record,
 * and {@code app}'s one recorder implements both ports.
 *
 * <h2>Required, with nothing to skip it</h2>
 *
 * <p>A <strong>required</strong> constructor parameter of {@link MerchantPayoutOutcomes}, so the
 * wiring is decided at compile time, and <strong>no do-nothing implementation ships in
 * production code</strong>: a payout that opens nothing is value leaving {@code PAYOUT_CLEARING}
 * that no expectation explains (`INV-SET-02`).
 *
 * <h2>Where it is called, and what crosses</h2>
 *
 * <p>Past the acting exit, after the posting whose clearing line the expectation copies, inside
 * the completing transaction — whichever resolver answered (the first answer, a takeover's
 * re-send, the resolution sweep: the provider has no webhook, so all three go through the one
 * applier). Identifiers, the declared position, the posted clearing account and entry and the
 * typed references cross — <strong>never an amount, a direction or a date</strong>: the
 * implementation reads all three off the posted entry (ADR-0067 §3, §4).
 */
public interface PayoutSettlementExpectations {

    /** Opens the completion's expectation with its keys. */
    void open(Connection unitOfWork, Opening opening);

    /**
     * The payout completions this module opens (ADR-0067 §2's table) — the payout's own, and
     * since `P8-TSK-019` its return (ADR-0073): {@code PAYOUT_RETURN}, reached through its
     * operation and opening no key.
     */
    enum Kind {
        MERCHANT_PAYOUT,
        PAYOUT_RETURN
    }

    /** The typed references the payout provider's report will quote (ADR-0067 §5). */
    enum ReferenceKind {
        /** The provider's reference for the payout, from its acceptance. */
        PAYOUT_PROVIDER_REF,
        /** Our own dispatch reference ({@code pyo-…}), sent with every attempt. */
        OUR_REF
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
     * One completion's opening: copies of facts the applier already holds.
     *
     * @param operationRef the identifier the posting key names — the key's suffix after its
     *     prefix
     * @param position the declared payout position ({@link PayoutSettlementDeclaration}) — the
     *     same definition the posting's account was resolved from
     * @param clearingAccount the account that declaration resolved and the posting hit
     */
    record Opening(
            Kind kind,
            String operationRef,
            String postingKey,
            AccountPurpose position,
            LedgerAccountId clearingAccount,
            JournalEntryId journalEntryId,
            List<Key> keys,
            Correlation correlation) {

        public Opening {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(operationRef, "operationRef must not be null");
            Objects.requireNonNull(postingKey, "postingKey must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(clearingAccount, "clearingAccount must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            keys = List.copyOf(keys);
        }
    }
}
