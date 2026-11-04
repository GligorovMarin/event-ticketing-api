package com.portfolio.ticketing.common;

import java.util.Locale;

/**
 * E-mail clean-up shared by the request records.
 *
 * <p>"  Ana@Clinic.COM " and "ana@clinic.com" are the same mailbox but different TEXT, and the database's
 * UNIQUE index compares text exactly. Normalising on the way in means a person cannot create two accounts
 * by changing capital letters, and can log in however they type their address.
 */
public final class Emails {

    private Emails() {
    }

    /** Trims and lower-cases; null stays null so Bean Validation can still report "must not be blank". */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
