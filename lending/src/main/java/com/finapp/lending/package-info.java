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
 * {@code ledger}, {@code creditmath}, {@code platform} and {@code sharedkernel} ({@code LendingModuleIsolationTest}), and
 * no module depends on this one - {@code bnpl} included.
 *
 * <p><strong>The engines are {@code creditmath}'s since `P12-TSK-002`</strong> (ADR-0101 decision 5). The schedule,
 * accrual and allocation engines, their records and the engine version identities moved there unchanged - every figure,
 * refusal and persisted name the same - so that {@code bnpl} computes with them without an edge to this module.
 * {@link com.finapp.lending.LoanEngines} is lending's side of that edge: the {@code LoanTerms} entries, mapping a loan's
 * terms onto the engines' records and delegating. The statement engine stays here - a line's engine, no other product's -
 * and its version identity stays in {@code creditmath}'s one registry beside the others.
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
 * ({@link com.finapp.creditmath.EngineVersion}) and the aggregates' typed identifiers. The loan chart of accounts
 * ({@code P11-TSK-003}, ledger {@code V026}): {@link com.finapp.lending.LoanAccounts} opens a loan's six ledger accounts
 * in its one currency, typed as ADR-0096's account plan, in the caller's transaction - the acceptance's
 * ({@code -012}). Lending capital ({@code P11-TSK-004}): {@code V002}'s two INSERT-only tables, the four-eyes
 * {@link com.finapp.lending.LendingCapitalAdministration} (advisory namespace {@code 11}), the ports
 * {@link com.finapp.lending.CapitalExpectations} and {@link com.finapp.lending.CapitalRecognitions} that {@code app}
 * implements over reconciliation, {@link com.finapp.lending.LendingCapitalHeadroom} (L6) and
 * {@link com.finapp.lending.LendingCapitalProof}. Lending still posts nothing: capital is credited only by bank
 * recognition of a statement line (rule 0, reconciliation's), and recognition is read here, never stored. Loan product
 * terms as versioned data ({@code P11-TSK-005}): {@code V003}'s money-free machine row and INSERT-only term set,
 * {@link com.finapp.lending.LoanTerms} judged whole (only implemented conventions), the four-eyes
 * {@link com.finapp.lending.LoanTermsAdministration} (A0 to A2; {@code pinActive} the {@code FOR SHARE} read the
 * application and offer will pin) and lending's first event, {@code lending.LoanTermsVersionActivated}. The
 * allocation engine ({@code P11-TSK-008}): {@link com.finapp.creditmath.AllocationEngineV1}, pure and versioned - a
 * payment over a {@link com.finapp.creditmath.DueState} by the agreement's pinned
 * {@link com.finapp.creditmath.AllocationRules} into {@link com.finapp.creditmath.AllocationLine}s, billed items oldest first,
 * the pinned component order within each, the remainder by the overpayment treatment; conserving and never above due
 * ({@code INV-LND-04}), unwired until billing and repayment ({@code -017}, {@code -018}). The accrual engine
 * ({@code P11-TSK-007}): {@link com.finapp.creditmath.AccrualEngineV1}, pure - daily {@code ACT_365F} interest on the base
 * the caller supplies, rounded once per period ({@code INV-LND-09}) - over the {@code ACT_365F} arithmetic it shares
 * with the schedule engine; nothing accrues yet (the sweep is {@code P11-TSK-016}).
 * The schedule engine ({@code P11-TSK-006}): {@link com.finapp.creditmath.ScheduleEngineV1}, pure, projecting a
 * conserving {@link com.finapp.creditmath.Schedule} or a typed {@link com.finapp.creditmath.ScheduleOutcome.Refused}, and
 * judging at the terms door, through the port {@link com.finapp.lending.LoanProductBounds} {@code app} implements over
 * credit, whether terms amortise across the product's bounds.
 * The statement engine ({@code P11-TSK-009}): {@link com.finapp.lending.StatementEngineV1}, pure - a credit line's
 * cycles from its agreement's statement day ({@link com.finapp.lending.StatementTerms}), and a cycle's
 * {@link com.finapp.lending.Statement}: the cycle's posted accruals summed, the fees carried, A24's principal part capped
 * at the drawn principal not yet due and the minimum payment, due the agreement's payment-due days (A23: 25) after the statement date - or a typed
 * {@link com.finapp.lending.StatementRefused}; nothing is stated yet (the statement step is {@code P11-TSK-022}).
 * The loan application ({@code P11-TSK-010}): {@code V004}'s money-free machine row and its history,
 * {@link com.finapp.lending.LoanApplications} (one customer act opening credit's decision request in the same
 * transaction through {@link com.finapp.lending.LoanCreditDecisions}, the party's standing through
 * {@link com.finapp.lending.LoanPartyStanding} - ports {@code app} implements over credit - and the withdrawal under the
 * application's row lock), the closed {@link com.finapp.lending.RepaymentMethod} and
 * {@link com.finapp.lending.PayoutPreference}.
 * The offer ({@code P11-TSK-011}): {@code V005}'s money-free machine row, its INSERT-only terms and its history;
 * {@link com.finapp.lending.LoanOrigination} (the origination step - an application's offer, decline or undecided
 * closure on credit's answer read through {@link com.finapp.lending.LoanCreditDecisions#decisionFor}, the terms version
 * active at the offering instant pinned - and the offer's expiry), {@link com.finapp.lending.LoanOffers} (the owner's
 * read and the decline), {@link com.finapp.lending.CanonicalOfferTerms} and {@link com.finapp.lending.OfferDocument},
 * each hashed; credit's events reach lending only as hints that pull a permit forward.
 * The acceptance and the loan account ({@code P11-TSK-012}): {@code V006}'s money-free {@code loan} of either kind and its
 * history, the INSERT-only agreement (version 1 the offer's terms copied byte for byte), acceptance evidence and a loan's
 * {@code PENDING} disbursement; {@link com.finapp.lending.LoanAcceptances} (T-acc: the party's credit profile first
 * through {@link com.finapp.lending.LoanCreditDecisions#lockExposure}, the offer, credit's consumption through
 * {@link com.finapp.lending.LoanCreditDecisions#consume}, for a loan the capital row and headroom, then the account and
 * its six ledger accounts) and {@link com.finapp.lending.Loans} (the owner's reads, balances derived from journal
 * lines); the payout destination verified through {@link com.finapp.lending.LoanPayoutDestinations}. Still no posting:
 * the first is the disbursement's ({@code -014}).
 */
package com.finapp.lending;
