package com.ticketing.inventory.service;

import com.ticketing.inventory.dto.SeatBatchImportResponse;
import com.ticketing.inventory.entities.Seat;
import com.ticketing.inventory.enums.SeatStatus;
import com.ticketing.inventory.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bulk-loads Seats from a CSV file. Every imported seat is always created as
 * AVAILABLE regardless of what (if anything) the CSV says, since a freshly
 * loaded seat has never been held or booked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatBatchImportService {

    private final SeatRepository seatRepository;

    public SeatBatchImportResponse importSeats(MultipartFile seatsFile) {
        List<String> errors = new ArrayList<>();
        List<Seat> seats = new ArrayList<>();

        for (CSVRecord record : parse(seatsFile, errors)) {
            try {
                seats.add(Seat.builder()
                        .eventId(Long.parseLong(record.get("eventId").trim()))
                        .seatNumber(record.get("seatNumber").trim())
                        .seatSection(getOptional(record, "seatSection"))
                        .price(new BigDecimal(record.get("price").trim()))
                        .status(SeatStatus.AVAILABLE)
                        .build());
            } catch (Exception ex) {
                errors.add("Seat row " + record.getRecordNumber() + ": " + ex.getMessage());
            }
        }

        List<Seat> saved = seatRepository.saveAll(seats);
        return new SeatBatchImportResponse(saved.size(), errors);
    }

    private Iterable<CSVRecord> parse(MultipartFile file, List<String> errors) {
        try {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreSurroundingSpaces(true)
                    .build();
            CSVParser parser = CSVParser.parse(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8), format);
            return parser.getRecords();
        } catch (IOException ex) {
            errors.add("Unable to read CSV file '" + file.getOriginalFilename() + "': " + ex.getMessage());
            return List.of();
        }
    }

    private String getOptional(CSVRecord record, String column) {
        return record.isMapped(column) ? record.get(column) : null;
    }
}
