package com.portfolio.ticketing.user;

/**
 * The two kinds of people who use the platform.
 *
 * <ul>
 *   <li>{@link #CUSTOMER}: browses events and buys tickets;</li>
 *   <li>{@link #ORGANIZER}: creates events and seat maps, and sees the sales of their own events.</li>
 * </ul>
 *
 * <p>Both roles can register themselves (see auth/AuthService): in a ticketing platform anyone may host an
 * event, and an organizer has no power over other people's accounts or events. Stored as text in the
 * database ({@code users.role}), guarded by a CHECK constraint in migration V1.
 */
public enum UserRole {
    CUSTOMER,
    ORGANIZER
}
