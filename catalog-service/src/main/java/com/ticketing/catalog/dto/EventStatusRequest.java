package com.ticketing.catalog.dto;

import com.ticketing.catalog.enums.EventStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EventStatusRequest(
        @NotNull Long id,
        EventStatus status
) {}