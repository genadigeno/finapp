---

paths:

* "**/*.java"
* "**/*.gradle"
* "**/*.gradle.kts"

---

# Java Lombok Standard

Lombok is the project-standard library for reducing repetitive Java boilerplate.

When implementing or modifying Java code, prefer Lombok wherever it improves clarity and removes mechanical boilerplate.

Do not repeatedly write boilerplate getters, setters, constructors, logging fields, builders, equals/hashCode, or toString implementations when an appropriate Lombok annotation can safely provide the same behavior.

## General Preference

Prefer appropriate Lombok annotations such as:

* `@Getter`
* `@Setter`
* `@RequiredArgsConstructor`
* `@NoArgsConstructor`
* `@AllArgsConstructor`
* `@Builder`
* `@SuperBuilder` when inheritance genuinely requires it
* `@Value`
* `@Slf4j`
* `@EqualsAndHashCode`
* `@ToString`
* `@Data` only when all of its generated semantics are actually appropriate

Prefer focused annotations over broad annotations when that makes the class behavior clearer.

## Constructor Injection

For Spring dependency injection, prefer constructor injection and use:

`@RequiredArgsConstructor`

for final dependencies where appropriate.

Do not use field injection merely to avoid writing constructors.

## Getters and Setters

Prefer:

`@Getter`

and/or:

`@Setter`

over manually written accessors when appropriate.

Do not generate setters merely because a field is mutable internally.

Domain invariants take priority over boilerplate reduction.

If unrestricted setters would allow invalid domain state, do not generate them.

Prefer explicit domain methods such as:

* activate()
* suspend()
* authorize()
* capture()
* refund()
* revoke()

when those methods enforce business rules.

## Constructors

Use Lombok constructor annotations when appropriate.

Use `@NoArgsConstructor` only when required by a framework, persistence technology, serialization mechanism, or another explicit design requirement.

Do not use `@NoArgsConstructor(force = true)` merely to satisfy a framework when doing so would create invalid domain state.

Use explicit constructors when constructor logic itself is part of the domain invariant.

## Builders

Use `@Builder` where builders improve construction clarity, especially for:

* DTOs;
* API models;
* test data;
* immutable configuration;
* complex value objects.

Do not use builders where they allow invalid domain state to be constructed without validation.

Do not use `@Builder` as a replacement for a meaningful domain factory when construction has business rules.

## Immutable Objects

Prefer Lombok `@Value` for genuinely immutable simple value-oriented classes where its generated semantics are appropriate.

Do not use `@Value` where framework requirements or domain lifecycle require mutability.

## Logging

Prefer:

`@Slf4j`

instead of manually declaring:

`private static final Logger ...`

Do not log:

* credentials;
* access tokens;
* secrets;
* raw payment credentials;
* unnecessary PII;
* sensitive KYC documents/data;
* security-sensitive information.

## equals / hashCode

Do not blindly generate equality for every class.

For JPA/database entities, aggregates, and other identity-based domain objects, determine the correct identity semantics before using:

`@EqualsAndHashCode`

Never let generated equality accidentally include mutable fields that can break collection behavior or persistence semantics.

For value objects, Lombok-generated equality is generally appropriate when all fields define value identity.

## toString

Never generate `toString()` that exposes:

* passwords;
* authentication secrets;
* access tokens;
* payment credentials;
* sensitive KYC/KYB data;
* confidential customer information.

Use exclusions or an explicit implementation when necessary.

## @Data

Do not use `@Data` by default.

`@Data` combines:

* `@Getter`
* `@Setter`
* `@RequiredArgsConstructor`
* `@ToString`
* `@EqualsAndHashCode`

Therefore it can unintentionally create mutable APIs, equality semantics, and logging behavior that are inappropriate for domain entities and financial models.

Prefer explicit Lombok annotations when class semantics require more control.

## Financial Domain

Lombok must never hide financial invariants.

For:

* ledger entities;
* journal entries;
* journal lines;
* accounts;
* balances;
* payments;
* transfers;
* loans;
* credit decisions;
* reconciliation records;

prefer explicit domain behavior when necessary to protect invariants.

Boilerplate reduction is subordinate to financial correctness.

## Persistence

Where persistence frameworks require constructors or accessors, use the minimum Lombok support necessary.

Do not add Lombok annotations that conflict with:

* ORM identity semantics;
* entity lifecycle;
* persistence proxies;
* serialization;
* domain encapsulation.

## Validation

Lombok-generated constructors/builders/accessors must not replace required validation.

Business validation remains explicit.

## Code Review Rule

When reviewing Java code, identify unnecessary manually written boilerplate that can safely be replaced with Lombok.

Also identify Lombok usage that should be removed because it hides domain behavior, leaks sensitive data, or creates unsafe equality/mutability semantics.

## Project Standard

For all new Java code:

Lombok should be considered first for repetitive boilerplate.

Manual boilerplate requires a reason when an appropriate Lombok alternative exists.

The reason may be:

* domain invariant;
* security;
* persistence semantics;
* framework requirement;
* generated-method behavior being inappropriate;
* readability;
* maintainability.

Do not introduce Lombok merely for annotation count reduction. Use the annotation that best expresses the intended semantics.
