package com.portfolio.ticketing.common;

/**
 * The request is well-formed but clashes with the current state: a duplicate e-mail, a seat that someone
 * else holds, a rule that forbids it right now (HTTP 409).
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
