package com.ticketing.catalog.service;

import com.ticketing.catalog.dto.BatchImportResponse;
import com.ticketing.catalog.entities.Event;
import com.ticketing.catalog.entities.Venue;
import com.ticketing.catalog.enums.EventStatus;
import com.ticketing.catalog.repository.EventRepository;
import com.ticketing.catalog.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk-loads Venues and Events from CSV files. Venues are imported first so
 * Events (matched to a venue by name, case-insensitive) can resolve either a
 * newly-imported venue or one that already existed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchImportService {

    private final VenueRepository venueRepository;
    private final EventRepository eventRepository;

    @CacheEvict(value = {"venues", "events", "published-events"}, allEntries = true)
    public BatchImportResponse importVenuesAndEvents(MultipartFile venuesFile, MultipartFile eventsFile) {
        List<String> errors = new ArrayList<>();

        Map<String, Venue> venuesByName = new HashMap<>();
        for (Venue existing : venueRepository.findAll()) {
            venuesByName.put(existing.getName().toLowerCase(), existing);
        }

        List<Venue> newVenues = new ArrayList<>();
        if (venuesFile != null && !venuesFile.isEmpty()) {
            for (CSVRecord record : parse(venuesFile, errors)) {
                try {
                    newVenues.add(Venue.builder()
                            .name(record.get("name").trim())
                            .city(getOptional(record, "city"))
                            .totalCapacity(Integer.parseInt(record.get("totalCapacity").trim()))
                            .build());
                } catch (Exception ex) {
                    errors.add("Venue row " + record.getRecordNumber() + ": " + ex.getMessage());
                }
            }
        }
        List<Venue> savedVenues = venueRepository.saveAll(newVenues);
        for (Venue saved : savedVenues) {
            venuesByName.put(saved.getName().toLowerCase(), saved);
        }

        List<Event> newEvents = new ArrayList<>();
        if (eventsFile != null && !eventsFile.isEmpty()) {
            for (CSVRecord record : parse(eventsFile, errors)) {
                try {
                    String venueName = record.get("venueName").trim();
                    Venue venue = venuesByName.get(venueName.toLowerCase());
                    if (venue == null) {
                        throw new IllegalArgumentException("venue '" + venueName + "' not found");
                    }
                    String statusColumn = getOptional(record, "status");
                    EventStatus status = (statusColumn == null || statusColumn.isBlank())
                            ? EventStatus.DRAFT
                            : EventStatus.valueOf(statusColumn.trim().toUpperCase());

                    newEvents.add(Event.builder()
                            .name(record.get("name").trim())
                            .venue(venue)
                            .startTime(LocalDateTime.parse(record.get("startTime").trim()))
                            .status(status)
                            .build());
                } catch (Exception ex) {
                    errors.add("Event row " + record.getRecordNumber() + ": " + ex.getMessage());
                }
            }
        }
        List<Event> savedEvents = eventRepository.saveAll(newEvents);

        return new BatchImportResponse(savedVenues.size(), savedEvents.size(), errors);
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
