package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplObligationId;
import com.finapp.lending.LoanId;
import com.finapp.risk.RiskStorageException;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credit agreement's party join's statements (`P13-TSK-025`), hermetically - the {@code BnplCreditExposureTest}
 * idiom: a recording connection answers each statement, so what is asked of the database is read here. One statement
 * per arm over the module's published definer view, by the agreement's id, the party alone selected, no lock, no table
 * of either module; nothing found is empty; a failure names the SQLSTATE only. The live read - the views, their columns
 * and the application role's grant on PostgreSQL - is {@code CollectionsCaseDatabaseTest#theJoinReadsThePublishedViews}'s
 * (written and owed, C5).
 */
@DisplayName("a credit agreement's party, read from lending's and bnpl's published views (P13-TSK-025)")
class JdbcCreditAgreementPartiesTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("one statement per arm, over the view, by the id, the party alone - never a table, a lock or a figure")
    void theStatementsReadTheViewsByIdAlone() {
        assertThat(JdbcCreditAgreementParties.LOAN)
                .isEqualTo("SELECT party_id FROM lending.party_principal_exposure WHERE loan_id = ?");
        assertThat(JdbcCreditAgreementParties.OBLIGATION)
                .isEqualTo("SELECT party_id FROM bnpl.party_principal_exposure WHERE obligation_id = ?");
        for (String statement : List.of(JdbcCreditAgreementParties.LOAN, JdbcCreditAgreementParties.OBLIGATION)) {
            assertThat(statement).doesNotContain("FOR UPDATE").doesNotContain("FOR SHARE")
                    .doesNotContainPattern("amount|balance|_minor|lending\\.loan\\b|bnpl\\.bnpl_obligation\\b");
        }
    }

    @Test
    @DisplayName("the party a row names is the answer, bound to the agreement's id; no row is empty")
    void theAnswer() {
        UUID borrower = IDS.next();
        LoanId loan = LoanId.of(IDS.next());
        Recorded found = new Recorded(List.of(List.of(borrower)));
        assertThat(new JdbcCreditAgreementParties().partyOfLoan(found.connection(), loan)).contains(borrower);
        assertThat(found.statements).containsExactly(JdbcCreditAgreementParties.LOAN);
        assertThat(found.bound).containsExactly(loan.value());

        BnplObligationId obligation = BnplObligationId.of(IDS.next());
        Recorded none = new Recorded(List.of(List.of()));
        assertThat(new JdbcCreditAgreementParties().partyOfObligation(none.connection(), obligation)).isEmpty();
        assertThat(none.statements).containsExactly(JdbcCreditAgreementParties.OBLIGATION);
        assertThat(none.bound).containsExactly(obligation.value());
    }

    @Test
    @DisplayName("a failure names the operation and the SQLSTATE - never the driver's message, which can carry a row")
    void aFailureNamesTheSqlStateOnly() {
        Connection failing = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    throw new SQLException("Failing row contains (secret-party)", "57014");
                });
        assertThatThrownBy(() -> new JdbcCreditAgreementParties().partyOfLoan(failing, LoanId.of(IDS.next())))
                .isInstanceOf(RiskStorageException.class).hasMessageEndingWith("SQLSTATE 57014")
                .hasMessageNotContaining("secret");
    }

    /** A connection answering each statement with the next result: its rows of one column each. */
    private static final class Recorded {
        final List<String> statements = new ArrayList<>();
        final List<Object> bound = new ArrayList<>();
        private final Iterator<List<UUID>> results;

        Recorded(List<List<UUID>> results) {
            this.results = results.iterator();
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            statements.add((String) args[0]);
                            return statement(results.next());
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private PreparedStatement statement(List<UUID> rows) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setObject" -> {
                            bound.add(args[1]);
                            yield null;
                        }
                        case "executeQuery" -> resultSet(rows.iterator());
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ResultSet resultSet(Iterator<UUID> rows) {
            UUID[] current = new UUID[1];
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            boolean more = rows.hasNext();
                            current[0] = more ? rows.next() : null;
                            yield more;
                        }
                        case "getObject" -> current[0];
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
