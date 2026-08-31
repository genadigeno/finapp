# API Design Rules

For API code:
- Validate input at the boundary.
- Use explicit API error contracts.
- Define idempotency for money-moving commands.
- Never assume a timeout means the financial operation failed.
- Model asynchronous outcomes explicitly.
- Do not expose provider-specific states unless they are intentionally part of the public contract.
- Protect sensitive data.
- Add correlation identifiers to observable flows.
- Prefer backwards-compatible evolution.
