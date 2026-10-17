/**
 * BNPL: buy now, pay later - merchant-financed instalment credit on Phase 10's credit decisions, the agreement and its
 * servicing facts, and never the money (`P12-TSK-002`; ADR-0101...ADR-0106, {@code PHASE_12_PLAN.md}).
 *
 * <p><strong>What belongs here.</strong> The two products ({@link com.finapp.bnpl.BnplProduct}): pay-in-4, four
 * instalments every two weeks, the first at purchase, interest-free; and pay-in-N, 3, 6 or 12 monthly instalments with
 * interest (owner decision B1). One economic event, two financial flows never collapsed: at checkout a customer's BNPL
 * Application opens one credit decision request per purchase (B5); an approved BNPL Offer's acceptance <em>is</em> the
 * BNPL Purchase - in one transaction the decision is consumed, the merchant financed (its payable credited the gross
 * less the merchant discount) and the Customer Repayment Obligation born, funded from lending capital (B2). Then the
 * obligation's servicing - its BNPL Schedule and BNPL Instalments, billings, pay-in-N's accruals, repayments and their
 * allocations, collection attempts, conditions, late fees and payoff - the merchant's refunds, latest instalments first
 * (B3), and the customer's disputes. This module owns the BNPL Terms Version, Application, Offer, Agreement and its
 * Acceptance Evidence, Purchase, Merchant Financing record, Obligation and every born-once servicing fact.
 *
 * <p><strong>What never belongs here.</strong> Money as a stored figure: every principal, interest, fee and
 * credit-balance figure is a balance of a per-obligation ledger account, posted through the ledger's
 * {@code PostingService} / {@code ReversalService} and never written here ({@code INV-LED-04}, {@code INV-BNPL-01});
 * every operational amount is an immutable born-once row, and no table holds a mutable money column
 * ({@code BnplSchemaHasNoMutableMoneyTest}). The merchant, its fee schedules, its BNPL enablement and every payout are
 * {@code merchant}'s - <strong>bnpl never pays a merchant out</strong>; the merchant payable is the ledger's position.
 * The checkout session and the order are {@code checkout}'s. The credit decision, its consumption and the exposure
 * judgement are {@code credit}'s - a decision is taken up only through credit's consumption port with
 * {@code ConsumerKind.BNPL_AGREEMENT}. Lending capital is {@code lending}'s, read through a port - <strong>bnpl never
 * reads a lending store</strong> and never creates a {@code loan} or posts a {@code LOAN_*} purpose: lending's
 * {@code loan} is deliberately not composed as the servicing back-end (ADR-0101 decision 4). The wallet is
 * {@code accounts}'. <strong>bnpl never opens a collections case</strong>: it emits the condition facts, and collections
 * is Phase 13's. Write-off and provisioning are Phase 14's. It calls no provider and adds no external evidence.
 *
 * <p><strong>Edges.</strong> Exactly {@code creditmath} (lending's pure schedule, accrual and allocation engines,
 * shared without an edge to lending), {@code ledger}, {@code platform} and {@code sharedkernel}
 * ({@code BnplModuleIsolationTest}), and no module depends on this one. Every other context is a port this module
 * declares and {@code app} implements, each landing with the task that first uses it.
 *
 * <p><strong>What exists so far.</strong> The boundary, the migrator-owned schema floor ({@code V001}, no table) and the
 * closed vocabularies ({@code P12-TSK-002}): the products, their plans and the instalment frequencies; the application,
 * offer, purchase, obligation and dispute lifecycles with the application's and the obligation's closure reasons (each
 * {@code BNPL_LIFECYCLES.md}'s); bnpl's own engine identities ({@link com.finapp.bnpl.BnplEngine}); and the aggregates'
 * typed identifiers. And the obligation's chart ({@code P12-TSK-003}, ledger {@code V027}):
 * {@link com.finapp.bnpl.BnplAccounts} opens an obligation's six ledger accounts - typed, in its one currency,
 * converged under concurrency, a mis-shaped account refused - into a {@link com.finapp.bnpl.BnplLedgerAccounts};
 * unwired until the purchase ({@code P12-TSK-013}), and it posts nothing. No port, bean, route, event or schedule.
 */
package com.finapp.bnpl;
