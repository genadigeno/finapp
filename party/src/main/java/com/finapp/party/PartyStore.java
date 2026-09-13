package com.finapp.party;

import java.util.Optional;

/**
 * Reads a Party and changes its display name, in the caller's transaction (`P1-TSK-030`).
 *
 * <p>The read side {@code P1-TSK-006} never needed: registration writes a Party and nothing had
 * cause to load one until {@code GET /v1/me}.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PartyStore<T> {

    /**
     * The Party with this identifier, if there is one.
     *
     * <p><strong>The identifier never comes from a request.</strong> Neither {@code /v1/me} endpoint
     * takes a path variable, a query parameter or a body field naming a party — it is resolved from
     * the proven session's Identity, which holds it by value (ADR-0029). ADR-0031's defect is
     * <em>trusting an identifier out of the request</em>, and here there is none to trust.
     */
    Optional<Party> findById(T unitOfWork, PartyId id);

    /**
     * The live Customer relationship of a Party, if one exists (`P2-TSK-008`).
     *
     * <p>"Live" is the partial unique index's own predicate — {@code status <> 'CLOSED'} — so
     * "the live customer" and "the relationship the one-live-relationship index guards" cannot be
     * two different questions, which is {@code JdbcKycCaseStore.findOpenFor}'s discipline applied
     * here. At most one row can match, by that index.
     *
     * <p>{@code PENDING} counts as live deliberately: KYC happens <em>while</em> a relationship is
     * pending — that is what pending means (`P1-TSK-005`) — so the document upload this read
     * serves must resolve a customer the decision has not yet activated.
     *
     * <p>The identifier never comes from a request: it is resolved from the proven session's
     * Identity, the same chain as {@link #findById}.
     */
    Optional<Customer> findLiveCustomerFor(T unitOfWork, PartyId partyId);

    /**
     * {@code from → to} on a customer, conditionally — the projection write (`P2-TSK-014`).
     *
     * <p>The one production caller is the decision orchestration ({@code DecisionRecording},
     * ADR-0035): the customer's status is a <strong>projection</strong> of the KYC decision
     * ({@code INV-KYC-05}), moved in the same transaction that records it, and nothing else
     * transitions a customer from a verification outcome. The conditional {@code WHERE
     * status = ?} is the machine's edge in the statement — row count is the outcome, so N
     * concurrent callers produce one move ({@code KycCaseStore.moveStatus}'s idiom).
     *
     * @return whether this caller moved the row. A false means the customer was not in
     *     {@code from} — and the caller decides how loud that is; for the decision
     *     orchestration it is a failure of the whole transaction, because a decision recorded
     *     beside an unmoved projection is the drift {@code INV-KYC-05} forbids
     */
    boolean moveCustomerStatus(
            T unitOfWork,
            CustomerId customerId,
            CustomerStatus from,
            CustomerStatus to,
            java.time.Instant at);

    /**
     * The organisation Customer this party registered, if any (`P2-TSK-016`).
     *
     * <p>The KYB surface's ownership read: {@code /v1/me/kyb} derives the organisation from the
     * proven session's Party through this — no identifier in any request — and the statement
     * carries the ownership predicate itself ({@code registrant_party_id = ?}), so "the caller's
     * organisation" and "the organisation the one-per-registrant index guards" are one question.
     * At most one row can match, by that total unique index.
     *
     * <p>Terminal customers are deliberately <em>not</em> filtered out: the acting person must
     * still see a REJECTED organisation's outcome, and the registrant slot does not free in
     * Phase 2 (the V006 bound) — so this is "the organisation", not "the live organisation".
     */
    Optional<Customer> organisationRegisteredBy(T unitOfWork, PartyId registrantPartyId);

    /**
     * The kind of a Party — and nothing else (`P2-TSK-015`).
     *
     * <p>A deliberately narrow read beside {@link #findById}: the owner-declaration
     * orchestration must refuse an {@code ORGANISATION} owner (the bounded-depth rule), and its
     * party identifier is <strong>request-supplied by the declarant</strong> — the provenance
     * {@link #findById}'s {@code SESSION_DERIVED} classification explicitly excludes. Reusing
     * that read would falsify its recorded claim ({@code OwnershipIsScopedTest}: every
     * operation citing an entry inherits its gap), and this read discloses only the kind — a
     * refusal input, not the person. Classified {@code ADMINISTERED}: what stands in for an
     * ownership predicate is the declaration being an audited, authorized act
     * ({@code kyc.OwnerDeclared}; the boundary permission arrives with `P2-TSK-016`).
     */
    Optional<PartyKind> kindOf(T unitOfWork, PartyId partyId);

    /**
     * The kind of the Party behind a customer (`P2-TSK-015`).
     *
     * <p>The case-kind resolution: an {@code ORGANISATION} customer's verification opens as a
     * {@code KYB} case, and the case-opening consumer asks this through {@code kyc}'s
     * {@code CaseKindResolver} port because {@code kyc} cannot see this module. The identifier
     * is the consumed event's aggregate — minted by registration, never a request's.
     */
    Optional<PartyKind> kindOfCustomer(T unitOfWork, CustomerId customerId);

    /**
     * The Party behind a customer (`P2-TSK-019`).
     *
     * <p>The consent-gate resolution: a lawful basis is the <strong>party's</strong> fact — it
     * outlives any one customer relationship — and the case-opening consumer holds only the
     * consumed event's customer, so the composition root asks this on the way to the gate.
     * The identifier's provenance is {@link #kindOfCustomer}'s exactly: the consumed
     * {@code party.CustomerOpened} event's aggregate, minted by registration, never a request's.
     */
    Optional<PartyId> partyOfCustomer(T unitOfWork, CustomerId customerId);

    /**
     * Changes the display name, and reports what it replaced.
     *
     * <p><strong>One statement, and that is what makes the audit record true.</strong> A read
     * followed by an update would record a "before" that another transaction may already have
     * replaced; here the previous value is captured in the same snapshot as the write.
     *
     * <p>A rename to the value already held changes nothing and returns
     * {@link Optional#empty()} — so the caller writes no audit record for it. An entry reading
     * <em>"changed from Ada to Ada"</em> is noise, and worse, it would let anybody pad the trail at
     * will.
     *
     * @return the name that was replaced, or empty if nothing changed
     */
    Optional<PartyName> rename(T unitOfWork, PartyId id, PartyName newName);
}
