package com.finapp.app.credit;

import com.finapp.consent.ConsentPurpose;
import java.util.UUID;

/**
 * The credit suites' fixtures, opened to the lending suites (`P11-TSK-010`): a customer on an MFA-assured or a
 * password-only session, consenting or not, through {@link CreditTestClient}; both products' seeded policies and the
 * scorecard brought into force, and a decision request stepped through credit's own progress with the simulated
 * sources, through {@link CreditWorld}. A bridge only - the fixtures themselves stay package-private where they live.
 */
public final class CreditFixtures {

    /** A customer: their session token, login and party. */
    public record Customer(String token, String login, UUID party) {}

    private final CreditTestClient client;

    public CreditFixtures(int port) {
        this.client = new CreditTestClient(port);
    }

    /** A verified customer, consenting to both credit purposes, on a {@code MULTI_FACTOR} session. */
    public Customer consentingCustomer() throws Exception {
        return of(client.consentingCustomer());
    }

    /** A customer - verified or not - with no consent, on a {@code MULTI_FACTOR} session. */
    public Customer customer(boolean verified) throws Exception {
        return of(client.customer(verified));
    }

    /** A verified customer on the {@code PASSWORD} session a login alone yields - no factor enrolled. */
    public Customer passwordOnlyCustomer(boolean verified) throws Exception {
        return of(client.passwordOnlyCustomer(verified));
    }

    /** A fresh {@code PASSWORD} session for an enrolled customer's login. */
    public String login(Customer customer) throws Exception {
        return client.login(new CreditTestClient.Customer(customer.token(), customer.login(), customer.party()));
    }

    public static void consent(UUID party, ConsentPurpose purpose) throws Exception {
        CreditTestClient.consent(party, purpose);
    }

    /** Both products' seeded policies and the seeded scorecard in force, if a person has not already. */
    public static void seedsInForce() {
        CreditWorld.seedsInForce();
    }

    /**
     * A BNPL product's credit policy in force - proposed by one person, activated by another (`P12-TSK-010` - the BNPL
     * application's suites; no migration activates one).
     */
    public static void bnplInForce(com.finapp.credit.CreditProduct product) {
        CreditWorld.bnplInForce(product);
    }

    /**
     * Steps {@code request} through credit's progress - over the simulated sources, never deciding - until it is
     * {@code status}, at most six steps; the status it reached.
     */
    public static String stepUntil(UUID request, String status) {
        var progress = CreditWorld.progressToEvaluation();
        String reached = CreditWorld.scalar("SELECT status FROM credit.decision_request WHERE id = ?", request);
        for (int i = 0; i < 6 && !status.equals(reached); i++) {
            CreditWorld.step(progress, request);
            reached = CreditWorld.scalar("SELECT status FROM credit.decision_request WHERE id = ?", request);
        }
        return reached;
    }

    /**
     * Decides {@code request} with credit's own progress and deciding transaction over the simulated sources (`P11-TSK-011`
     * - the offer's suites): stepped to {@code EVALUATED}, then decided; the decision's outcome.
     */
    public static String decide(UUID request) {
        String reached = stepUntil(request, "EVALUATED");
        if (!"EVALUATED".equals(reached)) {
            throw new IllegalStateException("the request stopped at " + reached);
        }
        CreditWorld.decide(CreditWorld.deciding(), request);
        return CreditWorld.scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", request);
    }

    /** The bureau's total balance for {@code party}, in minor units - a large one declines (`P11-TSK-011`). */
    public static void bureauBalance(UUID party, long minorUnits) {
        CreditWorld.BALANCES.put(party, minorUnits);
    }

    /** {@code party}'s standing lost - credit's next step abandons its open request (`P11-TSK-011`). */
    public static void standingLost(UUID party) {
        CreditWorld.SUSPENDED.add(party);
    }

    /**
     * A second credit decision request for {@code party}, born {@code SUBMITTED} through credit's store, for
     * {@code product} and {@code amount} (`P11-TSK-013` - a decision raced against the party's acceptance); its id.
     */
    public static UUID requestFor(UUID party, com.finapp.credit.CreditProduct product, com.finapp.sharedkernel.money.Money amount) {
        return CreditWorld.request(party, product, amount, java.time.Duration.ofDays(1));
    }

    /** One step of credit's progress on {@code request} - for a race. */
    public static void stepOnce(UUID request) {
        CreditWorld.step(CreditWorld.progressToEvaluation(), request);
    }

    private static Customer of(CreditTestClient.Customer customer) {
        return new Customer(customer.token(), customer.login(), customer.party());
    }
}
