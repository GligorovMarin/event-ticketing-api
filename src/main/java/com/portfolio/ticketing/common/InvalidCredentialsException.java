package com.portfolio.ticketing.common;

/**
 * Wrong e-mail or password (HTTP 401). The message is deliberately the same for "no such account" and
 * "wrong password", so an attacker cannot discover which e-mails have accounts.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
