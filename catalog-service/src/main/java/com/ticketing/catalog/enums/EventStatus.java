package com.ticketing.catalog.enums;

public enum EventStatus {
    DRAFT,       // event created but not visible to customers yet
    PUBLISHED,   // visible and bookable
    SOLD_OUT,    // all seats booked — set when the last seat transitions to BOOKED
    CANCELLED,   // event called off; existing bookings need refund handling
    COMPLETED    // event date has passed
}
