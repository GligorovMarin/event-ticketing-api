package com.portfolio.ticketing.auth.dto;

import com.portfolio.ticketing.user.User;
import com.portfolio.ticketing.user.UserRole;

import java.util.UUID;

/**
 * A user as the API shows them. There is NO passwordHash field: entities are never returned directly,
 * because a forgotten field would leak. Every response is an explicit allow-list like this record.
 */
public record UserResponse(UUID id, String email, String fullName, UserRole role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
