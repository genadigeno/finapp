package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.BookRail;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The compiled register and the seeded rows agree (`P8-TSK-002`): the register holds what a
 * source IS — kind, format, channels, position — and `settlement.source` holds only identity
 * and operational state, so the two must reconcile by code and kind exactly, or a deployment
 * carries a source its database has never heard of, or rows nobody declares.
 */
@Tag("database")
@DisplayName("the source register and the seeded rows agree (P8-TSK-002)")
class SettlementSourceRegisterDatabaseTest {

    @Test
    @DisplayName("codes and kinds match one for one, both ways")
    void theRegisterAndTheSeedsReconcile() throws SQLException {
        SettlementSources register =
                SettlementBeans.composedSettlementSources(
                        PaymentRails.of(
                                List.of(
                                        SimulatedCardPspAdapter.RAIL,
                                        SimulatedInstantSchemeAdapter.RAIL,
                                        BookRail.RAIL)));
        Map<String, String> declared = new TreeMap<>();
        for (SettlementSourceDescriptor source : register.declared()) {
            declared.put(source.code(), source.kind().name());
        }

        Map<String, String> seeded = new TreeMap<>();
        try (Connection connection = DatabaseRoles.application();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT code, kind FROM settlement.source")) {
            while (rows.next()) {
                seeded.put(rows.getString("code"), rows.getString("kind"));
            }
        }

        assertThat(seeded)
                .as("every declared source is seeded and every seeded row is declared - by"
                        + " code AND kind")
                .containsExactlyInAnyOrderEntriesOf(declared);
    }
}
