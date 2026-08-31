# Testing Rules

For meaningful changes:
- Add or update tests at the appropriate layer.
- Critical financial behavior requires integration tests where persistence and transaction boundaries matter.
- Use Testcontainers for real infrastructure behavior where appropriate.
- Test retries, duplicate delivery, concurrency, and failure cases for critical flows.
- Test state machines and invalid transitions.
- Test accounting invariants directly.
