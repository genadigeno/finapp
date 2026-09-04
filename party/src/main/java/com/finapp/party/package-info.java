/**
 * Party and Customer: who exists, and what relationship they hold toward the platform.
 *
 * <p><strong>What belongs here.</strong> The Party aggregate, the Customer aggregate, and profile
 * data. Nothing else in the platform owns the answer to "who is this person or organisation" or
 * "what commercial relationship do they hold with us".
 *
 * <p><strong>Party and Customer are two aggregates, not one</strong> (ADR-0029). A Party is
 * <em>who exists</em>; a Customer is <em>a role a Party plays toward the platform</em>. The
 * pressure to collapse them comes from the simplest first story, where every Party is a Customer
 * and the distinction looks like ceremony. The cost arrives in the cases the collapsed model
 * cannot represent at all: a beneficial owner who is a person we must record and never a customer,
 * an organisation that is a customer and not a person, and a Party who ceases to be a customer
 * while the records referring to them must not change.
 *
 * <p><strong>Why this is not {@code identity}.</strong> Who exists as a legal party and who can
 * log in are different questions with different lifecycles. A person's login can be retired and
 * replaced without the Party changing at all, and staff hold an Identity and are never Customers.
 * Unpicking a merge later means migrating identity data out of a table financial records already
 * reference, at which point {@code INV-HIST-01} forbids rewriting the history that points at it.
 *
 * <p><strong>What this module holds of other modules: identifiers only.</strong> Other modules
 * hold a {@code PartyId} and nothing more, and {@code identity} references a Party <em>by
 * value</em> — there is no foreign key across the schema boundary, because an FK across a module
 * boundary is coupling neither Gradle nor ArchUnit can see.
 *
 * <p><strong>Classification.</strong> Everything here is personal data. Columns are classified at
 * their ceiling before they hold anything (ADR-0022), access is ownership-scoped, and profile
 * changes are audited.
 *
 * <p><strong>Nothing is implemented yet.</strong> This module is the skeleton created by
 * {@code P1-TSK-003}: the boundary, the schema and the auditable-action registry exist so that the
 * aggregates land inside an enforced boundary rather than establishing one after the fact. The
 * aggregates themselves are {@code P1-TSK-005}.
 */
package com.finapp.party;
