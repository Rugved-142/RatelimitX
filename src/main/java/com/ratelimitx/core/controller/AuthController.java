package com.ratelimitx.core.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ratelimitx.core.dto.AuthResponse;
import com.ratelimitx.core.dto.LoginRequest;
import com.ratelimitx.core.dto.RegisterRequest;
import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.security.JwtUtil;

import jakarta.validation.Valid;





@RestController
@RequestMapping("/auth")
public class AuthController {
    
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    
    public AuthController(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil
    ) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }
    
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        
        if (userRepository.existsByUsername(request.getUsername())) {
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Username already exists"));
        }
        
        if (userRepository.existsByEmail(request.getEmail())) {
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Email already exists"));
        }
        
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);  // Default role
        user.setRateLimit(Role.USER.getDefaultRateLimit()); 
        
        userRepository.save(user);
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "User registered successfully");
        response.put("username", user.getUsername());
        response.put("role", user.getRole().name());
        response.put("rateLimit", user.getRateLimit());
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        
        try {
            // Authenticate user credentials
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );
            
            User user = userRepository.findByUsername(request.getUsername())
                    .orElseThrow(() -> new BadCredentialsException("User not found"));
            
            String token = jwtUtil.generateToken(
                    user.getUsername(),
                    "ROLE_" + user.getRole().name(),
                    user.getRateLimit()
            );
            
            AuthResponse response = new AuthResponse(
                    token,
                    jwtUtil.getExpirationTime() / 1000, 
                    user.getUsername(),
                    user.getRole().name(),
                    user.getRateLimit()
            );
            
            return ResponseEntity.ok(response);
            
        } catch (BadCredentialsException e) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }
    }
    
    
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }
        
        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Map<String, Object> response = new HashMap<>();
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("role", user.getRole().name());
        response.put("rateLimit", user.getRateLimit());
        response.put("createdAt", user.getCreatedAt());
        
        return ResponseEntity.ok(response);
    }
}