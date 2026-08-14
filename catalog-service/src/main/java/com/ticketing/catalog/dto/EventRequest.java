package com.ticketing.catalog.dto;

import com.ticketing.catalog.enums.EventStatus;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record EventRequest(
        @NotBlank String name,
        @NotNull Long venueId,
        @NotNull @Future LocalDateTime startTime
) {}

