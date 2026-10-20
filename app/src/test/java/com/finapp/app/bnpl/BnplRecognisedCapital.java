package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Lending capital that is <em>recognised</em>, for a suite whose assertions read the capital proof (`P12-TSK-028`): a
 * contribution proposed and approved under four eyes over lending's own door, then recognised from the simulated bank's
 * next EUR statement - `P11-TSK-004`'s one path (ADR-0096 section 4), {@code LendingCapitalDatabaseTest#recognise}'s
 * shape. {@link BnplPurchaseWorld#plantCapital}'s raw {@code DR CASH_AT_BANK / CR LENDING_CAPITAL} is headroom no
 * recognition explains, which the capital proof's completeness arm rightly fails ({@code INV-LND-13}); a suite that
 * asserts the whole three-way proof green at rest plants its capital here instead.
 *
 * <p>The statement chain starts at sequence 1 in the suite's own database, so this is for an {@code own-container} suite
 * that writes no other bank statement; the chain's place is held statically, one chain per JVM - one suite per JVM.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
final class BnplRecognisedCapital {

    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final String CONTRIBUTIONS = "/v1/operator/lending/capital-contributions";
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    /** The EUR chain's place - the next sequence and the last closing (minor units). */
    private static long nextSequence = 1;
    private static long closingMinor = 0;

    private final BnplApplicationClient client;
    private final Authorization authorization;
    private final LendingCapitalHeadroom headroom;
    private final BnplTransactions transactions;
    private final FileReception<Connection> reception;
    private final FileParsing parsing;
    private final BatchAcceptance acceptance;
    private final TransactionRunner settlementTransactions;

    BnplRecognisedCapital(BnplApplicationClient client, Authorization authorization, LendingCapitalHeadroom headroom,
            BnplTransactions transactions, FileReception<Connection> reception, FileParsing parsing,
            BatchAcceptance acceptance, TransactionRunner settlementTransactions) {
        this.client = client;
        this.authorization = authorization;
        this.headroom = headroom;
        this.transactions = transactions;
        this.reception = reception;
        this.parsing = parsing;
        this.acceptance = acceptance;
        this.settlementTransactions = settlementTransactions;
    }

    /** The EUR headroom raised to at least {@code target} by recognising the shortfall. */
    void headroomAtLeast(Money target) throws Exception {
        Money free = transactions.inTransaction(uow -> headroom.atRest(uow, BnplPurchaseWorld.EUR).headroom());
        if (free.compareTo(target) < 0) {
            recognise(target.minus(free).minorUnits());
        }
    }

    private void recognise(long minor) throws Exception {
        String proposer = client.operatorSession(authorization, RoleName.LENDING_TREASURY_OFFICER);
        String approver = client.operatorSession(authorization, RoleName.LENDING_TREASURY_OFFICER);
        String reference = "LCAP-" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
        HttpResponse<String> proposed = client.post(CONTRIBUTIONS, "{\"currency\":\"EUR\",\"amountMinor\":" + minor
                + ",\"reference\":\"" + reference + "\",\"reason\":\"BNPL capital\"}", proposer,
                BnplApplicationClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String contribution = BnplApplicationClient.field(proposed.body(), "id");
        HttpResponse<String> approved = client.post(CONTRIBUTIONS + "/" + contribution + "/approval",
                "{\"reason\":\"board minute\"}", approver, BnplApplicationClient.key());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        long before = transactions.inTransaction(uow -> headroom.atRest(uow, BnplPurchaseWorld.EUR).capital())
                .minorUnits();
        UUID file = pulledStatement(minor, reference);
        parsing.sweep();
        acceptance.sweep();
        assertThat(BnplApplicationClient.scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM"
                + " settlement.file WHERE id = ?", file)).isEqualTo("ACCEPTED:-");
        closingMinor += minor;
        long after = transactions.inTransaction(uow -> headroom.atRest(uow, BnplPurchaseWorld.EUR).capital())
                .minorUnits();
        assertThat(after - before).as("the contribution recognised from the statement").isEqualTo(minor);
    }

    /** Pulls the next EUR statement with one capital credit - its opening the chain's last closing. */
    private UUID pulledStatement(long minor, String reference) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String text = ":20:BNPL-CAP-STMT-" + nextSequence + "-" + marker + "\n"
                + ":25:SIMBANK-EUR-01\n"
                + ":28C:" + nextSequence + "\n"
                + ":60F:C," + today + ",EUR," + decimal(closingMinor) + "\n"
                + ":61:" + today + ",C," + decimal(minor) + "," + reference + "\n"
                + ":62F:C," + today + ",EUR," + decimal(closingMinor + minor) + "\n";
        nextSequence++;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            FileReception.Result result = settlementTransactions.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(BANK_SOURCE, DeliveryChannel.PULL, text.getBytes(StandardCharsets.UTF_8),
                            Optional.empty(), Actor.SYSTEM, SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private static String decimal(long minor) {
        return (minor / 100) + "." + String.format(Locale.ROOT, "%02d", minor % 100);
    }
}
