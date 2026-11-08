// A planted finding (infra/scripts/sast.sh): must be refused by finapp-sql-text-not-constant, by name, on every run.
package plant;

import java.sql.Connection;
import java.sql.PreparedStatement;

class Plant {
    PreparedStatement byReference(Connection connection, String reference) throws Exception {
        return connection.prepareStatement("SELECT * FROM ledger.journal_entry WHERE reference = '" + reference + "'");
    }
}
