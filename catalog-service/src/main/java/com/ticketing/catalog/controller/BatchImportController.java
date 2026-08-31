package com.ticketing.catalog.controller;

import com.ticketing.catalog.dto.BatchImportResponse;
import com.ticketing.catalog.service.BatchImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/imports")
@RequiredArgsConstructor
public class BatchImportController {

    private final BatchImportService batchImportService;

    @PostMapping(value = "/venues-events", consumes = "multipart/form-data")
    public ResponseEntity<BatchImportResponse> importVenuesAndEvents(
            @RequestParam(value = "venuesFile", required = false) MultipartFile venuesFile,
            @RequestParam(value = "eventsFile", required = false) MultipartFile eventsFile) {
        return ResponseEntity.ok(batchImportService.importVenuesAndEvents(venuesFile, eventsFile));
    }
}
