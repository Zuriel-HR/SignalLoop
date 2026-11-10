-- Extend simulator sources beyond ATM/XFS to branch IoT and SNMP assets.
ALTER TABLE simulator.error_catalog
    DROP CONSTRAINT IF EXISTS error_catalog_subsystem_check;
ALTER TABLE simulator.error_catalog
    ADD CONSTRAINT error_catalog_subsystem_check
    CHECK (subsystem IN ('XFS','IO','NETWORK','CORE','FRAUD','RECONCILIATION','IOT','SNMP'));

ALTER TABLE simulator.logs
    DROP CONSTRAINT IF EXISTS logs_subsystem_check;
ALTER TABLE simulator.logs
    ADD CONSTRAINT logs_subsystem_check
    CHECK (subsystem IN ('XFS','IO','NETWORK','CORE','FRAUD','RECONCILIATION','IOT','SNMP'));

CREATE TABLE IF NOT EXISTS simulator.assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    asset_key text NOT NULL,
    asset_type text NOT NULL CHECK (asset_type IN ('iot_refrigerator','iot_boiler','snmp_router','snmp_ups','snmp_network')),
    location_code text NOT NULL,
    vendor text NOT NULL,
    model text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scenario_id, asset_key)
);

ALTER TABLE simulator.tickets
    ADD COLUMN IF NOT EXISTS asset_id uuid REFERENCES simulator.assets(id) ON DELETE CASCADE;
ALTER TABLE simulator.tickets DROP CONSTRAINT IF EXISTS tickets_asset_reference_check;
ALTER TABLE simulator.tickets
    ADD CONSTRAINT tickets_asset_reference_check CHECK (atm_id IS NOT NULL OR asset_id IS NOT NULL);

ALTER TABLE simulator.logs
    ADD COLUMN IF NOT EXISTS asset_id uuid REFERENCES simulator.assets(id) ON DELETE CASCADE;
ALTER TABLE simulator.logs ALTER COLUMN atm_id DROP NOT NULL;
ALTER TABLE simulator.logs DROP CONSTRAINT IF EXISTS logs_asset_reference_check;
ALTER TABLE simulator.logs
    ADD CONSTRAINT logs_asset_reference_check CHECK (atm_id IS NOT NULL OR asset_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_sim_tickets_asset ON simulator.tickets(asset_id);
CREATE INDEX IF NOT EXISTS idx_sim_logs_asset_time ON simulator.logs(asset_id, occurred_at);
