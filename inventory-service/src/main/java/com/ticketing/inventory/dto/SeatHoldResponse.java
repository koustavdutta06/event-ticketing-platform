package com.ticketing.inventory.dto;

import com.ticketing.inventory.enums.SeatStatus;

public record SeatHoldResponse(Long seatId, SeatStatus status, boolean success) {}
