package com.ticketing.inventory.dto;

import com.ticketing.inventory.enums.SeatStatus;

import java.math.BigDecimal;

public record SeatResponse(
        Long id, Long eventId, String seatNumber, String seatSection, BigDecimal price, SeatStatus status
) {}
