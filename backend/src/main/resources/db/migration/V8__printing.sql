-- Phase 1 printing: printers driven by a print agent, the register-to-printer link and the server-side job queue.

CREATE TABLE printer (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    venue_id       uuid        NOT NULL,
    agent_id       uuid        NOT NULL,
    name           text        NOT NULL,
    windows_name   text        NOT NULL,
    width_mm       integer     NOT NULL DEFAULT 80 CHECK (width_mm IN (58, 80)),
    code_page      text        NOT NULL DEFAULT 'CP858' CHECK (code_page IN ('CP858', 'CP850', 'CP437', 'CP1252')),
    active         boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES device (tenant_id, id),
    UNIQUE (tenant_id, id),
    UNIQUE (venue_id, name)
);

-- The printer a register prints its receipts on.
ALTER TABLE device ADD COLUMN printer_id uuid;
ALTER TABLE device ADD CONSTRAINT device_printer_fk FOREIGN KEY (tenant_id, printer_id) REFERENCES printer (tenant_id, id);

-- Jobs wait here while the agent is offline; the agent mirrors them in its own queue.
CREATE TABLE print_job (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    venue_id    uuid        NOT NULL,
    printer_id  uuid        NOT NULL,
    order_id    uuid,
    kind        text        NOT NULL CHECK (kind IN ('RECEIPT', 'TICKET', 'REPORT', 'DRAWER', 'TEST')),
    document    jsonb       NOT NULL,
    status      text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'SENT', 'PRINTED', 'FAILED')),
    attempts    integer     NOT NULL DEFAULT 0,
    last_error  text,
    sent_at     timestamptz,
    finished_at timestamptz,
    created_by  uuid        REFERENCES app_user (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, venue_id) REFERENCES venue (tenant_id, id),
    FOREIGN KEY (tenant_id, printer_id) REFERENCES printer (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES customer_order (tenant_id, id)
);
CREATE INDEX print_job_pending_idx ON print_job (printer_id, created_at) WHERE status IN ('QUEUED', 'SENT');
CREATE INDEX print_job_order_idx ON print_job (order_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['printer', 'print_job']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
            'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON printer TO standpos_app;
GRANT SELECT, INSERT, UPDATE ON print_job TO standpos_app;
