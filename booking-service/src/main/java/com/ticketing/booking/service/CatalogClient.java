package com.ticketing.booking.service;

import com.ticketing.booking.dto.EventDetails;
import com.ticketing.booking.exception.EventNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class CatalogClient {

    private final WebClient catalogWebClient; // the bean from Step 3 — finally used

    public CatalogClient(@Qualifier("catalogWebClient") WebClient catalogWebClient) {
        this.catalogWebClient = catalogWebClient;
    }

    public Mono<EventDetails> getEvent(Long eventId) {
        return catalogWebClient.get()
                .uri("/api/v1/events/{eventId}", eventId)
                .retrieve()
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new EventNotFoundException("Event not found: " + eventId)))
                .bodyToMono(EventDetails.class);
    }
}