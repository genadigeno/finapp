-- The customer projection learns the KYC decision's vocabulary (P2-TSK-014, ADR-0035):
-- REJECTED joins the machine, the one-live index treats it as the terminal it is, and the
-- UPDATE grant V002 paid ahead of any writer is narrowed to the columns the writer now
-- actually needs.
--
-- CONSTRAINTS MOVE BY REPLACEMENT (ADR-0011: V002 is applied history and cannot be edited).
--   The CHECK below is generated from CustomerStatus.sqlValueList() and the index predicate
--   from CustomerStatus.sqlTerminalValueList(); PartyEnumMigrationTest derives the
--   highest-numbered definition of each and reconciles it against the enum - the
--   RoleAssignmentMigrationTest lesson - and separately pins V002's original literals,
--   because history keeping its shape is its own claim.
--
-- REJECTED FREES THE ONE-LIVE SLOT, like CLOSED and for the same reason: a terminal
--   relationship is not a live one, and re-onboarding after changed circumstances is a NEW
--   Customer (INV-LIFE-04) - blocking a refused party's re-application forever would be a
--   decision nobody took, hidden in an index predicate. PartyEnumMigrationTest's original
--   one-terminal assertion existed to break the day a second terminal arrived; this is that
--   day, and the derived predicate is the repair it demanded.

ALTER TABLE party.customer DROP CONSTRAINT customer_status_is_known;
ALTER TABLE party.customer ADD CONSTRAINT customer_status_is_known
    CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'REJECTED'));

DROP INDEX party.customer_one_live_relationship_per_party;
CREATE UNIQUE INDEX customer_one_live_relationship_per_party
    ON party.customer (party_id)
    WHERE status NOT IN ('CLOSED', 'REJECTED');

-- V002 granted table-level UPDATE with the argument "a status legitimately changes" - before
-- any writer existed. The writer arrives with this capability (DecisionRecording moves
-- status and status_changed_at, nothing else), so the grant narrows to exactly those columns
-- - the V004 display-name precedent: column-level used to NARROW, with the identity columns
-- (party_id, opened_at) and the primary key staying unwritable by the application role.
-- P0-TST-007's column-versus-table sweep is what keeps this narrowing visible to a reader
-- auditing table_privileges.
REVOKE UPDATE ON party.customer FROM finapp_app;
GRANT UPDATE (status, status_changed_at) ON party.customer TO finapp_app;

COMMENT ON TABLE party.customer IS
    'A relationship a party holds toward the platform. PENDING -> ACTIVE -> SUSPENDED <-> '
    'ACTIVE, -> CLOSED from any non-terminal, and PENDING -> REJECTED when the KYC decision '
    'refuses onboarding (P2-TSK-014). CLOSED and REJECTED are terminal (INV-LIFE-04); '
    'reopening is a new row, never a transition out of a terminal. The status is a PROJECTION '
    'of the kyc decision (INV-KYC-05, ADR-0035): updated in the same transaction that records '
    'the decision, never computed here, never authoritative in a dispute.';
