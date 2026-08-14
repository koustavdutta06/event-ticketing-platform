package com.ticketing.catalog.entities;

import com.ticketing.catalog.enums.SeatStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    private String seatNumber;   // e.g. "A12"
    private String seatSection;  // e.g. "Balcony"
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private SeatStatus status; // AVAILABLE, HELD, BOOKED

    @Version
    private Long version; // optimistic locking — you'll rely on this heavily in inventory-service later
}
