ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS order_id UUID,
    ADD COLUMN IF NOT EXISTS order_line_id UUID,
    ADD COLUMN IF NOT EXISTS product_ready BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_installation_booking_order_line
    ON bookings(order_line_id)
    WHERE purpose = 'INSTALLATION' AND order_line_id IS NOT NULL;
