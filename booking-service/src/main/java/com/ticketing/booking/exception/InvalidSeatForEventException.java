package com.ticketing.booking.exception;

public class InvalidSeatForEventException extends RuntimeException {
    public InvalidSeatForEventException(String message) {
        super(message);
    }
}
