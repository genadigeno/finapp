package com.finapp.fx;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Runs work in one transaction (`P9-TSK-005`) - the {@code settlement.TransactionRunner} shape,
 * fx's own so the module needs no edge to a sibling; {@code app} composes it over Spring.
 */
@FunctionalInterface
public interface TransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
