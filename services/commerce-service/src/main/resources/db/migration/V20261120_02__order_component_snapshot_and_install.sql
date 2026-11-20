ALTER TABLE products
    ADD COLUMN IF NOT EXISTS installable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS default_install_category VARCHAR(255);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS install_offering_id UUID,
    ADD COLUMN IF NOT EXISTS service_address_id UUID,
    ADD COLUMN IF NOT EXISTS preferred_install_start TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS install_amount BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS order_component_snapshots (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    component_type VARCHAR(32) NOT NULL,
    provider_id UUID,
    service_offering_id UUID,
    allocated_gross_amount BIGINT NOT NULL,
    allocated_discount_amount BIGINT NOT NULL DEFAULT 0,
    refundable_remaining_amount BIGINT NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_order_component_type CHECK (component_type IN ('PRODUCT', 'INSTALLATION')),
    CONSTRAINT chk_order_component_amounts CHECK (
        allocated_gross_amount >= 0
        AND allocated_discount_amount >= 0
        AND refundable_remaining_amount >= 0
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_order_component_type
    ON order_component_snapshots(order_line_id, component_type);

CREATE TABLE IF NOT EXISTS commerce_installation_refunds (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    order_id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
