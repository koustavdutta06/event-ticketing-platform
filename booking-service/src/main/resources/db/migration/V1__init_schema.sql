CREATE TABLE bookings (
    id                  BIGSERIAL PRIMARY KEY,
    seat_id             BIGINT NOT NULL,
    event_id            BIGINT NOT NULL,
    customer_email      VARCHAR(255),
    amount              NUMERIC(10, 2),
    status              VARCHAR(50) NOT NULL,
    razorpay_order_id   VARCHAR(255),
    razorpay_payment_id VARCHAR(255),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP,
    version             BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_seat_id ON bookings(seat_id);
CREATE INDEX idx_bookings_razorpay_order_id ON bookings(razorpay_order_id);
CREATE INDEX idx_bookings_customer_email ON bookings(customer_email);