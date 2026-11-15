package com.finapp.ledger;

import java.sql.Connection;
import java.util.Optional;

/**
 * A port the ledger declares, for {@code ModuleBoundaryRulesTest}'s teeth (`P15-REM-001`): what makes {@link SqlStoreProbe} a
 * store adapter rather than a published service. A test class claiming {@code com.finapp.ledger}; see
 * {@code SystemClockProbe} for why.
 */
public interface SqlStorePortProbe {

    Optional<SqlStoreProbe.Row> find(Connection unitOfWork, String key);
}
