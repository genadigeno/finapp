package com.finapp.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The upsert that decides a park, as text (`P15-TSK-028`). Its behaviour across connections is {@code DeadLetterDatabaseTest}'s, owed (C14). */
@DisplayName("the park is decided in SQL, on the row, on the server's clock (P15-TSK-028)")
class ParkingStatementsTest {

    @Test
    @DisplayName("the budget is both counts, and the elapsed floor is the server's")
    void theBudgetIsBothConditions() {
        assertThat(JdbcInboxParking.FAILURE_SQL)
                .contains("parked.attempts + 1 >= ?")
                .contains("now() - parked.first_failed_at >= (? * INTERVAL '1 millisecond')")
                .contains(" AND ");
    }

    @Test
    @DisplayName("a row another instance settled is left alone, so a terminal state never regresses")
    void aSettledRowIsNotMoved() {
        assertThat(JdbcInboxParking.FAILURE_SQL).contains("WHERE parked.state = 'FAILING'", "RETURNING state, attempts");
    }

    @Test
    @DisplayName("a handled record is settled from failing or parked, nothing else")
    void handledSettles() {
        assertThat(JdbcInboxParking.HANDLED_SQL).contains("state IN ('FAILING', 'PARKED')");
    }

    @Test
    @DisplayName("a record never parks on its first failure, and the elapsed floor is positive")
    void thePolicyIsBounded() {
        assertThatThrownBy(() -> new ParkingPolicy(1, Duration.ofMinutes(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParkingPolicy(5, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ParkingPolicy(5, Duration.ofMinutes(2)).attempts()).isEqualTo(5);
    }
}
