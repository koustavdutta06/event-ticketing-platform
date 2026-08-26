package com.ticketing.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentOrderRequest(
        @NotNull Long bookingId,
        @NotNull Long seatId,
        @NotNull BigDecimal amount
) {}