# Reconciliation Model

Reconciliation compares internal authoritative records with external financial evidence.

Typical pairs:
- internal ledger <-> processor
- payments <-> PSP
- merchant payouts <-> settlement files
- bank account <-> internal cash records

A reconciliation engine should support:
- batch/source metadata
- matching keys
- tolerances
- duplicate detection
- missing internal record
- missing external record
- amount difference
- currency difference
- fee difference
- timing difference
- reversal
- investigation
- controlled resolution
- audit history

Never erase evidence of a reconciliation break.
