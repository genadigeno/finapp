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

## Operational endpoints

The actuator is an allow-list, not a default (`P0-TSK-027`, ADR-0016). Only `health` and `info`
are served. The rest exist as beans whether or not they are reachable, and each is one property
away from being exposed:

| Endpoint | What it would give an unauthenticated caller |
|---|---|
| `/actuator/heapdump` | Every secret the process has ever held in memory |
| `/actuator/env`, `/actuator/configprops` | The configuration, including connection details |
| `/actuator/beans`, `/actuator/mappings` | A map of the application |
| `/actuator/loggers` | A writable endpoint |

Health responses publish **that** something is wrong, never **what**: `show-details: never` and
`show-components: never`, because a detailed body names the JDBC URL, the host, the database, the
driver and the failing exception. `when-authorized` becomes correct once `P0-EPIC-10` provides an
authority to authorize against; there is none yet, so `never` is the honest setting.

`/actuator/info` publishes build identity only - no JVM version, no OS, no environment
properties. A JVM version is free reconnaissance for anyone matching a CVE to a target.

The application connects to PostgreSQL as `finapp_app`, never the bootstrap superuser, so a
health check cannot pass on privileges the application would not otherwise hold.

Never put secrets, API credentials, private keys, or raw payment credentials in source code.
