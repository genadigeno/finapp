/**
 * Lending: originating and servicing credit on Phase 10's credit decisions - the contract and its servicing facts,
 * and never the money.
 *
 * <p><strong>What belongs here.</strong> The two products ({@link com.finapp.lending.LoanProduct}): the amortising
 * {@code PERSONAL_LOAN} and the revolving {@code CREDIT_LINE}, one account model for both
 * ({@link com.finapp.lending.LoanKind}). The Loan Application, the four-eyes Loan Product Terms Version, the expiring
 * Loan Offer, the immutable, versioned Loan Agreement and its Acceptance Evidence, the Loan account - identity, kind,
 * status and permits, with <em>no amount, rate or balance column</em> - and every born-once servicing fact: schedules,
 * billings, statements, draws, accruals, fee assessments, repayments and their allocations, collection attempts,
 * conditions, payoffs, amendments, and the administration of lending capital (ADR-0090...ADR-0100).
 *
 * <p><strong>What never belongs here.</strong> Money as a stored figure: every principal, interest, fee and
 * credit-balance figure is a balance of one of a loan's six ledger accounts, posted through the ledger's
 * {@code PostingService} / {@code ReversalService} and never written here ({@code INV-LED-04}, {@code INV-LND-01});
 * every operational amount is an immutable born-once row, and no table holds a mutable money column
 * ({@code LendingSchemaHasNoMutableMoneyTest}). The credit decision, its consumption and the exposure judgement are
 * {@code credit}'s - taken up only through credit's consumption port, reached through a port this module declares and
 * {@code app} composes, never a build edge (A16); the wallet is {@code accounts}', the external payout and its
 * ambiguity {@code payments}', the capital contribution's recognition {@code reconciliation}'s. The edges are exactly
 * {@code ledger}, {@code platform} and {@code sharedkernel} ({@code LendingModuleIsolationTest}), and no module depends
 * on this one.
 *
 * <p><strong>The Phase 12 boundary.</strong> Buy-now-pay-later is {@code bnpl}'s (Phase 12), not a third member of
 * {@link com.finapp.lending.LoanProduct}: merchant-financed instalment credit is one economic event producing two
 * financial flows - the merchant's financing and the customer's obligation - with refund and chargeback semantics a
 * loan does not have. {@code bnpl} will reference lending's concepts by identifier and own neither; lending neither
 * knows a merchant nor finances an order. Refinance, write-off and collections are deferred with named owners
 * ({@code PHASE_11_PLAN.md} section 17): no member, state or posting here anticipates them.
 *
 * <p><strong>What exists so far.</strong> The boundary, the migrator-owned schema floor ({@code V001}) and the closed
 * vocabularies ({@code P11-TSK-002}): the products and their kind, frequency and day count (only implemented members -
 * {@code INV-LND-09}), the application, offer and loan lifecycles with the loan's closure and cancellation reasons, the
 * delinquency buckets, the allocation components and overpayment treatments, the four engines at version 1
 * ({@link com.finapp.lending.EngineVersion}) and the aggregates' typed identifiers. The loan chart of accounts
 * ({@code P11-TSK-003}, ledger {@code V026}): {@link com.finapp.lending.LoanAccounts} opens a loan's six ledger accounts
 * in its one currency, typed as ADR-0096's account plan, in the caller's transaction - unwired until the acceptance
 * ({@code -012}). Lending capital ({@code P11-TSK-004}): {@code V002}'s two INSERT-only tables, the four-eyes
 * {@link com.finapp.lending.LendingCapitalAdministration} (advisory namespace {@code 11}), the ports
 * {@link com.finapp.lending.CapitalExpectations} and {@link com.finapp.lending.CapitalRecognitions} that {@code app}
 * implements over reconciliation, {@link com.finapp.lending.LendingCapitalHeadroom} (L6) and
 * {@link com.finapp.lending.LendingCapitalProof}. Lending still posts nothing: capital is credited only by bank
 * recognition of a statement line (rule 0, reconciliation's), and recognition is read here, never stored. Loan product
 * terms as versioned data ({@code P11-TSK-005}): {@code V003}'s money-free machine row and INSERT-only term set,
 * {@link com.finapp.lending.LoanTerms} judged whole (only implemented conventions), the four-eyes
 * {@link com.finapp.lending.LoanTermsAdministration} (A0 to A2; {@code pinActive} the {@code FOR SHARE} read the
 * application and offer will pin) and lending's first event, {@code lending.LoanTermsVersionActivated}. The schedule engine
 * ({@code P11-TSK-006}): {@link com.finapp.lending.ScheduleEngineV1}, pure, projecting a conserving
 * {@link com.finapp.lending.Schedule} or a typed {@link com.finapp.lending.ScheduleOutcome.Refused}, and judging at the
 * terms door, through the port {@link com.finapp.lending.LoanProductBounds} {@code app} implements over credit, whether
 * terms amortise across the product's bounds.
 */
package com.finapp.lending;
