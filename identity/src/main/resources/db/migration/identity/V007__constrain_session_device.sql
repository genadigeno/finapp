-- P1-TSK-016: bound the one caller-supplied column on the session, and forbid control characters.
--
-- V005 created `device` with no bound and no charset, because nothing populated it. This task is
-- the one its comment names, and populating it is what makes the absence matter.
--
-- WHY A CONSTRAINT AND NOT ONLY THE DOMAIN RULE. `DeviceDescription` already refuses both, and
-- DEFINITION_OF_DONE.md 1.3 says an invariant a database constraint can carry is enforced there:
-- the application is not the only thing that will ever write this column, and a migration, an
-- operator or code nobody has written yet is not bound by a Java constructor.
--
-- WHAT IT PROTECTS. `device` is RESTRICTED-PII (ADR-0022) and is rendered back to a person. A CR or
-- LF in it is a forged log line; a bidirectional override is a session list that lies about which
-- session is which. That is the P1-TSK-006 PartyName finding, in the last column on this table that
-- takes a caller's string.
--
-- DELIBERATELY NARROWER THAN THE DOMAIN RULE, AND SAYING SO. [[:cntrl:]] expresses the C0 and C1
-- ranges exactly and covers no other Unicode category, while DeviceDescription refuses five. A
-- constraint written to LOOK like parity while silently missing four categories would be worse than
-- an honestly narrower one, because the next reader would trust it. Same decision, and the same
-- wording, as V003 on party.display_name.
ALTER TABLE identity.session
    ADD CONSTRAINT session_device_is_bounded
        CHECK (device IS NULL OR (length(device) BETWEEN 1 AND 256)),
    ADD CONSTRAINT session_device_has_no_control_characters
        CHECK (device IS NULL OR device !~ '[[:cntrl:]]');

COMMENT ON COLUMN identity.session.device IS
    'What the session was established from, as a label its owner can recognise. RESTRICTED-PII. '
    'Recorded, never scored: whether a device is trusted is a Phase 13 risk decision.';
