package com.ticketing.inventory.dto;

import java.util.List;

public record SeatBatchImportResponse(int seatsImported, List<String> errors) {}
