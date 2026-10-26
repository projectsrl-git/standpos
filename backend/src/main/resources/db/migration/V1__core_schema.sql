-- Core schema for Phase 0: plans, tenants, venues, users, devices, catalogue and audit log.
-- Every tenant-owned table carries tenant_id; children reference parents through (tenant_id, id)
-- so a row can never point at another tenant's data, whatever the application does.

CREATE TABLE plan (
    id              uuid        PRIMARY KEY,
    code            text        NOT NULL UNIQUE,
    name            text        NOT NULL,
    sales_per_month integer     NOT NULL CHECK (sales_per_month > 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant (
    id                 uuid        PRIMARY KEY,
    name               text        NOT NULL,
    status             text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    plan_id            uuid        NOT NULL REFERENCES plan (id),
    sales_cap_override integer     CHECK (sales_cap_override > 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE usage_counter (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    month      date        NOT NULL CHECK (extract(day FROM month) = 1),
    sales      integer     NOT NULL DEFAULT 0 CHECK (sales >= 0),
    warned_at  timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, month)
);

CREATE TABLE venue (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    name            text        NOT NULL,
    kind            text        NOT NULL DEFAULT 'PERMANENT' CHECK (kind IN ('PERMANENT', 'EVENT')),
    time_zone       text        NOT NULL DEFAULT 'Europe/Rome',
    day_change_time time        NOT NULL DEFAULT '04:00',
    receipt_header  text,
    receipt_footer  text,
    ticket_mode     text        NOT NULL DEFAULT 'RECEIPT_ONLY' CHECK (ticket_mode IN ('RECEIPT_ONLY', 'RECEIPT_AND_TICKETS')),
    active          boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE area (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    venue_id   uuid        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    UNIQUE (venue_id, name)
);

-- Global: a person may belong to several tenants. Visibility is restricted by RLS through membership.
CREATE TABLE app_user (
    id                    uuid        PRIMARY KEY,
    email                 text,
    display_name          text        NOT NULL,
    password_hash         text,
    pin_hash              text,
    platform_admin        boolean     NOT NULL DEFAULT false,
    status                text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    failed_login_attempts integer     NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    pin_failed_attempts   integer     NOT NULL DEFAULT 0,
    pin_locked_until      timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CHECK (email IS NULL OR email = lower(email))
);
CREATE UNIQUE INDEX app_user_email_key ON app_user (email) WHERE email IS NOT NULL;

-- OWNER is tenant-wide (venue_id NULL); every other role is held on one venue.
CREATE TABLE membership (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    venue_id   uuid,
    role       text        NOT NULL CHECK (role IN ('OWNER', 'MANAGER', 'STOCK_CLERK', 'CASHIER', 'KITCHEN')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    CHECK ((role = 'OWNER') = (venue_id IS NULL)),
    UNIQUE NULLS NOT DISTINCT (tenant_id, user_id, venue_id, role)
);
CREATE INDEX membership_user_idx ON membership (user_id);

CREATE TABLE device (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    venue_id     uuid        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('REGISTER', 'KITCHEN', 'PRINT_AGENT')),
    name         text        NOT NULL,
    token_hash   bytea       NOT NULL UNIQUE CHECK (length(token_hash) = 32),
    last_seen_at timestamptz,
    revoked_at   timestamptz,
    created_by   uuid        REFERENCES app_user (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id)
);
CREATE INDEX device_venue_idx ON device (venue_id);

CREATE TABLE device_enrolment_code (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    venue_id   uuid        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('REGISTER', 'KITCHEN', 'PRINT_AGENT')),
    code       text        NOT NULL UNIQUE CHECK (code ~ '^[A-HJKMNP-Z2-9]{8}$'),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    device_id  uuid        REFERENCES device (id),
    created_by uuid        REFERENCES app_user (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id)
);

CREATE TABLE route (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    venue_id   uuid        NOT NULL,
    name       text        NOT NULL,
    sort       integer     NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    UNIQUE (tenant_id, venue_id, id),
    UNIQUE (venue_id, name)
);

CREATE TABLE category (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    venue_id         uuid        NOT NULL,
    name             text        NOT NULL,
    colour           text        NOT NULL DEFAULT '#607D8B' CHECK (colour ~ '^#[0-9A-Fa-f]{6}$'),
    sort             integer     NOT NULL DEFAULT 0,
    default_route_id uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    FOREIGN KEY (tenant_id, venue_id, default_route_id) REFERENCES route (tenant_id, venue_id, id),
    UNIQUE (tenant_id, venue_id, id),
    UNIQUE (venue_id, name)
);

CREATE TABLE product (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    venue_id      uuid        NOT NULL,
    category_id   uuid        NOT NULL,
    name          text        NOT NULL,
    price_cents   bigint      NOT NULL CHECK (price_cents >= 0),
    route_id      uuid,
    active        boolean     NOT NULL DEFAULT true,
    track_stock   boolean     NOT NULL DEFAULT false,
    low_threshold integer     CHECK (low_threshold >= 0),
    sold_out      boolean     NOT NULL DEFAULT false,
    sort          integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id, category_id) REFERENCES category (tenant_id, venue_id, id),
    FOREIGN KEY (tenant_id, venue_id, route_id) REFERENCES route (tenant_id, venue_id, id),
    UNIQUE (category_id, name)
);
CREATE INDEX product_venue_idx ON product (venue_id);

-- Append-only: no updated_at, UPDATE and DELETE rejected by trigger and not granted.
CREATE TABLE audit_log (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    user_id     uuid        REFERENCES app_user (id),
    device_id   uuid        REFERENCES device (id),
    action      text        NOT NULL,
    entity_type text        NOT NULL,
    entity_id   uuid,
    before_data jsonb,
    after_data  jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_tenant_created_idx ON audit_log (tenant_id, created_at DESC);

CREATE FUNCTION reject_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
