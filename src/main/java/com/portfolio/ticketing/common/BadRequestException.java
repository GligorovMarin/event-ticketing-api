package com.portfolio.ticketing.common;

/** The request breaks a business rule that annotations on a DTO cannot express (HTTP 400). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
