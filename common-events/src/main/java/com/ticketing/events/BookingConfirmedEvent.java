package com.ticketing.events;

public record BookingConfirmedEvent(Long bookingId, Long seatId, Long eventId) {}
