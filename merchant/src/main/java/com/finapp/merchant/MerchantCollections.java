package com.finapp.merchant;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The operator's merchant collections (`P17-TSK-033`, finapp-admin-panel B7.3, gaps G-50 and G-51; `API_CONVENTIONS.md`
 * section 7): keyset pages in one declared total order each, read under the caller's unit of work - the merchants, newest
 * onboarding first, and one merchant's payouts, newest first.
 *
 * <p><strong>Administered reads.</strong> Every caller is an operator holding {@code MERCHANT_ADMINISTER}, which reads across
 * tenants by permission: the merchants page names no tenant at all, and the payouts page names the merchant the route named -
 * its {@code merchant_id = ?} is the route's subject, not a merchant's own key ({@code INV-MER-01} is the merchant surface's,
 * which never reaches this port). Each page reads the store's own row columns and mapper, so a list and a by-id read never
 * describe one row two ways.
 */
public interface MerchantCollections {

    /** The merchants page's filters: a standing, and a name matched exactly against the legal or the display name. */
    record MerchantFilter(Optional<MerchantStatus> status, Optional<String> name) {
        public MerchantFilter {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /** Where a merchants page resumes: after the merchant onboarded at {@code createdAt} with id {@code id}. */
    record MerchantCursor(Instant createdAt, UUID id) {
        public MerchantCursor {
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * At most {@code limit} merchants matching {@code filter}, onboarded at descending and id descending, after
     * {@code after}.
     */
    List<Merchant> merchants(Connection unitOfWork, MerchantFilter filter, Optional<MerchantCursor> after, int limit);

    /** Where a payouts page resumes: after the payout created at {@code createdAt} with id {@code id}. */
    record PayoutCursor(Instant createdAt, UUID id) {
        public PayoutCursor {
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** At most {@code limit} of {@code merchant}'s payouts, created at descending and id descending, after {@code after}. */
    List<MerchantPayout> payouts(Connection unitOfWork, MerchantId merchant, Optional<PayoutCursor> after, int limit);
}
