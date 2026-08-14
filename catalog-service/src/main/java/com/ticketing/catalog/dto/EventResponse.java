package com.ticketing.catalog.dto;

import com.ticketing.catalog.enums.EventStatus;

import java.time.LocalDateTime;

public record EventResponse(
        Long id, String name, String venueName, LocalDateTime startTime, EventStatus status
) {}
