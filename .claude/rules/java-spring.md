---
paths:
  - "**/*.java"
  - "**/*.gradle"
  - "**/*.gradle.kts"
---

# Java / Spring Rules

- Prefer clear domain-oriented naming over framework-centric abstractions.
- Keep transactional boundaries explicit.
- Keep domain rules testable without requiring the full Spring container where practical.
- Avoid hidden side effects in entity setters.
- Do not introduce annotations or frameworks without a clear reason.
- Preserve nullability and validation semantics explicitly.
- Prefer composition over inheritance unless the domain truly requires polymorphism.
- Use the repository's existing Java/Spring conventions before introducing new patterns.
