package com.ticketing.catalog.dto;

import java.util.List;

public record BatchImportResponse(int venuesImported, int eventsImported, List<String> errors) {}
