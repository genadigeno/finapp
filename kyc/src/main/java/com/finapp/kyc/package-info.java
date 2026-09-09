/**
 * KYC/KYB: whether a party may be onboarded, and the evidence for that decision.
 *
 * <p><strong>What belongs here.</strong> The KYC/KYB case, its verification checks, screening
 * results, document references and the decision itself. Nothing else in the platform owns the
 * answer to "may this party transact?" — {@code party.customer.status} is a <em>projection</em>
 * of the decision recorded here, updated in reaction to it and never computed independently
 * ({@code INV-KYC-05}, ADR-0035).
 *
 * <p><strong>Why this is not {@code identity}.</strong> Proving who is logging in and deciding
 * whether a party may be onboarded are different questions with different authorities — the
 * first is answered per request against a credential, the second once per case against evidence,
 * by the platform under a versioned policy. {@code CLAUDE.md} §Domain Distinctions forbids
 * collapsing Identity and KYC, and the module boundary is what makes that a structure.
 *
 * <p><strong>A provider verdict is evidence, never the decision</strong> ({@code INV-KYC-01},
 * ADR-0038). Providers time out, disagree and revise; the platform answers to the regulator. The
 * raw payload is retained verbatim ({@code INV-HIST-02}), and every decision is a separately
 * recorded act referencing the evidence it rested on — immutable, attributable and
 * policy-pinned ({@code INV-KYC-02}).
 *
 * <p><strong>What this module holds of other modules: identifiers only.</strong> A case
 * references its Customer by value; there is no foreign key across the schema boundary.
 *
 * <p><strong>Classification.</strong> Identity documents are the most sensitive bytes the
 * platform holds before card data ({@code INV-KYC-06}): encrypted at rest under a key held
 * outside the database, readable through one audited path, columns classified at their ceiling
 * before they hold anything (ADR-0022).
 *
 * <p><strong>Nothing is implemented yet.</strong> This module is the skeleton created by
 * {@code P2-TSK-003}: the boundary, the schema and the auditable-action registry exist so the
 * aggregates land inside an enforced boundary rather than establishing one after the fact. The
 * KycCase aggregate is {@code P2-TSK-005}.
 */
package com.finapp.kyc;
