-- P1-TSK-030 — the application role may change a display name, and nothing else about a Party.
--
-- WHY THIS MIGRATION EXISTS AT ALL
--
-- `V002` granted `SELECT, INSERT` on party.party and no UPDATE, because at the time nothing ever
-- changed a Party. `PATCH /v1/me` is the first thing that does, and it failed with SQLState 42501
-- — insufficient_privilege — before a line of it had been reviewed.
--
-- That is `P0-TSK-022`'s privilege model working exactly as designed: the grant is the enforcement
-- (`DB-PRIVILEGE` ranks second only to `DB-CONSTRAINT` in the invariant catalogue), so widening one
-- is a deliberate act with a migration and a stated argument rather than a line in a service class.
--
-- WHY WIDENING IT IS CORRECT HERE
--
-- `INV-HIST-01` forbids editing **financial history**, and a person's display name is not that. It
-- is mutable data by definition — people marry, change names, and correct typos — and the record of
-- the change lives in `platform.audit_record`, which the application role still cannot UPDATE or
-- DELETE (`INV-HIST-03`). Nothing about this migration touches that.
--
-- WHY IT IS COLUMN-LEVEL, WHICH IS THE PART WORTH READING TWICE
--
-- `GRANT UPDATE ON party.party` would also permit changing `kind`, `id` and `registered_at`. Those
-- are facts rather than fields: a Party's kind is what it *is*, and its registration instant is when
-- it came into existence. Neither has any legitimate writer, and an UPDATE that could reach them is
-- a capability nobody asked for.
--
-- Column-level grants are also the mechanism `P0-TST-007` found can widen a privilege **invisibly**
-- — they do not appear in `information_schema.table_privileges` at all, which is how
-- `GRANT UPDATE (reason)` once let the application rewrite a committed audit record's justification
-- while the whole audit suite stayed green. Using that mechanism deliberately, to narrow rather than
-- to widen, is the right way round; and it is named here so a reader auditing table privileges knows
-- to look in `column_privileges` too.

GRANT UPDATE (display_name) ON party.party TO finapp_app;

COMMENT ON COLUMN party.party.display_name IS
    'A person''s name, as they would recognise it. RESTRICTED-PII, and the only column of this '
    'table the application role may change (P1-TSK-030). Changes are recorded by '
    'party.ProfileChanged, which names the field and never the value: change_summary is '
    'RESTRICTED-FINANCIAL and a name written there would sit outside the PII rules (ADR-0022).';
