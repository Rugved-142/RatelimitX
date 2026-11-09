package com.ratelimitx.core.security;

import java.util.Collection;
import java.util.Collections;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;

/**
 * The authenticated principal.
 *
 * <p>{@link JwtAuthenticationFilter} already loads the user row to validate the token, so the
 * fields the request path needs — id, role and effective rate limit — are carried here instead of
 * being read from Postgres a second time by every controller.
 *
 * <p>This is a snapshot taken when the request was authenticated. A role or rate limit changed
 * mid-request is not reflected until the next request.
 */
public class AuthenticatedUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final Role role;
    private final int rateLimit;
    private final boolean enabled;

    public AuthenticatedUser(Long id, String username, String password, Role role, int rateLimit,
            boolean enabled) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.role = role;
        this.rateLimit = rateLimit;
        this.enabled = enabled;
    }

    public static AuthenticatedUser from(User user) {
        Integer limit = user.getRateLimit();
        return new AuthenticatedUser(
                user.getId(),
                user.getUsername(),
                user.getPassword(),
                user.getRole(),
                limit != null ? limit : user.getRole().getDefaultRateLimit(),
                Boolean.TRUE.equals(user.getEnabled()));
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    /** @return the effective per-minute request limit for this user. */
    public int getRateLimit() {
        return rateLimit;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
