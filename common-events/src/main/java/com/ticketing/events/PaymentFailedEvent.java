package com.ticketing.events;

public record PaymentFailedEvent(Long bookingId, Long seatId, String reason) {}