package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.FxRuleSetV1;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * The cover suites' shared moves (`P9-TSK-012`): a booked conversion and the cover it wants, the FX
 * source's rule set v1 activated by two controllers when absent (the runbook's act, which the leg
 * expectations need), and reads of the cover's rows and entry - rendered "PURPOSE CCY DIRECTION
 * MINOR", the conversion suite's idiom.
 */
final class FxCoverFixtures {

    private FxCoverFixtures() {}

    /** The FX source's v1, proposed and approved by two people - once per shared container. */
    static void ensureFxSourceActive(RuleSetAdministration administration, RuleSets ruleSets, DataSource dataSource)
            throws SQLException {
        if (inTransaction(dataSource, uow -> ruleSets.hasActive(uow, FxRuleSetV1.SOURCE))) {
            return;
        }
        UUID ruleSetId = inTransaction(dataSource, uow -> administration.propose(uow,
                FxRuleSetV1.proposal("The FX source's first version, before the first cover executes"),
                new Actor("op-fx-controller-a", ActorType.EMPLOYEE), Instant.now(),
                CorrelationId.generate(FxTestClient.IDS)).ruleSetId());
        inTransaction(dataSource, uow -> administration.approve(uow, ruleSetId,
                new Actor("op-fx-controller-b", ActorType.EMPLOYEE), "Reviewed against O7", Instant.now(),
                CorrelationId.generate(FxTestClient.IDS)));
    }

    /** Books a conversion over HTTP and returns its trade's id. */
    static String convertedTrade(FxTestClient client, FxTestClient.Customer customer, String source,
            String destination, String side, String amount) throws Exception {
        String quote = client.quoteId(customer, source, destination, side, amount);
        HttpResponse<String> converted = client.convert(customer, quote, FxTestClient.key());
        assertThat(converted.statusCode()).as(converted.body()).isEqualTo(201);
        return FxTestClient.field(converted.body(), "id");
    }

    /** The cover a trade's quote wants. */
    static UUID coverOf(String tradeId) throws Exception {
        return UUID.fromString(FxTestClient.scalar(
                "SELECT c.id FROM fx.cover c JOIN fx.trade t ON t.quote_id = c.quote_id WHERE t.id = ?::uuid"
                        + " AND c.kind = 'COVER'", tradeId));
    }

    static String status(UUID cover) throws Exception {
        return FxTestClient.scalar("SELECT status FROM fx.cover WHERE id = ?", cover);
    }

    /** Attempt {@code attempt}'s reference T. */
    static String reference(UUID cover, int attempt) throws Exception {
        return FxTestClient.scalar("SELECT client_reference FROM fx.cover_attempt WHERE cover_id = ? AND attempt = ?",
                cover, attempt);
    }

    /** The cover entry fx-cover:<id>, line by line. */
    static List<String> coverLines(UUID cover) throws Exception {
        return lines("SELECT a.purpose, l.currency, l.direction, l.amount_minor FROM ledger.journal_line l"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " JOIN fx.cover_execution e ON e.journal_entry_id = l.entry_id WHERE e.cover_id = ?"
                + " ORDER BY l.currency, l.direction DESC, a.purpose", cover);
    }

    /** The conversion's entry fx-trade:<id>, line by line - the customer's lines. */
    static List<String> tradeLines(String trade) throws Exception {
        return lines("SELECT a.purpose, l.currency, l.direction, l.amount_minor FROM ledger.journal_line l"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " JOIN fx.trade t ON t.journal_entry_id = l.entry_id WHERE t.id = ?::uuid"
                + " ORDER BY l.currency, l.direction, a.purpose", trade);
    }

    /** FX_POSITION's net (DR - CR) per currency over the trade's and its cover's entries: 0 at rest. */
    static long positionNet(String trade, String currency) throws Exception {
        return FxTestClient.count("SELECT COALESCE(SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor"
                + " ELSE -l.amount_minor END), 0) FROM ledger.journal_line l"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id AND a.purpose = 'FX_POSITION'"
                + " WHERE l.currency = ? AND l.entry_id IN (SELECT t.journal_entry_id FROM fx.trade t WHERE t.id = ?::uuid"
                + " UNION SELECT e.journal_entry_id FROM fx.cover_execution e JOIN fx.cover c ON c.id = e.cover_id"
                + " JOIN fx.trade t ON t.quote_id = c.quote_id WHERE t.id = ?::uuid)", currency, trade, trade);
    }

    private static List<String> lines(String sql, Object parameter) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1) + " " + row.getString(2) + " " + row.getString(3) + " " + row.getLong(4));
                }
            }
        }
        return lines;
    }

    static <R> R inTransaction(DataSource dataSource, Function<Connection, R> work) throws SQLException {
        try (Connection uow = dataSource.getConnection()) {
            uow.setAutoCommit(false);
            try {
                R result = work.apply(uow);
                uow.commit();
                return result;
            } catch (RuntimeException failure) {
                uow.rollback();
                throw failure;
            }
        }
    }
}
