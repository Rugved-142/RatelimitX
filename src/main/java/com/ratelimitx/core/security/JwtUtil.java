package com.ratelimitx.core.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;


@Component
public class JwtUtil {
    
    @Value("${jwt.secret}")
    private String secretString;
    
    @Value("${jwt.expiration}")
    private Long expiration;
    
    
    private SecretKey getSigningKey() {
        byte[] keyBytes = secretString.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
    
    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        // Add custom claims (role, etc.)
        claims.put("role", userDetails.getAuthorities().iterator().next().getAuthority());
        return createToken(claims, userDetails.getUsername());
    }
    
    public String generateToken(String username, String role, Integer rateLimit) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("rateLimit", rateLimit);
        return createToken(claims, username);
    }
    
    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
    
    public boolean isTokenValid(String token) {
        try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
    
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
    

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }
    

    public Integer extractRateLimit(String token) {
        return extractClaim(token, claims -> claims.get("rateLimit", Integer.class));
    }
    

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }
    
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    
    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
    
    public Long getExpirationTime() {
        return expiration;
    }
}