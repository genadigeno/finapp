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
 * confined key, and the first audit actions - updated by the task that made the previous
 * sentence stale, the recurring class). The capture command is {@code P5-TSK-010}, the HTTP
 * surface {@code P5-TSK-011}, the refund command {@code P5-TSK-015}.
 *
 * <p><strong>The audit actions arrived exactly as the deliberately-few licence promised</strong>
 * ({@code P4-TSK-001}'s precedent, paid by {@code P5-TSK-009}): creation, confirmation and
 * cancellation as the person, outcome application as the platform
 * ({@code PaymentsAuditAction}). Still to come with their commands: the capture's action
 * ({@code P5-TSK-010}) and the refund's, with its required reason ({@code P5-TSK-015}).
 */
package com.finapp.payments;
