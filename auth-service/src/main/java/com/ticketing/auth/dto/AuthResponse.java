package com.ticketing.auth.dto;

public record AuthResponse(String token, String email, String role) {}