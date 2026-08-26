package com.ticketing.events;

public record BookingCancelledEvent(Long bookingId, Long seatId, String customerEmail, String reason) {}
