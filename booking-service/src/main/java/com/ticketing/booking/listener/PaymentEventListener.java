package com.ticketing.booking.listener;

import com.ticketing.booking.client.InventoryClient;
import com.ticketing.booking.entities.Booking;
import com.ticketing.booking.enums.BookingStatus;
import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.events.BookingCancelledEvent;
import com.ticketing.events.BookingConfirmedEvent;
import com.ticketing.events.PaymentFailedEvent;
import com.ticketing.events.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private static final String BOOKING_EVENTS_TOPIC = "booking-events";

    private final BookingRepository bookingRepository;
    private final InventoryClient inventoryClient; // to trigger seat release on failure
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "payment-events", groupId = "booking-service")
    @Transactional
    public void handlePaymentEvent(ConsumerRecord<String, Object> record) {
        if (record.value() instanceof PaymentSucceededEvent event) {
            handleSuccess(event);
        } else if (record.value() instanceof PaymentFailedEvent event) {
            handleFailure(event);
        } else {
            log.warn("Unrecognized payment event type: {}", record.value().getClass());
        }
    }

    private void handleSuccess(PaymentSucceededEvent event) {
        Booking booking = bookingRepository.findById(event.bookingId())
                .orElseThrow(() -> new IllegalStateException("Booking not found: " + event.bookingId()));

    if (booking.getStatus() == BookingStatus.CONFIRMED) {
        log.info("Booking {} already CONFIRMED — duplicate event ignored", event.bookingId());
        return;
    }

    if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
        // Booking already moved on — most likely the hold expired before this payment
        // event arrived. Payment succeeded on the gateway side, but we can't honor it.
        log.error("Payment succeeded for booking {} but booking is already in status {} — " +
            "seat hold likely expired before payment completed. Refund required.",
            booking.getId(), booking.getStatus());

        // In a real system: trigger a refund via the payment gateway here (Days 16-18).
        // For now, this at least prevents incorrectly confirming a stale booking.
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setUpdatedAt(LocalDateTime.now());
        bookingRepository.save(booking);
        return;
    }

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setAmount(event.amount());
        booking.setUpdatedAt(LocalDateTime.now());
        bookingRepository.save(booking);

        try {
            inventoryClient.updateSeatStatus(booking.getSeatId(), true).block();
        } catch (Exception e) {
            log.error("CRITICAL: failed to confirm seat {} for confirmed booking {} — manual intervention may be needed: {}",
                    booking.getSeatId(), booking.getId(), e.getMessage());
            // In a real production system: publish to a dead-letter topic, or a "compensation-failed" alert,
            // rather than just logging — this is the kind of gap that becomes a real incident.
        }

        kafkaTemplate.send(BOOKING_EVENTS_TOPIC, booking.getId().toString(),
                new BookingConfirmedEvent(booking.getId(), booking.getSeatId(), booking.getEventId()));

        log.info("Booking {} CONFIRMED after successful payment", booking.getId());
    }

    private void handleFailure(PaymentFailedEvent event) {
        Booking booking = bookingRepository.findById(event.bookingId())
                .orElseThrow(() -> new IllegalStateException("Booking not found: " + event.bookingId()));

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setUpdatedAt(LocalDateTime.now());
        bookingRepository.save(booking);

        // COMPENSATING ACTION: payment failed, so undo the seat hold from inventory-service
//        inventoryClient.releaseSeat(booking.getSeatId()).subscribe();

//        inventoryClient.releaseSeat(booking.getSeatId())
//                .doOnError(error -> log.error("Failed to release seat {} for cancelled booking {}: {}",
//                        booking.getSeatId(), booking.getId(), error.getMessage()))
//                .subscribe();

        try {
            // .block() forces this reactive call to complete synchronously before proceeding —
            // acceptable here because we're already inside a blocking Kafka listener thread,
            // and we genuinely need to know if this compensating action failed.
            inventoryClient.updateSeatStatus(booking.getSeatId(), false).block();
        } catch (Exception e) {
            log.error("CRITICAL: failed to release seat {} for cancelled booking {} — manual intervention may be needed: {}",
                    booking.getSeatId(), booking.getId(), e.getMessage());
            // In a real production system: publish to a dead-letter topic, or a "compensation-failed" alert,
            // rather than just logging — this is the kind of gap that becomes a real incident.
        }

        kafkaTemplate.send(BOOKING_EVENTS_TOPIC, booking.getId().toString(),
                new BookingCancelledEvent(booking.getId(), booking.getSeatId(), event.reason()));

        log.info("Booking {} CANCELLED, seat {} released — reason: {}",
                booking.getId(), booking.getSeatId(), event.reason());
    }
}