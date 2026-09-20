/**
 * Money movement whose outcome is decided by an unreliable third party: the payment, its
 * lifecycle, its provider evidence - never a posting.
 *
 * <p><strong>What belongs here.</strong> PaymentIntent, PaymentAttempt and Refund - the
 * customer's objective, the provider-facing try, and the bounded return - with their status
 * machines, per-operation provider idempotency references and verbatim provider evidence
 * ({@code MODULE_ARCHITECTURE.md} §4, bounded context 9). A payment's outcome is decided by a
 * third party, which is the exact boundary ADR-0043 named as where the transfer's one-transaction
 * answer stops: here <em>no transaction spans a provider call</em> (ADR-0046) - the dispatch
 * commits before the provider is asked, the outcome applies in a second transaction, and
 * ambiguity commits an {@code *_UNKNOWN} state ({@code INV-LIFE-03}), never an assumed failure.
 *
 * <p><strong>This module commands postings and writes none</strong> ({@code INV-LED-04}). The
 * ledger's first touch is <em>capture</em> (ADR-0048): debit PSP clearing, credit the customer
 * wallet, in the capture's outcome transaction through the ledger's command API - authorization
 * posts nothing, because the issuer holds the customer's external funds and nothing about our
 * books has changed. The direction is structural: with {@code payments -> ledger} in the build
 * graph, the reverse edge is a Gradle dependency cycle, and
 * {@link com.finapp.payments PaymentsModuleIsolationTest} pins the positive half.
 *
 * <p><strong>Deliberately no edge to {@code paymentmethods}</strong> - and unlike the ledger
 * asymmetry, no cycle backs this refusal, so the isolation test is the only control. The module
 * that talks to providers must not compile against the module that holds the PCI boundary
 * ({@code INV-PAY-02}, M7): the instrument resolves through a port {@code app} implements
 * ({@code P5-TSK-009}), and provider vocabulary stays behind the adapter ({@code INV-PAY-03}).
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema floor
 * ({@code P5-TSK-001}), the provider port with its simulated card-PSP adapter
 * ({@code P5-TSK-003}), all three aggregates with their machines
 * ({@code P5-TSK-006}/{@code -007}), the schema ({@code P5-TSK-008}: {@code V002}-{@code V006},
 * generated {@code CHECK}s and triggers reconciled by {@code PaymentsMigrationTest}, the
 * refund sum bound in-trigger under advisory-lock namespace 3), and the authorization slice
 * ({@code P5-TSK-009}: {@code PaymentCreation}/{@code PaymentConfirmation}/
 * {@code PaymentCancellation} on the dispatch-before-call discipline, the stores, the
 * {@code PaymentParticipants} port {@code app} implements, {@code EvidenceCipher} with its
 * confined key, the first audit actions), and the capture ({@code P5-TSK-010}:
 * {@code PaymentCapture} - the ledger's first touch, the {@code CAPTURED} transition, the
 * {@code payment-capture:} posting and the intent's {@code SUCCEEDED} one transaction,
 * ADR-0048), and the HTTP surface's vocabulary ({@code P5-TSK-011}: {@code PaymentsErrorCode}
 * - the refusals only, a judged failure is a body fact, {@code INV-PAY-03} at the contract -
 * with the controller and its capture-chaining service composed in {@code app}, where every
 * surface lives), and the webhook door's verifier ({@code P5-TSK-012}: {@code WebhookSignature}
 * - HMAC over {@code timestamp + "." + body} with the two-sided freshness window ADR-0047
 * requires beyond the {@code P2-TSK-011} scheme; the door itself and its evidence-first,
 * inbox-deduped ingestion live in {@code app}), and the one shared outcome application
 * ({@code P5-TSK-013}: {@code PaymentOutcomes} - the synchronous Tx2s, the webhook resolver
 * and the sweeper apply the same judgement through the same code, from their own source
 * states, with the capture's transition-posting-intent atomicity preserved by extraction
 * rather than re-decided), and the reconciliation-by-query sweeper ({@code P5-TSK-014}:
 * {@code PaymentSweeper} - no lease, no leader, by design; bounded candidates, the provider
 * asked about our stored reference holding no connection, answers applied through the shared
 * outcomes, an explicit UNRECOGNISED resolving to {@code FAILED(NEVER_RECEIVED)} and a 404
 * never earning it - updated by the task that made the previous sentence stale, the
 * recurring class), and the refund command ({@code P5-TSK-015}: {@code PaymentRefund} -
 * hold-then-post per ADR-0048 §4: the dispatch takes the attempt row lock FIRST (the pinned
 * attempt-then-account order), judges the two-rank bound (lock-then-look over
 * {@code sumNonFailedFor}, {@code V004}'s trigger beneath), places the hold and commits
 * before the wire call; completion releases-and-posts atomically keyed
 * {@code payment-refund:<refundId>}, failure releases with nothing posted, ambiguity leaves
 * the hold standing - the customer's funds visibly reserved, never silently spendable).
 *
 * <p><strong>The audit actions arrived exactly as the deliberately-few licence promised</strong>
 * ({@code P4-TSK-001}'s precedent, paid by {@code P5-TSK-009}): creation, confirmation and
 * cancellation as the person, outcome application as the platform, the capture's dispatch
 * ({@code P5-TSK-010}) and the refund's, with its required reason ({@code P5-TSK-015}) -
 * {@code PaymentsAuditAction}.
 */
package com.finapp.payments;
