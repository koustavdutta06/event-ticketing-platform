package com.ticketing.inventory.dto;

import jakarta.validation.constraints.NotNull;

public record SeatHoldRequest(@NotNull Long seatId) {}
