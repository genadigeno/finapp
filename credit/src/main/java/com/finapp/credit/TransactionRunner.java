package com.finapp.credit;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Runs work in one transaction (`P10-TSK-006`) - the {@code kyc.TransactionRunner} shape, credit's own so the
 * module needs no edge to a sibling; {@code app} composes it over Spring.
 */
@FunctionalInterface
public interface TransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
