-- P1-TSK-006 gate: a display name may not contain a control character.
--
-- WHY THIS EXISTS
--
-- The completion gate probed the registration endpoint with hostile input and found that a NUL byte
-- in displayName produced 500 api.InternalError - a caller's mistake reported as a platform failure,
-- which ERROR_CONTRACT.md section 3 forbids. PostgreSQL cannot store U+0000 in a text column at all,
-- so the driver rejected it three layers below the boundary and the catch-all rendered it as ours.
-- The same probe showed CR, LF and TAB being accepted into a RESTRICTED-PII column, which is a
-- forged log line waiting for the first component that ever prints a name.
--
-- PartyName now refuses them, and the boundary refuses them so a caller gets 422 naming the field.
-- This is the third place, because DEFINITION_OF_DONE.md section 1.3 says an invariant that a
-- database constraint can carry is enforced there and not only in application code - and the
-- application is not the only thing that will ever write this table.
--
-- WHY IT IS NOT A CHARSET RESTRICTION
--
-- Names contain apostrophes, hyphens, accents and non-Latin scripts; a rule narrow enough to feel
-- like a control would reject legitimate customers, which is worse than what it guards against.
-- What is excluded here is in nobody's name.
--
-- THE ASYMMETRY WITH THE DOMAIN RULE IS DELIBERATE AND IS STATED
--
-- PartyName excludes five Unicode categories: Cc, Cf, Cs, Co and Cn. This constraint covers the C0
-- and C1 control ranges only, which is what a POSIX class can express portably and exactly. It is
-- therefore NARROWER than the domain rule, and that is on purpose: a constraint written to look
-- like parity while silently missing three categories is worse than one that is honestly narrower,
-- because the next reader would trust it. The categories it does not cover - format characters,
-- unpaired surrogates, private use - are display-spoofing concerns rather than the storage failure
-- and log-forging ones this closes.
--
-- Forward-only (ADR-0011). A mistake here is corrected by a further migration, never by editing
-- this file.

ALTER TABLE party.party
    ADD CONSTRAINT party_display_name_has_no_control_characters
        CHECK (display_name !~ '[[:cntrl:]]');

COMMENT ON CONSTRAINT party_display_name_has_no_control_characters ON party.party IS
    'A display name may not contain a C0 or C1 control character. PostgreSQL cannot store U+0000 in '
    'a text column, so accepting one turns a caller mistake into a 500; and a CR or LF in a '
    'RESTRICTED-PII column is a forged log line waiting for the first component that prints a name. '
    'Narrower than PartyName, which also excludes Cf, Cs, Co and Cn - stated rather than implied.';
