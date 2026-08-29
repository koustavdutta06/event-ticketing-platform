package com.ticketing.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SeatHoldResult(Long seatId, String status, boolean success, BigDecimal price, LocalDateTime holdExpiresAt) {}