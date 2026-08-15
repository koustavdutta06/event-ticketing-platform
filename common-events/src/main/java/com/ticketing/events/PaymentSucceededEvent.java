package com.ticketing.events;

import java.math.BigDecimal;

public record PaymentSucceededEvent(Long bookingId, Long seatId, BigDecimal amount, String transactionRef) {}
