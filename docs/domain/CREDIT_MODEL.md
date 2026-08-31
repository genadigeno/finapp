# Credit Model

Separate:
- credit data
- credit profile
- risk assessment
- underwriting
- decisioning
- loan servicing

A decision should be reproducible from:
- data inputs or references
- policy version
- model version where applicable
- reason codes
- decision outcome
- timestamp
- decision context

Never encode the entire credit policy as opaque application conditionals with no versioning or audit trail.
