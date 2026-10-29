/**
 * Accounting / General Ledger and Reporting: the one authoritative ledger turned into reportable, closable,
 * reproducible financial information - <strong>without a second ledger</strong> (`P14-TSK-001`; ADR-0113...ADR-0118,
 * {@code PHASE_14_PLAN.md}).
 *
 * <p><strong>What belongs here.</strong> Contexts 25 and 27 in one module (ADR-0113 decision 1): the General Ledger as a
 * versioned mapping by ledger purpose over the journal ({@link com.finapp.accounting.GlClassification},
 * {@link com.finapp.accounting.StatementSection}); accounting periods and their four-eyes close
 * ({@link com.finapp.accounting.PeriodStatus}, {@link com.finapp.accounting.CloseCheckCode}); the posting rule register
 * and the GL &lt;-&gt; ledger &lt;-&gt; source checks with their born-once breaks
 * ({@link com.finapp.accounting.DatingClass}, {@link com.finapp.accounting.BreakType},
 * {@link com.finapp.accounting.BreakStatus}); valuation rate sets; prior-period adjustment records; immutable,
 * reproducible report runs ({@link com.finapp.accounting.ReportKind}, {@link com.finapp.accounting.RunStatus}); and
 * regulatory obligations and submissions behind an adapter ({@link com.finapp.accounting.SubmissionStatus}).
 *
 * <p><strong>What never belongs here.</strong> A balance, a line amount or a journal of its own: every GL figure is a
 * function of the ledger's journal lines and the mapping version the period pins ({@code INV-ACC-06}); the only stored
 * figures are a report run's frozen output. Accounting writes the journal only through the ledger's own
 * {@code ACCOUNTING}-origin adjustment door ({@link com.finapp.accounting.PriorPeriodPostings}), and its reporting never
 * writes at all ({@code INV-ACC-14}, {@code ReportingNeverWritesTest}: report code lives in
 * {@code com.finapp.accounting.reporting} and reaches no writer). The posting cutoff is the ledger's row; the product
 * proofs are their modules'; reconciliation's breaks and suspense are disclosed, never copied; no accounting surface is
 * customer- or merchant-facing ({@code NoCustomerSurfaceReadsAccountingTest}). Never floating point
 * ({@code INV-MON-01}).
 *
 * <p><strong>Edges.</strong> Exactly {@code platform} and {@code sharedkernel} ({@code AccountingModuleIsolationTest}) -
 * no ledger edge, no business module, not {@code risk} - and no module but {@code app} depends on this one. Every other
 * domain is a port this module declares and {@code app} implements: {@link com.finapp.accounting.JournalReads},
 * {@link com.finapp.accounting.LedgerChart}, {@link com.finapp.accounting.PostingCutoff},
 * {@link com.finapp.accounting.PriorPeriodPostings}, {@link com.finapp.accounting.SourceProofs},
 * {@link com.finapp.accounting.AccrualCompleteness}, {@link com.finapp.accounting.ReconciliationDisclosure},
 * {@link com.finapp.accounting.ValuationRates} and {@link com.finapp.accounting.RegulatoryReportingProvider} - declared
 * empty with the boundary, each given its methods and its implementation by the task that first consumes it.
 *
 * <p><strong>Trust.</strong> Three disjoint desks (owner decision C12; {@code INV-AUD-03}, {@code RoleNameTest}): the
 * accountant reads and proposes (mapping versions, closes, adjustments) and generates reports; the financial controller
 * approves (mapping versions, closes, adjustments, regulatory submissions); the regulatory reporter prepares and submits.
 * No role holds the propose and the approve of one act; holders need not be disjoint, so approver &lt;&gt; proposer is a
 * {@code CHECK} on every four-eyes row and a refusal at the domain ({@code accounting.SelfApprovalRefused},
 * {@code INV-AUD-04}).
 *
 * <p><strong>What exists so far.</strong> The boundary, the migrator-owned schema floor ({@code V001}, no table), the
 * closed vocabularies with their lifecycles' edge tables, the nine ports, the two error codes and one audit action the
 * role split owns. No table, no figure, no door, no event, no schedule.
 */
package com.finapp.accounting;
