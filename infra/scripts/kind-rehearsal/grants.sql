-- An alternating user holds exactly its carrier's rights (P15-TSK-020; SECRET_MANAGEMENT.md section 5.1, database-fleet-credential).
--
-- Run by kind-rehearsal/rotations.sh as the test-only server's superuser, with :carrier and :alternate set, on a database every migration
-- has reached. Three verdicts, each `PASS|FAIL|what|detail`:
--   1. every (object, privilege) pair of the database answers the same for the alternate as for the carrier: every relation and sequence,
--      every column that carries a grant of its own, every schema, every function, the database itself; the carrier holding at least one,
--      so an empty comparison is not a pass. A user missing one grant is one differing pair, and it is named;
--   2. no grant names the alternate: its rights come by membership alone, so a later migration's grant reaches it with nothing to copy;
--   3. its one membership is the carrier's, inheriting, unable to SET ROLE, unable to hand it on.
\set ON_ERROR_STOP on

WITH relations AS (
    SELECT c.oid, c.relkind, n.nspname || '.' || c.relname AS name
      FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
       AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
), relation_pairs AS (
    SELECT r.name, p.privilege,
           CASE WHEN r.relkind = 'S' THEN has_sequence_privilege(:'carrier', r.oid, p.privilege) ELSE has_table_privilege(:'carrier', r.oid, p.privilege) END AS carrier_has,
           CASE WHEN r.relkind = 'S' THEN has_sequence_privilege(:'alternate', r.oid, p.privilege) ELSE has_table_privilege(:'alternate', r.oid, p.privilege) END AS alternate_has
      FROM relations r,
           unnest(CASE WHEN r.relkind = 'S' THEN ARRAY['USAGE', 'SELECT', 'UPDATE']
                       ELSE ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'] END) AS p(privilege)
), column_pairs AS (
    SELECT n.nspname || '.' || c.relname || '.' || a.attname AS name, p.privilege,
           has_column_privilege(:'carrier', c.oid, a.attnum, p.privilege) AS carrier_has,
           has_column_privilege(:'alternate', c.oid, a.attnum, p.privilege) AS alternate_has
      FROM pg_catalog.pg_attribute a
      JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace,
           unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'REFERENCES']) AS p(privilege)
     WHERE a.attacl IS NOT NULL AND a.attnum > 0 AND NOT a.attisdropped
       AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
), schema_pairs AS (
    SELECT s.nspname AS name, p.privilege,
           has_schema_privilege(:'carrier', s.oid, p.privilege) AS carrier_has,
           has_schema_privilege(:'alternate', s.oid, p.privilege) AS alternate_has
      FROM pg_catalog.pg_namespace s, unnest(ARRAY['USAGE', 'CREATE']) AS p(privilege)
     WHERE s.nspname NOT IN ('pg_catalog', 'information_schema') AND s.nspname NOT LIKE 'pg\_%'
), function_pairs AS (
    SELECT f.oid::regprocedure::text AS name, 'EXECUTE' AS privilege,
           has_function_privilege(:'carrier', f.oid, 'EXECUTE') AS carrier_has,
           has_function_privilege(:'alternate', f.oid, 'EXECUTE') AS alternate_has
      FROM pg_catalog.pg_proc f JOIN pg_catalog.pg_namespace n ON n.oid = f.pronamespace
     WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
), database_pairs AS (
    SELECT current_database()::text AS name, p.privilege,
           has_database_privilege(:'carrier', current_database(), p.privilege) AS carrier_has,
           has_database_privilege(:'alternate', current_database(), p.privilege) AS alternate_has
      FROM unnest(ARRAY['CONNECT', 'CREATE', 'TEMPORARY']) AS p(privilege)
), every_pair AS (
    SELECT * FROM relation_pairs
    UNION ALL SELECT * FROM column_pairs
    UNION ALL SELECT * FROM schema_pairs
    UNION ALL SELECT * FROM function_pairs
    UNION ALL SELECT * FROM database_pairs
)
SELECT CASE WHEN count(*) FILTER (WHERE carrier_has IS DISTINCT FROM alternate_has) = 0 AND count(*) FILTER (WHERE carrier_has) > 0
            THEN 'PASS' ELSE 'FAIL' END,
       :'alternate' || ' holds exactly the rights of ' || :'carrier',
       count(*) || ' (object, privilege) pairs compared, ' || count(*) FILTER (WHERE carrier_has) || ' held by the carrier, '
           || count(*) FILTER (WHERE carrier_has IS DISTINCT FROM alternate_has) || ' differing: '
           || left(coalesce(string_agg(name || ' ' || privilege || ' (carrier ' || carrier_has || ', alternate ' || alternate_has || ')', '; ')
                                FILTER (WHERE carrier_has IS DISTINCT FROM alternate_has), 'none'), 600)
  FROM every_pair;

SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END,
       :'alternate' || ' is named by no grant of its own',
       count(*) || ' grants name it directly'
  FROM (
        SELECT 1 FROM pg_catalog.pg_class c, aclexplode(c.relacl) a WHERE a.grantee = :'alternate'::regrole
        UNION ALL SELECT 1 FROM pg_catalog.pg_attribute t, aclexplode(t.attacl) a WHERE a.grantee = :'alternate'::regrole
        UNION ALL SELECT 1 FROM pg_catalog.pg_namespace n, aclexplode(n.nspacl) a WHERE a.grantee = :'alternate'::regrole
        UNION ALL SELECT 1 FROM pg_catalog.pg_proc f, aclexplode(f.proacl) a WHERE a.grantee = :'alternate'::regrole
        UNION ALL SELECT 1 FROM pg_catalog.pg_database d, aclexplode(d.datacl) a WHERE a.grantee = :'alternate'::regrole
       ) direct;

SELECT CASE WHEN count(*) = 1 AND bool_and(g.rolname = :'carrier' AND m.inherit_option AND NOT m.set_option AND NOT m.admin_option)
            THEN 'PASS' ELSE 'FAIL' END,
       :'alternate' || '''s one membership is ' || :'carrier' || ', inheriting, without SET or ADMIN',
       coalesce(string_agg(g.rolname || ' inherit=' || m.inherit_option || ' set=' || m.set_option || ' admin=' || m.admin_option, '; '), 'no membership')
  FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles g ON g.oid = m.roleid
 WHERE m.member = :'alternate'::regrole;
