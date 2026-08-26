CREATE TABLE venues (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    city          VARCHAR(255),
    total_capacity INT NOT NULL
);

CREATE TABLE events (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    venue_id   BIGINT NOT NULL REFERENCES venues(id),
    start_time TIMESTAMP NOT NULL,
    status     VARCHAR(50) NOT NULL
);

CREATE INDEX idx_events_status ON events(status);
CREATE INDEX idx_events_venue_id ON events(venue_id);