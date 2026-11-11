package com.ratelimitx.core.dto;

import com.ratelimitx.core.entity.Role;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Changes a user's enforced rate limit, and optionally their role.
 */
public class UpdateUserLimitRequest {

    @NotBlank(message = "Username is required")
    private String username;

    /** Requests per minute. Omit to keep the current value, or to take the new role's default. */
    @Min(value = 1, message = "rateLimit must be at least 1")
    private Integer rateLimit;

    /** New role. Omit to leave it unchanged. */
    private Role role;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(Integer rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
