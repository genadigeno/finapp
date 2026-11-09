package com.finapp.platform.abuse;

import java.sql.Connection;
import java.sql.SQLException;

/** Supplies the connection one count runs on: never one a business transaction holds (`P15-TSK-022`). */
@FunctionalInterface
public interface CounterConnections {

    /** Opens a connection. The caller closes it. */
    Connection open() throws SQLException;
}
