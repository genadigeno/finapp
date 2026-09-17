-- The customer account product (P3-TSK-012, ADR-0042).
--
-- THE AGREEMENT, NEVER THE MONEY. There is no amount column here and never will be: the
-- product's money lives in ledger.ledger_account rows whose owner_ref is this table's id, one
-- per currency, and a balance question about the product is a question against those
-- (INV-BAL-01 - a balance column on the product row is the field an author under deadline
-- increments).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION. The status CHECK comes from
-- CustomerAccountStatus.sqlValueList(), the product CHECK from ProductType.sqlValueList(), and
-- the one-live-account index predicate from CustomerAccountStatus.sqlTerminalValueList().
-- CustomerAccountMigrationTest fails the build if this file and the enums disagree
-- (the P0-TSK-022 pattern), so a state or product added in code without its schema half - or
-- without deciding whether it frees the one-live slot - cannot land quietly.
--
-- NO FOREIGN KEYS, DELIBERATELY. customer_id references party.customer and no REFERENCES clause
-- says so: a cross-schema FK is coupling Gradle and ArchUnit cannot see (ADR-0029's rule, at
-- the ADR-0042 boundary). What guarantees the customer exists is the opening transaction, whose
-- gate resolves the identifier from party's own authoritative state (INV-KYC-05).

CREATE TABLE accounts.customer_account (
    -- UUIDv7, minted by the application (ADR-0013). The value the ledger stores as its opaque
    -- owner_ref - the one thing the accounting knows about the product (ADR-0042).
    id                uuid        PRIMARY KEY,

    -- The customer holding the agreement. An identifier by value; see the header.
    customer_id       uuid        NOT NULL,

    -- Generated from ProductType.sqlValueList().
    product_type      text        NOT NULL
        CONSTRAINT customer_account_product_type_is_known
            CHECK (product_type IN ('WALLET')),

    -- Generated from CustomerAccountStatus.sqlValueList(). The machine itself lives on the
    -- enum and in the aggregate (INV-LIFE-02); this CHECK bounds what any writer can store.
    status            text        NOT NULL
        CONSTRAINT customer_account_status_is_known
            CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),

    -- Application-supplied from the injected Clock, never DEFAULT now(): a coordination
    -- timestamp set by whichever clock the row happens to meet records the deployment, not
    -- the fact (P0-TSK-015's rule).
    opened_at         timestamptz NOT NULL,

    status_changed_at timestamptz NOT NULL,

    CONSTRAINT customer_account_status_change_is_not_before_opening
        CHECK (status_changed_at >= opened_at)
);

-- ONE LIVE ACCOUNT PER CUSTOMER AND PRODUCT TYPE - the concurrency arbiter (P3-TSK-012's
-- distributed requirement): "this customer's wallet" is one agreement however many instances
-- ask, and the store's openOrConverge hands the loser the winner's row. PARTIAL, over the
-- non-terminal states - the predicate generated from sqlTerminalValueList() - because closure
-- frees the slot: a successor agreement after a closed one is legitimate and is a NEW aggregate
-- (INV-LIFE-04), the kyc_case asymmetry rather than the login-identifier one.
CREATE UNIQUE INDEX customer_account_one_live_per_customer_product
    ON accounts.customer_account (customer_id, product_type)
    WHERE status NOT IN ('CLOSED');

-- Serves "this customer's products" - the P3-TSK-013 listing and the converge read.
CREATE INDEX customer_account_by_customer ON accounts.customer_account (customer_id);

COMMENT ON TABLE accounts.customer_account IS
    'The customer account product: the agreement, its status and lifecycle - never a balance (ADR-0042). Money lives in ledger.ledger_account rows whose owner_ref is this id.';
COMMENT ON COLUMN accounts.customer_account.customer_id IS
    'party.customer.id by value; no cross-schema FK by design (ADR-0029''s rule). Resolved from party''s authoritative state by the opening gate, never from a request.';
COMMENT ON COLUMN accounts.customer_account.status IS
    'PENDING -> ACTIVE -> {SUSPENDED <-> ACTIVE} -> CLOSED; CLOSED is terminal (INV-LIFE-04) and frees the one-live-account slot.';

-- THE GRANTS ARRIVE WITH THE TABLE (P3-TSK-011's floor made this available; PHASE_3_PLAN.md
-- section 8 names this exact set). SELECT and INSERT for the product's life; UPDATE narrowed to
-- the two columns a lifecycle move touches - identity columns (id, customer_id, product_type,
-- opened_at) are facts, not fields, and stay unwritable by the application role. No DELETE:
-- an agreement's end is a status, never an absence.
GRANT SELECT, INSERT ON accounts.customer_account TO finapp_app;
GRANT UPDATE (status, status_changed_at) ON accounts.customer_account TO finapp_app;
