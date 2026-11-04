package com.portfolio.ticketing.common;

/** Something the caller asked for does not exist, or must look as if it does not (HTTP 404). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
