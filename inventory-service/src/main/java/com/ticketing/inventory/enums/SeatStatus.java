package com.ticketing.inventory.enums;

public enum SeatStatus {
    AVAILABLE,  // free to be held/booked
    HELD,       // temporarily locked during checkout (this is the state your hold-expiry Kafka event targets later)
    BOOKED      // payment confirmed, seat is sold
}
