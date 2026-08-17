package com.ticketing.booking.listener;

import com.ticketing.booking.enums.BookingStatus;
import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.events.SeatHeldEvent;
import com.ticketing.events.SeatHoldExpiredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeatEventListener {

    private final BookingRepository bookingRepository;

    @KafkaListener(topics = "seat-events", groupId = "booking-service")
    @Transactional
    public void handleSeatEvent(ConsumerRecord<String, Object> record) {
        if (record.value() instanceof SeatHoldExpiredEvent expired) {
            handleExpiry(expired);
        }
        // SeatHeldEvent doesn't need handling here — booking-service already knows
        // about the hold, since it's the one that triggered it in initiateBooking().
    }

    private void handleExpiry(SeatHoldExpiredEvent event) {
        bookingRepository.findBySeatIdAndStatus(event.seatId(), BookingStatus.PENDING_PAYMENT)
                .ifPresent(booking -> {
                    booking.setStatus(BookingStatus.CANCELLED);
                    booking.setUpdatedAt(LocalDateTime.now());
                    bookingRepository.save(booking);
                    log.info("Booking {} auto-cancelled: seat {} hold expired before payment", booking.getId(), event.seatId());
                });
    }
}
