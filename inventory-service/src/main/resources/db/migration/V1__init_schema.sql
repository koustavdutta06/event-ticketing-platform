CREATE TABLE seats (
    id           BIGSERIAL PRIMARY KEY,
    event_id     BIGINT NOT NULL,
    seat_number  VARCHAR(50),
    seat_section VARCHAR(100),
    price        NUMERIC(10, 2),
    status       VARCHAR(50) NOT NULL DEFAULT 'AVAILABLE',
    held_until   TIMESTAMP,
    version      BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_seats_event_id ON seats(event_id);
CREATE INDEX idx_seats_status ON seats(status);
CREATE INDEX idx_seats_status_held_until ON seats(status, held_until);