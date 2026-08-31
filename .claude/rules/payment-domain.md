---
paths:
  - "**/payment*/**"
  - "**/payments/**"
  - "**/checkout/**"
  - "**/processor*/**"
  - "**/psp*/**"
  - "**/webhook*/**"
---

# Payment Domain Rules

- Distinguish payment intent, payment attempt, authorization, capture, clearing, settlement, refund, reversal, dispute, and chargeback.
- Provider adapters isolate provider-specific behavior.
- Webhooks are untrusted asynchronous inputs and may be duplicated or delayed.
- Never assume a client timeout means the provider did not act.
- Idempotency must be persisted at the appropriate financial boundary.
- Provider state mapping must be explicit and testable.
