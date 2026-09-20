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
 * ({@code P5-TSK-003}: {@code PaymentProvider}, the answer types, the wire client), all three
 * aggregates with their machines - the intent ({@code P5-TSK-006}) and the attempt and refund
 * ({@code P5-TSK-007}) - and the schema's tables ({@code P5-TSK-008}: {@code V002}-{@code V005}
 * - intent, attempt, refund, their histories and the encrypted provider evidence, the machines'
 * {@code CHECK}s and triggers generated from the enums and reconciled by
 * {@code PaymentsMigrationTest}, the refund sum bound in-trigger under advisory-lock
 * namespace 3 - updated by the task that made the previous sentence stale, the recurring
 * class). The commands are {@code P5-TSK-009}/{@code -010}/{@code -015}, and the evidence
 * cipher arrives with the first writer ({@code P5-TSK-009}).
 *
 * <p><strong>Deliberately no audit-action enum yet</strong> (the deliberately-few licence,
 * {@code P4-TSK-001}'s precedent): the actions arrive with the commands whose designs fix their
 * meaning - confirmation and cancellation as the person ({@code P5-TSK-009}/{@code -011}),
 * outcome application as the platform ({@code P5-TSK-009}/{@code -014}), the refund with its
 * required reason ({@code P5-TSK-015}).
 */
package com.finapp.payments;
