package com.ticketing.inventory.controller;

import com.ticketing.inventory.dto.SeatBatchImportResponse;
import com.ticketing.inventory.service.SeatBatchImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/imports")
@RequiredArgsConstructor
public class BatchImportController {

    private final SeatBatchImportService seatBatchImportService;

    @PostMapping(value = "/seats", consumes = "multipart/form-data")
    public ResponseEntity<SeatBatchImportResponse> importSeats(@RequestParam("seatsFile") MultipartFile seatsFile) {
        return ResponseEntity.status(HttpStatus.CREATED).body(seatBatchImportService.importSeats(seatsFile));
    }
}
