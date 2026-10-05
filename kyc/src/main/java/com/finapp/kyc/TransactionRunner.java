package com.finapp.kyc;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Runs work in one transaction (`P9-TSK-016`) - the {@code fx.TransactionRunner} shape, kyc's own so
 * the module needs no edge to a sibling; {@code app} composes it over Spring.
 */
@FunctionalInterface
public interface TransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
