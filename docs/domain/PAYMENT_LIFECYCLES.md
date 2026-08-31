# Payment Lifecycles

Payment concepts must remain separate.

Typical high-level lifecycle:

initiated
-> pending
-> authorized
-> captured
-> clearing
-> settled

Alternative terminal outcomes:
failed
cancelled
reversed
refunded
partially_refunded
disputed
chargeback

Provider-specific states should be mapped into a stable internal lifecycle rather than leaking into the core domain.

Authorization is not capture.
Capture is not settlement.
Settlement is not reconciliation.

A payment attempt is distinct from the customer-facing payment intent.
