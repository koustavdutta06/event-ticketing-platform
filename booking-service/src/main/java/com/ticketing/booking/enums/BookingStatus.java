package com.ticketing.booking.enums;

public enum BookingStatus {
    PENDING_PAYMENT,  // seat held, waiting on payment
    CONFIRMED,        // payment succeeded, booking finalized
    CANCELLED,        // payment failed or expired, seat released
    FAILED            // unrecoverable error in the saga itself
}
