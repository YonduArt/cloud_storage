package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.dto.RegisterRequest;
import com.diplom.cloudstorage.exception.ApiException;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final long defaultQuotaBytes;

    public UserService(AppUserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.storage.quota-bytes:1073741824}") long defaultQuotaBytes) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.defaultQuotaBytes = defaultQuotaBytes;
    }

    @Transactional
    public AppUser register(RegisterRequest request) {
        String normalizedUsername = request.username().trim().toLowerCase();
        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new ApiException(HttpStatus.CONFLICT, "User with this username already exists");
        }
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "User with this email already exists");
        }

        AppUser user = new AppUser();
        user.setUsername(normalizedUsername);
        user.setEmail(normalizedEmail);
        user.setDisplayName(request.displayName().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setStorageQuotaBytes(defaultQuotaBytes);
        if (userRepository.count() == 0) {
            user.setRole(AppUser.Role.ROLE_ADMIN);
        }
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public AppUser getByLogin(String login) {
        String normalized = login.trim().toLowerCase();
        return userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(normalized, normalized)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional(readOnly = true)
    public AppUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return getByLogin(authentication.getName());
    }
}
