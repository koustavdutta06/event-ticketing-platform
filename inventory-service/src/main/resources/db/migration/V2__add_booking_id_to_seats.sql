ALTER TABLE seats ADD COLUMN booking_id BIGINT;

CREATE INDEX idx_seats_booking_id ON seats(booking_id);
