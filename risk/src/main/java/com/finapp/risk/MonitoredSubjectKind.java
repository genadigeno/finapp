package com.finapp.risk;

/**
 * Whose movement a monitored transaction is (`P13-TSK-019`): the two owners the ledger's journal can name by reference -
 * a customer account ({@code ledger_account.owner_kind = CUSTOMER}, its {@code owner_ref} the customer account id) and
 * a merchant ({@code MERCHANT}, the merchant id). The party behind a customer account is not derivable from the journal
 * and is resolved by the rules' task through a port, never read here ({@link SubjectKind#ACCOUNT},
 * {@link SubjectKind#MERCHANT} are the risk subjects these map onto).
 */
public enum MonitoredSubjectKind {
    ACCOUNT,
    MERCHANT
}
