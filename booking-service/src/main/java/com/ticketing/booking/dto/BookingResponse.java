package com.ticketing.booking.dto;

import java.time.LocalDateTime;

public record BookingResponse(Long bookingId, String status, String message, LocalDateTime holdExpiresAt) {}