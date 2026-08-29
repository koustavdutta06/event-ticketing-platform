package com.ticketing.inventory.exception;

public class SeatEventMismatchException extends RuntimeException {
    public SeatEventMismatchException(String message) {
        super(message);
    }
}
