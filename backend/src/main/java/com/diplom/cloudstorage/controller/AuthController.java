package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.AuthRequest;
import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.AuthTokenResponse;
import com.diplom.cloudstorage.dto.RegisterRequest;
import com.diplom.cloudstorage.mapper.UserMapper;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.security.JwtService;
import com.diplom.cloudstorage.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "JWT auth endpoints")
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    public AuthController(UserService userService,
                          AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          UserMapper userMapper) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
    }

    @PostMapping("/register")
    @Operation(summary = "Register user and return JWT")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = userService.register(request);
        String token = jwtService.generateToken(user.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("Registered", new AuthTokenResponse(token, "Bearer", userMapper.toResponse(user))));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and return JWT")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> login(@Valid @RequestBody AuthRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.login(), request.password())
        );
        AppUser user = userService.getByLogin(authentication.getName());
        String token = jwtService.generateToken(user.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("Logged in", new AuthTokenResponse(token, "Bearer", userMapper.toResponse(user))));
    }
}
