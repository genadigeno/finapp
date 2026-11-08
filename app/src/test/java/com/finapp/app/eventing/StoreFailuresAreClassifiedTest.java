package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.bnpl.BnplApplicationId;
import com.finapp.bnpl.BnplStorageException;
import com.finapp.bnpl.JdbcBnplApplicationStore;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycStorageException;
import com.finapp.lending.JdbcLoanApplicationStore;
import com.finapp.lending.LendingStorageException;
import com.finapp.lending.LoanApplicationId;
import com.finapp.platform.inbox.InboxFailures;
import com.finapp.risk.JdbcRiskSignalStore;
import com.finapp.risk.RiskStorageException;
import com.finapp.sharedkernel.event.EventId;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the production handlers' own stores really throw, classified (`P15-TSK-028`'s review fix round, F1).
 *
 * <p>Each store is the real one, over a connection whose first statement fails with the driver's exception: a store drops that
 * exception on purpose, because a constraint violation's message carries the row, and keeps only its SQLState, in text. A
 * classification that walked the cause chain alone could not see any of these, so a brownout counted against every valid record.
 */
@DisplayName("what each production handler's store really throws is classified by its SQLState (P15-TSK-028)")
class StoreFailuresAreClassifiedTest {

    private static final UUID SOME = UUID.fromString("01890f42-98c4-7cc3-9c5c-0a1b2c3d4e5f");

    @ParameterizedTest(name = "{0}: transient={1}")
    @CsvSource({"08006,true", "40P01,true", "57P01,true", "53300,true", "55P03,true", "23514,false", "22P02,false", "P0001,false"})
    @DisplayName("KYC's, risk's, BNPL's and lending's stores, the four the production handlers write through")
    void everyStoreIsClassified(String sqlState, boolean transientFailure) {
        Connection failing = failingWith(sqlState);

        assertClassified(catchThrowable(() -> new JdbcKycCaseStore().findOpenFor(failing, SOME)), KycStorageException.class, transientFailure);
        assertClassified(catchThrowable(() -> new JdbcRiskSignalStore().recordedFrom(failing, EventId.of(SOME))), RiskStorageException.class, transientFailure);
        assertClassified(catchThrowable(() -> new JdbcBnplApplicationStore().ownedBy(failing, BnplApplicationId.of(SOME), SOME)), BnplStorageException.class, transientFailure);
        assertClassified(catchThrowable(() -> new JdbcLoanApplicationStore().ownedBy(failing, LoanApplicationId.of(SOME), SOME)), LendingStorageException.class, transientFailure);
    }

    private static void assertClassified(Throwable thrown, Class<? extends Throwable> expected, boolean transientFailure) {
        assertThat(thrown).isInstanceOf(expected).hasNoCause();
        assertThat(InboxFailures.isTransient(thrown)).as("%s: %s", expected.getSimpleName(), thrown.getMessage()).isEqualTo(transientFailure);
    }

    /** Fails the first statement as PostgreSQL would, with the row in the driver's message, which no store may keep. */
    private static Connection failingWith(String sqlState) {
        Function<Object[], Object> fail = args -> {
            throw new IllegalStateException("unreachable");
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                throw new SQLException("ERROR: Failing row contains (secret)", sqlState);
            }
            return fail.apply(args);
        });
    }
}
