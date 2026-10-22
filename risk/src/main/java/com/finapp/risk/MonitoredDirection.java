package com.finapp.risk;

/**
 * A monitored transaction's direction as seen from its subject's ledger account (`P13-TSK-019`): {@code IN} when the
 * account was credited, {@code OUT} when debited. A customer wallet and a merchant payable are liabilities, so a credit
 * is money arriving and a debit money leaving; the rules read the direction with the kind, never alone.
 */
public enum MonitoredDirection {
    IN,
    OUT
}
