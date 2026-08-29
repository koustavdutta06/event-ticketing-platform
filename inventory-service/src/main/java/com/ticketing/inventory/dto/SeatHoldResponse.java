package com.ticketing.inventory.dto;

import com.ticketing.inventory.enums.SeatStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SeatHoldResponse(Long seatId, SeatStatus status, boolean success, BigDecimal price, LocalDateTime holdExpiresAt) {}
