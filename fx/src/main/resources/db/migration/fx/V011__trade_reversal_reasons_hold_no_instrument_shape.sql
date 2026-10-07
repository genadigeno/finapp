-- The Phase 9 to 10 transition: the operator's FX trade reversal screens its reasons like every other reasoned
-- FX act (P9-TSK-025, the lifecycle document section 3.3; INV-AUD-02, SEC-03).
--
-- V009 held its three reason columns to a length only. Every other person-written reason in this schema
-- refuses a card-number or bank-account shape by name for a raw writer past every domain guard (V004's
-- <table>_<column>_no_instrument_shape over fx.holds_instrument_shape, the Java screen's PL/pgSQL twin);
-- the domain now screens with FxReasons, and these CHECKs hold the same rule beneath it.

ALTER TABLE fx.trade_reversal
    ADD CONSTRAINT trade_reversal_proposed_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(proposed_reason));

ALTER TABLE fx.trade_reversal
    ADD CONSTRAINT trade_reversal_decided_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(decided_reason));

ALTER TABLE fx.trade_reversal_event
    ADD CONSTRAINT trade_reversal_event_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(reason));
