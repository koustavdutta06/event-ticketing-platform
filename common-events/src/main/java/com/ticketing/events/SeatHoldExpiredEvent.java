package com.ticketing.events;

public record SeatHoldExpiredEvent(Long seatId, Long bookingId) {}
