package com.belunaro.tadmor.security;

/**
 * The signed-in user, as the spec's {@code User} object (spec/api.md §3).
 * It is the principal of every authenticated request, reread from the users
 * table each time, so deactivation and demotion take effect immediately.
 */
public record CurrentUser(int id, String email, String fullName, boolean isAdmin) {
}
