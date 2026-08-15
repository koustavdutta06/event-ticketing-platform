package com.ticketing.booking.dto;

import java.time.LocalDateTime;

public record EventDetails(Long id, String name, String venueName, LocalDateTime startTime, String status) {}