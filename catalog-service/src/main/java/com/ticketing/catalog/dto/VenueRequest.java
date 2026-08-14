package com.ticketing.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record VenueRequest(
        @NotBlank String name,
        String city,
        @Positive int totalCapacity
) {}
