-- Phase 1 stock: append-only ledger of movements, materialised levels with their value, suppliers, goods receipts
-- and count sessions. Quantities are integer units; values are integer cents. See docs/adr/0007-stock-ledger-and-valuation.md.

ALTER TABLE venue ADD COLUMN allow_negative_stock boolean NOT NULL DEFAULT false;

-- Lets stock rows reference a product of the same tenant and venue.
ALTER TABLE product ADD CONSTRAINT product_tenant_venue_id_key UNIQUE (tenant_id, venue_id, id);

-- One row per tracked product, updated in the same transaction as each movement.
CREATE TABLE stock_level (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    venue_id    uuid        NOT NULL,
    product_id  uuid        NOT NULL,
    on_hand     integer     NOT NULL DEFAULT 0,
    value_cents bigint      NOT NULL DEFAULT 0 CHECK (value_cents >= 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id, product_id) REFERENCES product (tenant_id, venue_id, id),
    UNIQUE (tenant_id, venue_id, product_id)
);

-- The ledger. Never updated or deleted: no grants for it, and a trigger as for audit_log.
CREATE TABLE stock_movement (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    venue_id       uuid        NOT NULL,
    product_id     uuid        NOT NULL,
    type           text        NOT NULL CHECK (type IN ('RECEIPT', 'SALE', 'SALE_REVERSAL', 'RETURN_TO_STOCK', 'WASTE',
                                                        'ADJUSTMENT', 'COUNT')),
    quantity       integer     NOT NULL CHECK (quantity <> 0),
    value_cents    bigint      NOT NULL,
    on_hand_after  integer     NOT NULL,
    reference_type text        CHECK (reference_type IN ('GOODS_RECEIPT', 'ORDER', 'STOCK_COUNT')),
    reference_id   uuid,
    user_id        uuid        REFERENCES app_user (id),
    device_id      uuid        REFERENCES device (id),
    note           text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id, product_id) REFERENCES product (tenant_id, venue_id, id),
    -- Inbound types add stock and value, outbound types remove them; adjustments and counts go either way.
    CHECK (CASE type
               WHEN 'RECEIPT' THEN quantity > 0 AND value_cents >= 0
               WHEN 'SALE_REVERSAL' THEN quantity > 0 AND value_cents >= 0
               WHEN 'RETURN_TO_STOCK' THEN quantity > 0 AND value_cents >= 0
               WHEN 'SALE' THEN quantity < 0 AND value_cents <= 0
               WHEN 'WASTE' THEN quantity < 0 AND value_cents <= 0
               ELSE (quantity > 0) = (value_cents >= 0) OR value_cents = 0
           END)
);
CREATE INDEX stock_movement_product_idx ON stock_movement (product_id, created_at);
CREATE INDEX stock_movement_reference_idx ON stock_movement (reference_id);

CREATE TRIGGER stock_movement_append_only
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- Tenant-wide.
CREATE TABLE supplier (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    name         text        NOT NULL,
    contact_name text,
    phone        text,
    email        text,
    notes        text,
    active       boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE goods_receipt (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    venue_id     uuid        NOT NULL,
    supplier_id  uuid,
    document_ref text,
    received_on  date        NOT NULL,
    notes        text,
    status       text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'POSTED')),
    created_by   uuid        REFERENCES app_user (id),
    posted_at    timestamptz,
    posted_by    uuid        REFERENCES app_user (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES supplier (tenant_id, id),
    UNIQUE (tenant_id, id),
    CHECK ((status = 'POSTED') = (posted_at IS NOT NULL))
);
CREATE INDEX goods_receipt_venue_idx ON goods_receipt (venue_id, received_on DESC);

CREATE TABLE goods_receipt_line (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    goods_receipt_id uuid        NOT NULL,
    venue_id         uuid        NOT NULL,
    product_id       uuid        NOT NULL,
    line_no          integer     NOT NULL,
    quantity         integer     NOT NULL CHECK (quantity > 0),
    unit_cost_cents  bigint      CHECK (unit_cost_cents >= 0),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, goods_receipt_id) REFERENCES goods_receipt (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, venue_id, product_id) REFERENCES product (tenant_id, venue_id, id),
    UNIQUE (goods_receipt_id, line_no)
);

-- A posted receipt is immutable, header and lines.
CREATE FUNCTION reject_posted_goods_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'goods receipt % is posted and cannot change', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

CREATE TRIGGER goods_receipt_posted_immutable
    BEFORE UPDATE OR DELETE ON goods_receipt
    FOR EACH ROW EXECUTE FUNCTION reject_posted_goods_receipt();

CREATE FUNCTION reject_posted_goods_receipt_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM goods_receipt r
               WHERE r.id = CASE WHEN TG_OP = 'INSERT' THEN NEW.goods_receipt_id ELSE OLD.goods_receipt_id END
                 AND r.status = 'POSTED') THEN
        RAISE EXCEPTION 'lines of a posted goods receipt cannot change' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

CREATE TRIGGER goods_receipt_line_posted_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON goods_receipt_line
    FOR EACH ROW EXECUTE FUNCTION reject_posted_goods_receipt_line();

CREATE TABLE stock_count (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    venue_id   uuid        NOT NULL,
    status     text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'POSTED')),
    notes      text,
    created_by uuid        REFERENCES app_user (id),
    posted_at  timestamptz,
    posted_by  uuid        REFERENCES app_user (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    UNIQUE (tenant_id, id),
    CHECK ((status = 'POSTED') = (posted_at IS NOT NULL))
);
CREATE INDEX stock_count_venue_idx ON stock_count (venue_id, created_at DESC);

-- expected is the quantity on hand when the session was opened; posting compares with the quantity at posting time.
CREATE TABLE stock_count_line (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    stock_count_id uuid        NOT NULL,
    venue_id       uuid        NOT NULL,
    product_id     uuid        NOT NULL,
    expected       integer     NOT NULL,
    counted        integer     CHECK (counted >= 0),
    difference     integer,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, stock_count_id) REFERENCES stock_count (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, venue_id, product_id) REFERENCES product (tenant_id, venue_id, id),
    UNIQUE (stock_count_id, product_id)
);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['stock_level', 'stock_movement', 'supplier', 'goods_receipt', 'goods_receipt_line',
                             'stock_count', 'stock_count_line']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
            'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON stock_level TO standpos_app;
GRANT SELECT, INSERT ON stock_movement TO standpos_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON supplier, goods_receipt, goods_receipt_line, stock_count, stock_count_line
    TO standpos_app;
