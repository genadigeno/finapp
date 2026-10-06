-- =============================================================================================
-- P9-TSK-018 - the cross-border offer (PHASE_9_PLAN.md section 12.3, ADR-0079, ADR-0080 section 6;
-- INV-XB-02, INV-XB-03, INV-FX-02, INV-FX-04, INV-HIST-04).
--
-- An offer is fx's CROSS_BORDER quote and crossborder's frozen terms beside it: the corridor's fee
-- (fixed in the source currency plus the corridor margin on the customer's source amount, a named
-- rounding, computed once), the total debit, and the destination amount the beneficiary is
-- guaranteed. The request is claimed first with both policy versions pinned, payability judged
-- in-lock, and - when the beneficiary's clearance has lapsed - the re-screen it asked for; the
-- quote and the offer are inserted in one transaction. Nothing moves money here.
-- =============================================================================================

CREATE TABLE crossborder.offer_request (
    id                 uuid        PRIMARY KEY,
    claim_key          text        NOT NULL,
    owner_party        uuid        NOT NULL,
    beneficiary_id     uuid        NOT NULL REFERENCES crossborder.beneficiary (id),
    corridor_policy_id uuid        NOT NULL REFERENCES crossborder.corridor_policy_version (id),
    corridor           text        NOT NULL,
    fixed_side         text        NOT NULL,
    amount_minor       bigint      NOT NULL,
    amount_currency    text        NOT NULL,
    rescreen_id        uuid,
    created_at         timestamptz NOT NULL,
    CONSTRAINT offer_request_one_per_claim UNIQUE (claim_key),
    CONSTRAINT offer_request_claim_is_bounded CHECK (char_length(claim_key) BETWEEN 1 AND 400),
    CONSTRAINT offer_request_corridor_is_coded CHECK (corridor ~ '^[A-Z]{3}-[A-Z]{3}-[A-Z]{2}$'),
    CONSTRAINT offer_request_fixed_side_is_known CHECK (fixed_side IN ('FIXED_SOURCE', 'FIXED_DESTINATION')),
    CONSTRAINT offer_request_amount_is_positive CHECK (amount_minor > 0),
    CONSTRAINT offer_request_currency_is_alpha3 CHECK (amount_currency ~ '^[A-Z]{3}$'),
    -- The fixed amount is in the fixed side's currency: the corridor's S or D.
    CONSTRAINT offer_request_amount_on_its_side CHECK (
        (fixed_side = 'FIXED_SOURCE' AND amount_currency = substr(corridor, 1, 3))
        OR (fixed_side = 'FIXED_DESTINATION' AND amount_currency = substr(corridor, 5, 3)))
);

COMMENT ON TABLE crossborder.offer_request IS
    'A cross-border quote''s claim (P9-TSK-018): the owner, the beneficiary, the pinned corridor policy '
    'version and corridor, the fixed side and amount, and the re-screen it requested when the clearance '
    'had lapsed. One per claim key - a takeover by the same key converges here. Append-only by grant.';

CREATE TABLE crossborder.payment_offer (
    id                      uuid        PRIMARY KEY,
    offer_request_id        uuid        NOT NULL REFERENCES crossborder.offer_request (id),
    quote_id                uuid        NOT NULL,
    owner_party             uuid        NOT NULL,
    beneficiary_id          uuid        NOT NULL REFERENCES crossborder.beneficiary (id),
    corridor_policy_id      uuid        NOT NULL REFERENCES crossborder.corridor_policy_version (id),
    corridor                text        NOT NULL,
    fee_minor               bigint      NOT NULL,
    fee_scale               integer     NOT NULL,
    source_minor            bigint      NOT NULL,
    total_debit_minor       bigint      NOT NULL,
    source_currency         text        NOT NULL,
    destination_minor       bigint      NOT NULL,
    destination_scale       integer     NOT NULL,
    destination_currency    text        NOT NULL,
    delivery_estimate_hours integer     NOT NULL,
    created_at              timestamptz NOT NULL,
    CONSTRAINT payment_offer_one_per_quote UNIQUE (quote_id),
    CONSTRAINT payment_offer_one_per_request UNIQUE (offer_request_id),
    CONSTRAINT payment_offer_corridor_is_coded CHECK (corridor ~ '^[A-Z]{3}-[A-Z]{3}-[A-Z]{2}$'),
    CONSTRAINT payment_offer_currencies_are_the_corridors CHECK (
        source_currency = substr(corridor, 1, 3) AND destination_currency = substr(corridor, 5, 3)),
    CONSTRAINT payment_offer_fee_is_not_negative CHECK (fee_minor >= 0),
    CONSTRAINT payment_offer_source_is_positive CHECK (source_minor > 0),
    CONSTRAINT payment_offer_destination_is_positive CHECK (destination_minor > 0),
    -- What the customer is debited is exactly what they pay for the conversion plus the fee.
    CONSTRAINT payment_offer_total_is_source_plus_fee CHECK (total_debit_minor = source_minor + fee_minor),
    CONSTRAINT payment_offer_scales_are_bounded CHECK (fee_scale BETWEEN 0 AND 3 AND destination_scale BETWEEN 0 AND 3),
    CONSTRAINT payment_offer_estimate_is_bounded CHECK (delivery_estimate_hours BETWEEN 1 AND 720)
);

COMMENT ON TABLE crossborder.payment_offer IS
    'The frozen cross-border offer (P9-TSK-018, INV-XB-03): fx''s CROSS_BORDER quote and, beside it, the '
    'corridor fee computed once, the total debit (source + fee, CHECK-held), the guaranteed destination '
    'amount and the delivery estimate - what the customer was shown is what is held, posted and instructed. '
    'One per quote and per request; append-only by grant.';

GRANT SELECT, INSERT ON crossborder.offer_request TO finapp_app;
GRANT SELECT, INSERT ON crossborder.payment_offer TO finapp_app;
