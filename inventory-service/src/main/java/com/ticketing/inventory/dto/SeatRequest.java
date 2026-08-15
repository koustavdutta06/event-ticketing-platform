package com.ticketing.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SeatRequest(
        @NotNull Long eventId,
        @NotBlank String seatNumber,
        String seatSection,
        @NotNull @Positive BigDecimal price
) {}
