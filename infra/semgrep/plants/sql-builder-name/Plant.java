// A planted finding (infra/scripts/sast.sh): a statement built with a StringBuilder from a value, read through a name.
// Must be refused by finapp-sql-text-not-constant, by name, on every run.
package plant;

import java.sql.Connection;
import java.sql.PreparedStatement;

class Plant {
    PreparedStatement byStatus(Connection connection, String status) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT * FROM payments.payment_intent WHERE status = '");
        sql.append(status).append("'");
        String text = sql.toString();
        return connection.prepareStatement(text);
    }
}
