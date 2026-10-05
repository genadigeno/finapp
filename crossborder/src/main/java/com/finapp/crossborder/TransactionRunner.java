package com.finapp.crossborder;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Runs work in one transaction (`P9-TSK-015`) - the {@code fx.TransactionRunner} shape, crossborder's
 * own so the module needs no edge to a sibling; {@code app} composes it over Spring.
 */
@FunctionalInterface
public interface TransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
