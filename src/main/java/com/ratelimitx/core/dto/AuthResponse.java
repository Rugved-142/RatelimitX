package com.ratelimitx.core.dto;


public class AuthResponse {
    
    private String token;
    private String tokenType = "Bearer";
    private Long expiresIn;
    private String username;
    private String role;
    private Integer rateLimit;
    
    public AuthResponse(String token, Long expiresIn, String username, String role, Integer rateLimit) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.username = username;
        this.role = role;
        this.rateLimit = rateLimit;
    }
    
    public String getToken() {
        return token;
    }
    
    public String getTokenType() {
        return tokenType;
    }
    
    public Long getExpiresIn() {
        return expiresIn;
    }
    
    public String getUsername() {
        return username;
    }
    
    public String getRole() {
        return role;
    }
    
    public Integer getRateLimit() {
        return rateLimit;
    }
}