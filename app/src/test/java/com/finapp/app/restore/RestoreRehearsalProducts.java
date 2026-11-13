package com.finapp.app.restore;

import static com.finapp.app.restore.RestoreRehearsalSeed.APPROVER;
import static com.finapp.app.restore.RestoreRehearsalSeed.IDS;
import static com.finapp.app.restore.RestoreRehearsalSeed.PROPOSER;
import static com.finapp.app.restore.RestoreRehearsalSeed.as;
import static com.finapp.app.restore.RestoreRehearsalSeed.count;
import static com.finapp.app.restore.RestoreRehearsalSeed.execute;
import static com.finapp.app.restore.RestoreRehearsalSeed.inTransaction;

import com.finapp.app.bnpl.BnplAgreementDesk;
import com.finapp.app.bnpl.BnplApplicationController;
import com.finapp.app.bnpl.BnplApplicationDesk;
import com.finapp.app.bnpl.BnplFlowScope;
import com.finapp.app.bnpl.BnplOfferController;
import com.finapp.app.bnpl.BnplOfferDesk;
import com.finapp.app.bnpl.BnplOriginationMetrics;
import com.finapp.app.bnpl.BnplOriginationSchedule;
import com.finapp.app.bnpl.BnplTermsController;
import com.finapp.app.bnpl.BnplTermsDesk;
import com.finapp.app.bnpl.BnplTransactions;
import com.finapp.app.bnpl.MerchantBnplDesk;
import com.finapp.app.checkout.CheckoutService;
import com.finapp.app.checkout.CreateSessionRequest;
import com.finapp.app.consent.ConsentService;
import com.finapp.app.credit.CreditFlowScope;
import com.finapp.app.credit.CreditPolicyController;
import com.finapp.app.credit.CreditPolicyDesk;
import com.finapp.app.credit.LendingStormCredit;
import com.finapp.app.credit.ScorecardAdministrationDesk;
import com.finapp.app.lending.LendingCapitalController;
import com.finapp.app.lending.LendingCapitalDesk;
import com.finapp.app.lending.LendingFlowScope;
import com.finapp.app.lending.LendingTransactions;
import com.finapp.app.lending.LoanAccountDesk;
import com.finapp.app.lending.LoanApplicationController;
import com.finapp.app.lending.LoanApplicationDesk;
import com.finapp.app.lending.LoanDisbursementMetrics;
import com.finapp.app.lending.LoanDisbursementSchedule;
import com.finapp.app.lending.LoanOfferController;
import com.finapp.app.lending.LoanOfferDesk;
import com.finapp.app.lending.LoanOfferMetrics;
import com.finapp.app.lending.LoanOriginationSchedule;
import com.finapp.app.lending.LoanTermsController;
import com.finapp.app.lending.LoanTermsDesk;
import com.finapp.app.merchant.AssignFeeScheduleRequest;
import com.finapp.app.merchant.CreateFeeScheduleRequest;
import com.finapp.app.merchant.CreateFeeScheduleVersionRequest;
import com.finapp.app.merchant.FeeScheduleOperations;
import com.finapp.app.merchant.MerchantApiKeyOperations;
import com.finapp.app.merchant.MerchantOperations;
import com.finapp.app.merchant.OnboardMerchantRequest;
import com.finapp.app.restore.RestoreRehearsalSeed.Holder;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.consent.ConsentPurpose;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionRequestId;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.lending.CapitalContributionStatus;
import com.finapp.lending.CapitalContributionStore;
import com.finapp.lending.CapitalRecognitionStatus;
import com.finapp.lending.CapitalRecognitions;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanOrigination;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.JdbcMerchantApiKeyStore;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantApiKeySecret;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.security.Sensitive;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * The restore rehearsal seed's lending and BNPL (`P16-TSK-024`; THREAT_MODEL item 44 (a)): a source holding one disbursed loan and one
 * BNPL obligation, so the restore's {@code LOAN_SUBLEDGER}, {@code LOAN_REPLAY}, {@code BNPL_SUBLEDGER}, {@code BNPL_THREE_WAY} and
 * {@code MERCHANT_PAYABLE} proofs judge subjects. Every act is the platform's: the desk a door calls, under the actor the door's
 * interceptor would enter and, for a customer's act, their {@code MULTI_FACTOR} session; every sweep the one the application schedules,
 * built from the context's own beans and properties and swept once. Capital enters only on the bank's statement
 * ({@link RestoreRehearsalStatements}).
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
final class RestoreRehearsalProducts {

    /**
     * The seed's contributions' bank reference prefix ({@code LCAP-} and 4 to 12 digits): the statement mode finds the one awaiting its
     * statement by it.
     */
    static final String CAPITAL_REFERENCE_PREFIX = "LCAP-7357";
    /** The first contribution, recognised now: enough for the loan and the purchase with room to spare. */
    static final long FIRST_CAPITAL_MINOR = 10_000_00L;
    /** The second contribution, approved now; its statement is the statement mode's. */
    static final long SECOND_CAPITAL_MINOR = 5_000_00L;

    /** credit {@code V008}'s personal loan policy and {@code V006}'s scorecard, seeded as proposals for two persons to activate. */
    static final String LOAN_POLICY_SEED = "0190a1b2-5c0e-7000-8000-00000000d001";
    static final String SCORECARD_SEED = "0190a1b2-5c0e-7000-8000-00000000c001";

    /** Pay-in-4's stress rate, factor and exposure ceiling: the credit suites' BNPL policy fixture ({@code CreditWorld.bnplPolicy}). */
    private static final int BNPL_STRESS_RATE_BPS = 1_800;
    private static final int BNPL_PLAN_STRESS_FACTOR_BPS = 30_000;
    private static final long BNPL_MINIMUM_DISPOSABLE_MINOR = 100_00L;
    private static final int BNPL_MINIMUM_PAYMENT_RATIO_BPS = 500;
    private static final long BNPL_MAXIMUM_EXPOSURE_MINOR = 20_000_00L;
    private static final long PAY_IN_4_MAXIMUM_MINOR = 1_000_00L;

    private static final String LOAN_AMOUNT = "1500.00";
    private static final long PURCHASE_MINOR = 200_00L;
    /** credit's progress takes at most four edges to a decision; a source still answering costs a step more. */
    private static final int CREDIT_STEPS = 10;
    private static final Set<DecisionProgress.Step> SETTLED = Set.of(DecisionProgress.Step.DECIDED, DecisionProgress.Step.REFERRED,
            DecisionProgress.Step.ABANDONED, DecisionProgress.Step.EXPIRED);

    private RestoreRehearsalProducts() {}

    /** A customer acting through their session: the party, the actor the session makes, the session. */
    record Customer(UUID party, Actor actor, Session session) {}

    static void seed(ConfigurableApplicationContext context, LendingStormCredit credit, LocalDate today, Holder holder) {
        DataSource dataSource = context.getBean(DataSource.class);
        creditInForce(context);
        termsInForce(context);
        capital(context, dataSource, today);

        Customer customer = customer(context, dataSource, holder);
        // Identifying facts the simulators' own derivation turns into a clean, affordable file: approvable by both products.
        credit.register(customer.party(), LendingStormCredit.Profile.GOOD);
        loan(context, dataSource, customer);
        purchase(context, dataSource, customer, merchant(context, dataSource));

        report(context, dataSource);
    }

    // ------------------------------------------------------------------ credit, terms and capital in force

    /**
     * credit's seeded personal loan policy and scorecard activated by a second person (each seeded as a migration's proposal), and a
     * pay-in-4 policy - the personal loan's rules made automatic, as the BNPL fixture states it - proposed by one person and activated by
     * another, all through credit's policy and scorecard desks.
     */
    private static void creditInForce(ConfigurableApplicationContext context) {
        CreditPolicyDesk policies = context.getBean(CreditPolicyDesk.class);
        ScorecardAdministrationDesk scorecards = context.getBean(ScorecardAdministrationDesk.class);
        CreditPolicyDesk.CreditPolicyReceipt loan = as(APPROVER, () -> policies.approve(key(), LOAN_POLICY_SEED,
                "the restore rehearsal's review of the seeded personal loan policy"));
        ScorecardAdministrationDesk.ScorecardReceipt scorecard = as(APPROVER, () -> scorecards.approve(key(), SCORECARD_SEED,
                "the restore rehearsal's review of the seeded scorecard"));
        CreditPolicyDesk.CreditPolicyView inForce = as(PROPOSER, () -> policies.activeAt("PERSONAL_LOAN", null));
        CreditPolicyDesk.CreditPolicyReceipt proposed = as(PROPOSER, () -> policies.propose(key(), payIn4Policy(inForce)));
        CreditPolicyDesk.CreditPolicyReceipt bnpl = as(APPROVER, () -> policies.approve(key(), proposed.id(),
                "the restore rehearsal's review of the pay-in-4 policy"));
        System.out.println("seed: credit in force by two persons: PERSONAL_LOAN policy v" + loan.version() + " " + loan.status()
                + ", BNPL_PAY_IN_4 policy v" + bnpl.version() + " " + bnpl.status() + ", scorecard v" + scorecard.version() + " "
                + scorecard.status());
    }

    /**
     * The BNPL policy fixture over the personal loan's policy in force (`P12-TSK-001`; {@code CreditWorld.bnplPolicy}): its sources and
     * rules, every referring rule declining instead and the amount cap dropped, the fallback {@code DECLINE}, an 18% stress rate, a
     * maximum exposure of EUR 20,000.00, the product's maximum as the auto-approval ceiling and three instalments a month.
     */
    private static CreditPolicyController.PolicyProposalRequest payIn4Policy(CreditPolicyDesk.CreditPolicyView loan) {
        List<CreditPolicyController.CreditPolicySourceRequest> sources = loan.sources().stream()
                .map(source -> new CreditPolicyController.CreditPolicySourceRequest(source.kind(),
                        Math.toIntExact(source.maximumDataAgeSeconds())))
                .toList();
        List<CreditPolicyController.CreditPolicyRuleRequest> rules = new ArrayList<>();
        for (CreditPolicyDesk.CreditPolicyRuleView rule : loan.rules()) {
            if ("CAP_AMOUNT".equals(rule.effect())) {
                continue;
            }
            rules.add(new CreditPolicyController.CreditPolicyRuleRequest(rule.ruleCode(), rule.subjectKind(), rule.subject(),
                    rule.operator(), rule.integerOperand(), rule.moneyOperandMinor(), rule.booleanOperand(), rule.codesOperand(),
                    "REFER".equals(rule.effect()) ? "DECLINE" : rule.effect(), rule.capMinor(), rule.reasonCode()));
        }
        return new CreditPolicyController.PolicyProposalRequest("BNPL_PAY_IN_4", loan.currency(), BNPL_STRESS_RATE_BPS,
                BNPL_MINIMUM_DISPOSABLE_MINOR, BNPL_MINIMUM_PAYMENT_RATIO_BPS, BNPL_MAXIMUM_EXPOSURE_MINOR, sources, "DECLINE",
                PAY_IN_4_MAXIMUM_MINOR, rules, "the restore rehearsal's pay-in-4 policy", BNPL_PLAN_STRESS_FACTOR_BPS);
    }

    /** The personal loan's and pay-in-4's terms, each proposed by one officer and activated by another. */
    private static void termsInForce(ConfigurableApplicationContext context) {
        LoanTermsDesk loanTerms = context.getBean(LoanTermsDesk.class);
        LoanTermsDesk.LoanTermsReceipt loanProposed = as(PROPOSER, () -> loanTerms.propose(key(), loanTerms()));
        LoanTermsDesk.LoanTermsReceipt loan = as(APPROVER, () -> loanTerms.approve(key(), loanProposed.id(),
                "the restore rehearsal's review of the personal loan's terms"));
        BnplTermsDesk bnplTerms = context.getBean(BnplTermsDesk.class);
        BnplTermsDesk.BnplTermsReceipt bnplProposed = as(PROPOSER, () -> bnplTerms.propose(key(), payIn4Terms()));
        BnplTermsDesk.BnplTermsReceipt bnpl = as(APPROVER, () -> bnplTerms.approve(key(), bnplProposed.id(),
                "the restore rehearsal's review of pay-in-4's terms"));
        System.out.println("seed: terms in force by two persons: " + loan.product() + " v" + loan.version() + " " + loan.status() + ", "
                + bnpl.product() + " v" + bnpl.version() + " " + bnpl.status());
    }

    /** The lending suites' personal loan terms ({@code LendingStormWorld.terms}): fixed 8.99%, no fee, every day 1 to 28. */
    private static LoanTermsController.LoanTermsProposalRequest loanTerms() {
        return new LoanTermsController.LoanTermsProposalRequest("PERSONAL_LOAN", "EUR", "0.0899", "FIXED", 0L, null, "DEDUCTED",
                1500L, 5, 6000L, 3, List.of(1, 30, 60, 90), 90, "OLDEST_DUE_FIRST", List.of("FEES", "INTEREST", "PRINCIPAL"),
                "HOLD_AS_CREDIT", false, true, "UP", "HALF_EVEN", "ACT_365F", "UTC", IntStream.rangeClosed(1, 28).boxed().toList(),
                15, 14, 30, 24, null, null, null, 1, 1, 1, null, "PERSONAL_LOAN_AGREEMENT", 1,
                "the restore rehearsal's personal loan terms");
    }

    /** The BNPL suites' pay-in-4 terms ({@code BnplTermsDeskTest.payIn4}). */
    private static BnplTermsController.BnplTermsProposalRequest payIn4Terms() {
        return new BnplTermsController.BnplTermsProposalRequest("BNPL_PAY_IN_4", "EUR", "0", "BIWEEKLY", 500L, 5, 1500L, 3,
                List.of(1, 30, 60, 90), 90, "OLDEST_DUE_FIRST", List.of("FEES", "INTEREST", "PRINCIPAL"), "HOLD_AS_CREDIT", true, true,
                1, "DOWN", null, null, "UTC", 4, 14, null, null, 30, 14, 25_000L, 30, "BNPL_SCHEDULE_ENGINE_V1", null,
                "ALLOCATION_ENGINE_V1", "REFUND_ALLOCATION_ENGINE_V1", "BNPL_PAY_IN_4_AGREEMENT", 1,
                "the restore rehearsal's pay-in-4 terms");
    }

    /**
     * Two contributions of the platform's own money, each proposed by one treasury operator and approved by another (an approval opens
     * the expectation, no capital); the first's bank statement then received, parsed and accepted, so bank recognition posts it. The
     * second's statement is left for the statement mode.
     */
    private static void capital(ConfigurableApplicationContext context, DataSource dataSource, LocalDate today) {
        LendingCapitalDesk desk = context.getBean(LendingCapitalDesk.class);
        String first = reference();
        contribution(desk, FIRST_CAPITAL_MINOR, first);
        contribution(desk, SECOND_CAPITAL_MINOR, reference());
        RestoreRehearsalStatements.Sent sent = RestoreRehearsalStatements.send(context, dataSource,
                RestoreRehearsalStatements.compose(context, dataSource, today, FIRST_CAPITAL_MINOR, first));
        if (!"ACCEPTED".equals(sent.status())) {
            throw new IllegalStateException("the first contribution's statement was not accepted: " + sent.status());
        }
        System.out.println("seed: the first contribution's bank statement " + sent.status() + ", sequence " + sent.sequence());
    }

    private static void contribution(LendingCapitalDesk desk, long minor, String reference) {
        LendingCapitalDesk.CapitalContributionReceipt proposed = as(PROPOSER, () -> desk.propose(key(),
                new LendingCapitalController.ContributionProposalRequest("EUR", minor, reference,
                        "the restore rehearsal's lending capital")));
        LendingCapitalDesk.CapitalContributionReceipt approved = as(APPROVER, () -> desk.approve(key(), proposed.id(),
                "the restore rehearsal's board minute"));
        if (!CapitalContributionStatus.APPROVED.name().equals(approved.status())) {
            throw new IllegalStateException("a contribution was not approved: " + approved.status());
        }
    }

    /** {@link #CAPITAL_REFERENCE_PREFIX} and eight random digits: twelve digits after {@code LCAP-}. */
    private static String reference() {
        return CAPITAL_REFERENCE_PREFIX + String.format("%08d", new SecureRandom().nextInt(100_000_000));
    }

    // ------------------------------------------------------------------ the customer

    /**
     * {@code holder} as a customer acting through a {@code MULTI_FACTOR} session, consenting to both credit purposes through the consent
     * door's service. The session is issued through the platform's session store, standing in for a password log-in and a TOTP
     * step-up: the identity was made by SQL and holds no credential to prove.
     */
    private static Customer customer(ConfigurableApplicationContext context, DataSource dataSource, Holder holder) {
        @SuppressWarnings("unchecked")
        SessionStore<Connection> sessions = context.getBean(SessionStore.class);
        SessionPolicy policy = context.getBean(SessionPolicy.class);
        Clock clock = context.getBean(Clock.class);
        Session session = inTransaction(dataSource, holder.actor(), connection -> sessions.insert(connection,
                Session.issue(IDS, clock, IdentityId.of(UUID.fromString(holder.actor().id())), SessionToken.issue(new SecureRandom()),
                        AssuranceLevel.MULTI_FACTOR, policy)));
        Customer customer = new Customer(holder.party(), holder.actor(), session);
        ConsentService consents = context.getBean(ConsentService.class);
        List<ConsentService.PurposeBasis> bases = as(customer.actor(), () -> consents.currentBases(session)).orElseThrow();
        for (ConsentPurpose purpose : List.of(ConsentPurpose.CREDIT_BUREAU_ACCESS, ConsentPurpose.FINANCIAL_DATA_ACCESS)) {
            int version = bases.stream().filter(basis -> basis.purpose() == purpose).findFirst().orElseThrow().currentTextVersion();
            ConsentService.GrantResult granted = as(customer.actor(), () -> consents.grant(session, purpose, version));
            if (!(granted instanceof ConsentService.GrantResult.Granted)) {
                throw new IllegalStateException("a consent was not granted: " + granted.getClass().getSimpleName());
            }
        }
        System.out.println("seed: a customer on a MULTI_FACTOR session, consenting to " + ConsentPurpose.CREDIT_BUREAU_ACCESS + " and "
                + ConsentPurpose.FINANCIAL_DATA_ACCESS);
        return customer;
    }

    // ------------------------------------------------------------------ the loan

    /**
     * A personal loan: applied for, decided by credit's own progress over the simulated sources, offered by the origination sweep,
     * accepted, and disbursed into the customer's wallet by the disbursement sweep.
     */
    private static void loan(ConfigurableApplicationContext context, DataSource dataSource, Customer customer) {
        LoanApplicationDesk applications = context.getBean(LoanApplicationDesk.class);
        LoanApplicationDesk.LoanApplicationView applied = as(customer.actor(), () -> applications.submit(customer.session(), key(),
                new LoanApplicationController.LoanApplicationBody("PERSONAL_LOAN", LOAN_AMOUNT, "EUR", 12, 10, "WALLET", "WALLET",
                        "6000.00", "1000.00")));
        UUID request = UUID.fromString(applied.decisionRequestId());
        decided(context, dataSource, request, "the personal loan");

        LendingTransactions transactions = context.getBean(LendingTransactions.class);
        LoanOrigination origination = context.getBean(LoanOrigination.class);
        // credit.CreditDecisionRequestClosed's hint, as the inbox would hand it over: the application due now, nothing else.
        transactions.inTransaction(uow -> origination.hintRequest(uow, request));
        Environment environment = context.getEnvironment();
        new LoanOriginationSchedule(origination, transactions, context.getBean(LoanOfferMetrics.class),
                duration(environment, "finapp.lending.origination.poll", "PT5S"),
                integer(environment, "finapp.lending.origination.batch", 20),
                duration(environment, "finapp.lending.origination.permit", "PT30S"), context.getBean(LendingFlowScope.class))
                .sweepOnce();
        String offer = as(customer.actor(), () -> applications.read(customer.session(), applied.applicationId())).offerId();
        if (offer == null) {
            throw new IllegalStateException("the origination sweep made no loan offer");
        }
        String terms = as(customer.actor(), () -> context.getBean(LoanOfferDesk.class).read(customer.session(), offer)).termsSha256();
        LoanAccountDesk accounts = context.getBean(LoanAccountDesk.class);
        LoanAccountDesk.LoanAcceptedView accepted = as(customer.actor(), () -> accounts.accept(customer.session(), offer, key(),
                new LoanOfferController.LoanAcceptanceBody(terms, null), Optional.empty(), Optional.empty()));
        new LoanDisbursementSchedule(context.getBean(LoanDisbursements.class), transactions,
                context.getBean(LoanDisbursementMetrics.class), duration(environment, "finapp.lending.disbursement.poll", "PT5S"),
                integer(environment, "finapp.lending.disbursement.batch", 20),
                duration(environment, "finapp.lending.disbursement.permit", "PT1M"), context.getBean(LendingFlowScope.class))
                .sweepOnce();
        String status = scalar(dataSource, "SELECT status FROM lending.loan WHERE id = ?", UUID.fromString(accepted.loanId()));
        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException("the loan was not disbursed: " + status);
        }
    }

    /**
     * {@code request} stepped through credit's progress, as the progress sweep steps each request it claims (under the system actor, in
     * the request's flow), until it is decided; refused unless credit approved it.
     */
    private static void decided(ConfigurableApplicationContext context, DataSource dataSource, UUID request, String what) {
        DecisionProgress progress = context.getBean(DecisionProgress.class);
        CreditFlowScope flows = context.getBean(CreditFlowScope.class);
        DecisionRequestId id = DecisionRequestId.of(request);
        DecisionProgress.Step step = DecisionProgress.Step.NOTHING;
        for (int i = 0; i < CREDIT_STEPS && !SETTLED.contains(step); i++) {
            try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
                CorrelationId correlation = CorrelationId.generate(IDS);
                step = flows.forRequest(id, correlation, () -> progress.step(id, correlation));
            }
        }
        String outcome = scalar(dataSource, "SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", request);
        if (!"APPROVED".equals(outcome)) {
            throw new IllegalStateException("credit did not approve " + what + ": the last step " + step + ", the outcome " + outcome);
        }
    }

    // ------------------------------------------------------------------ the merchant and the purchase

    /** The merchant as its API key authenticates it, and the actor the merchant-key interceptor enters for it. */
    record Merchant(AuthenticatedMerchant authenticated, Actor actor) {}

    /**
     * A trading merchant: its organisation's party by SQL, onboarded, priced for cards and keyed through the merchant doors' own
     * operations; BNPL-enabled by two persons and pay-in-4 priced by a returning schedule through merchant's BNPL desk.
     */
    private static Merchant merchant(ConfigurableApplicationContext context, DataSource dataSource) {
        UUID party = IDS.next();
        inTransaction(dataSource, PROPOSER, connection -> {
            execute(connection, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'ORGANISATION',"
                    + " 'Restore Rehearsal Trading', now() - interval '2 hour')", party);
            execute(connection, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES (?, ?, 'ACTIVE',"
                    + " now() - interval '1 hour', now() - interval '1 hour')", IDS.next(), party);
            return null;
        });
        MerchantOperations merchants = context.getBean(MerchantOperations.class);
        String merchantId = as(PROPOSER, () -> merchants.onboard(new OnboardMerchantRequest(party, "Restore Rehearsal Trading GmbH",
                "Restore Rehearsal Trading", "EUR"), key())).merchantId();
        FeeScheduleOperations fees = context.getBean(FeeScheduleOperations.class);
        String card = schedule(fees, "card", "0.029", 30L, "RETAINED");
        as(PROPOSER, () -> fees.assign(merchantId, new AssignFeeScheduleRequest(UUID.fromString(card),
                "the restore rehearsal's standard terms")));
        MerchantApiKeyOperations.IssuedKeyView issued = as(PROPOSER, () -> context.getBean(MerchantApiKeyOperations.class)
                .issue(merchantId, key()));
        // As MerchantKeyAuthenticationInterceptor verifies a presented key: the live row by its public id, the secret against it.
        AuthenticatedMerchant authenticated = inTransaction(dataSource, PROPOSER, connection -> new JdbcMerchantApiKeyStore()
                .findLiveFor(connection, MerchantApiKeyId.of(UUID.fromString(issued.keyId())))
                .filter(found -> found.verifies(MerchantApiKeySecret.of(issued.secret())))
                .map(found -> new AuthenticatedMerchant(found.merchantId(), found.id(), found.scopes(), Optional.of(found.issuedBy())))
                .orElseThrow(() -> new IllegalStateException("the merchant's key did not verify")));

        MerchantBnplDesk bnpl = context.getBean(MerchantBnplDesk.class);
        MerchantBnplDesk.BnplEnablementReceipt proposed = as(PROPOSER, () -> bnpl.propose(key(), merchantId,
                "the restore rehearsal's merchant agreement"));
        MerchantBnplDesk.BnplEnablementReceipt enabled = as(APPROVER, () -> bnpl.approve(key(), merchantId, proposed.id(),
                "the restore rehearsal's review of the merchant agreement"));
        String bnplFees = schedule(fees, "bnpl", "0.04", 0L, "RETURNED");
        as(PROPOSER, () -> bnpl.assignFeeSchedule(merchantId, "BNPL_PAY_IN_4", UUID.fromString(bnplFees),
                "the restore rehearsal's pay-in-4 pricing"));
        System.out.println("seed: a trading merchant, BNPL " + enabled.status() + " by two persons, pay-in-4 priced");
        return new Merchant(authenticated, new Actor(merchantId, ActorType.MERCHANT));
    }

    /** A fee schedule in EUR with one version: its id. */
    private static String schedule(FeeScheduleOperations fees, String kind, String rate, long fixedMinor, String refundFeePolicy) {
        String id = as(PROPOSER, () -> fees.create(new CreateFeeScheduleRequest("restore rehearsal " + kind + " "
                + UUID.randomUUID().toString().substring(0, 8), "EUR"))).feeScheduleId();
        as(PROPOSER, () -> fees.addVersion(id, new CreateFeeScheduleVersionRequest(new BigDecimal(rate), fixedMinor, "HALF_EVEN",
                refundFeePolicy, null, "the restore rehearsal's " + kind + " pricing")));
        return id;
    }

    /**
     * A pay-in-4 purchase at {@code merchant}'s checkout: the session opened by the merchant's key, the application submitted with its
     * token, decided by credit's own progress, offered by the BNPL origination sweep and accepted - the merchant's payable financed, the
     * obligation born and its first instalment collected from the customer's wallet, which the loan funded.
     */
    private static void purchase(ConfigurableApplicationContext context, DataSource dataSource, Customer customer, Merchant merchant) {
        CheckoutService checkout = context.getBean(CheckoutService.class);
        CheckoutService.CreatedSessionView opened = as(merchant.actor(), () -> checkout.create(merchant.authenticated(),
                new CreateSessionRequest(PURCHASE_MINOR, "EUR", "A bicycle"), key()));
        BnplApplicationDesk applications = context.getBean(BnplApplicationDesk.class);
        BnplApplicationDesk.BnplApplicationView applied = as(customer.actor(), () -> applications.submit(customer.session(), key(),
                new BnplApplicationController.BnplApplicationBody(Sensitive.of(opened.sessionToken()), "BNPL_PAY_IN_4", "PAY_IN_4")));
        UUID request = UUID.fromString(applied.decisionRequestId());
        decided(context, dataSource, request, "the pay-in-4 purchase");

        BnplTransactions transactions = context.getBean(BnplTransactions.class);
        BnplOrigination origination = context.getBean(BnplOrigination.class);
        transactions.inTransaction(uow -> origination.hintRequest(uow, request));
        Environment environment = context.getEnvironment();
        new BnplOriginationSchedule(origination, transactions, context.getBean(BnplOriginationMetrics.class),
                duration(environment, "finapp.bnpl.origination.poll", "PT5S"), integer(environment, "finapp.bnpl.origination.batch", 20),
                duration(environment, "finapp.bnpl.origination.permit", "PT30S"), context.getBean(BnplFlowScope.class)).sweepOnce();
        String offer = as(customer.actor(), () -> applications.read(customer.session(), applied.applicationId())).offerId();
        if (offer == null) {
            throw new IllegalStateException("the BNPL origination sweep made no offer");
        }
        String terms = as(customer.actor(), () -> context.getBean(BnplOfferDesk.class).read(customer.session(), offer)).termsSha256();
        BnplAgreementDesk agreements = context.getBean(BnplAgreementDesk.class);
        as(customer.actor(), () -> agreements.accept(customer.session(), offer, key(), new BnplOfferController.BnplAcceptanceBody(terms),
                Optional.empty(), Optional.empty()));
    }

    // ------------------------------------------------------------------ what was written, by count

    private static void report(ConfigurableApplicationContext context, DataSource dataSource) {
        Map<CapitalRecognitionStatus, Integer> capital = contributions(context, dataSource);
        System.out.println("seed: capital contributions: " + capital.getOrDefault(CapitalRecognitionStatus.RECOGNISED, 0)
                + " recognised, " + capital.getOrDefault(CapitalRecognitionStatus.AWAITING_FUNDS, 0) + " awaiting its statement");
        System.out.println("seed: credit decisions " + grouped(dataSource, "SELECT outcome, count(*) FROM credit.credit_decision"
                + " GROUP BY outcome"));
        grouped(dataSource, "SELECT status, count(*) FROM lending.loan GROUP BY status")
                .forEach((status, loans) -> System.out.println("seed: " + loans + " loan" + (loans == 1 ? " " : "s ") + status));
        long obligations = count(dataSource, "SELECT count(*) FROM bnpl.bnpl_obligation");
        System.out.println("seed: " + obligations + " BNPL obligation" + (obligations == 1 ? "" : "s"));
        System.out.println("seed: merchant payables " + count(dataSource, "SELECT count(*) FROM ledger.ledger_account"
                + " WHERE purpose = 'MERCHANT_PAYABLE'"));
        System.out.println("seed: credit evidence by key version " + evidenceKeyVersions(dataSource));
    }

    /** The seed's approved contributions by their derived recognition, read through lending's store and reconciliation's facts. */
    static Map<CapitalRecognitionStatus, Integer> contributions(ConfigurableApplicationContext context, DataSource dataSource) {
        CapitalContributionStore store = context.getBean(CapitalContributionStore.class);
        CapitalRecognitions recognitions = context.getBean(CapitalRecognitions.class);
        return inTransaction(dataSource, PROPOSER, connection -> {
            List<String> expectations = store.recent(connection, 100).stream()
                    .filter(row -> row.reference().startsWith(CAPITAL_REFERENCE_PREFIX))
                    .filter(row -> row.status() == CapitalContributionStatus.APPROVED)
                    .map(row -> row.expectationRef().orElseThrow())
                    .toList();
            Map<CapitalRecognitionStatus, Integer> counted = new EnumMap<>(CapitalRecognitionStatus.class);
            recognitions.expectations(connection, expectations).values()
                    .forEach(reading -> counted.merge(reading.status(), 1, Integer::sum));
            return counted;
        });
    }

    /**
     * How many credit evidence rows stand under each key version. The application's role cannot select the version column (credit
     * {@code V012}); it reads it, row by row, through {@code credit.read_evidence}, the definer that demands a reason. The ciphertext the
     * definer also answers is never selected.
     */
    private static Map<Integer, Integer> evidenceKeyVersions(DataSource dataSource) {
        return inTransaction(dataSource, PROPOSER, connection -> {
            List<UUID> evidence = new ArrayList<>();
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT id FROM credit.credit_evidence WHERE NOT consent_withdrawn ORDER BY id");
                    ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    evidence.add(rows.getObject(1, UUID.class));
                }
            }
            Map<Integer, Integer> versions = new TreeMap<>();
            try (PreparedStatement read = connection.prepareStatement("SELECT key_version FROM credit.read_evidence(?, ?)")) {
                for (UUID id : evidence) {
                    read.setObject(1, id);
                    read.setString(2, "the restore rehearsal seed counts its credit evidence by key version");
                    try (ResultSet row = read.executeQuery()) {
                        row.next();
                        versions.merge(row.getInt(1), 1, Integer::sum);
                    }
                }
            }
            return versions;
        });
    }

    // ------------------------------------------------------------------ plumbing

    static String key() {
        return UUID.randomUUID().toString();
    }

    private static Duration duration(Environment environment, String property, String fallback) {
        return Duration.parse(environment.getProperty(property, fallback));
    }

    private static int integer(Environment environment, String property, int fallback) {
        return environment.getProperty(property, Integer.class, fallback);
    }

    /** One text a query answers, or null. */
    private static String scalar(DataSource dataSource, String sql, Object argument) {
        return inTransaction(dataSource, PROPOSER, connection -> {
            try (PreparedStatement read = connection.prepareStatement(sql)) {
                read.setObject(1, argument);
                try (ResultSet row = read.executeQuery()) {
                    return row.next() ? row.getString(1) : null;
                }
            }
        });
    }

    /** A {@code (text, count)} query as an ordered map. */
    private static Map<String, Long> grouped(DataSource dataSource, String sql) {
        return inTransaction(dataSource, PROPOSER, connection -> {
            Map<String, Long> grouped = new TreeMap<>();
            try (PreparedStatement read = connection.prepareStatement(sql); ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    grouped.put(rows.getString(1), rows.getLong(2));
                }
            }
            return grouped;
        });
    }
}
