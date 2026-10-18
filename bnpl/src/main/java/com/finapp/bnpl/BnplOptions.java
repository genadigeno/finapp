package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The BNPL options of a checkout session (`P12-TSK-011`; {@code PHASE_12_PLAN.md} section 9 - the route its section 17
 * table left unassigned, placed here because it needs both engines): the products the session's merchant offers, the
 * session's gross within each product's bounds and covering its fee, and for each plan the active terms version offers
 * an ILLUSTRATIVE schedule of the session's gross from today on the database clock - <strong>no eligibility</strong>:
 * nothing of the customer's is read, no credit is asked, nothing is written and no row is locked beyond the share locks
 * of the merchant's standing.
 *
 * <p>The session is named by its token - the customer's capability, as at the application door - so an unknown token, a
 * session no longer {@code OPEN} or past its deadline, and one in another currency are one answer
 * ({@link BnplApplications.SessionNotEligible}); a product the merchant does not offer, outside its bounds or not
 * covering the fee, or with no {@code ACTIVE} terms is simply absent.
 */
@RequiredArgsConstructor
public final class BnplOptions {

    @NonNull private final BnplCheckoutSessions sessions;
    @NonNull private final BnplMerchants merchants;
    @NonNull private final BnplProductBounds bounds;
    @NonNull private final BnplTermsAdministration terms;
    @NonNull private final BnplOfferStore clock;

    /** One product's options: the terms version they illustrate and one illustration per plan offered. */
    public record ProductOption(BnplProduct product, int termsVersion, List<PlanOption> plans) {

        public ProductOption {
            Objects.requireNonNull(product, "product must not be null");
            plans = List.copyOf(plans);
        }
    }

    /** One plan's illustration. */
    public record PlanOption(BnplPlan plan, BnplIllustration illustration) {

        public PlanOption {
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(illustration, "illustration must not be null");
        }
    }

    /**
     * The options of the session {@code sessionToken} opens.
     *
     * @throws BnplApplications.SessionNotEligible when no open, unexpired session in the products' currency answers it
     */
    public List<ProductOption> of(Connection unitOfWork, Sensitive<String> sessionToken) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(sessionToken, "sessionToken");
        BnplCheckoutSessions.Session session = sessions.read(unitOfWork, sessionToken)
                .filter(found -> found.open() && !found.expired())
                .orElseThrow(() -> new BnplApplications.SessionNotEligible(
                        BnplApplications.SessionNotEligible.Cause.NOT_OPEN));
        Money gross = session.gross();
        List<ProductOption> options = new ArrayList<>();
        for (BnplProduct product : BnplProduct.values()) {
            if (!gross.currency().equals(product.currency())) {
                continue;
            }
            BnplProductBounds.Bounds within = bounds.of(product);
            if (gross.compareTo(within.minimumAmount()) < 0 || gross.compareTo(within.maximumAmount()) > 0) {
                continue;
            }
            if (merchants.standing(unitOfWork, session.merchantRef(), product) != BnplMerchants.Standing.ENABLED
                    || !merchants.coversFee(unitOfWork, session.merchantRef(), product, gross)) {
                continue;
            }
            BnplTermsVersion version;
            try {
                version = terms.activeAt(unitOfWork, product, java.util.Optional.empty());
            } catch (BnplTermsAdministration.TermsNotFound none) {
                continue;
            }
            LocalDate today = LocalDate.ofInstant(clock.databaseNow(unitOfWork),
                    ZoneId.of(version.terms().servicingZone()));
            List<PlanOption> plans = new ArrayList<>();
            for (BnplPlan plan : product.plans()) {
                if (BnplApplications.offers(version.terms(), plan)) {
                    plans.add(new PlanOption(plan, BnplIllustration.of(version.terms(), plan, gross, today)));
                }
            }
            if (!plans.isEmpty()) {
                options.add(new ProductOption(product, version.row().version(), plans));
            }
        }
        return options;
    }
}
