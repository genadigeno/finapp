package com.finapp.app.restore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.SourceCensus;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.ProductType;
import com.finapp.app.FinappApplication;
import com.finapp.app.accounting.GlMappingController;
import com.finapp.app.accounting.GlMappingDesk;
import com.finapp.app.credit.LendingStormCredit;
import com.finapp.app.ledger.AdjustmentRequest;
import com.finapp.app.ledger.LedgerAdjustments;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferResult;
import com.finapp.transfers.TransferStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * The restore rehearsal's source data (`P15-TSK-030`, widened by `P16-TSK-024`; {@code OPERATIONS_RUNBOOK.md} section 11.35), written
 * through the platform's own services as the application wires them, as {@code finapp_app}: so the books the backup copies are books
 * the platform's checks call whole, and every journal entry has the domain record the source-join census joins it to.
 *
 * <p><strong>The main mode</strong> ({@code RestoreRehearsalSeed <gl-mapping.json>}) writes: the current accounting period opened; the
 * reference GL chart (the {@code kind} rehearsal's, held to the chart by {@code KindRehearsalContractTest}) proposed by one operator and
 * activated by another; {@value #HOLDERS} customers, each with a EUR wallet funded by a manual adjustment from the operational fee
 * account, proposed by one operator and approved by another. Then a disbursed loan and a BNPL obligation
 * ({@link RestoreRehearsalProducts}, THREAT_MODEL item 44 (a)): credit's seeded personal loan policy and scorecard and a pay-in-4 policy
 * in force by two persons; the personal loan's and pay-in-4's terms in force by two persons; two lending-capital contributions, each
 * proposed by one treasury operator and approved by another, the first recognised by its bank statement through settlement intake
 * (uploaded by one operator, attested by another, parsed and accepted, so bank recognition posts the capital), the second left awaiting
 * its statement; one more customer, consenting to the credit purposes, who applies for a personal loan that credit decides over a
 * simulated bureau and financial-data provider, is offered it by the origination sweep, accepts it and has it disbursed into their
 * wallet by the disbursement sweep; and a trading merchant, BNPL-enabled by two persons and priced, at whose checkout the same customer
 * buys on pay-in-4: credit decides, the BNPL origination sweep offers, the acceptance finances the merchant's payable, bears the
 * obligation and collects its first instalment from the wallet. Then the census run to its end over all of that; then {@value #TRANSFERS}
 * transfers between the first wallets, which the census has not judged, so the backup carries a census head in the middle of the journal.
 *
 * <p><strong>The statement mode</strong> ({@code RestoreRehearsalSeed --statement <out-file>}), run later against the same source: the
 * second contribution's bank statement sent through settlement intake as the first was (capital recognised), and its exact bytes written
 * to {@code <out-file>}, the bank's copy the drill re-ingests into a restored database ({@link RestoreRehearsalStatements}).
 *
 * <p>What it does not write through a door's own path: a customer's or the merchant's party, identity and {@code ACTIVE} status come from
 * SQL, as the {@code kind} rehearsal's and the database suites' do (registration, KYC and KYB are not seed flows); the customer's
 * {@code MULTI_FACTOR} session is issued through the session store, standing in for a password log-in and a TOTP step-up, because the
 * identity holds no credential; the merchant's API key is verified as the merchant-key interceptor verifies it; credit's bureau and
 * financial-data provider are simulators started in this JVM and registered as primary beans of this context alone; credit's decision
 * hints, which the inbox would deliver, are handed to origination directly, and the sweeps the application schedules are built as the
 * application builds them and swept once. No journal entry and no balance is written by SQL. No card payment and no FX conversion
 * runs: each needs a simulated provider this seed does not start, so their proofs are read over no rows. Every line it prints is a
 * count or a status, never an amount.
 *
 * <p>Not a test, and never run by one: {@code infra/scripts/restore-rehearsal.sh} runs it against its own throwaway source.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
public final class RestoreRehearsalSeed {

    static final int HOLDERS = 4;
    static final int TRANSFERS = 6;
    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final String STATEMENT_MODE = "--statement";

    static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    static final Actor PROPOSER = new Actor("restore-rehearsal-operator-a", ActorType.EMPLOYEE);
    static final Actor APPROVER = new Actor("restore-rehearsal-operator-b", ActorType.EMPLOYEE);

    /** How long the seed's adapters wait for a simulated credit source: loopback, so generous. */
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(10);

    private RestoreRehearsalSeed() {}

    /** One customer: the party, the wallet product and its ledger account, and the actor a session of theirs makes. */
    record Holder(UUID party, UUID product, UUID wallet, Actor actor) {}

    public static void main(String[] args) {
        boolean statement = args.length == 2 && STATEMENT_MODE.equals(args[0]);
        boolean seeding = args.length == 1 && !args[0].startsWith("--");
        if (!statement && !seeding) {
            System.err.println("usage: RestoreRehearsalSeed <gl-mapping.json> | RestoreRehearsalSeed " + STATEMENT_MODE + " <out-file>");
            System.exit(2);
        }
        int exit = 0;
        try {
            if (statement) {
                try (ConfigurableApplicationContext context = context(null)) {
                    RestoreRehearsalStatements.sendAwaitingStatement(context, Path.of(args[1]));
                }
            } else {
                // The simulated credit sources live as long as the context that asks them, and no longer.
                try (LendingStormCredit credit = LendingStormCredit.start("", new SecureRandom().nextLong());
                        ConfigurableApplicationContext context = context(credit)) {
                    seed(context, credit, Path.of(args[0]));
                }
            }
        } catch (RuntimeException | Error failure) {
            // The class and the stack, never a message: a refusal's message can name a row's values.
            System.err.println("?? the seed failed: " + failure.getClass().getName());
            for (StackTraceElement frame : failure.getStackTrace()) {
                System.err.println("    at " + frame);
            }
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                System.err.println("   caused by " + cause.getClass().getName());
                for (StackTraceElement frame : cause.getStackTrace()) {
                    System.err.println("       at " + frame);
                }
            }
            exit = 1;
        }
        System.exit(exit);
    }

    /**
     * The platform's own context with no web server: the command opens no port, so nothing but this class reaches the context it holds.
     * With {@code credit}, its simulated bureau and financial-data provider are this context's primary sources - registered here and
     * nowhere else, never in production code.
     */
    private static ConfigurableApplicationContext context(LendingStormCredit credit) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(FinappApplication.class)
                .properties("spring.main.banner-mode=off", "spring.main.web-application-type=none");
        if (credit != null) {
            builder.initializers(initialized -> {
                GenericApplicationContext generic = (GenericApplicationContext) initialized;
                generic.registerBean("restoreRehearsalBureau", CreditBureau.class, () -> credit.bureau(PROVIDER_TIMEOUT),
                        definition -> definition.setPrimary(true));
                generic.registerBean("restoreRehearsalFinancialData", FinancialDataProvider.class,
                        () -> credit.financialData(PROVIDER_TIMEOUT), definition -> definition.setPrimary(true));
            });
        }
        return builder.run();
    }

    static void seed(ConfigurableApplicationContext context, LendingStormCredit credit, Path chart) {
        DataSource dataSource = context.getBean(DataSource.class);
        LocalDate today = inTransaction(dataSource, PROPOSER, connection -> date(connection));
        // Every report the simulators produce says it was retrieved an hour ago on the database's clock: fresh by any policy's age.
        credit.retrievedAt(inTransaction(dataSource, PROPOSER, connection -> instant(connection)).minus(Duration.ofHours(1)));

        List<YearMonth> opened = inTransaction(dataSource, PROPOSER,
                connection -> context.getBean(AccountingPeriods.class).openNext(connection, PROPOSER, correlation()));
        System.out.println("seed: periods opened " + opened);

        GlMappingDesk mappings = context.getBean(GlMappingDesk.class);
        GlMappingDesk.GlMappingReceipt proposed = as(PROPOSER, () -> mappings.propose(UUID.randomUUID().toString(),
                proposal(chart, YearMonth.from(today))));
        GlMappingDesk.GlMappingReceipt activated = as(APPROVER, () -> mappings.activate(UUID.randomUUID().toString(), proposed.id(),
                "the restore rehearsal's reference chart, the second person's review"));
        System.out.println("seed: GL mapping v" + activated.version() + " " + activated.status());

        List<Holder> holders = new ArrayList<>();
        for (int i = 0; i < HOLDERS; i++) {
            holders.add(holder(context, dataSource));
        }
        UUID fees = inTransaction(dataSource, PROPOSER, connection -> new JdbcLedgerAccountStore()
                .findOperational(connection, AccountPurpose.FEE_REVENUE, EUR).map(LedgerAccount::id).orElseThrow().value());
        LedgerAdjustments adjustments = context.getBean(LedgerAdjustments.class);
        for (Holder holder : holders) {
            AdjustmentRequest funding = new AdjustmentRequest(today, today, "restore rehearsal opening balance",
                    "the restore rehearsal funds a wallet it opened", List.of(
                            new AdjustmentRequest.Line(fees.toString(), Direction.DEBIT, "250.00", EUR.code()),
                            new AdjustmentRequest.Line(holder.wallet().toString(), Direction.CREDIT, "250.00", EUR.code())));
            LedgerAdjustments.ProposalView proposal = as(PROPOSER, () -> adjustments.propose(funding, UUID.randomUUID().toString()));
            as(APPROVER, () -> adjustments.approve(proposal.proposalId()));
        }
        System.out.println("seed: " + holders.size() + " wallets funded by two-person adjustments");

        // A disbursed loan and a BNPL obligation, before the census, so the census judges their entries (THREAT_MODEL item 44 (a)).
        RestoreRehearsalProducts.seed(context, credit, today, holder(context, dataSource));

        SourceCensus census = context.getBean(SourceCensus.class);
        int judged = 0;
        for (SourceCensus.Batch batch = census.sweepBatch(); batch.judged() > 0; batch = census.sweepBatch()) {
            judged += batch.judged();
        }
        System.out.println("seed: the census judged " + judged + " entries and stands at the end of them");

        TransferExecution transfers = context.getBean(TransferExecution.class);
        for (int i = 0; i < TRANSFERS; i++) {
            Holder from = holders.get(i % holders.size());
            Holder to = holders.get((i + 1) % holders.size());
            Money amount = Money.ofMinorUnits(1000L * (i + 1), EUR);
            String reference = "restore rehearsal transfer " + (i + 1);
            TransferResult result = inTransaction(dataSource, from.actor(), connection -> transfers.execute(connection,
                    new TransferCommand("restore-rehearsal-" + UUID.randomUUID(), from.party(), from.product(), to.product(), amount,
                            reference)));
            if (result.status() != TransferStatus.COMPLETED) {
                throw new IllegalStateException("a seed transfer did not complete: " + result.status() + " " + result.failureReason());
            }
        }
        System.out.println("seed: " + TRANSFERS + " transfers completed after the census's head");
    }

    // ------------------------------------------------------------------ the pieces

    /** A customer, as {@code holder} describes: party, customer and identity by SQL, the wallet through accounts' own opening. */
    static Holder holder(ConfigurableApplicationContext context, DataSource dataSource) {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        // The actor is the identity, as a session makes it: the transfer's risk judgement resolves the payer through it.
        Actor customer = new Actor(identity.toString(), ActorType.CUSTOMER);
        inTransaction(dataSource, customer, connection -> {
            execute(connection, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Restore Rehearsal',"
                    + " now() - interval '2 hour')", party);
            execute(connection, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES (?, ?, 'ACTIVE',"
                    + " now() - interval '1 hour', now() - interval '1 hour')", IDS.next(), party);
            execute(connection, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour', now() - interval '1 hour')", identity, party,
                    "restore-" + identity.toString().replace("-", "").substring(0, 24));
            return null;
        });
        UUID product = inTransaction(dataSource, customer, connection -> context.getBean(AccountOpening.class)
                .open(connection, party, ProductType.WALLET, EUR).account().id().value());
        UUID wallet = inTransaction(dataSource, customer, connection -> new JdbcLedgerAccountStore().findAllOwned(connection, product)
                .stream().filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET).findFirst().orElseThrow().id().value());
        return new Holder(party, product, wallet, customer);
    }

    /** The reference chart as the door's own request: the accounts and rules read from the file, the month and a reason added. */
    private static GlMappingController.GlMappingProposalRequest proposal(Path chart, YearMonth effectiveFrom) {
        try {
            ObjectMapper json = new ObjectMapper();
            JsonNode body = json.readTree(Files.readString(chart));
            List<GlMappingController.GlMappingAccountRequest> accounts = new ArrayList<>();
            body.get("accounts").forEach(account -> accounts.add(new GlMappingController.GlMappingAccountRequest(
                    account.get("code").asText(), account.get("name").asText(), account.get("classification").asText(),
                    account.get("section").asText())));
            List<GlMappingController.GlMappingRuleRequest> rules = new ArrayList<>();
            body.get("rules").forEach(rule -> rules.add(new GlMappingController.GlMappingRuleRequest(rule.get("purpose").asText(),
                    rule.get("ownerKind").asText(), rule.get("glAccountCode").asText())));
            return new GlMappingController.GlMappingProposalRequest(effectiveFrom.toString(), accounts, rules,
                    "the restore rehearsal's reference chart");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    interface Work<R> {
        R apply(Connection connection) throws SQLException;
    }

    /** {@code work} in one transaction as {@code actor}, committed when it returns. */
    static <R> R inTransaction(DataSource dataSource, Actor actor, Work<R> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("a seed transaction failed (SQLState " + failure.getSQLState() + ")", failure);
        }
    }

    /** A desk's call as {@code actor}: the desk opens its own transaction. */
    static <R> R as(Actor actor, java.util.function.Supplier<R> call) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()))) {
            return call.get();
        }
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    static LocalDate date(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("SELECT current_date"); ResultSet row = read.executeQuery()) {
            row.next();
            return row.getObject(1, LocalDate.class);
        }
    }

    /** The database's instant: the one clock every instance shares. */
    static Instant instant(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("SELECT transaction_timestamp()");
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    /** One {@code long} a query answers, read as the first operator: a count, never an amount. */
    static long count(DataSource dataSource, String sql, Object... arguments) {
        return inTransaction(dataSource, PROPOSER, connection -> {
            try (PreparedStatement read = connection.prepareStatement(sql)) {
                for (int i = 0; i < arguments.length; i++) {
                    read.setObject(i + 1, arguments[i]);
                }
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    return row.getLong(1);
                }
            }
        });
    }
}
