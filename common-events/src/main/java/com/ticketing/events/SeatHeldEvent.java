package com.ticketing.events;

import java.time.LocalDateTime;

public record SeatHeldEvent(
        Long seatId,
        Long eventId,
        Long bookingId,
        LocalDateTime heldAt,
        LocalDateTime expiresAt
) {}
