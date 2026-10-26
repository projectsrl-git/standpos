-- Phase 1 sales: cash drawer shifts, orders with snapshotted lines, payments, the daily order number per venue and
-- the moment the monthly usage cap was reached. See docs/adr/0008-sale-transaction.md.

-- Lets shifts, orders and printers reference a device of the same tenant.
ALTER TABLE device ADD CONSTRAINT device_tenant_id_key UNIQUE (tenant_id, id);

-- The first sale that reached the cap; new sales are blocked from the venue's next day change after it.
ALTER TABLE usage_counter ADD COLUMN cap_reached_at timestamptz;

CREATE TABLE shift (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    venue_id            uuid        NOT NULL,
    device_id           uuid        NOT NULL,
    cashier_id          uuid        NOT NULL REFERENCES app_user (id),
    opened_at           timestamptz NOT NULL,
    opening_float_cents bigint      NOT NULL CHECK (opening_float_cents >= 0),
    closed_at           timestamptz,
    closed_by           uuid        REFERENCES app_user (id),
    expected_cash_cents bigint,
    counted_cash_cents  bigint      CHECK (counted_cash_cents >= 0),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    FOREIGN KEY (tenant_id, device_id) REFERENCES device (tenant_id, id),
    UNIQUE (tenant_id, id),
    CHECK ((closed_at IS NULL) = (counted_cash_cents IS NULL))
);
-- One open shift per register.
CREATE UNIQUE INDEX shift_one_open_per_device ON shift (device_id) WHERE closed_at IS NULL;

-- Last daily order number per venue and business day, incremented inside the sale transaction.
CREATE TABLE order_day_counter (
    tenant_id     uuid        NOT NULL,
    venue_id      uuid        NOT NULL,
    business_date date        NOT NULL,
    last_number   integer     NOT NULL CHECK (last_number > 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, venue_id, business_date),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id)
);

CREATE TABLE customer_order (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    venue_id        uuid        NOT NULL,
    device_id       uuid        NOT NULL,
    shift_id        uuid        NOT NULL,
    cashier_id      uuid        NOT NULL REFERENCES app_user (id),
    client_order_id uuid        NOT NULL,
    business_date   date        NOT NULL,
    daily_number    integer     NOT NULL CHECK (daily_number > 0),
    status          text        NOT NULL CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'CANCELLED', 'VOIDED')),
    total_cents     bigint      NOT NULL CHECK (total_cents >= 0),
    receipt_printed boolean     NOT NULL DEFAULT false,
    paid_at         timestamptz,
    voided_at       timestamptz,
    voided_by       uuid        REFERENCES app_user (id),
    void_reason     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    FOREIGN KEY (tenant_id, device_id) REFERENCES device (tenant_id, id),
    FOREIGN KEY (tenant_id, shift_id) REFERENCES shift (tenant_id, id),
    UNIQUE (tenant_id, id),
    -- Idempotency: a retry with the same client UUID finds this order.
    UNIQUE (tenant_id, client_order_id),
    UNIQUE (venue_id, business_date, daily_number),
    CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL))
);
CREATE INDEX customer_order_venue_day_idx ON customer_order (venue_id, business_date, daily_number DESC);
CREATE INDEX customer_order_shift_idx ON customer_order (shift_id);

-- Name, price and route are copied at sale time so later catalogue edits never rewrite history.
CREATE TABLE order_line (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    order_id         uuid        NOT NULL,
    venue_id         uuid        NOT NULL,
    line_no          integer     NOT NULL,
    product_id       uuid        NOT NULL,
    product_name     text        NOT NULL,
    unit_price_cents bigint      NOT NULL CHECK (unit_price_cents >= 0),
    quantity         integer     NOT NULL CHECK (quantity > 0),
    line_total_cents bigint      NOT NULL CHECK (line_total_cents = unit_price_cents * quantity),
    route_id         uuid,
    note             text,
    void_disposition text        CHECK (void_disposition IN ('RETURN_TO_STOCK', 'WASTE')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, order_id) REFERENCES customer_order (tenant_id, id),
    FOREIGN KEY (tenant_id, venue_id, product_id) REFERENCES product (tenant_id, venue_id, id),
    UNIQUE (order_id, line_no)
);
CREATE INDEX order_line_product_idx ON order_line (product_id);

CREATE TABLE payment (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    order_id              uuid        NOT NULL,
    method                text        NOT NULL CHECK (method IN ('CASH', 'SUMUP_CARD', 'MANUAL_CARD')),
    status                text        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'REFUNDED')),
    amount_cents          bigint      NOT NULL CHECK (amount_cents >= 0),
    tendered_cents        bigint      CHECK (tendered_cents >= amount_cents),
    change_cents          bigint      CHECK (change_cents >= 0),
    needs_reconciliation  boolean     NOT NULL DEFAULT false,
    client_transaction_id text        UNIQUE,
    transaction_code      text,
    failure_reason        text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, order_id) REFERENCES customer_order (tenant_id, id),
    CHECK (method = 'CASH' OR (tendered_cents IS NULL AND change_cents IS NULL))
);
CREATE INDEX payment_order_idx ON payment (order_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['shift', 'order_day_counter', 'customer_order', 'order_line', 'payment']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
            'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON shift, order_day_counter, customer_order, order_line, payment TO standpos_app;
