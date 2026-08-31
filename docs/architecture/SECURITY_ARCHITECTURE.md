# Security Architecture

Security objectives:
- confidentiality of PII and financial data
- integrity of financial state
- strong authentication
- least privilege
- auditable privileged actions
- secure service-to-service communication
- minimized PCI/card-data exposure

Controls to consider:
- OAuth2/OIDC
- MFA
- WebAuthn/passkeys
- RBAC/ABAC
- service identity
- mTLS where appropriate
- secrets management
- KMS/key rotation
- encryption at rest and in transit
- tokenization
- field-level protection where appropriate
- secure logging
- privileged-access controls
- rate limits / abuse controls

Never put secrets, API credentials, private keys, or raw payment credentials in source code.
