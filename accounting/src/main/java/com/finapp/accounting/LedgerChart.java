package com.finapp.accounting;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

/**
 * The ledger's chart as a GL mapping version is checked against it (`P14-TSK-001` declared it, `P14-TSK-003` gives it
 * its method; {@code PHASE_14_PLAN.md} sections 4.2, 8.1 T-map; ADR-0113 decision 3; {@code INV-ACC-06}, {@code -07}) -
 * every account purpose of the ledger's closed enum with its owner kind and its account types, and the counterparties
 * that exist - so an activation can refuse a version that is not total over the purposes or not
 * classification-compatible ({@link GlClassification} names = the ledger's account type names). Purposes travel as
 * strings: {@code accounting} has no ledger edge; {@code app} implements this over the ledger's own vocabulary.
 *
 * <p><strong>Read-only</strong> ({@code SELECT} only), on the caller's unit of work, so the activation judges the chart
 * as its own transaction sees it.
 */
public interface LedgerChart {

    /** The chart now, read on {@code unitOfWork}. */
    Chart read(Connection unitOfWork);

    /**
     * One purpose: its name, its owner kind, and the account types it holds - the types of the ledger's accounts of this
     * purpose joined by the type its owning module declares for an account it opens at runtime (a loan's, an
     * obligation's, a wallet's, a merchant's), so a purpose with no account yet is still judged. Two types is a ledger
     * defect; none is unjudgeable - {@link MappingJudgement} refuses both.
     */
    record ChartPurpose(String purpose, String ownerKind, Set<String> types) {

        public ChartPurpose {
            types = Set.copyOf(types);
        }

        /** {@link MappingRule#key()}'s shape. */
        public String key() {
            return purpose + "/" + ownerKind;
        }
    }

    /** A counterparty the ledger settles with - a dimension under its purpose's GL account, never a mapping key. */
    record ChartCounterparty(String code, String kind) {}

    /** The chart: every purpose (the totality's domain) and the counterparties. */
    record Chart(List<ChartPurpose> purposes, List<ChartCounterparty> counterparties) {

        public Chart {
            purposes = List.copyOf(purposes);
            counterparties = List.copyOf(counterparties);
        }
    }
}
