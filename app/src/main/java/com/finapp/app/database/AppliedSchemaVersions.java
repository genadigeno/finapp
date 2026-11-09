package com.finapp.app.database;

import java.sql.SQLException;
import java.util.Map;

/** Reads each schema's highest applied migration version (`P15-TSK-005`). */
@FunctionalInterface
public interface AppliedSchemaVersions {

    /**
     * @return each schema that has any applied migration, with the highest; a schema with none is absent
     * @throws SQLException if the database did not answer
     */
    Map<String, SchemaVersion> read() throws SQLException;
}
