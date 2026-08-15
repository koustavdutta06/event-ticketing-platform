package com.ticketing.inventory.entities;

import com.ticketing.inventory.enums.SeatStatus;
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

    // No longer a JPA @ManyToOne relationship — Event lives in a different service/DB now.
    // Just store the foreign ID as a plain field, and resolve the actual Event via WebClient when needed.
    @Column(nullable = false)
    private Long eventId;

    private String seatNumber;   // e.g. "A12"
    private String seatSection;  // e.g. "Balcony"
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private SeatStatus status; // AVAILABLE, HELD, BOOKED

    @Version
    private Long version; // optimistic locking — you'll rely on this heavily in inventory-service later
}
