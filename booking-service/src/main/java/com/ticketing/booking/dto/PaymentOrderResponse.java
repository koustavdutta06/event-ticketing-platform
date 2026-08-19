package com.ticketing.booking.dto;

import java.math.BigDecimal;

public record PaymentOrderResponse(
        String razorpayOrderId,
        Long bookingId,
        Long seatId,
        BigDecimal amount,
        String currency,
        String status
) {}
